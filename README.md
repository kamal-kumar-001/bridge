# Bridge — Native Mac & Android Companion (Cross-Platform Ready: macOS, Windows, Linux)

Bridge brings a seamless Apple Continuity-like experience to Android and desktop operating systems without cloud accounts, complex network configuration, ADB, or proprietary vendor locks.

---

## Key Capabilities

- **Zero-Configuration Discovery**:
  - **Standard Wi-Fi LAN**: Native mDNS / DNS-SD (`_bridge._tcp`) discovered via `NsdManager` on Android, Bonjour on macOS, Avahi on Linux, and Windows DNS-SD.
  - **Personal Hotspot / Tethering Mode** (like iPhone + Mac): Dual-mode discovery with direct gateway probing (`<gateway>:52431`) and UDP broadcast beacons (`x.x.x.255:52431`) across wildcard `0.0.0.0`, overcoming Android chipset multicast filtering.
- **Cross-Platform Peer Architecture**:
  - Clean, OS-agnostic peer abstraction (`MACOS`, `WINDOWS`, `LINUX`, `ANDROID`).
  - Bridge Protocol v1: 5-byte length-prefixed binary stream with JSON control frames (`0x01`) and raw 64KB binary chunk frames (`0x02`).
  - The exact same Android app connects interchangeably to **macOS, Windows, or Linux** desktop clients.
- **Mutual Security & Pairing**:
  - Secure hardware-backed Android KeyStore identity.
  - Mutual 6-digit Short Authentication String (SAS / PIN) pairing prompt.
  - Persistent trusted device storage with instant revocation.
- **Instant Bi-Directional Clipboard Sync**:
  - SHA-256 rolling hash cache prevents reflection / echo loops.
  - Persistent foreground service pushes incoming text to the Android clipboard.
- **High-Speed File Transfer**:
  - Direct TCP socket streaming in 64KB chunks utilizing full Wi-Fi / Hotspot local bandwidth (30–80+ MB/s).
  - Native Android Share Sheet target (`ShareSheetActivity`) allows sharing photos, videos, PDFs, and text directly from any Android app to the connected desktop.
  - Saves received files automatically into `Downloads/Bridge/` and registers them with `MediaScannerConnection` so they immediately appear in Gallery / Google Photos.

---

## Repository Structure

```
bridge/
├── docs/
│   └── BRIDGE_PROTOCOL.md            # Complete Bridge Protocol v1 specification
├── tools/
│   └── desktop_bridge_agent.py       # Cross-platform desktop test harness (Mac/Win/Linux)
├── android/                          # Native Android companion application
│   ├── build.gradle.kts              # Root Gradle build script
│   ├── settings.gradle.kts           # Gradle settings
│   ├── gradle/
│   │   ├── libs.versions.toml        # Version catalog (AGP 8.7, Kotlin 2.0, Compose, Coroutines)
│   │   └── wrapper/
│   └── app/
│       ├── build.gradle.kts          # Jetpack Compose & Material 3 configurations
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml   # Permissions, Foreground Service, ShareSheet intent filters
│           ├── res/                  # Strings, colors, themes, FileProvider XML
│           └── java/com/bridge/app/
│               ├── BridgeApplication.kt
│               ├── core/
│               │   ├── model/        # Device, OSType, Message, Transfer
│               │   ├── protocol/     # PacketCodec, BridgeProtocol
│               │   ├── crypto/       # CryptoEngine, KeyStoreManager
│               │   ├── discovery/    # NsdDiscoveryManager, HotspotDiscoveryManager, DualDiscoveryCoordinator
│               │   └── network/      # BridgeSession, BridgeServer, BridgeClient
│               ├── features/
│               │   ├── clipboard/    # ClipboardManagerHelper, ClipboardSyncManager
│               │   ├── filetransfer/ # FileTransferManager, StorageHelper
│               │   └── pairing/      # PairingManager, PairedDeviceRepository
│               ├── service/          # BridgeForegroundService, ServiceController
│               └── ui/               # Jetpack Compose UI (Screens, Components, Theme, Activities)
```

---

## Quick Start & Testing

### 1. Run the Desktop Agent (Mac / Windows / Linux)

Bridge includes a standalone desktop agent written in Python 3 that implements Bridge Protocol v1. Run it directly on your Mac, Windows PC, or Linux machine:

```bash
# On macOS:
./tools/desktop_bridge_agent.py --os macos --name "MacBook Pro"

# On Windows:
python tools/desktop_bridge_agent.py --os windows --name "Windows PC"

# On Linux:
python3 tools/desktop_bridge_agent.py --os linux --name "Linux Workstation"
```

The desktop agent will start listening on port `52431` and begin broadcasting UDP hotspot beacons.

### 2. Connect via Wi-Fi or Phone Hotspot

- **Over Wi-Fi**: Both devices discover each other automatically via mDNS. Tap **Connect** in the Android app.
- **Over Phone Hotspot**:
  1. Turn on Personal Hotspot on your Android phone.
  2. Connect your Mac/PC to the phone's Hotspot Wi-Fi.
  3. In the desktop agent terminal, run:
     ```
     bridge> connect 192.168.43.1
     ```
     *(Or Android auto-discovers your computer via the gateway probe)*

### 3. Pair Devices

1. In the desktop agent, run:
   ```
   bridge> pair
   ```
2. A pairing prompt will pop up on your Android screen with a 6-digit PIN (e.g. `482 195`).
3. Tap **Allow**. Both devices are now securely linked.

### 4. Test Clipboard & File Transfer

- **Send text from Mac/PC to phone**:
  ```
  bridge> clip Meeting at 5 PM: https://meet.google.com/abc-defg-hij
  ```
  *The text is immediately copied to your phone's clipboard.*

- **Send file from Mac/PC to phone**:
  ```
  bridge> send /path/to/sample.jpg
  ```
  *The file streams in 64KB blocks, saves to `Downloads/Bridge/sample.jpg`, and appears in your Android Photos app.*

- **Send file or text from Android to Mac/PC**:
  - Open Bridge -> tap **Send File** or **Send Text**.
  - Or in Google Photos / Files / Browser, tap **Share** -> **Bridge**.
