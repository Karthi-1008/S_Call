import { v4 as uuidv4 } from 'uuid';
import { ActiveCall, CallState } from './types.js';

const calls = new Map<string, ActiveCall>();

const RING_TIMEOUT_MS = 45_000; // 45 seconds
const CONNECT_TIMEOUT_MS = 15_000; // 15 seconds

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

  // Set ring timeout
  call.timeoutTimer = setTimeout(() => {
    if (call.state === 'INVITING' || call.state === 'RINGING') {
      endCall(callId);
      onTimeout(call, 'ring_timeout');
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

export function setCallRinging(callId: string): boolean {
  const call = calls.get(callId);
  if (call && call.state === 'INVITING') {
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
  if (call && (call.state === 'RINGING' || call.state === 'INVITING')) {
    call.state = 'CONNECTING';

    // Clear old timer and set connect timeout
    if (call.timeoutTimer) clearTimeout(call.timeoutTimer);
    call.timeoutTimer = setTimeout(() => {
      if (call.state === 'CONNECTING') {
        endCall(callId);
        onTimeout(call, 'connect_timeout');
      }
    }, CONNECT_TIMEOUT_MS);

    return true;
  }
  return false;
}

export function setCallConnected(callId: string): boolean {
  const call = calls.get(callId);
  if (call && call.state === 'CONNECTING') {
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

export function endCall(callId: string, _reason?: string): ActiveCall | undefined {
  const call = calls.get(callId);
  if (call) {
    call.state = 'ENDED';
    call.endedAt = Date.now();
    if (call.timeoutTimer) {
      clearTimeout(call.timeoutTimer);
      call.timeoutTimer = undefined;
    }
    // Clean up call from active map after 10 seconds
    setTimeout(() => {
      calls.delete(callId);
    }, 10_000);
    return call;
  }
  return undefined;
}
