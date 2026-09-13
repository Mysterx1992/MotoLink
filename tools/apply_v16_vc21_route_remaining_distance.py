from pathlib import Path

p = Path('app/src/main/java/it/motolink/app/V16Navigation.kt')
s = p.read_text(encoding='utf-8')

# Add route-summary model after MapsVisualData.
old = '''    private data class MapsVisualData(\n        val textLines: List<String>,\n        val maneuverIcon: Bitmap?,\n    )\n'''
new = '''    private data class MapsVisualData(\n        val textLines: List<String>,\n        val maneuverIcon: Bitmap?,\n    )\n\n    private data class RouteSummary(\n        val distanceMeters: Int,\n        val timeSeconds: Int?,\n        val arrivalHour: Int?,\n        val arrivalMinute: Int?,\n    )\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

# Replace maneuver distance + ETA extraction with separated route summary extraction.
old = '''        val distance = instructionFields.asSequence().mapNotNull(::explicitDistanceMeters).firstOrNull()\n        val textManeuver = instructionFields.asSequence()\n'''
new = '''        // Google Maps normally exposes a route summary such as\n        // "23 min · 17 km · 18:42" separately from the distance to the next maneuver.\n        // VOGE byte 7..9 is the whole-route remaining distance, not the turn distance.\n        val routeSummary = instructionFields.asSequence().mapNotNull(::routeSummaryFrom).firstOrNull()\n        val distance = instructionFields.asSequence()\n            .filter { routeSummaryFrom(it) == null }\n            .mapNotNull(::explicitDistanceMeters)\n            .firstOrNull()\n        val textManeuver = instructionFields.asSequence()\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''        val eta = instructionFields.asSequence().mapNotNull { line ->\n            etaRegex.find(line)?.let { m ->\n                m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }\n            }\n        }.firstOrNull()\n\n        return TurnByTurnInstruction(\n            maneuver = maneuver,\n            distanceMeters = distance,\n            arrivalHour = eta?.first,\n            arrivalMinute = eta?.second,\n'''
new = '''        val eta = routeSummary?.let { summary ->\n            summary.arrivalHour?.let { h -> h to (summary.arrivalMinute ?: 0) }\n        } ?: instructionFields.asSequence().mapNotNull { line ->\n            etaRegex.find(line)?.let { m ->\n                m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }\n            }\n        }.firstOrNull()\n\n        return TurnByTurnInstruction(\n            maneuver = maneuver,\n            distanceMeters = distance,\n            routeRemainDistanceMeters = routeSummary?.distanceMeters,\n            routeRemainTimeSeconds = routeSummary?.timeSeconds,\n            arrivalHour = eta?.first,\n            arrivalMinute = eta?.second,\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

# Add helper before explicitDistanceMeters.
anchor = '''    private fun explicitDistanceMeters(text: String): Int? {\n'''
helper = r'''    private fun routeSummaryFrom(text: String): RouteSummary? {
        val parts = text.split(Regex("\\s*[·•]\\s*")).map(String::trim).filter(String::isNotBlank)
        if (parts.size < 3) return null

        // Garminuino and other Maps-notification parsers observe the stable order
        // duration · remaining distance · ETA. Require both a distance and either time
        // or ETA so an ordinary maneuver sentence containing bullets cannot be mistaken.
        val distance = explicitDistanceMeters(parts[1]) ?: return null
        val timeSeconds = durationSeconds(parts[0])
        val eta = etaRegex.find(parts[2])?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }
        }
        if (timeSeconds == null && eta == null) return null
        return RouteSummary(
            distanceMeters = distance,
            timeSeconds = timeSeconds,
            arrivalHour = eta?.first,
            arrivalMinute = eta?.second,
        )
    }

    private fun durationSeconds(text: String): Int? {
        val hours = Regex("(?i)(\\d+)\\s*(?:h|hr|hrs|ora|ore|hour|hours)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minutes = Regex("(?i)(\\d+)\\s*(?:min|minuto|minuti|minute|minutes)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hours == null && minutes == null) return null
        val seconds = (hours ?: 0) * 3600L + (minutes ?: 0) * 60L
        return seconds.coerceIn(0L, 0xFFFFL).toInt()
    }

'''
assert s.count(anchor) == 1, s.count(anchor)
s = s.replace(anchor, helper + anchor, 1)

# Add route cache to listener and enrich outgoing model.
old = '''    companion object { private const val MAPS_PACKAGE = "com.google.android.apps.maps" }\n    private var lastFingerprint: String? = null\n'''
new = '''    companion object { private const val MAPS_PACKAGE = "com.google.android.apps.maps" }\n    private var lastFingerprint: String? = null\n    private var lastRouteRemainDistanceMeters: Int? = null\n    private var lastRouteRemainTimeSeconds: Int? = null\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''        val model = GoogleMapsSourceAdapter.parse(this, n) ?: return\n        val fingerprint = "${model.rawInstruction}|${model.maneuver}|${model.distanceMeters}|${model.arrivalHour}:${model.arrivalMinute}"\n        if (fingerprint == lastFingerprint) return\n        lastFingerprint = fingerprint\n        if (!model.hasRealDistance) {\n'''
new = '''        val parsed = GoogleMapsSourceAdapter.parse(this, n) ?: return\n        parsed.routeRemainDistanceMeters?.takeIf { it > 0 }?.let { lastRouteRemainDistanceMeters = it }\n        parsed.routeRemainTimeSeconds?.takeIf { it > 0 }?.let { lastRouteRemainTimeSeconds = it }\n        val model = parsed.copy(\n            routeRemainDistanceMeters = parsed.routeRemainDistanceMeters ?: lastRouteRemainDistanceMeters,\n            routeRemainTimeSeconds = parsed.routeRemainTimeSeconds ?: lastRouteRemainTimeSeconds,\n        )\n        val fingerprint = "${model.rawInstruction}|${model.maneuver}|${model.distanceMeters}|${model.routeRemainDistanceMeters}|${model.routeRemainTimeSeconds}|${model.arrivalHour}:${model.arrivalMinute}"\n        if (fingerprint == lastFingerprint) return\n        lastFingerprint = fingerprint\n        if (!model.hasRealDistance) {\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''        AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m roadFlag=${model.roadFlag} annular=${model.annularDegrees} dir=${model.directionOverride ?: -1}")\n'''
new = '''        AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m routeRemain=${model.routeRemainDistanceMeters ?: -1}m routeTime=${model.routeRemainTimeSeconds ?: -1}s roadFlag=${model.roadFlag} annular=${model.annularDegrees} dir=${model.directionOverride ?: -1}")\n'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')

checks = [
    'val routeSummary = instructionFields.asSequence().mapNotNull(::routeSummaryFrom).firstOrNull()',
    'routeRemainDistanceMeters = routeSummary?.distanceMeters',
    'routeRemainTimeSeconds = routeSummary?.timeSeconds',
    'text.split(Regex("\\\\s*[·•]\\\\s*"))',
    'routeRemainDistanceMeters = parsed.routeRemainDistanceMeters ?: lastRouteRemainDistanceMeters',
    'routeRemain=${model.routeRemainDistanceMeters ?: -1}m',
    'putBe(p, 7, (m.routeRemainDistanceMeters ?: 0).coerceIn(0, 0xFFFFFF), 3)',
    'ZERO TRANSITION GUARD: distance=0 soppressa',
]
for marker in checks:
    if marker not in s:
        raise SystemExit(f'VC21 VERIFY FAIL: {marker}')
print('VC21 PATCH TEST OK')
