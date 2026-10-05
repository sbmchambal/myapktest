# RemoteControl LAN (Android Native Kotlin + Jetpack Compose)

**RemoteControl LAN** is a high-performance, private, zero-internet native Android application designed to remotely control one Android device from another over a local Wi-Fi hotspot or direct USB connection.

Designed specifically for real-world hardware setups such as:
- **Phone A (Host / Controlled Device)**: Redmi Note 10 (Android 11–13, rooted, USB debugging enabled, Wi-Fi Hotspot ON).
- **Phone B (Controller)**: Redmi Note 12 (Android 13–14, connected to Phone A's hotspot).

---

## 🌟 Key Architecture & Capabilities

1. **One Unified APK**:
   - Contains both **Host Mode** and **Controller Mode**, selectable on first launch and switchable anytime.
2. **True Offline LAN & Hotspot Operation**:
   - Zero internet access required.
   - Automatic host discovery via UDP subnet beacons (`8889`), gateway probing (`192.168.43.1`), and manual IP fallback.
3. **Hardware-Accelerated Low-Latency Screen Mirroring**:
   - Android standard `MediaProjection` API for screen capture (respecting user consent).
   - Low-latency `MediaCodec` H.264/AVC baseline hardware video encoding (720p @ 30 FPS, 3 Mbps default).
4. **Direct Interactive Touch & Gestures**:
   - Precision coordinate transformation layer translating Controller surface dimensions to Host's physical screen resolution.
   - Supports tap, double tap, long press, drag, swipe, and scroll.
5. **Secure Allowlisted Root Command Engine**:
   - Root detection with execution confirmation (`su -c id`).
   - Strict allowlist system for `input tap`, `input swipe`, `input keyevent`, `input text`, `monkey -p`, `am force-stop`, and `screencap`.
   - Complete parameter sanitization preventing shell injection.
   - Non-root fallback via Android `AccessibilityService` (`dispatchGesture` and `performGlobalAction`).
6. **Robust 6-Digit PIN Pairing & Security**:
   - Host displays an ephemeral 6-digit pairing PIN and QR code.
   - Controller enters PIN on first connection.
   - Cryptographically strong 256-bit session token generated and saved in Android Keystore / `EncryptedSharedPreferences`.
   - Full unpair / revoke / disconnect controls.
7. **Remote Navigation & Text Entry**:
   - Remote navigation bar: Back, Home, Recents, Volume -, Volume +, and Power (with confirmation warning).
   - Remote keyboard dialog with backspace and return triggers.
8. **Remote File Manager & App Browser**:
   - Browse Host internal storage, Downloads, Pictures, Movies, Music, Documents.
   - Create directories, rename, delete files.
   - View installed user apps, one-tap launch, and root force-stop.
9. **Dual Transport Abstraction (Wi-Fi + USB)**:
   - Transport interface abstraction (`WiFiTransport`, `UsbTransport`).
   - Runtime USB capability detection via `UsbManager`.
   - Prefer USB when connected via USB-C OTG cable; automatic fallback to Wi-Fi.
10. **Android Foreground Service**:
    - `HostService` keeps the streaming server alive in background.
    - Persistent notification with quick [Disconnect] and [Stop Host] action buttons.

---

## 🚀 Step-by-Step Test Guide: Redmi Note 10 → Redmi Note 12

### Phase 1: Preparation
1. Install `app-debug.apk` on both **Redmi Note 10** and **Redmi Note 12**.
2. On **Redmi Note 10** (Host):
   - Ensure Root is granted (e.g. Magisk or KernelSU prompt: Grant Superuser access to RemoteControl LAN).
   - Enable **Portable Hotspot** in Android Settings.
   - (Optional if non-root fallback tested): Enable **RemoteControl LAN Accessibility Service** in Settings → Accessibility.
3. On **Redmi Note 12** (Controller):
   - Turn on Wi-Fi and connect to the Redmi Note 10's Wi-Fi Hotspot.

### Phase 2: Start Host Mode on Redmi Note 10
1. Open **RemoteControl LAN** on Redmi Note 10.
2. Select **HOST MODE**.
3. Verify Dashboard shows:
   - Device: `Redmi Note 10`
   - Root: `Detected`
   - Network: `Wi-Fi Hotspot (192.168.43.1)`
   - Server: `Stopped`
4. Tap **START HOST (SCREEN SHARE)**.
5. Android displays the system `MediaProjection` consent dialog: tap **Start Now** (or **Entire Screen**).
6. Notice the Host status changes to **HOST ACTIVE** and a 6-digit PIN is displayed (e.g., `482910`).

### Phase 3: Connect from Redmi Note 12
1. Open **RemoteControl LAN** on Redmi Note 12.
2. Select **CONTROLLER MODE**.
3. Under **REMOTE HOSTS**, the Redmi Note 10 appears automatically:
   ```
   Redmi Note 10
   192.168.43.1:8887
   Rooted • Ready
   ```
   *(If your carrier ROM blocks broadcast packets, tap "Connect to Default Hotspot (192.168.43.1)")*
4. Tap **CONNECT**.
5. Enter the 6-digit PIN shown on the Redmi Note 10.
6. The session authenticates and saves the secure token.

### Phase 4: Control & Verification
- **Screen**: Redmi Note 10's screen is mirrored live in 720p @ 30 FPS.
- **Touch**: Tap and swipe anywhere on the Redmi Note 12 screen; watch Redmi Note 10 react in real-time.
- **Navigation**: Tap `Back`, `Home`, `Recents`, `Vol +`, `Vol -` on the overlay bar.
- **Keyboard**: Tap the Keyboard icon in the top overlay, type "Hello World", and tap Send.
- **Files**: Tap the Folder icon to explore Redmi Note 10's files and photos.
- **Apps**: Tap the Apps icon to view installed apps and launch any app directly.

---

## 🛠️ Building the APK Locally & On GitHub Actions

### Method 1: GitHub Actions (Recommended)
1. Push this repository to your GitHub account:
   ```bash
   git init
   git add .
   git commit -m "Initial RemoteControl LAN commit"
   git branch -M main
   git remote add origin https://github.com/your-username/remotecontrol-lan.git
   git push -u origin main
   ```
2. In your GitHub repository, click the **Actions** tab.
3. The **Build RemoteControl LAN APK** workflow automatically runs on JDK 17 with Gradle 8.10.2.
4. When finished, download `RemoteControl-LAN-Debug-APK` from the build artifacts! The APK file is at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Method 2: Android Studio / Local CLI
Requirements:
- Android Studio Ladybug / Meerkat (or JDK 17+)
- Android SDK 35 build tools

Run:
```bash
./gradlew assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

To install onto connected devices via ADB:
```bash
adb -s <redmi_note_10_serial> install app/build/outputs/apk/debug/app-debug.apk
adb -s <redmi_note_12_serial> install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔒 Android Platform Limits & Security Architecture

- **MediaProjection Consent**: Android strictly mandates user consent on every screen recording session. RemoteControl LAN uses the official `createScreenCaptureIntent()` and never attempts to bypass this user security protection.
- **Allowlisted Root**: The Host executes root commands exclusively through allowlisted parameters (`input tap`, `input swipe`, `keyevent`, `text`). Arbitrary remote shell command execution is prohibited.
- **Direct USB Android-to-Android**: Direct Android-to-Android USB communication requires USB Host/OTG capability on one device and AOA (Android Open Accessory) or compatible driver support. When not present on physical hardware, the UI displays a clear explanation and routes traffic over the high-speed local Wi-Fi Hotspot.
