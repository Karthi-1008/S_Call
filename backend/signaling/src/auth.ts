import jwt from 'jsonwebtoken';
import { v4 as uuidv4 } from 'uuid';
import { UserRole, ContactInfo } from './types.js';

const JWT_SECRET = process.env.JWT_SECRET || 'super-secret-jwt-key-replace-in-production-min-32-chars';
const JWT_EXPIRY = parseInt(process.env.JWT_EXPIRY || '86400', 10); // 24h

export interface TokenPayload {
  userId: string;
  role: UserRole;
  iat?: number;
  exp?: number;
}

export interface PairingCode {
  code: string;
  elderId: string;
  expiresAt: number;
}

// In-memory store (mirrored to Redis in production)
const users = new Map<string, {
  userId: string;
  role: UserRole;
  deviceName: string;
  fcmToken?: string;
  pairedUsers: Set<string>;
  contacts: ContactInfo[];
}>();

const pairingCodes = new Map<string, PairingCode>();

export function generateToken(userId: string, role: UserRole): string {
  return jwt.sign({ userId, role }, JWT_SECRET, { expiresIn: JWT_EXPIRY });
}

export function verifyToken(token: string): TokenPayload | null {
  try {
    return jwt.verify(token, JWT_SECRET) as TokenPayload;
  } catch {
    return null;
  }
}

export function registerUser(role: UserRole, deviceName: string, fcmToken?: string) {
  const userId = `usr_${uuidv4().replace(/-/g, '').slice(0, 12)}`;
  const token = generateToken(userId, role);
  const refreshToken = `ref_${uuidv4().replace(/-/g, '')}`;

  users.set(userId, {
    userId,
    role,
    deviceName,
    fcmToken,
    pairedUsers: new Set(),
    contacts: [],
  });

  return { userId, token, refreshToken, role };
}

export function getUser(userId: string) {
  return users.get(userId);
}

export function updateUserFcm(userId: string, fcmToken: string) {
  const user = users.get(userId);
  if (user) {
    user.fcmToken = fcmToken;
  }
}

export function startPairing(elderId: string): { code: string; qrPayload: string; expiresAt: number } {
  // Generate random 6-digit code
  const code = Math.floor(100000 + Math.random() * 900000).toString();
  const expiresAt = Date.now() + 10 * 60 * 1000; // 10 minutes

  pairingCodes.set(code, { code, elderId, expiresAt });

  // Clean expired codes
  for (const [c, p] of pairingCodes.entries()) {
    if (p.expiresAt < Date.now()) {
      pairingCodes.delete(c);
    }
  }

  const qrPayload = JSON.stringify({
    type: 'seniorcall_pair',
    elderId,
    code,
    expiresAt,
  });

  return { code, qrPayload, expiresAt };
}

export function confirmPairing(familyId: string, code: string): { success: boolean; elderId?: string; error?: string } {
  const pairing = pairingCodes.get(code);
  if (!pairing) {
    return { success: false, error: 'Invalid pairing code' };
  }

  if (pairing.expiresAt < Date.now()) {
    pairingCodes.delete(code);
    return { success: false, error: 'Pairing code expired' };
  }

  const elder = users.get(pairing.elderId);
  const family = users.get(familyId);

  if (!elder || !family) {
    return { success: false, error: 'User not found' };
  }

  // Link accounts
  elder.pairedUsers.add(familyId);
  family.pairedUsers.add(pairing.elderId);

  // Add default contact entries
  const familyContact: ContactInfo = {
    id: familyId,
    name: family.deviceName || 'Family Member',
    role: 'family',
    autoAnswerAllowed: false,
    priority: elder.contacts.length + 1,
  };

  const elderContact: ContactInfo = {
    id: elder.userId,
    name: elder.deviceName || 'Elder',
    role: 'senior',
    autoAnswerAllowed: false,
    priority: 1,
  };

  if (!elder.contacts.some(c => c.id === familyId)) {
    elder.contacts.push(familyContact);
  }
  if (!family.contacts.some(c => c.id === elder.userId)) {
    family.contacts.push(elderContact);
  }

  pairingCodes.delete(code);
  return { success: true, elderId: elder.userId };
}

export function arePaired(userId1: string, userId2: string): boolean {
  const user1 = users.get(userId1);
  return !!user1 && user1.pairedUsers.has(userId2);
}

export function getContacts(userId: string): ContactInfo[] {
  const user = users.get(userId);
  return user ? [...user.contacts] : [];
}

export function updateElderContacts(elderId: string, contacts: ContactInfo[]): boolean {
  const elder = users.get(elderId);
  if (!elder) return false;
  elder.contacts = contacts;
  return true;
}
