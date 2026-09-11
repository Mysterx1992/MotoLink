from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding="utf-8")

old = '        p[14] = (if (m.maneuver == NavManeuver.ROUNDABOUT) 2 else m.roadFlag).coerceIn(0, 4).toByte()\n'
new = '        p[14] = m.roadFlag.coerceIn(0, 4).toByte()\n'

count = s.count(old)
if count != 1:
    raise SystemExit(f"VC18 ROUNDABOUT ENCODER FIX FAIL: expected exact stale encoder once, found {count}")

s = s.replace(old, new, 1)

required = [
    'p[14] = m.roadFlag.coerceIn(0, 4).toByte()',
    'p[16] = m.annularDegrees.coerceIn(0, 255).toByte()',
    'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0',
    'annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0',
    'NavManeuver.STRAIGHT, NavManeuver.ROUNDABOUT -> 1',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f"VC18 ROUNDABOUT ENCODER VERIFY FAIL: missing {marker}")

forbidden = [
    'p[14] = (if (m.maneuver == NavManeuver.ROUNDABOUT) 2 else m.roadFlag)',
    'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 2 else 0',
]
for marker in forbidden:
    if marker in s:
        raise SystemExit(f"VC18 ROUNDABOUT ENCODER VERIFY FAIL: stale marker still present {marker}")

p.write_text(s, encoding="utf-8")
print("VC18 ROUNDABOUT ENCODER FIX COMPLETE: opcode 0x6A byte14 now uses model roadFlag; ROUNDABOUT=4 preserved")
