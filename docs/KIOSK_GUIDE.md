# SeniorCall — Kiosk & Device Lock Guide

To prevent an elderly relative from accidentally leaving the app, pulling down the notification shade, or uninstalling apps, SeniorCall includes a full Kiosk Mode.

---

## 1. Option A: True Kiosk Mode via Device Owner (Recommended)

Device Owner gives SeniorCall full hardware control to pin the app, disable keyguards, and prevent exiting.

### Steps:
1. Factory reset an old phone (or on a phone with **no Google accounts added** in Settings > Accounts).
2. Enable **Developer Options** (tap Build Number 7 times) and turn on **USB Debugging**.
3. Install the SeniorCall APK:
   ```bash
   adb install app-universal-release.apk
   ```
4. Set SeniorCall as the Device Owner via ADB:
   ```bash
   adb shell dpm set-device-owner com.seniorcall/.service.AdminReceiver
   ```
5. You should see:
   `Success: Device owner set to package com.seniorcall`
6. Open SeniorCall. The app can now lock tasks without system prompts, suppress the status bar, and disable power-off dialogs.

---

## 2. Option B: Home Launcher Mode (No Computer Required)

If you don't have a computer or ADB available:
1. Go to phone **Settings > Apps > Default Apps > Home App**.
2. Select **SeniorCall** as the default Home app.
3. Now, pressing the Home button or swiping home will always return directly to SeniorCall.
4. Use Android's built-in **App Pinning / Screen Pinning** feature:
   - Go to **Settings > Security > App Pinning** (or Screen Pinning).
   - Turn it ON.
   - Open SeniorCall, open the Recent Apps tray, tap the app icon, and select **Pin**.

---

## 3. Hidden Family Settings Access
To prevent the senior from altering settings, the configuration menu is hidden:
1. Tap the **"SeniorCall"** header text **5 times consecutively**.
2. A security dialog will prompt for the **4-digit Family PIN** (default: `1234`).
3. Entering the correct PIN opens the settings menu, allowing you to update Wi-Fi, change contacts, or exit Kiosk mode.
