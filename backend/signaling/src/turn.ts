import crypto from 'node:crypto';
import { TurnServerConfig } from './types.js';

export function generateTurnCredentials(
  userId: string,
  turnSecret: string,
  turnDomain: string,
  stunPort = 3478,
  turnsPort = 5349,
  ttlSeconds = 12 * 3600 // 12 hours (§3.3)
): TurnServerConfig[] {
  const expiry = Math.floor(Date.now() / 1000) + ttlSeconds;
  const username = `${expiry}:${userId}`;
  const hmac = crypto.createHmac('sha1', turnSecret);
  hmac.update(username);
  const credential = hmac.digest('base64');

  const configs: TurnServerConfig[] = [
    // Primary STUN servers
    {
      urls: [
        'stun:stun.l.google.com:19302',
        'stun:stun1.l.google.com:19302',
        'stun:stun.cloudflare.com:3478',
      ],
    },
    // Primary coturn instance (UDP first, then TCP, then TLS §3.3)
    {
      urls: [
        `turn:${turnDomain}:${stunPort}?transport=udp`,
        `turn:${turnDomain}:${stunPort}?transport=tcp`,
        `turns:${turnDomain}:${turnsPort}?transport=tcp`,
      ],
      username,
      credential,
    },
  ];

  // If running in development/local or coturn not configured with valid domain
  if (turnDomain === 'localhost' || turnDomain.includes('example.com')) {
    configs.push({
      urls: [
        'turn:openrelay.metered.ca:80',
        'turn:openrelay.metered.ca:443',
        'turns:openrelay.metered.ca:443?transport=tcp',
      ],
      username: 'openrelay',
      credential: 'openrelay',
    });
  }

  return configs;
}
