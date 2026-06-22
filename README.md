<div align="center">
  <h1>PhoneLink Addon</h1>
  <p><b>An advanced LSPosed Module for seamless Microsoft Phone Link Projection Bypass</b></p>
</div>

---

## 📖 Overview
**PhoneLink Addon** is a powerful, zero-overhead LSPosed module tailored for Android devices (specifically optimized for MIUI/HyperOS) that actively bypasses the strict restrictions imposed on Microsoft's Phone Link projection feature. 

Out of the box, attempting to project your Android screen to a PC via Phone Link often results in aggressive lockscreen behaviors, blocked projection events, or severe graphical glitches (like fingerprint sensor blinking). This module systematically hooks into the Android System, SystemUI, and Microsoft Apps to silence these restrictions, providing a buttery smooth, PC-like projection experience.

## ✨ Core Features & The "Start to End" Process

### 1. Lockscreen Bypass & Media Projection Control
Normally, when the screen is locked, `MediaProjectionStopController` aggressively terminates the projection stream, while `KeyguardController` blocks inputs.
- **The Fix:** The module hooks into `com.android.server.media.projection.MediaProjectionStopController` and forces `isStartForbidden` to return `false`. It also intercepts `isKeyguardLocked` and `isKeyguardShowing` to trick the system into keeping the projection alive even when your physical phone is locked in your pocket.

### 2. Auto-Bypass "Start Recording/Casting" Dialog
Whenever you start a projection, Android pops up a mandatory security warning asking for permission to cast.
- **The Fix:** We hook into the `MediaProjectionManagerService` and `SystemUI` to automatically grant this permission. The dialog is bypassed entirely, allowing 1-click seamless connection from your PC.

### 3. Cross-Device Service Broker Interception
Microsoft's `crossdeviceservicebroker` app attempts to draw an artificial "Black Screen" overlay on top of the phone to save battery during projection.
- **The Fix:** We intercept the initialization methods within `BrokerC` and `BrokerD` classes inside `com.microsoftsdk.crossdeviceservicebroker`. By hooking `BrokerC.a()`, the module stops Microsoft from injecting their own locked overlay, giving you full control.

### 4. The "Invisible Fingerprint" (Zero-Lag Hack)
**The Problem:** On devices with under-display fingerprint scanners (like MIUI/HyperOS), activating the Black Screen overlay caused a severe bug where the fingerprint icon would strobe/blink aggressively at 60Hz, lagging the entire UI.
**The Start-to-End Solution:**
- Instead of disabling the fingerprint scanner (which breaks security) or relying on system flags, we hook directly into the `MiuiGxzwFrameAnimation` class.
- When projection starts, our module intercepts `decodeBitmap` and dynamically injects a transparent `1x1` bitmap in place of the glowing fingerprint icon.
- **The Result:** The glowing fingerprint icon becomes completely invisible, permanently fixing the blinking/lag issue, while the actual biometric hardware underneath remains 100% fully functional!
- **Instant Restore:** A custom broadcast receiver (`BLACK_SCREEN_STATE`) actively monitors when the projection ends and instantly fires `updateGxzwState()` to immediately redraw and restore the fingerprint icon to the lockscreen with zero delay.

### 5. Beautiful & Modern UI
The module comes with a dedicated Material Design 3 configuration app.
- **Setup Wizard:** A full-screen onboarding experience that verifies root access, checks LSPosed activation, and guides the user.
- **Persistent State:** Uses a fail-safe `.flag` system to remember setup completion even if the user accidentally hits "Clear Data" in Android settings.
- **Granular Controls:** Colorful, semantic toggles to individually enable/disable the Master Switch, Bypass Dialog, Lockscreen Bypass, Black Screen, Auto Restore, and the Invisible Fingerprint fix.
- **Root Restart:** Includes an action button to silently execute `killall com.android.systemui` and force-stop Microsoft services to apply hook changes on the fly without rebooting.

---

## ⚙️ Installation & Usage

### Prerequisites
- **Rooted Android Device** (Magisk / KernelSU / APatch)
- **LSPosed Framework** installed and running
- **Microsoft Phone Link** / Link to Windows installed

### How to Install
1. Download the latest `PhoneLink-Addon-v1.0.1.apk` from the `Releases/v1.0.1/` folder.
2. Install the APK normally.
3. Open your **LSPosed Manager**.
4. Navigate to **Modules** -> enable **PhoneLink Addon**.
5. Ensure the recommended scope is checked:
   - System Framework (`android`)
   - System UI (`com.android.systemui`)
   - Link to Windows (`com.microsoft.appmanager`)
   - Cross Device Service Broker (`com.microsoftsdk.crossdeviceservicebroker`)
6. **Reboot your device** (or use the Restart Services button in the app).
7. Open the app from your launcher to configure your preferences!

---

## 🛠️ Build from Source
The project is built entirely in standard Java and uses the official Xposed API structure.

```bash
# Clone the repository
git clone https://github.com/YourUsername/PhoneLink-Addon.git
cd "PhoneLink Addon"

# Clean the workspace
cd ProjectionBypassModule
./gradlew clean

# Build the Release APK
./gradlew assembleRelease

# The output APK will be located at:
# app/build/outputs/apk/release/app-release-unsigned.apk
```
