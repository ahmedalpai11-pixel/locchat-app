# Roadmap (LocChat vs bitchat)

## Next (security)
- [ ] Noise XX sessions with forward secrecy + visible safety numbers
- [ ] Signed QR v2 (Ed25519 signature over node ID, name, both keys)
- [ ] Fail loudly when Keystore is unavailable; require device lock for biometric option
- [ ] Salted/versioned channel-key derivation with migration
- [ ] Independent audit

## Next (features)
- [ ] Disappearing messages (per-chat timer) with signed remote delete
- [ ] Private groups with rotating shared keys
- [ ] Wi-Fi Direct bulk transfer (images/files), BLE for discovery
- [ ] Encrypted identity backup (password-protected export)
- [ ] Read receipts + typing indicator (optional)
- [ ] Emergency SOS broadcast with coarse location
- [ ] Traffic-shaping mode (padding + jittered sends)
- [ ] Share the APK offline via QR/Wi-Fi Direct

## Quality
- [ ] Enable R8 minification with BouncyCastle keep rules and test on device
- [ ] Change `applicationId` from `com.example`
- [ ] Instrumented BLE tests on two physical devices
