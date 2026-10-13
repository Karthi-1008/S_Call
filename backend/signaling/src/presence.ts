import { WebSocket } from 'ws';
import { PresenceMessage } from './types.js';

export interface ConnectedDevice {
  userId: string;
  deviceId: string;
  role: string;
  ws: WebSocket;
  battery?: number;
  lastPong: number;
  lastSeen: number;
}

// userId -> (deviceId -> ConnectedDevice)
const sockets = new Map<string, Map<string, ConnectedDevice>>();

export function registerSocket(userId: string, deviceId: string, role: string, ws: WebSocket) {
  let devMap = sockets.get(userId);
  if (!devMap) {
    devMap = new Map();
    sockets.set(userId, devMap);
  }

  const old = devMap.get(deviceId);
  if (old && old.ws !== ws) {
    try {
      old.ws.close(4000, 'replaced');
    } catch {
      // ignore
    }
  }

  devMap.set(deviceId, {
    userId,
    deviceId,
    role,
    ws,
    lastPong: Date.now(),
    lastSeen: Date.now(),
  });
}

export function unregisterSocket(userId: string, deviceId: string, ws: WebSocket) {
  const devMap = sockets.get(userId);
  if (!devMap) return;

  const current = devMap.get(deviceId);
  // S1: ONLY remove if it is still the same socket instance
  if (current && current.ws === ws) {
    devMap.delete(deviceId);
    if (devMap.size === 0) {
      sockets.delete(userId);
    }
  }
}

export function getSocket(userId: string, deviceId?: string): WebSocket | undefined {
  const devMap = sockets.get(userId);
  if (!devMap || devMap.size === 0) return undefined;

  if (deviceId && devMap.has(deviceId)) {
    return devMap.get(deviceId)?.ws;
  }

  // Return the first open socket
  for (const client of devMap.values()) {
    if (client.ws.readyState === WebSocket.OPEN) {
      return client.ws;
    }
  }
  return undefined;
}

export function getAllSocketsForUser(userId: string): WebSocket[] {
  const devMap = sockets.get(userId);
  if (!devMap) return [];
  return Array.from(devMap.values())
    .map(c => c.ws)
    .filter(ws => ws.readyState === WebSocket.OPEN);
}

export function isUserOnline(userId: string): boolean {
  const devMap = sockets.get(userId);
  if (!devMap) return false;
  const now = Date.now();

  for (const client of devMap.values()) {
    if (client.ws.readyState === WebSocket.OPEN && (now - client.lastPong < 35_000)) {
      return true;
    }
  }
  return false;
}

export function updateHeartbeat(userId: string, deviceId: string, battery?: number) {
  const devMap = sockets.get(userId);
  if (!devMap) return;

  const client = devMap.get(deviceId) || Array.from(devMap.values())[0];
  if (client) {
    const now = Date.now();
    client.lastPong = now;
    client.lastSeen = now;
    if (battery !== undefined) {
      client.battery = battery;
    }
  }
}

export function broadcastPresence(userId: string, online: boolean, pairedUserIds: string[]) {
  const devMap = sockets.get(userId);
  const sampleClient = devMap ? Array.from(devMap.values())[0] : undefined;

  const msg: PresenceMessage = {
    v: 2,
    t: 'presence',
    userId,
    online,
    battery: sampleClient?.battery,
    lastSeen: sampleClient?.lastSeen || Date.now(),
  };

  const payload = JSON.stringify(msg);

  for (const peerId of pairedUserIds) {
    const peerSockets = getAllSocketsForUser(peerId);
    for (const ws of peerSockets) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(payload);
      }
    }
  }
}

// S2: Half-open detection interval (every 20s)
setInterval(() => {
  const now = Date.now();
  for (const [userId, devMap] of sockets.entries()) {
    for (const [deviceId, client] of devMap.entries()) {
      if (client.ws.readyState === WebSocket.OPEN) {
        if (now - client.lastPong > 45_000) {
          console.warn(`[Presence] Terminating dead/half-open socket for ${userId}:${deviceId}`);
          client.ws.terminate();
          devMap.delete(deviceId);
        } else {
          try {
            client.ws.ping();
          } catch {
            // ignore
          }
        }
      }
    }
    if (devMap.size === 0) {
      sockets.delete(userId);
    }
  }
}, 20_000).unref();
