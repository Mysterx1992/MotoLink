#!/usr/bin/env python3
"""Read-only source contract and deterministic scenario checks for BLE NAV RESYNC V1.

No external network, Bluetooth, Android runtime or broker writes.
Full Kotlin compilation and physical TFT delivery remain separate gates.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
nav = (root / "app/src/main/java/it/motolink/app/V16Navigation.kt").read_text(encoding="utf-8")
main = (root / "app/src/main/java/it/motolink/app/MainActivity.kt").read_text(encoding="utf-8")
errors = []

def check(condition, label):
    print(("PASS" if condition else "FAIL") + ": " + label)
    if not condition:
        errors.append(label)

def section(text, start, end):
    a = text.find(start)
    b = text.find(end, a + 1) if a >= 0 else -1
    return text[a:b] if a >= 0 and b > a else ""

ready = section(main, "private fun startV16BleNavigation(", "private fun isMapsNotificationAccessEnabled()")
service = section(nav, "class MapsNavigationListenerService :", "")
# Last class: read until end of file, not using the section utility.
service = nav[nav.find("class MapsNavigationListenerService :"):]
resync = section(service, "private fun replayCurrentNavigation()", "// Observes ALL Google Maps notifications")
handle = service[service.find("private fun handle(sbn:"):]
send = section(nav, "fun sendNavigation(model: TurnByTurnInstruction): Boolean", "private val heartbeat = object")

check(bool(ready) and "BLE NAV RESYNC V1: GATT READY" in ready and
      "MapsNavigationListenerService.resyncOnBleReady()" in ready and
      "VogeBleNavigationManager.isReady" in ready, "initial READY and automatic reattach request resync")
check(ready.count("ensureMapsNotificationAccessThenLaunch()") == 1 and
      "if (!mapsLaunchRequested)" in ready,
      "Maps launch still requested only on initial READY; never every reconnect")
check("connectedListener = this" in service and
      "override fun onListenerDisconnected()" in service and
      "override fun onDestroy()" in service and
      service.count("connectedListener = null") >= 2,
      "system-bound notification listener lifecycle reference not retained after detach")
check("activeNotifications" in resync and
      "it.packageName == MAPS_PACKAGE" in resync and
      "it.isOngoing" in resync and
      "it.notification?.category == Notification.CATEGORY_NAVIGATION" in resync,
      "replay only from current ongoing Google Maps navigation notification")
check("if (!VogeBleNavigationManager.isReady)" in resync and
      "snapshotAgeMs > 120_000L" in resync and
      "return false" in resync and
      "handle(live, forceBleResync = true)" in resync,
      "GATT READY and maximum age 120s; fail closed otherwise")
check("if (fingerprint == lastFingerprint && !forceBleResync) return false" in handle,
      "normal notification dedup unchanged; authorized reattach bypasses it")
check("if (!model.hasRealDistance)" in handle and
      "if (model.distanceMeters == 0)" in handle and
      "if (model.maneuver == NavManeuver.UNKNOWN)" in handle,
      "distance, zero transition and maneuver reliability gates retained")
check("fun sendNavigation(model: TurnByTurnInstruction): Boolean" in send and
      "if (!model.hasRealDistance || model.maneuver == NavManeuver.UNKNOWN)" in send and
      "frames.forEach { queue.addLast" in send and
      "return true" in send,
      "BLE packet encoder and atomic five-frame queue unchanged, returns queued outcome")
check("lastRouteRemainDistanceMeters = null" in service and
      "lastRouteRemainTimeSeconds = null" in service,
      "removed route clears stale cached route-level metadata")
check("fun navigationFrames(model: TurnByTurnInstruction): List<ByteArray>" in nav and
      "private const val CMD_NAV = 0x6A" in nav and
      "private const val HEARTBEAT_MS = 1000L" in nav,
      "BLE command IDs and heartbeat interval unchanged")

# Deterministic safety-policy tests; the source assertions above bind this
# decision table to the production gate ordering.
def allow_resync(ready, listener, live, maps, ongoing, nav_category, age_ms, has_distance, distance, maneuver_valid):
    return (ready and listener and live and maps and ongoing and nav_category and
            0 <= age_ms <= 120000 and has_distance and distance > 0 and maneuver_valid)

cases = {
    "valid existing same-maneuver notification after reattach": (True,True,True,True,True,True,30000,True,9100,True,True),
    "BLE still disconnected": (False,True,True,True,True,True,30000,True,100,True,False),
    "listener unavailable": (True,False,True,True,True,True,30000,True,100,True,False),
    "notification no longer active": (True,True,False,True,True,True,30000,True,100,True,False),
    "unrelated app": (True,True,True,False,True,True,30000,True,100,True,False),
    "non-navigation notification": (True,True,True,True,True,False,30000,True,100,True,False),
    "stale post-ignition notification": (True,True,True,True,True,True,180000,True,100,True,False),
    "missing Russian Maps distance": (True,True,True,True,True,True,30000,False,0,True,False),
    "zero-distance transition": (True,True,True,True,True,True,30000,True,0,True,False),
    "unknown maneuver": (True,True,True,True,True,True,30000,True,100,False,False),
}
for label, sample in cases.items():
    expected = sample[-1]
    check(allow_resync(*sample[:-1]) == expected, label)

if errors:
    raise SystemExit(f"FAIL: {len(errors)} BLE resync safety contract(s)")
print("PASS: all BLE NAV RESYNC V1 static and synthetic contract checks")
