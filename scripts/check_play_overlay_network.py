#!/usr/bin/env python3
"""Fail-closed static audit for MotoLink's scoped network policy and overlay uses.

This is NOT a runtime security scan or Google Play certification. No external
network requests, APK modification or real motorcycle tests are performed.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def main() -> int:
    failed = []
    def check(yes, msg):
        print(("PASS" if yes else "FAIL") + ": " + msg)
        if not yes:
            failed.append(msg)

    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    app = manifest.find("application")
    if app is None:
        print("FAIL: <application> missing")
        return 1

    # Creating a Network Security Config overrides usesCleartextTraffic on API >=24;
    # base-config preserves frozen dynamic TFT/LAN behavior.
    check(app.get(ANDROID + "networkSecurityConfig") == "@xml/network_security_config",
          "network security XML attached to Android application")
    check(app.get(ANDROID + "usesCleartextTraffic") == "true",
          "legacy cleartext setting unchanged")
    xml = ET.parse(ROOT / "app/src/main/res/xml/network_security_config.xml").getroot()
    check(xml.tag == "network-security-config", "valid network-security-config root")
    base = xml.find("base-config")
    check(base is not None and base.get("cleartextTrafficPermitted") == "true",
          "existing dynamically addressed TFT LAN traffic remains permitted")
    restricted = set()
    for d in xml.findall("domain-config"):
        check(d.get("cleartextTrafficPermitted") == "false",
              "internet domain-config explicitly forbids cleartext")
        for domain in d.findall("domain"):
            restricted.add(domain.text)
            check(domain.get("includeSubdomains") == "true",
                  "protected domain includes subdomains")
    check({"supabase.co", "paypal.com", "paypalobjects.com"}.issubset(restricted),
          "known internet domains protected from cleartext")

    permissions = {p.get(ANDROID + "name") for p in manifest.findall("uses-permission")}
    check("android.permission.SYSTEM_ALERT_WINDOW" in permissions,
          "real overlay permission not accidentally removed")
    main = (ROOT / "app/src/main/java/it/motolink/app/MainActivity.kt").read_text()
    mirror = (ROOT / "app/src/main/java/it/motolink/app/MirrorService.kt").read_text()
    ai = (ROOT / "app/src/main/java/it/motolink/app/MotoLinkAiClient.kt").read_text()
    dashboard = (ROOT / "app/src/main/java/it/motolink/app/TrofeoDashboardView.kt").read_text()
    check('if (pocketModeForPendingStart) {' in main and
          "ensureBackgroundGatePermissionThenProjection()" in main and
          "Settings.ACTION_MANAGE_OVERLAY_PERMISSION" in main and
          "Settings.canDrawOverlays(this)" in main,
          "overlay requested conditionally, with standard Android permission UI")
    check(mirror.count("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY") >= 2,
          "manual black screen and adaptation panel overlay uses remain present")
    check("supabaseUrl.startsWith(\"https://\")" in ai and
          'URL(url)' in ai and
          'https://www.paypal.com/donate/' in main and
          "https://www.paypal.com/" in dashboard,
          "known internet code paths still use HTTPS")
    check("MirrorService.ACTION_START" in main and "getMediaProjection(" in mirror,
          "projection source unchanged (static markers)")

    if failed:
        print(f"FAIL: {len(failed)} security audit checks")
        return 1
    print("PASS: scoped overlay/network static contract; physical parity not certified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
