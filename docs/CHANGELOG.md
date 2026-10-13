# SeniorCall — Engineering Changelog

## [1.0.0] - 2026-10-13

### Phase 0 — Scaffolding
- Initialized clean monorepo architecture: `/android`, `/backend`, `/web-client`, `/docs`, `.github/workflows`.
- Configured Gradle 8.9 wrapper, Android Gradle Plugin 8.5.1 with Kotlin 1.9.24.
- Setup GitHub Actions CI pipeline for automated testing and APK packaging.

### Phase 1 — Backend MVP
- Implemented high-performance TypeScript WebSocket signaling server on Node.js 20.
- Implemented JWT authentication, device registration, and 6-digit pairing code generation and verification.
- Built ephemeral coturn STUN/TURN HMAC-SHA1 credential generator.
- Developed Call State Machine (`IDLE -> INVITING -> RINGING -> CONNECTING -> CONNECTED -> ENDED`) with 45s ring and 15s connect timeouts.
- Built Docker Compose orchestration with coturn, Redis, and Caddy reverse proxy with automated TLS.
- Added comprehensive integration tests covering end-to-end two-client calling, media negotiation, screen sharing, and clean teardown.

### Phase 2 — Android Core Calling Engine
- Implemented `RtcEngine` with pre-warmed `PeerConnectionFactory`, `HardwareVideoEncoderFactory` (H.264 constrained baseline / VP8), and software fallback.
- Implemented `AudioRouter` defaulting to loudspeaker mode, `MODE_IN_COMMUNICATION`, and auto volume enforcement ≥ 80%.
- Implemented `SignalingClient` using OkHttp WebSocket with exponential backoff reconnect capped at 5s and 15s ping/pong heartbeats.
- Built `CallSession` with early ICE candidate gathering, trickle ICE, and ICE restart support.
- Built always-on `CallService` with high-priority full-screen intent notification across Android 6.0 through Android 15.

### Phase 3 — Senior-First Accessibility UX
- Designed high-contrast AAA color palette (Green Answer, Red Hangup, Blue Video, Orange Share).
- Built giant touch targets (≥ 96dp min, 120dp primary buttons) with thick 3-4dp affordance borders.
- Implemented Text-To-Speech caller name announcements ("Rahul is calling. Press the green button to answer.") and multilingual strings (English and Hindi).
- Built auto-answer 3-2-1 countdown with giant STOP button for trusted family members.
- Added `Haptics` tick feedback on all primary interactions.

### Phase 4 — Screen Sharing
- Built bidirectional screen sharing engine via `ScreenShare` using `MediaProjectionManager` and `ScreenCapturerAndroid` at 720p 15fps.
- Implemented `ScreenShareService` (`mediaProjection` foreground service type on API 29+).
- Built `ShareConsentActivity` senior pre-explainer screen with automatic TTS guidance before OS permission dialogs.
- Added persistent orange sharing indicator with one-tap stop button.

### Phase 5 — Kiosk Mode & Device Hardening
- Added `HOME` launcher category in manifest so Home button always returns to SeniorCall.
- Implemented `AdminReceiver` for `dpm set-device-owner` Lock Task Mode.
- Implemented hidden family settings access (5 taps on logo + 4-digit PIN).
- Created watchdog with AlarmManager exact alarms and WorkManager periodic workers.
- Handled aggressive OEM battery killers (Xiaomi, Samsung, Oppo, Vivo).

### Phase 6 & 7 — Family Web Client & Performance Tuning
- Created installable PWA web client with WebRTC calling, screen sharing, and remote contact management.
- Added WebRTC stats monitoring and quality dots (●●●●○) with automatic fallback to audio-only if bitrate drops below 120 kbps.
- Documented complete device setup, admin deployment, and kiosk configuration guides.
