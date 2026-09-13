from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding="utf-8")

anchor = '''        if (!model.hasRealDistance) {
            AppLog.add("MAPS NAV V1.6 GATE: istruzione ricevuta ma distanza reale alla manovra non esposta; TFT non aggiornato")
            return
        }
        if (model.maneuver == NavManeuver.UNKNOWN) {
'''
replacement = '''        if (!model.hasRealDistance) {
            AppLog.add("MAPS NAV V1.6 GATE: istruzione ricevuta ma distanza reale alla manovra non esposta; TFT non aggiornato")
            return
        }
        if (model.distanceMeters == 0) {
            AppLog.add("MAPS NAV V1.6 ZERO TRANSITION GUARD: distance=0 soppressa; attendo la prossima istruzione positiva per evitare stale 0 sul TFT")
            return
        }
        if (model.maneuver == NavManeuver.UNKNOWN) {
'''

if s.count(anchor) != 1:
    raise SystemExit(f"VC20 ZERO GUARD FAIL: anchor count={s.count(anchor)}")
s = s.replace(anchor, replacement, 1)
p.write_text(s, encoding="utf-8")

checks = [
    'if (model.distanceMeters == 0)',
    'ZERO TRANSITION GUARD: distance=0 soppressa',
    'VogeBleNavigationManager.sendNavigation(model)',
]
for marker in checks:
    if marker not in s:
        raise SystemExit(f"VC20 ZERO GUARD VERIFY FAIL: {marker}")
print("VC20 ZERO TRANSITION GUARD OK: zero-distance Maps transitions no longer overwrite TFT")
