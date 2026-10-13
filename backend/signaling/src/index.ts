import http from 'node:http';
import { parse } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';
import dotenv from 'dotenv';
import {
  registerUser,
  verifyToken,
  getUser,
  startPairing,
  confirmPairing,
  arePaired,
  getContacts,
  updateElderContacts,
  updateUserFcm,
} from './auth.js';
import {
  registerSocket,
  unregisterSocket,
  getSocket,
  isUserOnline,
  updateHeartbeat,
  broadcastPresence,
} from './presence.js';
import {
  createCall,
  getCall,
  getActiveCallForUser,
  setCallWaking,
  setCallRinging,
  setCallConnecting,
  setCallConnected,
  endCall,
  handleSignalingLoss,
  handleSignalingResume,
  queueForOfflineUser,
  drainOutbox,
} from './calls.js';
import { generateTurnCredentials } from './turn.js';
import {
  SignalingMessage,
  HelloMessage,
  ErrorMessage,
  CallEndMessage,
} from './types.js';

dotenv.config();

const PORT = parseInt(process.env.PORT || '8080', 10);
const DOMAIN = process.env.DOMAIN || 'localhost';
const TURN_DOMAIN = process.env.TURN_DOMAIN || DOMAIN;
const TURN_SECRET = process.env.TURN_SECRET || 'super-secret-coturn-shared-secret-key-32chars';
const STUN_PORT = parseInt(process.env.STUN_PORT || '3478', 10);
const TURNS_PORT = parseInt(process.env.TURNS_PORT || '443', 10);

export function createSignalingServer() {
  const server = http.createServer((req, res) => {
    const parsedUrl = parse(req.url || '', true);
    const pathname = parsedUrl.pathname || '';
    const method = req.method || 'GET';

    // CORS headers
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Device-Id');

    if (method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    // Helper: read JSON body
    const readBody = (callback: (body: any) => void) => {
      let data = '';
      req.on('data', chunk => {
        data += chunk;
      });
      req.on('end', () => {
        try {
          callback(data ? JSON.parse(data) : {});
        } catch {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Invalid JSON body' }));
        }
      });
    };

    // Helper: get auth token from header
    const getBearerToken = (): string | null => {
      const authHeader = req.headers.authorization;
      if (authHeader?.startsWith('Bearer ')) {
        return authHeader.substring(7);
      }
      return null;
    };

    // REST Endpoints
    if (pathname === '/healthz' && method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'ok', serverTime: Date.now() }));
      return;
    }

    if (pathname === '/v1/register' && method === 'POST') {
      readBody(body => {
        const { role, deviceName, fcmToken } = body;
        if (!role || (role !== 'senior' && role !== 'family')) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Valid role (senior|family) required' }));
          return;
        }
        const result = registerUser(role, deviceName || `${role} device`, fcmToken);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          userId: result.userId,
          jwt: result.token,
          refresh: result.refreshToken,
          role: result.role,
        }));
      });
      return;
    }

    if (pathname === '/v1/pair/start' && method === 'POST') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      const pairData = startPairing(verified.userId);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(pairData));
      return;
    }

    if (pathname === '/v1/pair/confirm' && method === 'POST') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      readBody(body => {
        const { code } = body;
        if (!code) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Pairing code required' }));
          return;
        }
        const result = confirmPairing(verified.userId, code);
        if (!result.success) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: result.error }));
          return;
        }

        // Notify both sockets if connected of contacts update
        if (result.elderId) {
          const elderSocket = getSocket(result.elderId);
          if (elderSocket && elderSocket.readyState === WebSocket.OPEN) {
            elderSocket.send(JSON.stringify({
              t: 'contacts_update',
              contacts: getContacts(result.elderId),
            }));
          }
        }
        const familySocket = getSocket(verified.userId);
        if (familySocket && familySocket.readyState === WebSocket.OPEN) {
          familySocket.send(JSON.stringify({
            t: 'contacts_update',
            contacts: getContacts(verified.userId),
          }));
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(result));
      });
      return;
    }

    if (pathname === '/v1/contacts' && method === 'GET') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      const targetUserId = (parsedUrl.query.elderId as string) || verified.userId;
      const contacts = getContacts(targetUserId);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ contacts }));
      return;
    }

    if (pathname === '/v1/contacts' && method === 'PUT') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      readBody(body => {
        const { elderId, contacts } = body;
        const targetId = elderId || verified.userId;
        if (!Array.isArray(contacts)) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'Contacts array required' }));
          return;
        }
        updateElderContacts(targetId, contacts);

        // Sync live to elder device
        const elderSocket = getSocket(targetId);
        if (elderSocket && elderSocket.readyState === WebSocket.OPEN) {
          elderSocket.send(JSON.stringify({
            t: 'contacts_update',
            contacts: getContacts(targetId),
          }));
        }

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      });
      return;
    }

    if (pathname === '/v1/turn' && method === 'GET') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      const iceServers = generateTurnCredentials(verified.userId, TURN_SECRET, TURN_DOMAIN, STUN_PORT, TURNS_PORT);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ iceServers }));
      return;
    }

    if (pathname === '/v1/push-token' && method === 'POST') {
      const token = getBearerToken();
      const verified = token ? verifyToken(token) : null;
      if (!verified) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      readBody(body => {
        if (body.fcmToken) {
          updateUserFcm(verified.userId, body.fcmToken);
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: true }));
      });
      return;
    }

    // Default 404
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Endpoint not found' }));
  });

  // WebSocket Server
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (request, socket, head) => {
    const { pathname, query } = parse(request.url || '', true);
    if (pathname !== '/ws') {
      socket.destroy();
      return;
    }

    const authHeader = request.headers.authorization;
    const token = (query.token as string) || (authHeader?.startsWith('Bearer ') ? authHeader.substring(7) : '');
    const verified = token ? verifyToken(token) : null;

    if (!verified) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    const deviceId = (query.deviceId as string) || (request.headers['x-device-id'] as string) || 'default';

    wss.handleUpgrade(request, socket, head, ws => {
      wss.emit('connection', ws, request, { ...verified, deviceId });
    });
  });

  wss.on('connection', (ws: WebSocket, _request: http.IncomingMessage, userPayload: any) => {
    const userId = userPayload.userId;
    const role = userPayload.role;
    const deviceId = userPayload.deviceId || 'default';
    const user = getUser(userId);

    registerSocket(userId, deviceId, role, ws);
    handleSignalingResume(userId);

    // Send 'hello' message immediately (§13.2 / §3.6)
    const turn = generateTurnCredentials(userId, TURN_SECRET, TURN_DOMAIN, STUN_PORT, TURNS_PORT);
    const contacts = getContacts(userId);
    const helloMsg: HelloMessage = {
      t: 'hello',
      turn,
      contacts,
      serverTime: Date.now(),
      userId,
      deviceId,
    };
    ws.send(JSON.stringify(helloMsg));

    // Drain outbox if any messages queued for this user
    const pendingMessages = drainOutbox(userId);
    for (const msg of pendingMessages) {
      ws.send(JSON.stringify(msg));
    }

    // Broadcast presence online to paired users
    if (user) {
      broadcastPresence(userId, true, Array.from(user.pairedUsers));
    }

    ws.on('message', (rawData: string) => {
      try {
        const msg = JSON.parse(rawData.toString()) as SignalingMessage;
        handleSignalingMessage(userId, deviceId, ws, msg);
      } catch (err) {
        console.error('[WS] Parse error:', err);
        const errReply: ErrorMessage = {
          t: 'error',
          code: 'BAD_JSON',
          message: 'Malformed JSON payload',
        };
        ws.send(JSON.stringify(errReply));
      }
    });

    ws.on('close', () => {
      // S3: Signaling loss grace period (60s). Do NOT kill media prematurely on socket close!
      const activeCall = getActiveCallForUser(userId);
      if (activeCall && activeCall.state !== 'ENDED') {
        const peerId = activeCall.callerId === userId ? activeCall.calleeId : activeCall.callerId;
        const peerSocket = getSocket(peerId);
        if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
          peerSocket.send(JSON.stringify({
            t: 'peer_signaling_lost',
            callId: activeCall.callId,
          }));
        }

        handleSignalingLoss(userId, (expiredCall) => {
          const pSocket = getSocket(peerId);
          if (pSocket && pSocket.readyState === WebSocket.OPEN) {
            const endMsg: CallEndMessage = {
              t: 'call_end',
              callId: expiredCall.callId,
              reason: 'signaling_timeout',
            };
            pSocket.send(JSON.stringify(endMsg));
          }
        });
      }

      unregisterSocket(userId, deviceId, ws);
      if (user && !isUserOnline(userId)) {
        broadcastPresence(userId, false, Array.from(user.pairedUsers));
      }
    });
  });

  function handleSignalingMessage(fromUserId: string, deviceId: string, ws: WebSocket, msg: any) {
    const user = getUser(fromUserId);

    switch (msg.t) {
      case 'ping': {
        updateHeartbeat(fromUserId, deviceId, msg.battery);
        ws.send(JSON.stringify({ t: 'pong', serverTime: Date.now() }));
        break;
      }

      case 'pong': {
        updateHeartbeat(fromUserId, deviceId, msg.battery);
        break;
      }

      case 'resume': {
        handleSignalingResume(fromUserId);
        const pending = drainOutbox(fromUserId);
        for (const p of pending) {
          ws.send(JSON.stringify(p));
        }
        break;
      }

      case 'ack': {
        // Acknowledged up to msg.upTo
        break;
      }

      case 'call_invite': {
        const { to, media, sdp } = msg;
        if (!to) {
          ws.send(JSON.stringify({ t: 'error', code: 'INVALID_TARGET', message: 'Target user ID required' }));
          return;
        }

        // Security check: must be paired (§13.5)
        if (!arePaired(fromUserId, to)) {
          ws.send(JSON.stringify({ t: 'error', code: 'NOT_PAIRED', message: 'You are not paired with this user' }));
          return;
        }

        // Busy check (§9.1 F7)
        const targetActiveCall = getActiveCallForUser(to);
        if (targetActiveCall && targetActiveCall.state !== 'ENDED') {
          ws.send(JSON.stringify({
            t: 'call_reject',
            callId: targetActiveCall.callId,
            reason: 'busy',
          }));
          return;
        }

        const caller = getUser(fromUserId);
        const call = createCall(fromUserId, to, media || 'video', (timedOutCall, reason) => {
          const callerWs = getSocket(timedOutCall.callerId);
          if (callerWs && callerWs.readyState === WebSocket.OPEN) {
            callerWs.send(JSON.stringify({
              t: 'call_end',
              callId: timedOutCall.callId,
              reason,
            }));
          }
          const calleeWs = getSocket(timedOutCall.calleeId);
          if (calleeWs && calleeWs.readyState === WebSocket.OPEN) {
            calleeWs.send(JSON.stringify({
              t: 'call_end',
              callId: timedOutCall.callId,
              reason,
            }));
          }
        });

        const invitePayload = {
          t: 'call_invite',
          callId: call.callId,
          from: fromUserId,
          to,
          media: call.media,
          callerName: caller?.deviceName || 'Family Member',
          autoAnswerAllowed: !!msg.autoAnswerAllowed,
          sdp: sdp, // Offer-in-Invite (§3.6)
        };

        const targetSocket = getSocket(to);
        if (targetSocket && targetSocket.readyState === WebSocket.OPEN) {
          targetSocket.send(JSON.stringify(invitePayload));
          // Notify caller of ringing
          ws.send(JSON.stringify({
            t: 'call_ringing',
            callId: call.callId,
          }));
        } else {
          // S4: Callee offline or reconnecting, queue in outbox for 45s
          queueForOfflineUser(to, invitePayload, 45_000);
          setCallWaking(call.callId);
          ws.send(JSON.stringify({
            t: 'call_state',
            callId: call.callId,
            state: 'waking',
          }));
        }
        break;
      }

      case 'call_ringing': {
        const call = getCall(msg.callId);
        if (call) {
          setCallRinging(call.callId);
          const callerSocket = getSocket(call.callerId);
          if (callerSocket && callerSocket.readyState === WebSocket.OPEN) {
            callerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'call_accept': {
        const call = getCall(msg.callId);
        if (call) {
          setCallConnecting(call.callId, (timedOutCall, reason) => {
            const callerWs = getSocket(timedOutCall.callerId);
            if (callerWs?.readyState === WebSocket.OPEN) {
              callerWs.send(JSON.stringify({ t: 'call_end', callId: timedOutCall.callId, reason }));
            }
          });
          const callerSocket = getSocket(call.callerId);
          if (callerSocket && callerSocket.readyState === WebSocket.OPEN) {
            callerSocket.send(JSON.stringify(msg)); // Forwards call_accept with answer sdp
          }
        }
        break;
      }

      case 'call_reject': {
        const call = getCall(msg.callId);
        if (call) {
          endCall(call.callId, msg.reason || 'declined');
          const callerSocket = getSocket(call.callerId);
          if (callerSocket && callerSocket.readyState === WebSocket.OPEN) {
            callerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'call_connected': {
        const call = getCall(msg.callId);
        if (call) {
          setCallConnected(call.callId);
          const peerId = call.callerId === fromUserId ? call.calleeId : call.callerId;
          const peerSocket = getSocket(peerId);
          if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
            peerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'offer':
      case 'answer': {
        const call = getCall(msg.callId);
        if (call) {
          if (msg.t === 'answer') {
            setCallConnected(call.callId);
          }
          const peerId = call.callerId === fromUserId ? call.calleeId : call.callerId;
          const peerSocket = getSocket(peerId);
          if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
            peerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'ice_restart_request': {
        const call = getCall(msg.callId);
        if (call) {
          // Caller generates the offer on ICE restart (§6.2)
          const callerSocket = getSocket(call.callerId);
          if (callerSocket && callerSocket.readyState === WebSocket.OPEN) {
            callerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'ice':
      case 'renegotiate':
      case 'screen_request':
      case 'screen_request_reply':
      case 'screen_share_started':
      case 'screen_share_stopped': {
        const call = getCall(msg.callId);
        let peerId = call ? (call.callerId === fromUserId ? call.calleeId : call.callerId) : msg.to;
        if (!peerId && user) {
          peerId = Array.from(user.pairedUsers)[0];
        }
        if (peerId) {
          const peerSocket = getSocket(peerId);
          if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
            peerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      case 'call_end': {
        const call = getCall(msg.callId);
        if (call) {
          endCall(call.callId, msg.reason || 'normal');
          const peerId = call.callerId === fromUserId ? call.calleeId : call.callerId;
          const peerSocket = getSocket(peerId);
          if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
            peerSocket.send(JSON.stringify(msg));
          }
        }
        break;
      }

      default: {
        console.warn(`[WS] Unhandled message type: ${msg.t}`);
      }
    }
  }

  return { server, wss };
}

import { fileURLToPath } from 'node:url';

// Standalone execution
const isMainModule = () => {
  if (!process.argv[1]) return false;
  try {
    const currentFile = fileURLToPath(import.meta.url);
    return process.argv[1] === currentFile ||
           (process.argv[1].endsWith('index.js') && !process.argv[1].includes('test'));
  } catch {
    return false;
  }
};

if (isMainModule() && process.env.NODE_ENV !== 'test') {
  const { server } = createSignalingServer();
  server.listen(PORT, () => {
    console.log(`[SeniorCall] Signaling Server listening on port ${PORT}`);
  });
}
