# SeniorCall — Device Setup & Family Guide

This guide walks family members through configuring an Android phone (Android 6.0+) for a senior citizen.

---

## 1. Prerequisites
- An Android phone running Android 6.0 (Marshmallow / API 23) through Android 15+.
- Dedicated home Wi-Fi network that stays powered on.
- The phone should ideally stay plugged into a wall charger on a nightstand, desk, or table.

---

## 2. Initial Phone Configuration
1. **Connect Wi-Fi**: Connect the elder phone to the home Wi-Fi network and ensure "Auto-reconnect" is turned ON.
2. **Display Settings**:
   - Set Font Size to **Large** or **Largest**.
   - Set Display Sleep / Screen Timeout to **Dim after 2 min, never off** (or 30 minutes).
3. **Sound Settings**:
   - Set Ring Volume and Call Volume to **Maximum**.
4. **Install SeniorCall APK**:
   ```bash
   adb install app-universal-release.apk
   ```

---

## 3. Setup Wizard Walkthrough
1. Open **SeniorCall**.
2. Select **"Elder Phone (Senior Mode)"**.
3. **Permissions**:
   - Tap **"Grant Permissions"**.
   - Allow **Camera**, **Microphone**, and on Android 13+, **Notifications**.
4. **Battery Optimisation Exemption**:
   - When prompted, allow SeniorCall to **"Ignore Battery Optimisations"** (Don't optimize). This prevents Android Doze from cutting off the socket.
5. **Pairing**:
   - A large 6-digit code and QR code will appear on the elder's screen.
   - On your phone or laptop, open the SeniorCall Family Web Portal (or Family Mode app).
   - Enter the 6-digit code or scan the QR code.
   - The device is now linked!
6. Tap **"Complete Setup"**.

---

## 4. Handling OEM Battery Killers (Critical for 24/7 Availability)

Different Android phone manufacturers aggressively terminate background services unless explicitly exempted:

### Xiaomi / Redmi / POCO (MIUI / HyperOS)
1. Go to **Settings > Apps > Manage Apps > SeniorCall**.
2. Enable **Autostart**.
3. Under **Battery Saver**, select **No Restrictions**.
4. In Security App, lock SeniorCall in the Recent Apps tray (padlock icon).

### Samsung (One UI)
1. Go to **Settings > Apps > SeniorCall > Battery**.
2. Select **Unrestricted**.
3. Go to **Settings > Battery and device care > Background usage limits**.
4. Ensure SeniorCall is in **Never sleeping apps**.

### Oppo / Realme / OnePlus (ColorOS / OxygenOS)
1. Go to **Settings > Battery > App Battery Management > SeniorCall**.
2. Turn ON **Allow foreground activity** and **Allow background activity**.
3. Turn ON **Allow auto-launch** and **Allow secondary launch**.

### Vivo (Funtouch OS)
1. Go to **Settings > Battery > High background power consumption**.
2. Find **SeniorCall** and toggle it **ON**.
3. Enable **Autostart** in Settings > More Settings > Applications.

---

## 5. Testing the Setup
1. **Outgoing Test**: On the elder's screen, tap **🎥 VIDEO** on your contact tile. Verify the call rings on your device.
2. **Incoming Test**: Put the elder's phone to sleep (screen off). Call the elder from your device.
   - Verify the screen wakes up immediately.
   - Verify caller photo and name appear in giant letters.
   - Tap **🟢 ANSWER** and verify audio/video flows with < 350 ms latency.
3. **Screen Sharing Test**: During the call, tap **📺 SHARE** on your phone.
   - Verify the elder sees the pre-explainer screen ("Rahul wants to see your screen") with TTS voice announcement.
