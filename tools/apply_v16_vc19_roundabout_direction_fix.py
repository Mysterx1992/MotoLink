#!/usr/bin/env python3
from pathlib import Path

NAV = Path('app/src/main/java/it/motolink/app/V16Navigation.kt')
ML = Path('app/src/main/java/it/motolink/app/V16ManeuverClassifier.kt')

nav = NAV.read_text(encoding='utf-8')
ml = ML.read_text(encoding='utf-8')

old = '''    val roadFlag: Int = 0,\n    val annularDegrees: Int = 0,\n    val source: String = "",'''
new = '''    val roadFlag: Int = 0,\n    val annularDegrees: Int = 0,\n    val directionOverride: Int? = null,\n    val source: String = "",'''
if nav.count(old) != 1:
    raise SystemExit(f'VC19 NAV field patch FAIL count={nav.count(old)}')
nav = nav.replace(old, new, 1)

old = '        p[10] = directionCode(m.maneuver).toByte()'
new = '        p[10] = (m.directionOverride ?: directionCode(m.maneuver)).coerceIn(0, 9).toByte()'
if nav.count(old) != 1:
    raise SystemExit(f'VC19 NAV byte10 patch FAIL count={nav.count(old)}')
nav = nav.replace(old, new, 1)

old = '''        val roundaboutSector = visual.maneuverIcon?.let {\n            V16ManeuverClassifier.classifyRoundaboutSector(context, it)\n        }\n        val maneuver = textManeuver\n            ?: if (roundaboutSector != null) NavManeuver.ROUNDABOUT else maneuverFromIcon(visual.maneuverIcon)'''
new = '''        val roundaboutEncoding = visual.maneuverIcon?.let {\n            V16ManeuverClassifier.classifyRoundaboutEncoding(context, it)\n        }\n        val roundaboutSector = roundaboutEncoding?.annularDegrees\n        val maneuver = textManeuver\n            ?: if (roundaboutEncoding != null) NavManeuver.ROUNDABOUT else maneuverFromIcon(visual.maneuverIcon)'''
if nav.count(old) != 1:
    raise SystemExit(f'VC19 NAV classifier patch FAIL count={nav.count(old)}')
nav = nav.replace(old, new, 1)

old = '''            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0,\n            annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0,\n            source = "GOOGLE_MAPS_NOTIFICATION_$sourceKind",'''
new = '''            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0,\n            annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0,\n            directionOverride = if (maneuver == NavManeuver.ROUNDABOUT) roundaboutEncoding?.directionCode else null,\n            source = "GOOGLE_MAPS_NOTIFICATION_$sourceKind",'''
if nav.count(old) != 1:
    raise SystemExit(f'VC19 NAV model field patch FAIL count={nav.count(old)}')
nav = nav.replace(old, new, 1)

old = '        AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m roadFlag=${model.roadFlag} annular=${model.annularDegrees}")'
new = '        AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m roadFlag=${model.roadFlag} annular=${model.annularDegrees} dir=${model.directionOverride ?: -1}")'
if nav.count(old) != 1:
    raise SystemExit(f'VC19 NAV log patch FAIL count={nav.count(old)}')
nav = nav.replace(old, new, 1)

old = '''    fun classifyRoundaboutSector(context: Context, bitmap: Bitmap): Int? {\n        val code = classifyCode(context, bitmap) ?: return null\n        val section16 = when (code) {\n            in 26..41 -> code - 25\n            in 42..57 -> code - 41\n            else -> return null\n        }\n        val vogeSector = (section16 * 12.0 / 16.0).roundToInt().coerceIn(1, 12)\n        AppLog.add("MAPS NAV V1.6 ML: class=$code section16=$section16 -> VOGE annular=$vogeSector")\n        return vogeSector\n    }'''
new = '''    data class RoundaboutEncoding(\n        val annularDegrees: Int,\n        val directionCode: Int,\n    )\n\n    fun classifyRoundaboutEncoding(context: Context, bitmap: Bitmap): RoundaboutEncoding? {\n        val code = classifyCode(context, bitmap) ?: return null\n        val section16 = when (code) {\n            in 26..41 -> code - 25\n            in 42..57 -> code - 41\n            else -> return null\n        }\n        val vogeSector = (section16 * 12.0 / 16.0).roundToInt().coerceIn(1, 12)\n        // VOGE Global 1.1.7 Mapbox path does NOT force roundabouts to direction=1.\n        // It combines roadFlag=4 + annularDegrees with the exit modifier mapped to\n        // the same 1..9 direction table used for ordinary maneuvers. The OpenDash\n        // model's 16-sector labels encode that modifier geometrically.\n        val direction = when (section16) {\n            2 -> 7   // slight right\n            4 -> 5   // right\n            6 -> 9   // sharp right\n            8 -> 1   // straight\n            10 -> 6  // slight left\n            12 -> 4  // left\n            14 -> 8  // sharp left\n            16 -> 2  // U-turn for right-hand traffic (VOGE Mapbox drivingSide=right)\n            else -> return null\n        }\n        AppLog.add("MAPS NAV V1.6 ML: class=$code section16=$section16 -> VOGE annular=$vogeSector direction=$direction")\n        return RoundaboutEncoding(vogeSector, direction)\n    }'''
if ml.count(old) != 1:
    raise SystemExit(f'VC19 ML API patch FAIL count={ml.count(old)}')
ml = ml.replace(old, new, 1)

NAV.write_text(nav, encoding='utf-8')
ML.write_text(ml, encoding='utf-8')
print('VC19 roundabout direction fix applied: OEM Mapbox direction + roadFlag4 + annular preserved')
