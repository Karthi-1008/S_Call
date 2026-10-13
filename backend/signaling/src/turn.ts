import crypto from 'node:crypto';
import { TurnServerConfig } from './types.js';

export function generateTurnCredentials(
  userId: string,
  turnSecret: string,
  turnDomain: string,
  stunPort = 3478,
  turnsPort = 443,
  ttlSeconds = 3600
): TurnServerConfig[] {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
  const username = `${expiry}:${userId}`;
  const hmac = crypto.createHmac('sha1', turnSecret);
  hmac.update(username);
  const credential = hmac.digest('base64');

  return [
    {
      urls: [
        `stun:${turnDomain}:${stunPort}`,
        `turn:${turnDomain}:${stunPort}?transport=udp`,
        `turn:${turnDomain}:${stunPort}?transport=tcp`,
        `turns:${turnDomain}:${turnsPort}?transport=tcp`,
      ],
      username,
      credential,
    },
  ];
}
