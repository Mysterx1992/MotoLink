from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding="utf-8")


def replace_exact(old: str, new: str, label: str, count: int = 1):
    global s
    found = s.count(old)
    if found != count:
        raise SystemExit(f"VC16 PATCH FAIL {label}: expected {count}, found {found}")
    s = s.replace(old, new, count)
    print(f"VC16 PATCH OK {label}")

# VOGE Global 1.1.7 OEM semantics recovered from the app itself:
#   roadFlag 2 = tollgate, roadFlag 4 = roundabout.
# The existing V1.6 encoder already writes TurnByTurnInstruction.annularDegrees
# to byte 16 of opcode 0x6A, so vc16 only needs to feed the correct OEM values.
# A local TFLite model (OpenDash / KTM-Nav-GEN3, MIT) identifies the Google Maps
# roundabout exit shape. Non-roundabout maneuvers keep the vc15 path.
old = '''        val textManeuver = instructionFields.asSequence()
            .map(::maneuverFrom)
            .firstOrNull { it != NavManeuver.UNKNOWN }
        val maneuver = textManeuver ?: maneuverFromIcon(visual.maneuverIcon)
        if (maneuver == NavManeuver.UNKNOWN && distance != null && visual.maneuverIcon != null) {
'''
new = '''        val textManeuver = instructionFields.asSequence()
            .map(::maneuverFrom)
            .firstOrNull { it != NavManeuver.UNKNOWN }
        val roundaboutSector = visual.maneuverIcon?.let {
            V16ManeuverClassifier.classifyRoundaboutSector(context, it)
        }
        val maneuver = textManeuver
            ?: if (roundaboutSector != null) NavManeuver.ROUNDABOUT else maneuverFromIcon(visual.maneuverIcon)
        if (maneuver == NavManeuver.UNKNOWN && distance != null && visual.maneuverIcon != null) {
'''
replace_exact(old, new, "maps_ml_roundabout_sector")

old = '''        val sourceKind = if (textManeuver != null) "TEXT" else if (maneuver != NavManeuver.UNKNOWN) "ICON" else "NONE"
'''
new = '''        val sourceKind = when {
            textManeuver != null -> "TEXT"
            roundaboutSector != null -> "ML_ICON"
            maneuver != NavManeuver.UNKNOWN -> "ICON"
            else -> "NONE"
        }
        if (maneuver == NavManeuver.ROUNDABOUT && roundaboutSector == null) {
            AppLog.add("MAPS NAV V1.6 ROUNDABOUT: flag OEM corretto ma settore uscita non classificato; annular=0")
        }
'''
replace_exact(old, new, "maps_ml_source_and_safe_fallback")

replace_exact(
    '            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 2 else 0,\n',
    '            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0,\n'
    '            annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0,\n',
    "voge_oem_roundabout_flag_and_annular",
)

replace_exact(
    'AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m")',
    'AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m roadFlag=${model.roadFlag} annular=${model.annularDegrees}")',
    "maps_accept_log_oem_fields",
)

required = [
    'V16ManeuverClassifier.classifyRoundaboutSector(context, it)',
    'roundaboutSector != null -> "ML_ICON"',
    'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0',
    'annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0',
    'flag OEM corretto ma settore uscita non classificato; annular=0',
    'NavManeuver.STRAIGHT, NavManeuver.ROUNDABOUT -> 1',
    'UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e")',
    'val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f"VC16 PATCH VERIFY FAIL: {marker}")
if 'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 2 else 0' in s:
    raise SystemExit("VC16 PATCH VERIFY FAIL: stale tollgate roadFlag=2 still present")

p.write_text(s, encoding="utf-8")
print("VC16 VOGE OEM ROUNDABOUT PATCH COMPLETE")
