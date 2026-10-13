package com.seniorcall.signaling

import com.google.gson.annotations.SerializedName
import com.seniorcall.data.Contact

data class TurnConfig(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null
)

data class HelloMessage(
    val t: String = "hello",
    val turn: List<TurnConfig> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val serverTime: Long = 0L,
    val userId: String = ""
)

data class CallInviteMessage(
    val t: String = "call_invite",
    val callId: String = "",
    val to: String = "",
    val from: String = "",
    val media: String = "video", // audio | video
    val callerName: String? = null,
    val autoAnswerAllowed: Boolean = false
)

data class CallRingingMessage(
    val t: String = "call_ringing",
    val callId: String = ""
)

data class CallAcceptMessage(
    val t: String = "call_accept",
    val callId: String = ""
)

data class CallRejectMessage(
    val t: String = "call_reject",
    val callId: String = "",
    val reason: String = "declined" // declined | busy
)

data class SdpMessage(
    val t: String = "offer", // offer | answer
    val callId: String = "",
    val sdp: String = ""
)

data class IceCandidateMessage(
    val t: String = "ice",
    val callId: String = "",
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0
)

data class RenegotiateMessage(
    val t: String = "renegotiate",
    val callId: String = "",
    val sdp: String = "",
    val type: String = "offer"
)

data class ScreenRequestMessage(
    val t: String = "screen_request",
    val callId: String = "",
    val from: String = ""
)

data class ScreenRequestReplyMessage(
    val t: String = "screen_request_reply",
    val callId: String = "",
    val accepted: Boolean = false
)

data class ScreenShareStateMessage(
    val t: String = "screen_share_started", // screen_share_started | screen_share_stopped
    val callId: String = "",
    val trackId: String? = null
)

data class CallEndMessage(
    val t: String = "call_end",
    val callId: String = "",
    val reason: String = "normal"
)

data class PresenceMessage(
    val t: String = "presence",
    val userId: String = "",
    val online: Boolean = false,
    val battery: Int? = null,
    val lastSeen: Long = 0L
)

data class ContactsUpdateMessage(
    val t: String = "contacts_update",
    val contacts: List<Contact> = emptyList()
)

data class GenericMessage(
    val t: String,
    val callId: String? = null,
    val userId: String? = null
)
