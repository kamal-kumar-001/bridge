# Bridge protocol v2

Development protocol. v1 plaintext peers are deliberately incompatible.

## Transport and trust

TCP port 52431 carries TLS 1.2. Both peers present persistent, self-signed EC certificates. Bootstrap TLS trust permits a first contact; **this is not authenticated identity yet**. Android keeps its private key in Android KeyStore; desktop keeps a restricted-permission PEM identity. Hardware-backed storage depends on the Android device and is not assumed.

Each peer sends one HELLO with `version:2`, a device ID/name/type/OS, and a fresh cryptographically random 16-byte nonce as 32 lowercase hex characters. Repeated/legacy HELLOs are refused. No clipboard/file messages or chunks are allowed until authentication finishes.

Compute each certificate's SHA-256 DER fingerprint as lowercase hex. Form two strings, `fingerprint:nonce`, one for each end. Sort lexicographically, join with `|`, hash UTF-8 with SHA-256, read the first four bytes as an unsigned big-endian integer, take modulo 1,000,000 and left-pad with zeroes to six digits. Both users must compare the displayed code, not a code supplied by a peer. The code binds certificates and fresh nonces to this session. It is a short authentication string, not a password.

After local confirmation send `{"type":"PAIR_RESPONSE","accepted":true}`. Only when local and remote confirmations are both true may a peer save the device-ID/certificate pin and authorize data. An existing matching pin can confirm automatically; a changed certificate under an existing device ID closes the connection. A new device ID requires new confirmation. Rejection/disconnect never grants trust. Legacy ID-only trust records do not authenticate a certificate.

This bootstrap design needs independent security review before production use. The user must check codes on BOTH devices. Discovery metadata and claimed device names are not trusted identity.

## Inner framing

Inside TLS, each frame is a 4-byte big-endian length (includes the type byte), one type byte, and payload. Maximum frame length is 1 MiB. Type 1 is UTF-8 JSON object with a single `type` discriminator; type 2 is binary file data. Type 3/custom encryption is unused. No plaintext fallback.

A type-2 payload is UUID 16 bytes, unsigned offset 8 bytes, chunk length 4 bytes, then 1..65536 data bytes. Payload size must equal `28 + chunk_length`; offsets must be contiguous and never exceed the offered file size. Empty files finish without chunks.

## Messages

- HELLO: `type`, `version:2`, `deviceId`, `deviceName`, `deviceType` (`PHONE`/`DESKTOP`), `osType` (`ANDROID`/`MACOS`/`WINDOWS`/`LINUX`/`UNKNOWN`), `nonce`, `supportedFeatures`.
- HELLO_ACK is tolerated for Android compatibility but does not establish trust or a peer's paired status.
- PAIR_RESPONSE: boolean `accepted`. Peer-supplied PAIR_REQUEST PIN values never establish trust.
- CLIPBOARD_TEXT: `content`, `timestamp`, `sourceDeviceId`, SHA-256 UTF-8 `hash`. Text is capped at 256 KiB; clipboard access remains an OS/user preference decision.
- FILE_OFFER: UUID `transferId`, safe base `fileName`, integer `fileSize` from 0 through 2 GiB, `mimeType`.
- FILE_ACCEPT: matching `transferId`, `offset:0`. Resume is not implemented.
- FILE_REJECT / FILE_CANCEL: `transferId`, optional `reason`. Received cancel is not echoed.
- FILE_COMPLETE: `transferId`. Receiver requires the exact expected byte count before completion.
- PING / PONG: `timestamp`; DISCONNECT: optional `reason`.

Every file offer requires user acceptance. Filenames cannot escape the receiving directory. Desktop receives into unique partial files and does not overwrite existing destinations. Android receives in app-specific storage and deletes cancelled partial files. TLS protects integrity in transit; independent file checksum/resume support is not implemented.

## Discovery

`_bridge._tcp.local.` advertises ID, name, OS, protocol and port. UDP presence beacons may be sent to local broadcast port 52431. Android also has hotspot discovery code. Availability varies with OS/network restrictions and needs physical validation. Manual IP connection uses the same TLS/pairing flow.
