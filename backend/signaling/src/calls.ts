import { v4 as uuidv4 } from 'uuid';
import { ActiveCall, CallState } from './types.js';
import { db } from './db.js';

const calls = new Map<string, ActiveCall>();

const RING_TIMEOUT_MS = 45_000; // 45 seconds (§11)
const CONNECT_TIMEOUT_MS = 25_000; // 25 seconds (§11)
const SIGNALING_GRACE_MS = 60_000; // 60 seconds grace on socket drop (§11 / §3.5 S3)

export interface QueuedMessage {
  id: string;
  seq?: number;
  msg: any;
  expiresAt: number;
}

// userId -> QueuedMessage[]
const outbox = new Map<string, QueuedMessage[]>();

// userId -> Timeout
const graceTimers = new Map<string, NodeJS.Timeout>();

export function createCall(
  callerId: string,
  calleeId: string,
  media: 'audio' | 'video',
  onTimeout: (call: ActiveCall, reason: string) => void
): ActiveCall {
  const callId = `call_${uuidv4().replace(/-/g, '').slice(0, 16)}`;

  const call: ActiveCall = {
    callId,
    callerId,
    calleeId,
    media,
    state: 'INVITING',
    createdAt: Date.now(),
  };

  call.timeoutTimer = setTimeout(() => {
    if (call.state === 'INVITING' || call.state === 'RINGING' || call.state === 'WAKING') {
      endCall(callId, 'no_answer');
      onTimeout(call, 'no_answer');
    }
  }, RING_TIMEOUT_MS);

  calls.set(callId, call);
  return call;
}

export function getCall(callId: string): ActiveCall | undefined {
  return calls.get(callId);
}

export function getActiveCallForUser(userId: string): ActiveCall | undefined {
  for (const call of calls.values()) {
    if ((call.callerId === userId || call.calleeId === userId) && call.state !== 'ENDED') {
      return call;
    }
  }
  return undefined;
}

export function setCallWaking(callId: string): boolean {
  const call = calls.get(callId);
  if (call && call.state === 'INVITING') {
    call.state = 'WAKING';
    return true;
  }
  return false;
}

export function setCallRinging(callId: string): boolean {
  const call = calls.get(callId);
  if (call && (call.state === 'INVITING' || call.state === 'WAKING')) {
    call.state = 'RINGING';
    return true;
  }
  return false;
}

export function setCallConnecting(
  callId: string,
  onTimeout: (call: ActiveCall, reason: string) => void
): boolean {
  const call = calls.get(callId);
  if (call && (call.state === 'RINGING' || call.state === 'INVITING' || call.state === 'WAKING')) {
    call.state = 'CONNECTING';

    if (call.timeoutTimer) clearTimeout(call.timeoutTimer);
    call.timeoutTimer = setTimeout(() => {
      if (call.state === 'CONNECTING') {
        endCall(callId, 'connect_timeout');
        onTimeout(call, 'connect_timeout');
      }
    }, CONNECT_TIMEOUT_MS);

    return true;
  }
  return false;
}

export function setCallConnected(callId: string): boolean {
  const call = calls.get(callId);
  if (call && (call.state === 'CONNECTING' || call.state === 'RINGING')) {
    call.state = 'CONNECTED';
    call.connectedAt = Date.now();
    if (call.timeoutTimer) {
      clearTimeout(call.timeoutTimer);
      call.timeoutTimer = undefined;
    }
    return true;
  }
  return false;
}

export function endCall(callId: string, reason = 'normal'): ActiveCall | undefined {
  const call = calls.get(callId);
  if (call) {
    call.state = 'ENDED';
    call.endedAt = Date.now();
    if (call.timeoutTimer) {
      clearTimeout(call.timeoutTimer);
      call.timeoutTimer = undefined;
    }

    // Persist to SQLite call_logs
    try {
      const duration = call.connectedAt ? Math.round((call.endedAt - call.connectedAt) / 1000) : 0;
      const insert = db.prepare(`
        INSERT OR REPLACE INTO call_logs (callId, callerId, calleeId, media, state, createdAt, connectedAt, endedAt, duration, reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);
      insert.run(
        call.callId,
        call.callerId,
        call.calleeId,
        call.media,
        call.state,
        call.createdAt,
        call.connectedAt || null,
        call.endedAt,
        duration,
        reason
      );
    } catch (e) {
      console.error('[DB] Failed to log call', e);
    }

    // Keep call record for 60s for idempotent message handling (§3.7)
    setTimeout(() => {
      calls.delete(callId);
    }, 60_000).unref();
  }
  return call;
}

// S3: Signaling loss grace period
export function handleSignalingLoss(userId: string, onGraceExpired: (call: ActiveCall) => void) {
  const activeCall = getActiveCallForUser(userId);
  if (!activeCall || activeCall.state === 'ENDED') return;

  console.log(`[Calls] User ${userId} disconnected during call ${activeCall.callId}. Starting 60s grace timer.`);

  // Cancel any existing timer for this user
  const existing = graceTimers.get(userId);
  if (existing) clearTimeout(existing);

  const timer = setTimeout(() => {
    graceTimers.delete(userId);
    const stillActive = getActiveCallForUser(userId);
    if (stillActive && stillActive.callId === activeCall.callId && stillActive.state !== 'ENDED') {
      console.warn(`[Calls] Grace timer expired for ${userId} in call ${activeCall.callId}. Ending call.`);
      endCall(activeCall.callId, 'peer_unreachable');
      onGraceExpired(activeCall);
    }
  }, SIGNALING_GRACE_MS).unref();

  graceTimers.set(userId, timer);
}

export function handleSignalingResume(userId: string) {
  const timer = graceTimers.get(userId);
  if (timer) {
    console.log(`[Calls] User ${userId} resumed connection. Canceling grace timer.`);
    clearTimeout(timer);
    graceTimers.delete(userId);
  }
}

// S4: Outbox queue
export function queueForOfflineUser(userId: string, msg: any, ttlMs = 45_000) {
  let list = outbox.get(userId);
  if (!list) {
    list = [];
    outbox.set(userId, list);
  }
  list.push({
    id: msg.id || uuidv4(),
    seq: msg.seq,
    msg,
    expiresAt: Date.now() + ttlMs,
  });
}

export function drainOutbox(userId: string): any[] {
  const list = outbox.get(userId);
  if (!list || list.length === 0) return [];

  const now = Date.now();
  const valid = list.filter(item => item.expiresAt > now);
  outbox.delete(userId);

  return valid.map(item => item.msg);
}
