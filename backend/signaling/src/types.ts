export type UserRole = 'senior' | 'family';

export interface UserSession {
  userId: string;
  role: UserRole;
  pairedWith?: string[]; // IDs of paired contacts
  fcmToken?: string;
  online: boolean;
  battery?: number;
  lastSeen: number;
}

export interface ContactInfo {
  id: string;
  name: string;
  role: UserRole;
  photoUrl?: string;
  autoAnswerAllowed: boolean;
  priority: number;
  language?: string;
}

export type CallState = 'IDLE' | 'INVITING' | 'RINGING' | 'CONNECTING' | 'CONNECTED' | 'ENDED';

export interface ActiveCall {
  callId: string;
  callerId: string;
  calleeId: string;
  media: 'audio' | 'video';
  state: CallState;
  createdAt: number;
  connectedAt?: number;
  endedAt?: number;
  timeoutTimer?: NodeJS.Timeout;
}

// Signaling Protocol Messages (§13.2)
export type SignalingMessageType =
  | 'hello'
  | 'ping'
  | 'pong'
  | 'call_invite'
  | 'call_ringing'
  | 'call_accept'
  | 'call_reject'
  | 'offer'
  | 'answer'
  | 'ice'
  | 'renegotiate'
  | 'screen_request'
  | 'screen_request_reply'
  | 'screen_share_started'
  | 'screen_share_stopped'
  | 'call_end'
  | 'presence'
  | 'contacts_update'
  | 'error';

export interface BaseMessage {
  t: SignalingMessageType;
  id?: string;
  callId?: string;
}

export interface TurnServerConfig {
  urls: string[];
  username?: string;
  credential?: string;
}

export interface HelloMessage extends BaseMessage {
  t: 'hello';
  turn: TurnServerConfig[];
  contacts: ContactInfo[];
  serverTime: number;
  userId: string;
}

export interface CallInviteMessage extends BaseMessage {
  t: 'call_invite';
  to: string;
  from: string;
  media: 'audio' | 'video';
  callerName?: string;
  autoAnswerAllowed?: boolean;
}

export interface CallRingingMessage extends BaseMessage {
  t: 'call_ringing';
  callId: string;
}

export interface CallAcceptMessage extends BaseMessage {
  t: 'call_accept';
  callId: string;
}

export interface CallRejectMessage extends BaseMessage {
  t: 'call_reject';
  callId: string;
  reason: 'declined' | 'busy';
}

export interface SdpMessage extends BaseMessage {
  t: 'offer' | 'answer';
  callId: string;
  sdp: string;
}

export interface IceCandidateMessage extends BaseMessage {
  t: 'ice';
  callId: string;
  candidate: string;
  sdpMid: string;
  sdpMLineIndex: number;
}

export interface RenegotiateMessage extends BaseMessage {
  t: 'renegotiate';
  callId: string;
  sdp: string;
  type: string;
}

export interface ScreenRequestMessage extends BaseMessage {
  t: 'screen_request';
  callId: string;
  from: string;
}

export interface ScreenRequestReplyMessage extends BaseMessage {
  t: 'screen_request_reply';
  callId: string;
  accepted: boolean;
}

export interface ScreenShareStateMessage extends BaseMessage {
  t: 'screen_share_started' | 'screen_share_stopped';
  callId: string;
  trackId?: string;
}

export interface CallEndMessage extends BaseMessage {
  t: 'call_end';
  callId: string;
  reason?: string;
}

export interface PresenceMessage extends BaseMessage {
  t: 'presence';
  userId: string;
  online: boolean;
  battery?: number;
  lastSeen: number;
}

export interface ContactsUpdateMessage extends BaseMessage {
  t: 'contacts_update';
  contacts: ContactInfo[];
}

export interface ErrorMessage extends BaseMessage {
  t: 'error';
  code: string;
  message: string;
}

export type SignalingMessage =
  | HelloMessage
  | BaseMessage
  | CallInviteMessage
  | CallRingingMessage
  | CallAcceptMessage
  | CallRejectMessage
  | SdpMessage
  | IceCandidateMessage
  | RenegotiateMessage
  | ScreenRequestMessage
  | ScreenRequestReplyMessage
  | ScreenShareStateMessage
  | CallEndMessage
  | PresenceMessage
  | ContactsUpdateMessage
  | ErrorMessage;
