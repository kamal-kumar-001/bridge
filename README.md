# Bridge

A local-first Android and desktop companion. Current priority is macOS + Android; Windows and Linux product work is deferred. No cloud account or relay service.

**Status: development prototype, not a production-ready ecosystem.** Protocol v2 replaces the original plaintext prototype. Upgrade both ends together; v1 is refused. The separate clipboard-history app is not integrated yet.

## This development slice

- Android native app and a Python/Tk desktop window intended for macOS, Windows and Linux.
- TLS 1.2 encrypted transport with a certificate presented by each side. First contact is not trusted automatically: compare a session-bound six-digit code on both screens, then approve on both devices. Later connections check the saved certificate pin. A changed certificate is refused.
- Explicit text/link sharing and file offers. A receiving user accepts files before writing starts.
- 64 KiB file chunks, strict framing/offset/size checks, 2 GiB per-file limit, filename checks and collision handling.
- Desktop mDNS advertisement and UDP presence beacons. Manual phone-IP connection is available if multicast discovery is blocked. Discovery is not authentication.
- Trusted-device revocation closes the connected session. Android clipboard-change sharing is a real saved setting, off by default.

This is original implementation work using standard TLS libraries. No proprietary app was decompiled, copied or bypassed. See [research and decisions](docs/IMPLEMENTATION_NOTES.md).

## Mac + Android first

See `MAC_START_HERE.txt`. On Mac, `Start Bridge.command` checks Python/Tk, asks before installing dependencies into a folder-local virtual environment and opens the desktop window. It is source-based test tooling, not a signed Mac app. Mac execution still needs a real-device test.

## Run the desktop companion

Use Python 3.10 or newer with Tk support. Some Linux distributions package Tk separately as `python3-tk`; macOS/Windows Python distributions may include it.

```sh
python3 -m venv .venv
# macOS/Linux:
. .venv/bin/activate
# Windows PowerShell instead: .venv\Scripts\Activate.ps1
python -m pip install -r desktop/requirements.txt
python tools/desktop_bridge_agent.py
```

The desktop listens on local TCP port 52431. Allow local-network access in the OS firewall. Only use on a network you control. Connect the phone and desktop to the same Wi-Fi, or connect the desktop to the phone's hotspot. The desktop can connect to the phone's actual local IP; do not assume all Android hotspots use `192.168.43.1`.

The Android app should discover the desktop's advertised service where the network permits it. If discovery fails, desktop **Connect by IP** can connect to the Android phone's local address. Router client isolation and some hotspot implementations block peer traffic; there is no claim to bypass them.

On first contact, keep both apps visible, compare the code, and confirm only if the codes match and you initiated the connection. Never approve an unexpected request. Text and file controls stay disabled until both sides agree. Files require a second, per-file acceptance because they write storage.

Desktop identity/pins live in `~/.bridge/`. Do not share the private `identity.key`. Desktop files save into `~/Downloads/Bridge/`. **Forget connected device** removes the local pin and disconnects; revoke on the other device too if you want both to forget each other.

## Android development

Open `android/` in Android Studio, using JDK 17 and Android SDK 35, or:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
# Windows: gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Minimum Android version is 9 (API 28). Keep the app visible for pairing and incoming-file prompts. The foreground service supports the connection, not unrestricted access to Android's clipboard.

Received Android files currently save to **app-specific external storage**, under `Android/data/com.bridge.app/files/Download/Bridge/` where available, and are removed on uninstall. Public Downloads export through MediaStore/Storage Access Framework is a remaining feature. They are not promised to appear in Photos or the public Downloads app.

Android restricts clipboard reads in the background. Manual Send Text and the Share Sheet are the reliable intended paths; a foreground service does not bypass OS restrictions. Automatic clipboard-change sending is opt-in and only works when Android grants clipboard access. Received text can update the phone clipboard after pairing; desktop received text is shown for manual copying. No clipboard-history app or desktop background clipboard watcher is included in this slice.

## Validation

```sh
python tools/test_protocol_simulation.py
```

- 12 desktop tests cover TLS loopback pairing, both confirmations, unpaired-data refusal, changed pins, fragmented frames, malformed chunks, paths, persistence and file roundtrip/no-overwrite behavior.
- 5 Android JVM tests cover real Kotlin JSON serialization/discrimination, cross-platform HELLO decoding, chunk framing and malformed/truncated input.
- Kotlin Android compilation and JVM tests passed in the development environment.
- Native Linux desktop pairing/connection/file acceptance/completion windows were visually inspected with isolated loopback peers.
- **Debug APK packaging passed** after stopping a stale compiler daemon and using one worker with a bounded heap. `apksigner` verified the v2 debug signature; `aapt2` verified package `com.bridge.app`, version `0.1.0`, min API28/target35. SHA-256: `db8633d05c5354a4243fbd32696167e37b9360288a1bcd3b9e12656cd9fd7e8c`. This does not prove installation or runtime behavior. The APK is a debug test build, not a release-signed product.
- No physical Android-to-Mac/Windows/Linux test has been run. Android KeyStore/TLS provider behavior, hardware-backed key availability, Android UI pixels, Wi-Fi/hotspot discovery, mobile storage behavior, OS firewall permissions and actual throughput need device validation.
- No measured Wi-Fi speed, automatic background clipboard guarantee, signed installer or production security audit is claimed.

## Next slices

1. Install the debug Android APK and run the Mac launcher; validate Android-to-Mac mutual TLS and pairing on real hardware before expanding features.
2. Validate discovery/reconnect on Wi-Fi and phone hotspot, certificate changes and revocation.
3. Public Downloads export, transfer progress/cancel UX, timeout/resource limits, desktop trusted-device management and native installers.
4. Only then add notification features and the separate clipboard-history app, with explicit per-feature permissions.

### Android handshake update

`0.1.1-dev` corrects Android TLS key signing authorization and adds visible
connection error details. Keep the existing Mac launcher. Install the newer
APK over the old app, then try one connection and compare both pairing codes.
This correction is source-verified and build/test checked, not yet verified on
a physical Android + Mac pair. See `docs/IMPLEMENTATION_NOTES.md` for the cause
candidate, tests and limits. Do not uninstall or clear app data just to update.
