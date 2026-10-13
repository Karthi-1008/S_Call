export type UserRole = 'senior' | 'family';

export interface UserSession {
  userId: string;
  role: UserRole;
  pairedWith?: string[];
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

export type CallState = 'IDLE' | 'INVITING' | 'WAKING' | 'RINGING' | 'CONNECTING' | 'CONNECTED' | 'ENDED';

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

// Protocol v2 Message Types (§3.6)
export type SignalingMessageType =
  | 'hello'
  | 'resume'
  | 'ack'
  | 'ping'
  | 'pong'
  | 'call_invite'
  | 'call_state'
  | 'call_accept'
  | 'call_taken'
  | 'call_reject'
  | 'ice_restart_request'
  | 'offer'
  | 'answer'
  | 'ice'
  | 'renegotiate'
  | 'call_connected'
  | 'peer_signaling_lost'
  | 'screen_request'
  | 'screen_request_reply'
  | 'screen_share_started'
  | 'screen_share_stopped'
  | 'call_end'
  | 'presence'
  | 'contacts_update'
  | 'error';

export interface BaseMessage {
  v?: number;
  t: SignalingMessageType;
  id?: string;
  callId?: string;
  seq?: number;
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
  deviceId?: string;
  turnExpiresAt?: number;
  resumeFrom?: number;
}

export interface ResumeMessage extends BaseMessage {
  t: 'resume';
  lastSeq?: number;
  activeCallId?: string;
}

export interface AckMessage extends BaseMessage {
  t: 'ack';
  upTo: number;
}

export interface CallInviteMessage extends BaseMessage {
  t: 'call_invite';
  to: string;
  from?: string;
  media: 'audio' | 'video';
  callerName?: string;
  autoAnswerAllowed?: boolean;
  sdp?: string; // Offer SDP included in invite (§3.6)
}

export interface CallStateMessage extends BaseMessage {
  t: 'call_state';
  callId: string;
  state: 'waking' | 'ringing' | 'connecting';
}

export interface CallAcceptMessage extends BaseMessage {
  t: 'call_accept';
  callId: string;
  sdp?: string; // Answer SDP included in accept (§3.6)
}

export interface CallTakenMessage extends BaseMessage {
  t: 'call_taken';
  callId: string;
  by: string;
}

export interface CallRejectMessage extends BaseMessage {
  t: 'call_reject';
  callId: string;
  reason: 'declined' | 'busy' | string;
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

export interface IceRestartRequestMessage extends BaseMessage {
  t: 'ice_restart_request';
  callId: string;
}

export interface RenegotiateMessage extends BaseMessage {
  t: 'renegotiate';
  callId: string;
  sdp: string;
  type: string;
}

export interface CallConnectedMessage extends BaseMessage {
  t: 'call_connected';
  callId: string;
  pair?: {
    local?: string;
    remote?: string;
    protocol?: string;
  };
}

export interface PeerSignalingLostMessage extends BaseMessage {
  t: 'peer_signaling_lost';
  callId: string;
}

export interface ScreenRequestMessage extends BaseMessage {
  t: 'screen_request';
  callId: string;
  from?: string;
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
  lastSeen?: number;
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
  | ResumeMessage
  | AckMessage
  | CallInviteMessage
  | CallStateMessage
  | CallAcceptMessage
  | CallTakenMessage
  | CallRejectMessage
  | IceRestartRequestMessage
  | SdpMessage
  | IceCandidateMessage
  | RenegotiateMessage
  | CallConnectedMessage
  | PeerSignalingLostMessage
  | ScreenRequestMessage
  | ScreenRequestReplyMessage
  | ScreenShareStateMessage
  | CallEndMessage
  | PresenceMessage
  | ContactsUpdateMessage
  | ErrorMessage;
