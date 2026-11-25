# Changelog

## 1.2 — Forward secrecy & wider mesh reach

### Security
- **Forward secrecy for direct chats.** Each pair of contacts now runs a 2-message handshake (Noise-KK-style, new packet types `SESSION_INIT` / `SESSION_RESP`) with fresh ephemeral X25519 keys mixed with the static keys (es+ss+ee+se, HKDF-SHA256, key confirmation). Every message then uses its own key from a one-way hash ratchet and the previous key is erased, so leaking a device key later does not expose past messages. Ephemeral keys are never stored. Sessions rotate after 30 min / 100 messages. Session state is wrapped with the Android Keystore.
- The message counter and session id are bound into the AES-GCM AAD; replayed or duplicated message keys are rejected.
- Direct chats no longer use the static-static key at all (channels are unchanged).

### Fixes
- A message that could not be decrypted was still acknowledged as *delivered*; the sender now only gets an ack after successful decryption.
- When a peer has lost its session (e.g. app data cleared) the receiver automatically starts a new handshake.

### Mesh reach
- Store-and-forward cache raised to 300 packets (media chunks are evicted before text), sync batch to 40, seen-packet cache to 3000.
- Peer timeout raised to 150 s so peers several hops away stop flickering in the list.

### Breaking changes
Wire protocol gained two packet types and direct-message payloads now carry `sid`/`n`; 1.2 cannot read direct messages from 1.1 and earlier.

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
