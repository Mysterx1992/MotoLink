# MotoLink V1.8 — BLE NAV Resync TEST V1 (25 October 2026)

Status: **experimental Android debug build, not official V1.8 and not approved for Google Play release**.

## Source of the diagnosis

Physical tester report (Russian Google Maps): after stopping with route active, ignition OFF for about ten minutes, ignition ON, TFT navigation did not promptly reappear until manual STOP/START.

Uploaded log `MotoLink_Log_20261024_170353.txt` contained:
- 2026-10-24 12:03:04 GATT write callback timeout, BLE starts automatic recovery.
- 12:10:57-58 GATT services/CCCD/heartbeat ready; `reattach=true`, no prior manual START.
- 12:11:06 Google Maps active but distance missing, therefore notification correctly rejected.
- 12:11:46 right turn at 50 m parsed while BLE was not READY, no navigation packet sent.
- 12:11:48 GATT READY after recovery; prior V1.8 has no explicit live notification resync.
- Manual STOP/START by the tester is a workaround, not proof that the router recovered navigation spontaneously.

## Root cause (verified in source)

`VogeBleNavigationManager` automatically reconnects GATT, validates CCCD and 0x5A heartbeat, then invokes `onReady` in MainActivity. Former `onReady` did not ask `MapsNavigationListenerService` for the current navigation state. The Maps notification listener only processes initial listener binding or new notification posts, and deduplicates identical instruction fingerprints. Notifications parsed while BLE is unverified are discarded from the TFT transport.

Thus GATT READY and TFT navigation display readiness are **different states**. The log cannot measure the exact ignition ON timestamp or TFT rendering time.

## Change scope

1. On initial and subsequent GATT READY, MainActivity schedules `MapsNavigationListenerService.resyncOnBleReady()` without a new Google Maps launch.
2. The Android-system-bound notification listener registers itself only while connected. Resync queries `activeNotifications` at READY, never replaying a cached maneuver.
3. Only ongoing `com.google.android.apps.maps` notifications categorized as navigation are eligible.
4. Notification freshness gate: post age at most 120 seconds. Older snapshots and missing notification access fail closed. This deliberately does not guarantee display on a 100 km straight when Maps has not produced an up-to-date usable notification.
5. Force bypass of notification fingerprint dedup is restricted to the GATT READY resync; standard dedup unchanged. Real-distance, zero-distance, maneuver and roundabout validation continue to apply.
6. BLE `sendNavigation()` returns whether a five-frame packet group was queued. Packet encoding, characteristic UUIDs, heartbeat interval, scan/recovery/backoff are unchanged.
7. Debug-only applicationId `it.motolink.app.bletest`, app label `MotoLink BLE TEST`. Builds are debug-signed by each CI runner; no release keystore. This APK installs alongside production MotoLink and other debug test apps.

## Verification gates

- CI Android APK compilation (Kotlin), 16 KiB APK/native alignments, Play preflight (existing), and `python3 scripts/check_ble_navigation_resync.py`.
- GitHub Actions artifact `MotoLink_V1_8_BLE_RESYNC_TEST_DEBUG_APK` containing `app-debug.apk` and SHA-256 digest file.
- Physical TFT validation REQUIRED. Test safely with the bike stationary for power-cycle checks:
  1. Start route with a navigation notification containing a **real valid distance**.
  2. Connect via BLE and verify normal maneuver transmission.
  3. Ignition OFF ~10 minutes (MotoLink START and Google Maps navigation left active).
  4. Ignition ON, **do not press STOP/START**. Note time to BLE READY, resync log event and TFT first displayed turn.
  5. Repeat with an unchanged instruction (long straight) and one case where Maps notification has no real distance. Do not accept fabricated or stale distance.
  6. End route and verify no stale instruction replay after notification removed.
- Collect full logs, motorbike and Android model/OS, Google Maps version, approximate ignition ON time. Log only; no credentials or device identifiers.

## Safe rollback

Keep production V1.8 unchanged. Uninstall only `MotoLink BLE TEST` if a regression occurs. This branch and its draft PR are test-only; do not merge, publish a Play AAB or replace official APK without physical PASS and user's explicit approval.

## Known limits

`NAV_GROUP_COMPLETE` indicates Android GATT write acknowledged, **not** confirmation that the TFT rendered a navigation indicator. The 120-second safety gate and Google Maps localized notifications may legitimately cause no replay. A missing/invalid distance must never be filled using the last known numeric distance.
