# Changelog

## 1.1 — Security & stability pass

### Security
- Encryption now **fails closed**: if AES-GCM encryption throws, the message is *not* sent (previously the plaintext was sent).
- Relay **TTL is clamped** to the local max (7). TTL is unsigned, so a malicious node could previously set `ttl=255`.
- **Node IDs are 128-bit** (`LC-` + 32 hex) instead of 64-bit. Wire format, BLE scan-response meta and tests updated.
- **Channel key derivation** is PBKDF2-HMAC-SHA256 with 210,000 iterations (salt label `V2`) and the derived key is cached per channel/passphrase.
- **Manual "add friend" no longer marks a contact verified** unless the peer is currently visible, its key is bound to its node ID, and the typed fingerprint matches.

### QR / camera
- Camera is released when the scan dialog closes (it used to stay bound to the Activity).
- "Open app settings" shortcut when the camera permission was permanently denied.
- Camera error message localized (ar / en / fr).

### Build
- Debug build no longer fails when `debug.keystore` is absent locally.
- CI: runs unit tests (non-blocking), always uploads the debug APK and the test report.

### Breaking changes
Old identities, verified contacts and password-protected channels are not compatible with 1.1 (new node-ID length and key-derivation version). Re-add contacts and recreate protected channels.
