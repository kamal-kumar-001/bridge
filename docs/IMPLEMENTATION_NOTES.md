# Implementation notes

## Scope

Keep Bridge's native Android foundation and add a small cross-platform desktop window. Focus on safe local pairing/text/files before notification mirroring, remote input, clipboard history or installer polish. Keep the Bridge name until there is a concrete naming reason. Do not merge the separate clipboard-history app yet.

## Comparable projects

Research sources inspected during implementation:

- https://apps.kde.org/kdeconnect/ - phone/desktop clipboard, files, notifications and remote controls. Broad continuity scope is feasible, but not proof of Bridge features.
- https://github.com/KDE/kdeconnect-android - native Android implementation, TLS transport, GPL v2/v3 licensing. No GPL code copied into Bridge. A future KDE protocol/code integration needs a deliberate license decision.
- https://blip.net/ - direct encrypted cross-platform file-transfer UX. Not identified as the unnamed paid app in the request. No proprietary code copied or decompiled.

These were product/architecture references, not reverse-engineering tools. Bridge uses Python cryptography/pyOpenSSL/zeroconf and Android platform TLS. Their dependency licenses remain applicable; redistribution/license review is needed before packaged distribution. No new project license was invented on the owner's behalf.

## Known limitations

- This is not an audited cryptographic protocol or a production release.
- TLS 1.2 is used consistently by both ends; self-signed certificate bootstrap is gated by application SAS/pins. Certificate identity is not an account identity.
- KeyStore provider behavior and real phone TLS handshakes are unverified until an Android install can run.
- Desktop GUI is Tk/Python, not a polished signed native installer.
- Linux desktop loopback tests do not establish macOS/Windows integration or Android background execution.
- Authentication failure UI, timeouts/resource exhaustion, richer transfer status/cancellation and public Downloads export need follow-up work.
- Discovery is best-effort. Manual IP is the fallback; there is no cloud relay.
- Existing Android legacy discovery code remains and needs device-level testing.
- Incoming Android text currently writes to the clipboard after paired sharing. Automatic outgoing clipboard monitoring is off by default; no passwords/clipboard contents are logged.

## Local results

12 Python TLS/security tests pass. 5 Android JVM tests pass; native Android Kotlin compilation passes. Initial APK dex packaging failed while a stale Kotlin compiler daemon held memory. A single resource-cleaned retry succeeded in 79 seconds. The debug APK passed signature/package inspection and was delivered separately; it is not physically runtime-tested. Mac + Android is the current priority, with other desktop product work deferred. Desktop loopback pairing, mutual confirmation, file acceptance and completed transfer were inspected as actual native windows. Physical phone, Mac and Windows validation remain outstanding.

## Android TLS signing correction (2026-11-05)

Real Mac feedback showed repeated `Unexpected EOF`; discovery was visible on Android.
An EOF alone does not prove a cause. Source review found a concrete AndroidKeyStore
configuration defect: the old identity authorized only SHA-256, while Android's TLS
provider may hash internally and request raw `NONEwithECDSA` signing. Android's
`KeyGenParameterSpec.Builder.setDigests` documentation explicitly recommends
`DIGEST_NONE` for TLS client/server keys:
https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...)

The correction uses a distinct `bridge_tls_key_v2` EC P-256 alias, authorized for
NONE/SHA-256/SHA-384/SHA-512 signing. The legacy key is preserved, not deleted.
An explicit key manager selects only this alias for either TLS direction. No
plaintext fallback, certificate bypass, automatic pin reset or skipped pairing
was added. If a device previously completed pairing, a changed certificate must
still be checked and explicitly revoked/repaired through the existing UI.

Android now displays transport failures instead of swallowing them in logcat.
Version 0.1.1-dev / code 2 updates the old debug build with the same signing cert.
Seven JVM tests (including two alias selection tests), Kotlin compilation/APK
packaging and the existing 12 desktop tests pass. APK signature/package checks
pass. No AndroidKeyStore provider/emulator or physical phone is available here;
the exact device failure is not reproduced, and the new error card's pixels are
not inspected. A single updated-APK test against the unchanged Mac client is
still required. Do not claim the user's connection is fixed until it succeeds.
