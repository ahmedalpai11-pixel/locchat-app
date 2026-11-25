# LocChat — Offline Bluetooth LE mesh messenger (Android)

Serverless, internet-free chat over a Bluetooth LE mesh: direct end-to-end encrypted chats, public/password channels,
a neighborhood feed, voice notes and images, QR friend verification, stealth mode and panic wipe.
Interface languages: العربية · English · Français.

> Status: **1.1 — security/stability pass, not independently audited.** See [SECURITY.md](SECURITY.md) and [ROADMAP.md](ROADMAP.md).

## Features
- BLE GATT mesh with multi-hop relay (TTL ≤ 7), store-and-forward sync, compact binary wire format (Deflate)
- Ed25519 identities, X25519 + HKDF + AES-256-GCM direct chats, signed packets, key pinning
- Channels with optional passphrase (PBKDF2 210k)
- QR identity exchange (camera scan) with node-ID ↔ key verification
- Android Keystore–wrapped keys, biometric/device-credential app lock, stealth mode, panic wipe
- Images and voice notes (chunked + CRC), delivery acknowledgements, reactions
- Neighborhood feed with expiring posts, block lists

## Build
Requirements: Android Studio (latest), JDK 17, Android SDK 36.

```bash
./gradlew :app:assembleDebug          # or: gradle :app:assembleDebug (Gradle 9.3.1)
./gradlew :app:testDebugUnitTest
```
A debug APK is also built by GitHub Actions on every push (Actions → latest run → Artifacts).

## Try it
Install on **two or more** Android phones (8.0+ recommended), grant Bluetooth/notification permissions, then
Contacts → *My QR* on one phone and *Scan QR* on the other (camera permission is requested on first scan).

## Project layout
`app/src/main/java/com/example/` — `mesh/` (BLE engine, codec, repository), `data/` (crypto, Room, keystore), `ui/` (Compose screens).

## Notes
- `applicationId` is still `com.example`; change it before publishing.
- Breaking change in 1.1: old identities/contacts/protected channels must be recreated (see [CHANGELOG.md](CHANGELOG.md)).
