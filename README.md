# SeniorCall — Always-On Wi-Fi Video/Audio Call App for Elders (Android 6+)

> **Build brief for Anti-Gravity (or any coding agent/engineer).**
> This document is the single source of truth. Build the project from scratch end-to-end, or fork an open-source WebRTC call app as the core (see §16) and re-skin/re-wire it to this spec.

---

## 1. Product Summary

**SeniorCall** is a dedicated calling app for an Android phone that lives permanently on home Wi-Fi and is used by a senior citizen who is not comfortable with smartphones.

- Works **only over the internet** (Wi-Fi). No SIM / telecom / SMS dependency.
- The other participant (son, daughter, caregiver, doctor) can be **anywhere in the world** on Wi-Fi or cellular data.
- Supports: **receive call, make call, audio call, video call, screen sharing in both directions**, and viewing both sides at once.
- Must be **fast, smooth, and ultra-low latency** (target < 250 ms mouth-to-ear, < 400 ms glass-to-glass globally).
- Must be **very lightweight** and run on **Android 6.0 (API 23) through the latest Android (15/16)**, including low-end 1–2 GB RAM phones.
- UI is designed **for people who have never used a smartphone**: giant buttons, no menus, no gestures, voice + haptic feedback.

### Primary users
| Role | Description |
|---|---|
| **Elder (Senior mode)** | Uses the always-on phone. Sees 2–6 big contact tiles. Taps to call, taps to answer. |
| **Family/Caregiver (Family mode)** | Uses the same app (or a web link) on their own phone/PC anywhere. Calls the elder, can request screen share, can remotely manage contacts/settings of the elder's device. |

---

## 2. Goals & Non-Goals

### Goals
1. One-tap call out, one-tap answer, one-tap hang up.
2. Elder phone is **always reachable** (survives reboot, Doze, app kill, Wi-Fi blips).
3. Optional **auto-answer** for trusted family so the elder doesn't need to do anything.
4. Two-way screen sharing with explicit, simple consent.
5. APK ≤ **20 MB** per ABI (target ~12–15 MB), cold start < 1.5 s on a 2 GB phone, idle RAM < 90 MB.
6. Works behind NAT/firewalls (STUN + TURN over UDP/TCP/TLS-443).

### Non-Goals
- No PSTN/SIM calling, no SMS, no group calls (v1), no chat history, no social features, no ads, no analytics that identify content.

---

## 3. Key Decisions (already made — do not re-litigate)

| Topic | Decision | Reason |
|---|---|---|
| Platform | **Native Android, Kotlin**, `minSdk 23`, `targetSdk 35` (bump yearly) | Lowest latency, full control of foreground services/MediaProjection, smallest size |
| UI toolkit | **Android Views + ViewBinding** (NOT Jetpack Compose) | Compose adds size/startup cost and is slower on Android 6 low-end devices |
| Media engine | **WebRTC** (`org.webrtc` native library, e.g. `io.getstream:stream-webrtc-android` prebuilt, or Google libwebrtc build) | Industry-standard low latency, hardware codecs, adaptive bitrate, NAT traversal |
| Signaling | **Self-hosted WebSocket server** (Node.js 20 + `ws`) over TLS | Simple, fast, full control |
| NAT traversal | **coturn** (STUN+TURN) with UDP 3478, TCP 3478, **TLS 443/5349** | TLS-443 passes strict networks |
| Wake-up when app dead | **Persistent WebSocket in a foreground service** (primary) + **FCM high-priority push** (backup) | Phone is always on Wi-Fi, so own socket is fastest; FCM covers edge cases |
| Auth | **Pairing code / QR** between elder and family. No passwords for the elder. | Elders can't manage passwords |
| Hosting | One small VPS per region (Mumbai, Frankfurt, Virginia) behind GeoDNS | Low latency worldwide |

---

## 4. High-Level Architecture

```
 ┌──────────────────┐        WSS (signaling)        ┌─────────────────────┐
 │  Elder phone     │ <───────────────────────────> │  Signaling Server   │
 │  (Senior mode)   │                               │  Node.js + ws       │
 │  Foreground svc  │                               │  Redis (presence)   │
 └────────┬─────────┘                               │  FCM sender (opt.)  │
          │                                         └──────────┬──────────┘
          │  SRTP media (WebRTC, P2P when possible)            │
          │                                                    │
          │         ┌──────────────────┐                       │
          └────────>│  coturn TURN/STUN│<──────────────────────┘
                    │  UDP/TCP/TLS 443 │
                    └────────┬─────────┘
                             │ relayed media only if P2P fails
 ┌───────────────────────────▼────┐
 │ Family phone (Family mode)     │   or Web client (PWA, optional)
 └────────────────────────────────┘
```

Media is **peer-to-peer whenever possible**; TURN relays only when NAT blocks it. The server never stores media.

---

## 5. Tech Stack & Dependencies

### Android app
- Kotlin 1.9+/2.x, AGP 8.x, Gradle Kotlin DSL
- `minSdk 23`, `compileSdk 35`, `targetSdk 35`
- WebRTC Android library (arm64-v8a, armeabi-v7a; **x86/x86_64 only in debug**)
- OkHttp 4.x (WebSocket client with ping/pong)
- AndroidX: `core-ktx`, `appcompat`, `constraintlayout`, `lifecycle-service`, `work-runtime-ktx` (fallback watchdog), `security-crypto`
- Firebase Messaging (FCM) — optional module, guarded so app still works without Google Play Services
- ZXing-lite / ML Kit-free QR (use `com.journeyapps:zxing-android-embedded` or tiny `zxing-core` to keep size low)
- Coil **not** needed — use plain `BitmapFactory` with downsampled contact photos
- R8 full mode + resource shrinking; ABI splits; `vectorDrawables.useSupportLibrary = true`

### Backend
- Node.js 20, `ws`, `jsonwebtoken`, `ioredis`, `firebase-admin` (optional), `zod`
- coturn 4.6+ (REST-API ephemeral credentials via shared secret)
- Docker Compose, Caddy or nginx for TLS (Let's Encrypt)
- Redis 7 for presence/session lookup (single node per region is enough)

### Web client (optional phase 2)
- Vanilla JS + WebRTC, installable PWA, same signaling protocol

---

## 6. Android Version Compatibility Matrix (API 23 → 35)

| Concern | API 23–25 (6, 7) | API 26–28 (8, 9) | API 29–30 (10, 11) | API 31–35 (12–15) |
|---|---|---|---|---|
| Runtime permissions | Required (request CAMERA, RECORD_AUDIO at first run) | same | same | + `POST_NOTIFICATIONS` (33+) |
| Background service | `startService` OK | Must use **foreground service** w/ notification channel | FG service types appear (29+) | Declare `foregroundServiceType` (`phoneCall`/`microphone`/`camera`/`mediaProjection`/`specialUse`) |
| Incoming-call UI while screen off | `FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`, wake lock | same | **Background activity start blocked** → use **full-screen intent** notification + `setShowWhenLocked(true)/setTurnScreenOn(true)` | `USE_FULL_SCREEN_INTENT` (14+: must be allowed; as a dedicated kiosk device, grant via Settings during setup). Optionally also draw overlay (`SYSTEM_ALERT_WINDOW`) |
| Screen sharing | MediaProjection (API 21+) OK | OK | Must run in FG service with `mediaProjection` type | **Android 14+: consent per session**, FG service must start *after* consent; 15: status-bar chip; handle `onStop` callback |
| Doze/battery | Doze (6+) — request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, keep socket in FG service, hold partial wake lock only during call | + background limits | + app standby buckets | Handle OEM killers (Xiaomi, Oppo, Vivo, Samsung): show a one-time setup checklist |
| TLS | Android 6 has old root store → **bundle ISRG Root X1 in `network_security_config`**; enable TLS 1.2 only (1.3 auto where available) | same | same | same |
| Cleartext | Disallow (`usesCleartextTraffic=false`) except debug | | | |
| Codecs | H.264 HW + VP8 SW fallback | same | same | + AV1 ignored in v1 |
| Locale/fonts | Use system font scaling override: app-level 1.3× min | | | |

Use **`Build.VERSION.SDK_INT` guards**, never raise `minSdk` above 23.

---

## 7. Permissions (AndroidManifest)

```
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE
RECORD_AUDIO, CAMERA, MODIFY_AUDIO_SETTINGS
WAKE_LOCK, VIBRATE, DISABLE_KEYGUARD
RECEIVE_BOOT_COMPLETED
FOREGROUND_SERVICE
FOREGROUND_SERVICE_PHONE_CALL, FOREGROUND_SERVICE_MICROPHONE,
FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MEDIA_PROJECTION,
FOREGROUND_SERVICE_SPECIAL_USE        (API 34+, for the always-on socket)
POST_NOTIFICATIONS                    (API 33+)
USE_FULL_SCREEN_INTENT                (API 29+)
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
SYSTEM_ALERT_WINDOW                   (optional, for incoming-call overlay)
```
No location, contacts, storage, phone-state, or SMS permissions. Contacts are stored in-app, set by family.

---

## 8. Senior-First UX Specification (CRITICAL)

### 8.1 Design principles
1. **One screen = one purpose.** No menus, no hamburger, no tabs, no swipes, no long-press, no double-tap, no gestures.
2. **Every action is a big, labelled button** — icon **and** text, never icon alone.
3. **Colour means the same thing everywhere:** 🟢 Green = Answer/Call, 🔴 Red = End/Decline, 🔵 Blue = Video, 🟠 Orange = Share screen.
4. **Nothing disappears.** Call controls never auto-hide.
5. **Forgiving:** accidental hang-up can be undone with a big "Call back" on the next screen; no destructive confirmations except for settings.
6. **Speak to the user:** Text-to-Speech announces "Rahul is calling. Press the green button to answer."

### 8.2 Visual specs
| Item | Spec |
|---|---|
| Minimum touch target | **96 dp × 96 dp** (primary buttons ≥ 120 dp tall), ≥ 16 dp spacing |
| Body text | ≥ **28 sp**; names ≥ **36 sp** bold; button labels ≥ **28 sp** bold |
| Contrast | WCAG **AAA (7:1)**; default **high-contrast light theme**, optional dark |
| Font | System sans (Roboto), no custom font files (size) |
| Corner radius | 24 dp, thick 3 dp borders on buttons for clear affordance |
| Feedback | Press = colour change + **haptic tick** + subtle click sound |
| Ringtone | Loud, distinctive, repeating; plus vibration + **flashing screen** (for hearing loss) + camera LED flash (optional) |
| Orientation | Portrait locked for Home; Call screen allows rotation |
| Screen | Keep screen on during calls; dim (not off) to ~20% when idle on Home, with clock |

### 8.3 Screens

**A. Home (Senior mode)**
```
┌──────────────────────────────┐
│  Tue 13 Oct   12:25 PM   📶  │  ← big clock/date, Wi-Fi + "Ready" status
│                              │
│ ┌───────────┐ ┌───────────┐  │
│ │  [photo]  │ │  [photo]  │  │  ← 2 columns × up to 3 rows, max 6 tiles
│ │  RAHUL    │ │  PRIYA    │  │     (photo + name, ≥ 36 sp)
│ │ 🎥 VIDEO  │ │ 🎥 VIDEO  │  │
│ │ 📞 VOICE  │ │ 📞 VOICE  │  │  ← two big buttons per tile
│ └───────────┘ └───────────┘  │
│                              │
│  [ 🔴 EMERGENCY – Call Rahul ]│  ← optional pinned SOS tile
└──────────────────────────────┘
```
- Tile tap area = whole tile → opens that contact's **two-button choice** (Video / Voice) only if `simpleMode=false`. Default `simpleMode=true`: tile has two visible buttons directly, no extra step.
- If > 6 contacts, show 4 + "MORE" big button (paged, large "NEXT / BACK").
- Status banner: "✅ Ready to receive calls" / "⚠️ No internet — please check Wi-Fi" (with TTS).

**B. Incoming Call (full-screen, over lock screen)**
- Caller photo fills top half, name 48 sp.
- Two giant buttons, bottom: **🟢 ANSWER** (left/top, 50% width) and **🔴 DECLINE**.
- Auto-answer (if enabled for that contact): 3-2-1 big countdown "Answering Rahul in 3…" with a large **STOP** button.
- Ringing: ringtone + vibration + flashing border + TTS name announcement every 6 s.

**C. In-Call**
```
┌──────────────────────────────┐
│     [ REMOTE VIDEO – large ] │
│                    ┌───────┐ │
│                    │  me   │ │ ← self-view (draggable disabled; fixed corner)
│                    └───────┘ │
│  Rahul  •  04:21   ●●●●○     │ ← name, timer, quality dots
│ ┌────┐ ┌────┐ ┌─────┐ ┌────┐ │
│ │ 🔇 │ │ 📷 │ │SHARE│ │ 🔴 │ │ ← MUTE | CAMERA | SHARE SCREEN | END
│ │MUTE│ │CAM │ │SCRN │ │END │ │   (labels always visible, ≥ 28 sp)
│ └────┘ └────┘ └─────┘ └────┘ │
└──────────────────────────────┘
```
- Speaker defaults **ON (loudspeaker)** for elder; large **VOLUME UP / DOWN** buttons available via a single "LOUDER" button.
- Max 4 buttons. END is always red, rightmost, 20% wider.
- Tap on remote video swaps layout (only for screen share, see D).

**D. Screen-share layouts** ("view both sides, vice versa")
- **Remote is sharing screen →** remote screen fills the main area; remote camera as small tile top-right; my camera small tile bottom-right. Pinch-zoom allowed *only* here (and a big "ZOOM +/-" button pair as an alternative).
- **I am sharing →** a persistent orange bar: "📺 Rahul can see your screen" + giant **STOP SHARING** button; my own camera is hidden unless requested.
- **Both sharing** → split view: top = their screen, bottom = my screen preview (label "What Rahul sees").
- Android-mandated consent dialog is unavoidable on the sharer's device (see §10): Pre-explain with a large custom screen: "Rahul wants to see your screen. Press **YES, SHOW** — then press **START NOW** on the next box." + TTS reading of this.

**E. Post-call / Missed call**
- Big "CALL BACK 🟢" and "OK" buttons. Missed calls show as a red badge on the tile with name + time.

**F. Family mode screens**
- Contacts list, Pair device (QR), Elder-device remote settings, Call history. Standard-size UI is fine (family is tech-savvy) but keep ≥ 48 dp targets.

**G. Setup Wizard (done once by family, standing in front of the device)**
1. Pick **"This phone is for: Senior / Family"**.
2. Grant permissions one at a time with big explanation + TTS.
3. Battery optimisation exemption + OEM autostart guidance (detect `Build.MANUFACTURER`, show screenshots/steps).
4. Allow full-screen intents / overlay.
5. Pair with family via **QR / 6-digit code**.
6. Add contacts (photo from gallery/camera, name, language, auto-answer toggle, priority).
7. Enable **Kiosk mode** (§12) and finish with a test call.

### 8.4 Accessibility extras
- Full TalkBack labels on all controls (`contentDescription`).
- App-level **font scale ≥ 1.3×** regardless of system setting; optional "Extra Large".
- Multi-language UI incl. **English, Hindi, Tamil, Telugu, Kannada, Malayalam, Bengali, Marathi, Gujarati** (strings in `res/values-xx`); TTS in selected language.
- No timeouts that auto-dismiss important UI.
- Optional **voice command** "Call Rahul" (Android `SpeechRecognizer`, offline where available) — phase 3.

---

## 9. Functional Requirements

### 9.1 Calling
| ID | Requirement |
|---|---|
| F1 | Elder can place an **audio** or **video** call to any saved contact with one tap. |
| F2 | Elder can **receive** a call with one tap (or auto-answer if allowed). |
| F3 | Either side can end the call; other side is notified < 300 ms. |
| F4 | **Mid-call upgrade/downgrade**: audio→video and video→audio via renegotiation, with one button. |
| F5 | **Redial**: "Call back" on missed/ended screens. |
| F6 | If the callee is offline, caller sees "Rahul is not available" + option "Leave a ring" (push notification via FCM to family). |
| F7 | **Busy handling**: second incoming call during a call shows small non-blocking banner; elder may ignore (auto-decline with "busy"). |
| F8 | Network handover (Wi-Fi ↔ cellular on family side, router reboot on elder side) → **ICE restart** automatically, call survives up to 30 s dropout. |
| F9 | Echo cancellation, noise suppression, AGC on (WebRTC AEC3 / hardware AEC if available). |
| F10 | Bluetooth headset/hearing-aid routing supported (`AudioManager` / `AudioDeviceInfo`). |

### 9.2 Screen sharing (bidirectional)
| ID | Requirement |
|---|---|
| S1 | Either participant can share screen during any call (audio or video). |
| S2 | Both can share **simultaneously**; each side renders remote screen + remote camera. |
| S3 | Sharer sees an unmissable "You are sharing" banner and a one-tap stop. |
| S4 | Screen share uses a **separate video track/transceiver** (not replacing the camera) so camera + screen both flow. |
| S5 | Screen track uses `contentHint = "detail"`, 1280×720 max (720×1280 portrait), 10–15 fps default, 30 fps if bandwidth allows. |
| S6 | **Family can request** share: elder receives a big "Rahul wants to see your screen — YES / NO" prompt. Family **cannot** force-start sharing (OS forbids; consent always needed). |
| S7 | Optional phase 3: **remote assist overlay** (family draws arrows/circles on the shared screen; elder sees them) via data channel + overlay window. Remote *control* via AccessibilityService is **out of scope v1** (privacy/Play policy). |

### 9.3 Always-on behaviour
| ID | Requirement |
|---|---|
| A1 | `CallService` (foreground, `specialUse`/`phoneCall`) holds a **persistent WebSocket** with heartbeat every 15 s; reconnect with exponential backoff capped at 5 s, immediate retry on `ConnectivityManager.onAvailable`. |
| A2 | Starts on **boot** (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) with no user action. |
| A3 | **Watchdog**: `WorkManager` periodic (15 min) + `AlarmManager.setExactAndAllowWhileIdle` heartbeat to resurrect service if killed. |
| A4 | Keep Wi-Fi alive: `WifiManager.WifiLock(WIFI_MODE_FULL_HIGH_PERF/LOW_LATENCY)` held while service runs (plugged-in device — acceptable power). |
| A5 | Recommended: device always on charger; show battery <15% warning to family via push. |
| A6 | Self-health: if socket down > 60 s, show on-screen warning + notify family ("Dad's phone is offline"). |
| A7 | Auto-recover from crash (default uncaught handler restarts service). |

### 9.4 Contacts & pairing
- Contacts are stored **on server per elder account** and cached locally (encrypted, `EncryptedSharedPreferences`/Room-less lightweight JSON file). Family edits → synced to elder phone live.
- Pairing: elder device shows **QR + 6-digit code** → family scans/enters → server links accounts (`familyId ↔ elderId`). Rate-limited, code expires in 10 min.
- Elder may have many family contacts; family may manage many elders.

---

## 10. Screen Sharing — Technical Notes (Android specifics)

1. Request `MediaProjectionManager.createScreenCaptureIntent()` from an Activity.
2. On result OK, **start the foreground service with type `mediaProjection` first**, *then* call `getMediaProjection()` (mandatory on API 29+; on 34+ reuse of the token is not allowed → request each time).
3. Use WebRTC's `ScreenCapturerAndroid(resultData, callback)` + `SurfaceTextureHelper`; create a second `VideoSource` (`isScreencast = true`) and `VideoTrack` ("screen").
4. Add via `PeerConnection.addTransceiver(MediaType.VIDEO, SEND_ONLY)` → renegotiate. Remote identifies the track by **`msid`/track id prefix `screen-`** (also signalled in the `screen_share_started` message).
5. Handle `MediaProjection.Callback.onStop()` (user stops from system UI) → send `screen_share_stopped`.
6. Screen share **audio** (system audio, API 29+) is optional; off by default.
7. Reduce load: cap capture to 1280×720, downscale on weak devices, `degradationPreference = MAINTAIN_RESOLUTION` for screen track and `MAINTAIN_FRAMERATE` for camera.

---

## 11. Low-Latency & Performance Engineering (targets + how)

| Target | Value |
|---|---|
| Call setup (tap → ringing on remote) | **< 1.0 s** (same region), < 2.0 s global |
| Answer → first remote frame | **< 700 ms** |
| One-way audio latency | **< 200 ms** typical |
| Video glass-to-glass | **< 350 ms** typical |
| Cold start of app | < 1.5 s on 2 GB device |
| Idle CPU | < 1 % (socket only) |
| APK | ≤ 15–20 MB / ABI |

**How:**
1. **Pre-warm:** keep `PeerConnectionFactory` initialised in the service; on incoming call create `PeerConnection` immediately on `offer` arrival (even before the user answers) and gather ICE candidates so the answer is instant ("early ICE"). Pre-create audio device module.
2. **Trickle ICE** + pre-fetched TURN credentials (refresh every 30 min) so no REST call on the critical path.
3. **Codec policy:**
   - Audio: **Opus** 48 kHz, `maxaveragebitrate=32000` (speech) , `useinbandfec=1`, `usedtx=1`, `ptime=20`, `stereo=0`.
   - Video: prefer **H.264 (Constrained Baseline, HW)** if both sides support; else **VP8**. Disable VP9/AV1 on devices < API 28 / low RAM.
   - Enable hardware encoder/decoder with `HardwareVideoEncoderFactory(eglContext, enableIntelVp8Encoder=false, enableH264HighProfile=false)`; fallback to `SoftwareVideoEncoderFactory`.
4. **Bandwidth adaptation:** enable **transport-cc / GCC**, REMB, NACK, PLI/FIR; start bitrate 600 kbps, min 150 kbps, max 1.5 Mbps (camera 640×480@24 default for elder uplink; upgrade to 720p if BWE > 1.2 Mbps). Auto-drop video to audio-only below ~120 kbps with a friendly banner ("Weak internet – showing voice only").
5. **Jitter buffer:** keep default adaptive NetEq for audio; set `playoutDelayHint` ≈ 0 on video receivers.
6. **Network:** bind to Wi-Fi network (`ConnectivityManager.bindProcessToNetwork`) so media doesn't leak to a missing mobile network; DSCP marking (`networkPriority = HIGH`, `setDscp`) when router honours it.
7. **Server placement:** regional signaling + coturn, GeoDNS / latency-based routing; elder & family attach to nearest region; inter-region signaling via Redis pub/sub.
8. **Rendering:** `SurfaceViewRenderer` (not TextureView), `setEnableHardwareScaler(true)`, mirror only self-view, no per-frame Kotlin allocations.
9. **Threading:** all WebRTC calls on WebRTC's signalling thread; UI thread only for views. No blocking I/O on main thread. StrictMode enabled in debug.
10. **Memory:** reuse `EglBase` context; release renderers in `onDestroy`; limit contact photos to 256×256 WebP cached on disk.
11. **Size:** R8 full mode, `shrinkResources`, `abiFilters "arm64-v8a","armeabi-v7a"`, App Bundle for Play distribution + universal APKs for side-load; strip WebRTC unused codecs (use a trimmed prebuilt if available).

---

## 12. Kiosk / Senior-Safe Device Mode

Goal: elder can't get lost in Android.

- Set app as **HOME launcher** (`<category android:name="android.intent.category.HOME"/>`), so pressing Home returns to SeniorCall.
- **Screen pinning / Lock Task Mode:**
  - Preferred: device owner via `adb shell dpm set-device-owner com.seniorcall/.AdminReceiver` (done once by family) → `startLockTask()` with whitelisted packages, disable status bar & keyguard.
  - Fallback (no device owner): `startLockTask()` screen pinning (user prompt once) + hide system UI immersive sticky.
- Hide: notification shade pulls, settings, play store, app drawer.
- **Hidden Family Settings:** accessible only via a PIN (e.g., tap logo 5× + 4-digit PIN) — never visible to elder.
- Disable: lock-screen swipe, screen timeout → "dim after 2 min, never off" (or off with wake on call).
- Auto-volume: force `STREAM_VOICE_CALL` and `STREAM_RING` to ≥ 80 % at call start.

---

## 13. Backend Specification

### 13.1 Repo layout
```
/backend
  /signaling        # Node.js WebSocket server
    src/index.ts
    src/auth.ts            # JWT, pairing
    src/presence.ts        # Redis presence
    src/calls.ts           # call state machine
    src/push.ts            # FCM
    src/turn.ts            # ephemeral TURN creds
  /coturn
    turnserver.conf
  docker-compose.yml
  Caddyfile
/web-client          # optional PWA
/android             # app (see §14)
```

### 13.2 Signaling protocol (JSON over WSS `wss://api.example.com/ws?token=JWT`)

Every message: `{ "t": "<type>", "id": "<uuid>", "callId": "<uuid>", ...payload }`

| Type | Direction | Payload |
|---|---|---|
| `hello` | S→C | `{turn:[{urls,username,credential}], contacts:[…], serverTime}` |
| `ping/pong` | both | every 15 s |
| `call_invite` | C→S→C | `{to, from, media:"audio"\|"video", autoAnswerAllowed}` |
| `call_ringing` | callee→caller | `{callId}` |
| `call_accept` | callee→caller | `{callId}` |
| `call_reject` | callee→caller | `{callId, reason:"declined"\|"busy"}` |
| `offer` / `answer` | peers | `{callId, sdp}` |
| `ice` | peers | `{callId, candidate, sdpMid, sdpMLineIndex}` |
| `renegotiate` | peers | `{callId, sdp, type}` (for add video / screen) |
| `screen_request` | family→elder | `{callId}` (elder shows YES/NO) |
| `screen_request_reply` | elder→family | `{callId, accepted}` |
| `screen_share_started/stopped` | sharer→peer | `{callId, trackId}` |
| `call_end` | either | `{callId, reason}` |
| `presence` | S→C | `{userId, online, battery?, lastSeen}` |
| `contacts_update` | S→elder | full contacts JSON |
| `error` | S→C | `{code,message}` |

**Call state machine:** `IDLE → INVITING → RINGING → CONNECTING → CONNECTED → ENDED` (with timeouts: ring 45 s, connect 15 s).

### 13.3 REST endpoints (HTTPS)
- `POST /v1/register` `{role, deviceName, fcmToken?}` → `{userId, jwt, refresh}`
- `POST /v1/pair/start` (elder) → `{code, qrPayload, expiresAt}`
- `POST /v1/pair/confirm` (family) `{code}`
- `GET/PUT /v1/contacts` (family edits elder's)
- `POST /v1/push-token`
- `GET /v1/turn` → ephemeral creds (`username = expiry:userId`, `credential = HMAC-SHA1(secret, username)`, TTL 1 h)

### 13.4 coturn essentials
```
listening-port=3478
tls-listening-port=443          # share with Caddy via SNI/stream or use 5349 + alt port
fingerprint
use-auth-secret
static-auth-secret=<SECRET>
realm=turn.example.com
no-multicast-peers
no-cli
cert=/etc/letsencrypt/live/turn.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/turn.example.com/privkey.pem
min-port=49160
max-port=49200
```
ICE servers list given to clients: `stun:turn…:3478`, `turn:…:3478?transport=udp`, `turn:…:3478?transport=tcp`, `turns:…:443?transport=tcp`.

### 13.5 Security
- TLS everywhere; WebRTC media is **DTLS-SRTP end-to-end encrypted** (server never sees media).
- JWT (short-lived 24 h access + refresh), device-bound.
- Only paired users may call each other (server-enforced allow-list).
- Rate limiting on pairing and invites; Redis TTL on codes.
- Privacy: no recordings, no media logs; log only call metadata (who, when, duration) with 30-day retention, deletable.
- Screen share always requires on-device OS consent; app shows persistent indicator.
- Family-mode PIN protects hidden settings on the elder device.

---

## 14. Android Project Structure

```
android/
 ├─ app/
 │   ├─ build.gradle.kts
 │   ├─ proguard-rules.pro
 │   └─ src/main/
 │       ├─ AndroidManifest.xml
 │       ├─ java/com/seniorcall/
 │       │   ├─ App.kt                         # Application: init WebRTC, notification channels
 │       │   ├─ core/
 │       │   │   ├─ Prefs.kt                   # encrypted prefs
 │       │   │   ├─ Net.kt                     # connectivity, wifi lock
 │       │   │   ├─ Tts.kt                     # text-to-speech helper
 │       │   │   └─ Haptics.kt, Sounds.kt
 │       │   ├─ signaling/
 │       │   │   ├─ SignalingClient.kt         # OkHttp WebSocket, reconnect, heartbeat
 │       │   │   └─ Messages.kt                # data classes (kotlinx.serialization or Moshi-lite)
 │       │   ├─ rtc/
 │       │   │   ├─ RtcEngine.kt               # PeerConnectionFactory, pre-warm
 │       │   │   ├─ CallSession.kt             # PeerConnection lifecycle, ICE restart, stats
 │       │   │   ├─ ScreenShare.kt             # MediaProjection + track mgmt
 │       │   │   ├─ AudioRouter.kt             # speaker/BT/earpiece
 │       │   │   └─ QualityMonitor.kt          # getStats → quality dots / audio-only fallback
 │       │   ├─ service/
 │       │   │   ├─ CallService.kt             # foreground, always-on socket
 │       │   │   ├─ ScreenShareService.kt      # FG type mediaProjection
 │       │   │   ├─ BootReceiver.kt
 │       │   │   ├─ Watchdog.kt                # WorkManager + AlarmManager
 │       │   │   ├─ PushService.kt             # FCM (optional)
 │       │   │   └─ AdminReceiver.kt           # device-owner for kiosk
 │       │   ├─ ui/
 │       │   │   ├─ HomeActivity.kt            # senior home (launcher)
 │       │   │   ├─ IncomingCallActivity.kt    # showWhenLocked/turnScreenOn
 │       │   │   ├─ CallActivity.kt            # in-call + share layouts
 │       │   │   ├─ ShareConsentActivity.kt    # pre-explainer screens
 │       │   │   ├─ PostCallActivity.kt
 │       │   │   ├─ setup/                     # wizard
 │       │   │   └─ family/                    # family-mode screens
 │       │   └─ data/
 │       │       ├─ Contact.kt, ContactStore.kt, CallLog.kt
 │       └─ res/
 │           ├─ layout/ (+ layout-land for CallActivity)
 │           ├─ values/ (+ values-hi, -ta, -te, -kn, -ml, -bn, -mr, -gu)
 │           ├─ drawable/ (vector icons only; 1 raster fallback ≤ 48 KB)
 │           ├─ raw/ ringtone.ogg (≤ 80 KB), click.ogg
 │           └─ xml/network_security_config.xml
 ├─ build.gradle.kts
 └─ settings.gradle.kts
```

### 14.1 Key Gradle settings
```kotlin
android {
  compileSdk = 35
  defaultConfig {
    applicationId = "com.seniorcall"
    minSdk = 23; targetSdk = 35
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    vectorDrawables.useSupportLibrary = true
    multiDexEnabled = false        // keep method count < 64k; if needed enable for API 21+ (fine)
  }
  buildTypes { release {
    isMinifyEnabled = true; isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
  }}
  splits { abi { isEnable = true; reset(); include("arm64-v8a","armeabi-v7a"); isUniversalApk = true } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  buildFeatures { viewBinding = true }
}
```
`network_security_config.xml`: base config trusting system CAs **plus a bundled ISRG Root X1** (`res/raw/isrg_root_x1.pem`) so Android 6/7 connect to Let's Encrypt hosts.

### 14.2 Manifest highlights
```xml
<application android:allowBackup="false" android:usesCleartextTraffic="false"
             android:networkSecurityConfig="@xml/network_security_config" ...>
  <activity android:name=".ui.HomeActivity" android:exported="true"
            android:launchMode="singleTask" android:screenOrientation="portrait">
     <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
        <category android:name="android.intent.category.HOME"/>
        <category android:name="android.intent.category.DEFAULT"/>
     </intent-filter>
  </activity>
  <activity android:name=".ui.IncomingCallActivity" android:showWhenLocked="true"
            android:turnScreenOn="true" android:launchMode="singleInstance"/>
  <service android:name=".service.CallService" android:foregroundServiceType="specialUse|microphone|camera" .../>
  <service android:name=".service.ScreenShareService" android:foregroundServiceType="mediaProjection"/>
  <receiver android:name=".service.BootReceiver" android:exported="true"> BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, MY_PACKAGE_REPLACED </receiver>
</application>
```
> Note for API 34+: `microphone`/`camera` FG service types may only be *started* while the app is visible or by a permitted exemption. Therefore: the always-on service runs as `specialUse` (socket only); on an incoming call, **start the call activity via full-screen intent**, and only after the user/auto-answer brings the Activity to foreground, **promote/start the media FG service** (`phoneCall` or `microphone|camera`).

### 14.3 Incoming-call flow (critical path)
1. `SignalingClient` receives `call_invite` → `CallService` posts a **high-priority full-screen-intent notification** (channel `calls`, importance HIGH, category `CALL`) and starts ringtone/vibration/flash/TTS.
2. `IncomingCallActivity` shows over lock screen (`setShowWhenLocked`, `setTurnScreenOn`, `KeyguardManager.requestDismissKeyguard`).
3. In parallel, `RtcEngine` creates `PeerConnection`, sets remote offer (if arrived), gathers ICE (pre-warm).
4. User taps **ANSWER** (or auto-answer countdown ends) → `createAnswer` → send → media flows.
5. Start FG media service, route audio to speaker, keep screen on.

### 14.4 WebRTC configuration snippet (reference)
```kotlin
val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
  sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
  continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
  iceTransportsType = PeerConnection.IceTransportsType.ALL
  bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
  rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
  tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
  candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
  iceCandidatePoolSize = 2            // pre-gather
  enableDscp = true
  enableCpuOveruseDetection = true
}
```
Audio constraints: `googEchoCancellation`, `googNoiseSuppression`, `googAutoGainControl`, `googHighpassFilter` = true.

---

## 15. Build Plan for Anti-Gravity (execute in order)

**Phase 0 — Scaffolding (Day 1)**
- Create monorepo `/android`, `/backend`, `/web-client`, `/docs`.
- Bootstrap Gradle project (§14.1), CI (GitHub Actions: build, lint, unit tests, assemble ABI APKs).

**Phase 1 — Backend MVP (Days 2–4)**
- Signaling server with JWT, presence, call state machine, pairing, contacts API, TURN creds.
- Docker Compose with Caddy + coturn + Redis. Deploy to one VPS. Add `/healthz`.
- Integration tests with two simulated WebSocket clients.

**Phase 2 — Android core calling (Days 5–10)**
- `SignalingClient`, `RtcEngine`, `CallSession`, audio call end-to-end.
- Add video, camera switching, mute, speaker, BT routing.
- `CallService` always-on + `BootReceiver` + watchdog + wifi lock + battery exemption flow.
- Incoming-call full-screen UI across API 23–35.

**Phase 3 — Senior UI (Days 8–12, parallel)**
- Home, Incoming, In-call, Post-call screens per §8 with AAA contrast, 96 dp targets, TTS, haptics, multilingual strings.
- Setup wizard + pairing (QR/code) + contact photos.

**Phase 4 — Screen sharing (Days 11–14)**
- `ScreenShare` + `ScreenShareService`, request/consent flows, both-way layouts, stop handling, Android 14/15 behaviour.

**Phase 5 — Kiosk & hardening (Days 14–17)**
- Launcher mode, lock task, device owner script, hidden family settings, OEM battery guides, crash-restart, offline banners.

**Phase 6 — Performance & QA (Days 17–21)**
- Latency instrumentation (§11), adaptive bitrate tuning, size reduction, startup profiling (Baseline Profiles for API 28+).
- Device matrix tests (§17), network chaos tests (packet loss, jitter, handover).

**Phase 7 — Family web client & polish (optional, Days 22–26)**
- PWA with call + screen share + contact management. Release builds, signed APK/AAB, privacy policy.

> After each phase: run tests, commit, tag, and update `/docs/CHANGELOG.md`.

---

## 16. Reuse Options (if forking a core instead of from scratch)

Anti-Gravity may fork a base and then apply §8, §9.3, §12 on top. Candidates (verify license + activity before use):
- **google-webrtc / AppRTC Android demo** — minimal reference for PeerConnection + signaling.
- **Jitsi Meet SDK (Android)** — full-featured (screen share, TURN), heavier (~40 MB+); only if time-to-market matters more than size.
- **LinphoneAndroid / Linphone SDK (SIP)** — mature always-on calling, but SIP adds server complexity.
- **Stream WebRTC Android (`stream-webrtc-android`)** — maintained prebuilt libwebrtc, ideal as the engine dependency.
- **LiveKit Android SDK** — excellent SFU-based option if group calls are later required (adds server cost).

Recommended: **from-scratch app UI + `stream-webrtc-android` + own signaling** (lightest, best latency, full control).

---

## 17. Testing & Acceptance Criteria

### 17.1 Device/OS matrix (minimum)
| API | Example devices | Must pass |
|---|---|---|
| 23 (6.0) | Moto G3, Samsung J7 (old) | install, call audio+video, boot start, TLS to Let's Encrypt |
| 24–25 (7.x) | Redmi 4, Galaxy J2 | same + Doze behaviour |
| 26–28 (8–9) | Redmi Note 5/7, Galaxy A10 | FG service rules, full-screen call UI |
| 29–30 (10–11) | Realme, Vivo, Samsung A series | MediaProjection FG rules, BG activity block |
| 31–33 (12–13) | Pixel, Samsung A/M | notification permission, exact alarms |
| 34–35 (14–15) | Pixel 8/9, Samsung S24 | FGS type enforcement, per-session screen consent |
| Low-RAM | 1–2 GB devices (Android Go) | smooth 480p video, idle RAM < 90 MB |
| OEMs | Xiaomi (MIUI/HyperOS), Oppo/Realme (ColorOS), Vivo (Funtouch), Samsung (One UI), Tecno/Infinix | survives 24 h idle + reboot |

### 17.2 Acceptance tests
1. **Reboot test:** reboot elder phone → within 60 s it is "Ready", incoming call rings with screen off.
2. **24-hour soak:** idle on Wi-Fi for 24 h with screen dimmed; then receive call within 1.5 s of invite.
3. **Router reboot:** disconnect Wi-Fi 20 s during call → call recovers automatically via ICE restart.
4. **Cellular family side:** family on 4G in another country → video call connects ≤ 2 s; audio latency ≤ 250 ms (measured with loopback tool).
5. **Strict firewall:** only TCP 443 open → call connects via `turns:443`.
6. **Screen share:** both directions simultaneously, 10 min, no crash, CPU < 60 % on a 2 GB phone.
7. **Usability test:** 5 seniors (age 65+) with no prior smartphone use complete: answer call, place call, end call, show them a screen — **≥ 90 % task success without help** after a 2-minute demo.
8. **Size/startup:** release APK ≤ 20 MB/ABI; cold start < 1.5 s.
9. **Accessibility:** TalkBack navigable, all touch targets ≥ 96 dp (automated lint test), font scale 1.3× no clipping.
10. **Security:** unpaired user cannot invite elder; expired pairing code rejected; no cleartext traffic.

### 17.3 Metrics to log locally (debug) / anonymous aggregate (opt-in)
Call setup time, ICE state timeline, selected candidate pair (host/srflx/relay), RTT, jitter, packet loss, bitrate, fps, freeze count, battery drain/hour.

---

## 18. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| OEMs killing background service | Setup wizard OEM guides, battery exemption, autostart, watchdog, FG notification |
| Android 14+ FGS start restrictions | Always-on socket as `specialUse`; promote to media FGS only from foreground Activity (§14.2) |
| Full-screen intent not shown (Android 14+ policy) | Treat device as dedicated; grant permission in setup; also use overlay + wake lock fallback |
| Old Android 6/7 TLS roots | Bundle ISRG Root X1 in network security config |
| Poor home Wi-Fi | Audio-first fallback, adaptive bitrate, advise 5 GHz / near router, show "weak signal" banner |
| Elder accidentally exits app | Kiosk/launcher mode + lock task + hidden settings |
| Remote screen consent can't be bypassed | Pre-explainer screens + TTS + family "request share" flow; document clearly that OS requires the tap |
| Play Store policy (phoneCall FGS, accessibility) | Distribute via side-load/APK + managed devices; avoid AccessibilityService in v1 |
| Privacy | E2E-encrypted media, minimal metadata, explicit indicators, no recording |

---

## 19. Definition of Done

- [ ] Signed release APKs (arm64-v8a, armeabi-v7a, universal) < 20 MB each.
- [ ] Backend deployed in ≥ 2 regions with TLS, coturn, monitoring (uptime + alerts).
- [ ] All acceptance tests in §17.2 pass on the device matrix.
- [ ] Setup wizard lets a family member configure an elder phone in < 10 minutes.
- [ ] `/docs` includes: Setup Guide (with OEM screenshots), Admin Guide (server), Troubleshooting, Privacy Policy, CHANGELOG.
- [ ] Usability test report with ≥ 90 % senior task success.

---

## 20. Quick Start (for the implementer)

```bash
# Backend
cd backend && cp .env.example .env     # set JWT_SECRET, TURN_SECRET, DOMAIN, FCM creds (optional)
docker compose up -d                   # signaling + redis + coturn + caddy

# Android
cd android
./gradlew assembleDebug                # debug build
./gradlew assembleRelease              # signed release (configure keystore.properties)

# Make an elder phone kiosk (once, on a fresh/factory-reset phone, no accounts added)
adb install app/build/outputs/apk/release/app-universal-release.apk
adb shell dpm set-device-owner com.seniorcall/.service.AdminReceiver
```

---

**Final instruction to the builder:** prioritise, in this order — **(1) reliability of receiving calls 24×7, (2) latency, (3) senior-friendly simplicity, (4) lightness, (5) extra features.** When in doubt, remove a button rather than add one.
