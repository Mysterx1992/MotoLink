#!/usr/bin/env python3
"""Read-only preflight of the declared foreground service and related consent wiring.

Static source/Manifest check ONLY: it cannot replace Play review or an Android
runtime test. This script must not change MotoLink's media, BLE or TFT behavior.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parents[1]
NS = "{http://schemas.android.com/apk/res/android}"


def must(condition: bool, explanation: str, errors: list[str]) -> None:
    if condition:
        print("PASS:", explanation)
    else:
        print("FAIL:", explanation, file=sys.stderr)
        errors.append(explanation)


def run() -> int:
    errors: list[str] = []
    root = ET.parse(BASE / "app/src/main/AndroidManifest.xml").getroot()
    perms = [p.get(NS + "name") for p in root.findall("uses-permission")]
    services = root.findall("./application/service")
    fg_services = [s for s in services if s.get(NS + "foregroundServiceType")]
    media = [s for s in fg_services if s.get(NS + "name") == ".MirrorService"]
    must(len(media) == 1 and len(fg_services) == 1, "one declared FGS: MirrorService", errors)
    must(set(perms).issuperset({
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    }), "MediaProjection FGS permissions declared", errors)
    must(len(media) == 1 and media[0].get(NS + "foregroundServiceType") == "mediaProjection",
         "FGS type is mediaProjection only", errors)
    must(len(media) == 1 and media[0].get(NS + "exported") == "false",
         "MirrorService is not exported", errors)
    listeners = [s for s in services if s.get(NS + "name") == ".MapsNavigationListenerService"]
    must(len(listeners) == 1 and
         listeners[0].get(NS + "permission") == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
         "Maps listener guarded by Android binding permission", errors)
    must("android.permission.ACCESS_BACKGROUND_LOCATION" not in perms,
         "no background location permission", errors)

    main = (BASE / "app/src/main/java/it/motolink/app/MainActivity.kt").read_text()
    mirror = (BASE / "app/src/main/java/it/motolink/app/MirrorService.kt").read_text()
    nav = (BASE / "app/src/main/java/it/motolink/app/V16Navigation.kt").read_text()
    must("mgr.createScreenCaptureIntent()" in main and
         "if (requestCode != REQ_CAPTURE) return" in main and
         "if (resultCode != RESULT_OK || data == null)" in main and
         "startForegroundService(serviceIntent)" in main,
         "capture-intent, positive user result and foreground service wiring still present", errors)
    must("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION" in mirror and
         re.search(r"startForeground\s*\(", mirror) is not None and
         "getMediaProjection(resultCode, resultData)" in mirror and
         "projection!!.registerCallback" in mirror and
         "stopSelf()" in mirror and "shutdown(" in mirror,
         "projection foreground start/consent consumption/stop paths still present", errors)
    must('MAPS_PACKAGE = "com.google.android.apps.maps"' in nav and
         "if (sbn?.packageName != MAPS_PACKAGE) return" in nav and
         "VogeBleNavigationManager.sendNavigation(model)" in nav,
         "Maps-only navigation filtering remains present", errors)

    if errors:
        print(f"FAIL: {len(errors)} static Play permission contract checks")
        return 1
    print("PASS: static Play permission contract (not runtime/Play approval)")
    return 0


if __name__ == "__main__":
    sys.exit(run())
