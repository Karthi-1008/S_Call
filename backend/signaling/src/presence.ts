import { WebSocket } from 'ws';
import { PresenceMessage } from './types.js';

export interface ConnectedClient {
  userId: string;
  role: string;
  ws: WebSocket;
  battery?: number;
  lastSeen: number;
}

const activeSockets = new Map<string, ConnectedClient>();

export function registerSocket(userId: string, role: string, ws: WebSocket) {
  activeSockets.set(userId, {
    userId,
    role,
    ws,
    lastSeen: Date.now(),
  });
}

export function unregisterSocket(userId: string) {
  activeSockets.delete(userId);
}

export function getSocket(userId: string): WebSocket | undefined {
  return activeSockets.get(userId)?.ws;
}

export function isUserOnline(userId: string): boolean {
  const client = activeSockets.get(userId);
  return !!client && client.ws.readyState === WebSocket.OPEN;
}

export function updateHeartbeat(userId: string, battery?: number) {
  const client = activeSockets.get(userId);
  if (client) {
    client.lastSeen = Date.now();
    if (battery !== undefined) {
      client.battery = battery;
    }
  }
}

export function broadcastPresence(userId: string, online: boolean, pairedUserIds: string[]) {
  const client = activeSockets.get(userId);
  const msg: PresenceMessage = {
    t: 'presence',
    userId,
    online,
    battery: client?.battery,
    lastSeen: client?.lastSeen || Date.now(),
  };

  const payload = JSON.stringify(msg);

  for (const peerId of pairedUserIds) {
    const peerSocket = getSocket(peerId);
    if (peerSocket && peerSocket.readyState === WebSocket.OPEN) {
      peerSocket.send(payload);
    }
  }
}
