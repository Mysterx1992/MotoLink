#!/usr/bin/env python3
"""Read-only four-language GUI audit: MotoLink v1.8.2.

Checks presentation boundaries, canonical connection semantics, BLE preservation
and real keys in each dictionary. No Android instrumentation or network.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
src = root / "app/src/main/java/it/motolink/app"
locale = (src / "MotoLinkLocale.kt").read_text(encoding="utf-8")
main = (src / "MainActivity.kt").read_text(encoding="utf-8")
dash = (src / "TrofeoDashboardView.kt").read_text(encoding="utf-8")
nav = (src / "V16Navigation.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
errors = []

def check(ok, msg):
    print(("PASS" if ok else "FAIL") + ": " + msg)
    if not ok: errors.append(msg)

keys = {}
for lang, later in (("EN", "RU"), ("RU", "ZH"), ("ZH", "EN_FALLBACK")):
    first = "private val %s = mapOf(" % lang
    end = "private val %s = mapOf(" % later
    section = locale.split(first, 1)[1].split(end, 1)[0]
    keys[lang] = re.findall(r'^\s*"([^"]+)"\s+to\s+"', section, re.M)
    check(len(set(keys[lang])) == len(keys[lang]), lang + " translation map has no duplicate source keys")

for key in (
    "Pronto", "Nessun collegamento", "Moto collegata", "Connesso",
    "Sistema pronto", "Rete pronta", "Ripristino Wi-Fi",
    "Pronto. Premi AVVIA per iniziare.", "IMPOSTAZIONI · Ripristino Wi-Fi",
    "RIPRISTINO WI-FI", "Accendi il Bluetooth del telefono e premi di nuovo AVVIA.",
    "Autorizza Dispositivi nelle vicinanze per eseguire la diagnostica.",
    "Ricerca avviata…", "Scegli quale app vuoi cambiare",
    "Nome moto obbligatorio", "Wi‑Fi moto manuale",
):
    check(all(key in keys[lang] for lang in keys), "localized key in EN/RU/ZH: " + key)

check('if (lang == "it") return raw' in locale and '"ru" -> RU[key]' in locale
    and '"zh" -> ZH[key]' in locale and "else -> EN[key]" in locale,
    "follows system language: it/ru/zh; English default for unsupported")
check("fun releaseNotes182(): String" in locale and
    "MotoLinkLocale.releaseNotes182()" in main, "localized V1.8.2 version dialog")
check('val prefix = MotoLinkLocale.t(context, "Stato: ")' in dash
      and 'val localizedValue = MotoLinkLocale.t(context, value)' in dash
      and 'val start = prefix.length' in dash,
      "status label and value both localized; colored span offset recalculated")
check("MotoLinkLocale.t(context, prefix)" in dash and
      "MotoLinkLocale.t(context, value).take(48)" in dash,
      "bike header prefix and no connection localized")
check('private fun sectionTitle(value: String): TextView' in dash and
      'text = MotoLinkLocale.t(context, value)' in dash,
      "page section headers localized")
check('dashboard.updateHeader(\n            title,\n            subtitle,' in main and
      'dashboard.updateState(\n            title,\n            subtitle,' in main,
      "status model remains canonical before presentation layer")
check('normalized.equals("Connesso", ignoreCase = true)' in dash and
      "isConnectedStatus()" in dash, "background and UI connection-state comparator preserved")
check('wifiRecoveryState?.text = MotoLinkLocale.t(this, message)' in main,
      "Wi-Fi worker messages rendered via locale")
check('text = MotoLinkLocale.t(this@MainActivity, "RIPRISTINO WI-FI")' in main,
      "Wi-Fi recovery title localized")
check("resyncOnBleReady()" in nav and
      "MAPS NAV BLE RESYNC:" in nav and
      "MapsNavigationListenerService.resyncOnBleReady()" in main,
      "BLE GATT READY -> current Maps notification resync preserved")
check('applicationId = "it.motolink.app"' in gradle and
      'versionCode = 36' in gradle and
      'versionName = "1.8.2"' in gradle,
      "official v1.8.2 package and version identity")

if errors:
    raise SystemExit("FAIL: %d localization guards" % len(errors))
print("PASS: MotoLink V1.8.2 multilingual regression gates")
