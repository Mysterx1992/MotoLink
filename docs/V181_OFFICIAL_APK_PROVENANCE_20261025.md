# MotoLink V1.8.1 official APK — source and signing verification (25 October 2026)

## Provenance

- Production applicationId: `it.motolink.app` (NO debug `applicationIdSuffix`).
- Version: `versionCode=35`, `versionName=1.8.1`, Android `minSdk=29`, `targetSdk=36`.
- GitHub CI run for unsigned release: `40191811805`, source commit `e518396c6d8202cc07f97560193dde0dc09dbcaa`.
- GitHub Actions artifact: `MotoLink_V1_8_1_UNSIGNED_ALIGNED_OFFLINE_SIGN` (id `12598741195`).
- Artifact contained a byte-checked, zipaligned unsigned release APK and Android SDK Build Tools 36.0.0 `apksigner.jar`.
- Signature completed locally in a temporary private directory using the existing signing backup `MotoLink_Release_Keystore.jks`. Temporary keystore copy and password-bearing process environment were NOT committed to GitHub.
- Resulting APK `MotoLink1.8.1.apk`:
  - SHA-256: `1d01d0886e13f44538d20c8cbbab9c86efe0cf722effa48dc9de6f64b5677b30`
  - verified `apksigner` (exit 0), certificate SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100` identical to the published V1.8.
  - Android APK Signature Scheme **v3 verified true**. The original published V1.8 also uses v3 and not v2, so the release verifier was aligned to the real original.
- Archive with APK, SHA256 and signature verification also passed CRC verification.
- Production V1.8 asset and main branch were **not modified** while original-signature publication is blocked.

## Publication blocker

The GitHub Actions repository still lacks its required original-keystore secrets, so the official-signing run fails closed with `Original release keystore credentials not configured`. The signing backup contains the correct four named values, but they MUST NOT be pushed into a public repository, commit, issue, Actions artifact, or chat response. The active GitHub connector cannot configure GitHub Actions repository secrets.

An authorized maintainer should install `MOTOLINK_KEYSTORE_B64`, `MOTOLINK_KEYSTORE_PASSWORD`, `MOTOLINK_KEY_ALIAS`, and `MOTOLINK_KEY_PASSWORD` as GitHub Actions repository secrets from the private backup, rerun the sign workflow, and compare resulting APK certificate/digest. Only after successfully verifying the official installer should branch be merged and release v1.8.1 published from main, using the exact final APK and corresponding SHA256.

**No GitHub release v1.8.1 has been published by this change.** Releasing is distinct from producing the correctly signed APK.

## Physical confirmation

The BLE GATT reconnection-to-Maps resync source fix is compiled but is pending a physical ignition OFF / ON test on the Russian tester's TFT. Logging `BLE NAV RESYNC V1` and `MAPS NAV BLE RESYNC` should be correlated with the observed navigation indicator. Missing/old real-distance notifications must fail closed.
