# Bridge Protocol Specification (v1)

The **Bridge Protocol** is a lightweight, cross-platform peer-to-peer communication protocol designed for local network synchronization between mobile devices (Android) and desktop computers (macOS, Windows, Linux).

---

## 1. Network & Discovery Architecture

Bridge operates locally over Wi-Fi, Ethernet, or mobile Hotspot/Tethering with zero cloud servers.

### 1.1 Discovery Mechanisms

Bridge supports two complementary discovery mechanisms:

#### A. Multicast DNS (mDNS / DNS-SD) — Standard Wi-Fi / LAN
- **Service Type**: `_bridge._tcp`
- **Default Port**: `52431` (or dynamically allocated)
- **TXT Records**:
  - `id`: Unique device UUID (e.g. `c4b2a8d1-1234-...`)
  - `name`: Human-readable device name (e.g. `MacBook Pro`, `Pixel 9`, `ThinkPad-Linux`)
  - `os`: Peer operating system (`MACOS`, `WINDOWS`, `LINUX`, `ANDROID`)
  - `proto`: Protocol version (integer `1`)
  - `port`: Port on which the TCP listener is running

#### B. Direct Gateway & Subnet Broadcast — Phone Hotspot / Tethering
When a desktop connects to an Android phone's Personal Hotspot:
1. The phone acts as the default gateway (`192.168.43.1` or `172.20.10.1`).
2. The Android server listens on wildcard `0.0.0.0:52431` across all interfaces (`wlan0`, `ap0`, `rndis0`).
3. The desktop detects when its network interface has a private gateway IP and probes `<default_gateway>:52431` directly with a `HELLO` probe.
4. Concurrently, lightweight UDP broadcast beacons are periodically emitted on port `52431` to `x.x.x.255`.

---

## 2. Transport & Framing

Communication runs over a bidirectional TCP socket stream.

### 2.1 Packet Frame Format

Every frame consists of a 5-byte header followed by the payload:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Payload Length (32-bit uint)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Frame Type   |                                               |
+-+-+-+-+-+-+-+-+                                               |
|                      Payload (Length - 1 bytes)               |
|                              ...                              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **Payload Length**: 4 bytes (Big-Endian unsigned integer) representing the total size of `[Frame Type + Payload Data]`.
- **Frame Type**: 1 byte enum:
  - `0x01` (`FRAME_JSON`): UTF-8 JSON control packet.
  - `0x02` (`FRAME_FILE_CHUNK`): Binary chunk data for high-speed file transfers.
  - `0x03` (`FRAME_ENCRYPTED`): AEAD encrypted envelope (when session encryption is active).

---

## 3. Message Definitions (JSON Frames - Type `0x01`)

All JSON messages include a `"type"` string attribute.

### 3.1 Handshake

#### `HELLO`
Sent immediately upon connection to identify the client:
```json
{
  "type": "HELLO",
  "version": 1,
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceName": "Kamal's Pixel 9",
  "deviceType": "PHONE",
  "osType": "ANDROID",
  "publicKey": "<Base64 encoded public key>",
  "supportedFeatures": ["CLIPBOARD", "FILE_TRANSFER"]
}
```

#### `HELLO_ACK`
Response to `HELLO`:
```json
{
  "type": "HELLO_ACK",
  "version": 1,
  "deviceId": "7a94d89a-0e28-4e89-9a29-b696773e3a41",
  "deviceName": "MacBook Pro",
  "deviceType": "DESKTOP",
  "osType": "MACOS",
  "isPaired": false,
  "publicKey": "<Base64 encoded public key>"
}
```
*(Note: `osType` can be `MACOS`, `WINDOWS`, `LINUX`, or `ANDROID`)*

---

### 3.2 Pairing & Authentication

When two devices connect for the first time, pairing is required.

#### `PAIR_REQUEST`
```json
{
  "type": "PAIR_REQUEST",
  "pin": "482195",
  "nonce": "<Base64 16-byte nonce>"
}
```

#### `PAIR_RESPONSE`
User on peer device confirms or rejects:
```json
{
  "type": "PAIR_RESPONSE",
  "accepted": true,
  "nonce": "<Base64 16-byte nonce>"
}
```

#### SAS (Short Authentication String) Calculation:
```
SAS_PIN = Truncate6( Decimal( SHA256( DeviceIdA + DeviceIdB + NonceA + NonceB ) ) )
```
Both devices display this 6-digit code for visual verification. Once accepted, both devices persist the peer's `deviceId` and public key fingerprint in trusted storage.

---

### 3.3 Clipboard Synchronization

#### `CLIPBOARD_TEXT`
```json
{
  "type": "CLIPBOARD_TEXT",
  "content": "https://github.com/project/bridge",
  "timestamp": 1727221200000,
  "sourceDeviceId": "550e8400-e29b-41d4-a716-446655440000",
  "hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

**Echo Loop Prevention**:
Each client maintains a rolling cache of the last 20 hashes of received/sent clipboard items. When receiving a `CLIPBOARD_TEXT`, if `hash` is in the cache, it is ignored. If not, the system clipboard is updated and the hash is cached without sending it back.

---

### 3.4 File Transfer

File transfers use a two-phase protocol: metadata offer followed by binary chunk streaming.

#### `FILE_OFFER`
```json
{
  "type": "FILE_OFFER",
  "transferId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "fileName": "landscape.jpg",
  "fileSize": 14592034,
  "mimeType": "image/jpeg",
  "sha256": "optional-file-checksum"
}
```

#### `FILE_ACCEPT`
```json
{
  "type": "FILE_ACCEPT",
  "transferId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "offset": 0
}
```

#### `FILE_REJECT`
```json
{
  "type": "FILE_REJECT",
  "transferId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "reason": "USER_DECLINED"
}
```

#### Binary Chunk Stream (Frame Type `0x02`)
Chunks are transferred in standard 64KB (65,536 bytes) blocks:
```
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Payload Length (32-bit uint)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Type: 0x02   |                                               |
+-+-+-+-+-+-+-+-+                                               |
|                    Transfer ID (16 bytes UUID)                |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Chunk Offset (64-bit Big-Endian uint)      |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Raw Chunk Bytes (up to 64KB)               |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

#### `FILE_COMPLETE`
```json
{
  "type": "FILE_COMPLETE",
  "transferId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

#### `FILE_CANCEL`
```json
{
  "type": "FILE_CANCEL",
  "transferId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

---

### 3.5 Session Liveness & Control

#### `PING` & `PONG`
Sent every 10 seconds to verify socket health:
```json
{ "type": "PING", "timestamp": 1727221200000 }
```
```json
{ "type": "PONG", "timestamp": 1727221200000 }
```

#### `DISCONNECT`
```json
{ "type": "DISCONNECT", "reason": "USER_QUIT" }
```

---

## 4. Cross-Platform Implementation Guide

| Platform | Recommended Discovery | Socket Transport | Cryptography |
|---|---|---|---|
| **Android** | `android.net.nsd.NsdManager` + UDP broadcast | Java NIO / Kotlin Coroutines `Socket` | Android KeyStore (`AES/GCM/NoPadding`) |
| **macOS** | Bonjour (`NWBrowser` / `NSNetService`) | `Network.framework` `NWConnection` | CryptoKit (`AES.GCM`) |
| **Windows** | Windows DNS-SD / `zeroconf` | .NET `System.Net.Sockets.TcpClient` | `System.Security.Cryptography.AesGcm` |
| **Linux** | Avahi (`avahi-client`) / `zeroconf` | POSIX sockets / Rust `tokio` / Go `net` | OpenSSL / `ring` / Go `crypto/cipher` |
