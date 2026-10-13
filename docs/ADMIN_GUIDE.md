# SeniorCall — Backend & Infrastructure Administration Guide

This guide details how to deploy and operate the SeniorCall Signaling Server, Redis presence store, coturn STUN/TURN server, and Caddy reverse proxy.

---

## 1. Architecture Overview

```
                      [ Client (Elder / Family) ]
                                   │
                 ┌─────────────────┴─────────────────┐
                 │                                   │
       WSS / HTTPS :443                      UDP/TCP/TLS :443/:3478
                 │                                   │
          [ Caddy Proxy ]                   [ coturn TURN Server ]
                 │                                   │
        [ Signaling Node.js ]                (E2E Encrypted SRTP)
                 │
          [ Redis Store ]
```

---

## 2. Server Requirements
- **Host**: Any Linux VPS (Ubuntu 22.04 LTS / Debian 12 recommended)
- **Specs**: 1 vCPU, 1 GB RAM, 20 GB SSD
- **Regions**: Recommended to deploy in 2–3 regional VPS (e.g. Mumbai, Frankfurt, Virginia) behind GeoDNS (Route53 or Cloudflare) for ultra-low latency.

---

## 3. Firewall & Port Requirements

| Port | Protocol | Service | Purpose |
|---|---|---|---|
| **80** | TCP | Caddy | ACME HTTP-01 challenge (Let's Encrypt) |
| **443** | TCP | Caddy | HTTPS API & WSS signaling |
| **3478** | UDP/TCP | coturn | Standard STUN and TURN |
| **5349** | TLS/TCP | coturn | TURNS (Secure TLS TURN) |
| **49160–49200** | UDP | coturn | TURN media relay UDP port range |

---

## 4. Quick Deployment (Docker Compose)

### Step 1: Clone and configure environment
```bash
cd backend
cp .env.example .env
```

Edit `.env`:
```ini
DOMAIN=call.yourdomain.com
TURN_DOMAIN=turn.yourdomain.com
JWT_SECRET=generate-a-strong-32-byte-hex-string
TURN_SECRET=generate-a-strong-32-byte-hex-string
```

Update `coturn/turnserver.conf`:
```ini
static-auth-secret=generate-a-strong-32-byte-hex-string
realm=turn.yourdomain.com
```

### Step 2: Launch stack
```bash
docker compose up -d --build
```

### Step 3: Verify Health
```bash
curl https://call.yourdomain.com/healthz
# Response: {"status":"ok","serverTime":1700000000000}
```

---

## 5. Ephemeral Coturn Credentials & Security
Coturn uses REST-API ephemeral credentials generated via shared HMAC-SHA1:
- `username`: `<expiry_timestamp>:<userId>`
- `credential`: `Base64(HMAC-SHA1(turnSecret, username))`
- The signaling server generates these on every `hello` or `/v1/turn` request with a 1-hour validity window.
- Media never passes unencrypted through any server: WebRTC enforces DTLS-SRTP end-to-end encryption.
