package com.seniorcall

import com.google.gson.Gson
import com.seniorcall.signaling.*
import org.junit.Assert.*
import org.junit.Test

class MessagesTest {
    private val gson = Gson()

    @Test
    fun testCallInviteSerialization() {
        val invite = CallInviteMessage(
            callId = "call_12345678",
            to = "usr_elder",
            from = "usr_family",
            media = "video",
            callerName = "Rahul",
            autoAnswerAllowed = true
        )
        val json = gson.toJson(invite)
        assertTrue(json.contains("\"t\":\"call_invite\""))
        assertTrue(json.contains("\"callerName\":\"Rahul\""))
        assertTrue(json.contains("\"autoAnswerAllowed\":true"))

        val parsed = gson.fromJson(json, CallInviteMessage::class.java)
        assertEquals(invite.callId, parsed.callId)
        assertEquals(invite.callerName, parsed.callerName)
        assertTrue(parsed.autoAnswerAllowed)
    }

    @Test
    fun testIceCandidateSerialization() {
        val ice = IceCandidateMessage(
            callId = "call_123",
            candidate = "candidate:1 1 UDP 2122260223 192.168.1.1 5000 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        )
        val json = gson.toJson(ice)
        val parsed = gson.fromJson(json, IceCandidateMessage::class.java)
        assertEquals(ice.candidate, parsed.candidate)
        assertEquals("ice", parsed.t)
    }

    @Test
    fun testScreenShareStateSerialization() {
        val started = ScreenShareStateMessage(
            t = "screen_share_started",
            callId = "call_999",
            trackId = "screen-100"
        )
        val json = gson.toJson(started)
        assertTrue(json.contains("screen_share_started"))
        assertTrue(json.contains("screen-100"))
    }
}
