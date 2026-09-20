# MotoLink V1.6.1 — Proximity Hotfix

- package: `it.motolink.app`
- versionName: `1.6.1`
- versionCode: `22`
- base: MotoLink V1.6 vc21
- scope: Pocket Mode / proximity only

When Pocket Mode is OFF, MotoLink sends `ACTION_PROX_RELEASE`, unregisters the
`TYPE_PROXIMITY` listener and releases any `PROXIMITY_SCREEN_OFF_WAKE_LOCK`.
EasyConn, H264, BLE navigation and clock logic are unchanged.

Official-release gate: signer certificate SHA-256 must be exactly
`9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`.
