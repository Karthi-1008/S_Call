import jwt from 'jsonwebtoken';
import { v4 as uuidv4 } from 'uuid';
import { UserRole, ContactInfo } from './types.js';
import { db } from './db.js';

const JWT_SECRET = process.env.JWT_SECRET || 'super-secret-jwt-key-replace-in-production-min-32-chars';
const JWT_EXPIRY = parseInt(process.env.JWT_EXPIRY || '86400', 10); // 24h

if (process.env.NODE_ENV === 'production' && (!process.env.JWT_SECRET || process.env.JWT_SECRET.includes('replace-in-production'))) {
  console.error('[SECURITY FATAL] Refusing to start with placeholder JWT_SECRET in production. Set a random 32+ char secret in .env');
  process.exit(1);
}

export interface TokenPayload {
  userId: string;
  role: UserRole;
  iat?: number;
  exp?: number;
}

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
  const now = Date.now();

  const insertUser = db.prepare('INSERT OR REPLACE INTO users (userId, role, deviceName, createdAt) VALUES (?, ?, ?, ?)');
  insertUser.run(userId, role, deviceName, now);

  if (fcmToken) {
    updateUserFcm(userId, fcmToken);
  }

  return { userId, token, refreshToken, role };
}

export function getUser(userId: string) {
  const select = db.prepare('SELECT * FROM users WHERE userId = ?');
  const user = select.get(userId) as any;
  if (!user) return undefined;

  // Fetch paired users
  const selectPairings = db.prepare('SELECT familyId FROM pairings WHERE elderId = ? UNION SELECT elderId FROM pairings WHERE familyId = ?');
  const pairedRows = selectPairings.all(userId, userId) as any[];
  const pairedUsers = new Set<string>(pairedRows.map(r => r.familyId || r.elderId));

  return {
    userId: user.userId,
    role: user.role as UserRole,
    deviceName: user.deviceName,
    pairedUsers,
    createdAt: user.createdAt,
  };
}

export function updateUserFcm(userId: string, fcmToken: string) {
  const insertFcm = db.prepare('INSERT OR REPLACE INTO fcm_tokens (userId, token, updatedAt) VALUES (?, ?, ?)');
  insertFcm.run(userId, fcmToken, Date.now());
}

export function startPairing(elderId: string): { code: string; qrPayload: string; expiresAt: number } {
  // Generate random 6-digit code (§6.1)
  const code = Math.floor(100000 + Math.random() * 900000).toString();
  const expiresAt = Date.now() + 10 * 60 * 1000; // 10 minutes

  const insertCode = db.prepare('INSERT OR REPLACE INTO pair_codes (code, elderId, expiresAt, attempts, used) VALUES (?, ?, ?, 0, 0)');
  insertCode.run(code, elderId, expiresAt);

  const qrPayload = JSON.stringify({
    type: 'seniorcall_pair',
    elderId,
    code,
    expiresAt,
  });

  return { code, qrPayload, expiresAt };
}

export function confirmPairing(familyId: string, code: string): { success: boolean; elderId?: string; error?: string } {
  const selectCode = db.prepare('SELECT * FROM pair_codes WHERE code = ?');
  const pairing = selectCode.get(code) as any;

  if (!pairing) {
    return { success: false, error: 'Invalid pairing code' };
  }

  // Attempt limiting (§6.2: max 5 attempts)
  if (pairing.attempts >= 5) {
    return { success: false, error: 'Too many failed attempts. Code locked.' };
  }

  if (pairing.used) {
    return { success: false, error: 'Pairing code has already been used' };
  }

  if (pairing.expiresAt < Date.now()) {
    return { success: false, error: 'Pairing code expired' };
  }

  const elder = getUser(pairing.elderId);
  const family = getUser(familyId);

  if (!elder || !family) {
    db.prepare('UPDATE pair_codes SET attempts = attempts + 1 WHERE code = ?').run(code);
    return { success: false, error: 'User not found' };
  }

  // Mark code as used
  db.prepare('UPDATE pair_codes SET used = 1 WHERE code = ?').run(code);

  // Link accounts
  const now = Date.now();
  const insertPairing = db.prepare('INSERT OR IGNORE INTO pairings (elderId, familyId, pairedAt) VALUES (?, ?, ?)');
  insertPairing.run(elder.userId, familyId, now);

  // Add default contact entries
  const insertContact = db.prepare(`
    INSERT OR REPLACE INTO contacts (elderId, contactId, name, role, autoAnswerAllowed, priority, language)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `);

  insertContact.run(elder.userId, familyId, family.deviceName || 'Family Member', 'family', 0, 1, 'en');
  insertContact.run(familyId, elder.userId, elder.deviceName || 'Elder', 'senior', 0, 1, 'en');

  return { success: true, elderId: elder.userId };
}

export function arePaired(userId1: string, userId2: string): boolean {
  const query = db.prepare(`
    SELECT 1 FROM pairings
    WHERE (elderId = ? AND familyId = ?) OR (elderId = ? AND familyId = ?)
  `);
  const row = query.get(userId1, userId2, userId2, userId1);
  return !!row;
}

export function getContacts(userId: string): ContactInfo[] {
  const query = db.prepare('SELECT * FROM contacts WHERE elderId = ? ORDER BY priority ASC');
  const rows = query.all(userId) as any[];

  return rows.map(r => ({
    id: r.contactId,
    name: r.name,
    role: r.role as UserRole,
    photoUrl: r.photoUrl || undefined,
    autoAnswerAllowed: Boolean(r.autoAnswerAllowed),
    priority: r.priority,
    language: r.language || 'en',
  }));
}

export function updateElderContacts(elderId: string, contacts: ContactInfo[]) {
  const del = db.prepare('DELETE FROM contacts WHERE elderId = ?');
  del.run(elderId);

  const insert = db.prepare(`
    INSERT INTO contacts (elderId, contactId, name, role, photoUrl, autoAnswerAllowed, priority, language)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);

  for (const c of contacts) {
    insert.run(
      elderId,
      c.id,
      c.name,
      c.role,
      c.photoUrl || null,
      c.autoAnswerAllowed ? 1 : 0,
      c.priority,
      c.language || 'en'
    );
  }
}
