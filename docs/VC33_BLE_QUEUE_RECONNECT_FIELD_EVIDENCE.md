# MotoLink V1.7 vc33 — BLE latest-wins and ignition reconnect

**Status:** candidate only — NOT a publicly released APK.

**Current stable:** MotoLink V1.7 vc32, released in `v1.7`, SHA-256 `ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625`. The homepage button must continue downloading that officially signed APK until the vc33 gate is complete.

**Source:** `feature/v17-vc33-ble-latest-wins-reconnect`; PR #15; `versionCode 33`, `versionName 1.7`; unchanged `it.motolink.app`.

## Field findings — Russian VOGE Valico 625 DSX

The field tester reports Google Maps turn-by-turn and roundabout maneuvers operating on the physical TFT, including when the phone display is off. Two independent issues remain: delayed TFT distance display and failure to auto-reattach after ignition OFF/ON.

In `MotoLink_Log_20261012_093513.txt` (1,541 lines):
- 194 accepted TBT updates: RIGHT 87, LEFT 25, SHARP_RIGHT 34, ROUNDABOUT 48.
- 166 queued BLE updates; of 48 roundabout updates, 41 queued and 7 blocked while the channel was unverified.
- 23 `distanceMissing=true` diagnostics, 28 accepted instructions not queued because the TFT channel was not verified.
- Two observed roundabout encodings: `roadFlag=4, annular=11, dir=8` and `roadFlag=4, annular=6, dir=1`.
- Example RIGHT distance progression: 90m at 09:23:32, 80m at 09:23:34, 70m at 09:23:36, 60m at 09:23:37, 50m at 09:23:39; all accepted and queued. Queueing does not prove TFT display.
- User-reported lag: TFT remains at 90m while Maps already shows 50m. Without synchronized TFT observation cannot quantify end-to-end delay.

## vc33 change scope

1. **Atomic five-frame NAV groups, latest-wins pending.** When a new valid instruction arrives, discard obsolete queued NAV groups while preserving frames of the already started group. The new pending group replaces any previous pending group.
2. Keep a single GATT write in flight, the 100ms NAV frame pacing, heartbeat, 0x6A–0x6E packet semantics, and the existing callback/watchdog discipline unchanged.
3. Measure (monotonic elapsedRealtime) per-group queue wait and last GATT callback. Logs identify TFT rendering latency as **not measured**.
4. **Ignition reconnect:** a BLE explicit scan with no bike re-enters scan cycles (6.5s scan + 2.5/5/10/15s pauses) without cancelling START. STOP cancels retries via generation invalidation.
5. On reconnection, confirm GATT and heartbeat before announcing READY; do not reopen Google Maps on subsequent READY callbacks.
6. Preserve initial AUTOMATIC fallback to Hotspot/QR when no BLE bike is available. Once BLE has actually been READY, prefer BLE reattach after ignition cycle over switching transports.

No Google Maps parser, text/ru localization, maneuver classifier, roundabout encoding, EasyConn, H264, MediaProjection, network recovery, Pocket Mode, QR, profiles, or visible GUI design changes in this candidate.

## Gates and publication

- [ ] Final PR commit debug CI compile PASS.
- [ ] Synthetic scheduler model and source contract regression PASS (does not replace real-world test).
- [ ] `assembleRelease` unsigned candidate PASS; CI artifact retained temporarily.
- [ ] Private official signing using existing cert, APK signature v2/v3, package/version and SHA-256 PASS. **Do not put keystores/passwords into GitHub.**
- [ ] Physical VOGE 625 DSX test: delayed-distance improvement, two distinct roundabout geometries, TFT rendering latency, ignition OFF/ON without START, STOP cancellation, same Maps navigation session.
- [ ] After physical + signing PASS, release assets with stable `MotoLink_V1.7.apk` and SHA, archive vc32, `main` merge, release notes and README/direct download update. Badge only says **V1.7**.

No unverified claim of vc33 deployment is permitted. Current public vc32 must remain available until all these gates pass.
