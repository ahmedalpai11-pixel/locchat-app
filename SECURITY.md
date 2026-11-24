# Security model

## What is protected
| Layer | Mechanism |
|---|---|
| Identity | Ed25519 keypair; node ID = `LC-` + first 16 bytes of SHA-256(public key) |
| Packet authenticity | Ed25519 signature over id/type/sender/name/x25519 key/target/payload/timestamp |
| Direct chats | X25519 static DH → HKDF-SHA256 (bound to both public keys) → AES-256-GCM with AAD |
| Channels | AES-256-GCM, key from PBKDF2-SHA256 (210k iterations) or a public per-channel key if no passphrase |
| Keys at rest | Wrapped by a non-exportable Android Keystore AES-GCM key |
| Replay | Timestamp window + seen-packet cache |
| Trust | Key pinning for verified contacts; QR verification binds node ID ↔ Ed25519 key |

## Known limitations (see ROADMAP.md)
- Direct chats have forward secrecy per message (hash ratchet inside an ephemeral-key session). Compromise of a live session's current chain key still exposes messages until the next rotation (≤30 min / 100 messages). Channels use a passphrase-derived key and have no forward secrecy.
- The session handshake is a custom Noise-KK-style construction and has not been independently reviewed.
- Relays and nearby observers can see metadata (sender, target, size, timing).
- QR payload is not signed; the X25519 key in it is not cryptographically bound to the Ed25519 key.
- Biometric lock is skipped when the device has no screen lock/biometrics enrolled.
- If the Android Keystore is unavailable, private keys fall back to unprotected storage.
- This project has **not** been independently audited. Do not rely on it for life-critical secrecy.

## Reporting
Open a private security advisory on the repository.
