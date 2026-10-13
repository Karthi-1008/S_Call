import { DatabaseSync } from 'node:sqlite';
import path from 'node:path';
import fs from 'node:fs';

const dbPath = process.env.DB_PATH || path.resolve(process.cwd(), 'data', 'seniorcall.db');

// Ensure directory exists
const dir = path.dirname(dbPath);
if (!fs.existsSync(dir)) {
  fs.mkdirSync(dir, { recursive: true });
}

export const db = new DatabaseSync(dbPath);

// Initialize schema
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA synchronous = NORMAL;

  CREATE TABLE IF NOT EXISTS users (
    userId TEXT PRIMARY KEY,
    role TEXT NOT NULL,
    deviceName TEXT NOT NULL,
    createdAt INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS pairings (
    elderId TEXT NOT NULL,
    familyId TEXT NOT NULL,
    pairedAt INTEGER NOT NULL,
    PRIMARY KEY (elderId, familyId)
  );

  CREATE TABLE IF NOT EXISTS pair_codes (
    code TEXT PRIMARY KEY,
    elderId TEXT NOT NULL,
    expiresAt INTEGER NOT NULL,
    attempts INTEGER DEFAULT 0,
    used INTEGER DEFAULT 0
  );

  CREATE TABLE IF NOT EXISTS contacts (
    elderId TEXT NOT NULL,
    contactId TEXT NOT NULL,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    photoUrl TEXT,
    autoAnswerAllowed INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 1,
    language TEXT DEFAULT 'en',
    PRIMARY KEY (elderId, contactId)
  );

  CREATE TABLE IF NOT EXISTS fcm_tokens (
    userId TEXT NOT NULL,
    token TEXT NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY (userId, token)
  );

  CREATE TABLE IF NOT EXISTS call_logs (
    callId TEXT PRIMARY KEY,
    callerId TEXT NOT NULL,
    calleeId TEXT NOT NULL,
    media TEXT NOT NULL,
    state TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    connectedAt INTEGER,
    endedAt INTEGER,
    duration INTEGER,
    reason TEXT
  );
`);

console.log(`[DB] SQLite initialized at ${dbPath}`);
