from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding="utf-8")

# The code-13 physical test proved BLE READY/heartbeat status=0 and real distance extraction.
# This patch changes only Google Maps maneuver extraction/classification. BLE/Voge framing,
# UUIDs, queueing, EasyConn, H264 and mirroring are intentionally untouched.

anchor = "import android.app.Notification\n"
imports = """import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
"""
if s.count(anchor) != 1:
    raise SystemExit("MAPS FIX FAIL: Notification import anchor")
s = s.replace(anchor, imports, 1)

anchor = "import android.service.notification.StatusBarNotification\n"
imports = """import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
"""
if s.count(anchor) != 1:
    raise SystemExit("MAPS FIX FAIL: view import anchor")
s = s.replace(anchor, imports, 1)

start = s.find("object GoogleMapsSourceAdapter {")
end = s.find("\nclass MapsNavigationListenerService", start)
if start < 0 or end < 0:
    raise SystemExit("MAPS FIX FAIL: GoogleMapsSourceAdapter block not found")

new_adapter = r'''object GoogleMapsSourceAdapter {
    private val distanceRegex = Regex("(?i)(?:\\btra\\s+|\\bfra\\s+|\\bin\\s+|\\bafter\\s+)?(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi|ft)\\b")
    private val etaRegex = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b")
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val ICON_SAMPLE = 48

    private data class MapsVisualData(
        val textLines: List<String>,
        val maneuverIcon: Bitmap?,
    )

    fun parse(context: Context, notification: Notification): TurnByTurnInstruction? {
        if (notification.category != null && notification.category != "navigation") return null
        val e = notification.extras ?: return null
        val title = clean(e.getCharSequence(Notification.EXTRA_TITLE))
        if (title.isBlank()) return null
        val text = clean(e.getCharSequence(Notification.EXTRA_TEXT))
        val big = clean(e.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val sub = clean(e.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val info = clean(e.getCharSequence(Notification.EXTRA_INFO_TEXT))
        val summary = clean(e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        val ticker = clean(notification.tickerText)

        // Recent Maps versions can expose distance/street as text while the maneuver itself is
        // a graphic. First inspect every text surface, including RemoteViews; then use the
        // notification maneuver glyph only if no textual maneuver can be classified.
        val visual = readVisualData(context, notification)
        val instructionFields = LinkedHashSet<String>().apply {
            listOf(title, text, big, sub, info, summary, ticker).filterTo(this) { it.isNotBlank() }
            visual.textLines.filterTo(this) { it.isNotBlank() }
        }.toList()

        val distance = instructionFields.asSequence().mapNotNull(::explicitDistanceMeters).firstOrNull()
        val textManeuver = instructionFields.asSequence()
            .map(::maneuverFrom)
            .firstOrNull { it != NavManeuver.UNKNOWN }
        val maneuver = textManeuver ?: maneuverFromIcon(visual.maneuverIcon)
        val sourceKind = if (textManeuver != null) "TEXT" else if (maneuver != NavManeuver.UNKNOWN) "ICON" else "NONE"
        val eta = instructionFields.asSequence().mapNotNull { line ->
            etaRegex.find(line)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }
            }
        }.firstOrNull()

        return TurnByTurnInstruction(
            maneuver = maneuver,
            distanceMeters = distance,
            arrivalHour = eta?.first,
            arrivalMinute = eta?.second,
            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 2 else 0,
            source = "GOOGLE_MAPS_NOTIFICATION_$sourceKind",
            rawInstruction = title
        )
    }

    private fun explicitDistanceMeters(text: String): Int? {
        val m = distanceRegex.find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (value < 0) return null
        return when (m.groupValues[2].lowercase(Locale.US)) {
            "m" -> value.roundToInt()
            "km" -> (value * 1000.0).roundToInt()
            "mi" -> (value * 1609.344).roundToInt()
            "ft" -> (value * 0.3048).roundToInt()
            else -> null
        }?.coerceIn(0, 0xFFFFFF)
    }

    private fun maneuverFrom(text: String): NavManeuver {
        val s = text.lowercase(Locale.getDefault())
        return when {
            listOf("inversione a u a sinistra", "inversione a sinistra", "u-turn left", "uturn left", "u turn left", "⤺", "↶").any(s::contains) -> NavManeuver.UTURN_LEFT
            listOf("inversione a u a destra", "inversione a destra", "u-turn right", "uturn right", "u turn right", "⤴", "↷").any(s::contains) -> NavManeuver.UTURN_RIGHT
            listOf("rotatoria", "rotonda", "roundabout", "rotary").any(s::contains) -> NavManeuver.ROUNDABOUT
            listOf("svolta bruscamente a sinistra", "gira bruscamente a sinistra", "sharp left", "hard left").any(s::contains) -> NavManeuver.SHARP_LEFT
            listOf("svolta bruscamente a destra", "gira bruscamente a destra", "sharp right", "hard right").any(s::contains) -> NavManeuver.SHARP_RIGHT
            listOf("leggermente a sinistra", "slight left", "mantieni la sinistra", "tieni la sinistra", "keep left", "stay left", "↖", "↙").any(s::contains) -> NavManeuver.SLIGHT_LEFT
            listOf("leggermente a destra", "slight right", "mantieni la destra", "tieni la destra", "keep right", "stay right", "↗", "↘").any(s::contains) -> NavManeuver.SLIGHT_RIGHT
            listOf("svolta a sinistra", "gira a sinistra", "turn left", "a sinistra", "←", "↰").any(s::contains) -> NavManeuver.LEFT
            listOf("svolta a destra", "gira a destra", "turn right", "a destra", "→", "↱").any(s::contains) -> NavManeuver.RIGHT
            listOf("prosegui dritto", "continua dritto", "vai dritto", "continue straight", "go straight", "prosegui", "continua su", "↑").any(s::contains) -> NavManeuver.STRAIGHT
            else -> NavManeuver.UNKNOWN
        }
    }

    private fun readVisualData(context: Context, notification: Notification): MapsVisualData {
        val lines = LinkedHashSet<String>()
        var maneuverIcon: Bitmap? = null

        // Inspect Maps' RemoteViews first: this is where the maneuver glyph is normally
        // rendered. Failure is contained and falls back to largeIcon/extras.
        runCatching {
            val mapsContext = context.createPackageContext(MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            val builder = Notification.Builder.recoverBuilder(context, notification)
            val remote = builder.createBigContentView() ?: builder.createContentView()
            if (remote != null) {
                val root = remote.apply(mapsContext, null)
                val walked = walkNotificationView(mapsContext, root, lines)
                if (maneuverIcon == null) maneuverIcon = walked
            }
        }

        // Layout-independent fallback used by several Maps versions for the same turn glyph.
        if (maneuverIcon == null) {
            maneuverIcon = runCatching {
                notification.getLargeIcon()?.loadDrawable(context)?.let(::drawableToBitmap)
            }.getOrNull()
        }

        return MapsVisualData(lines.toList(), maneuverIcon)
    }

    private fun walkNotificationView(context: Context, view: View, lines: MutableSet<String>): Bitmap? {
        var foundIcon: Bitmap? = null
        when (view) {
            is TextView -> clean(view.text).takeIf { it.isNotBlank() }?.let(lines::add)
            is ImageView -> {
                val entryName = runCatching {
                    if (view.id > 0) context.resources.getResourceEntryName(view.id) else ""
                }.getOrDefault("")
                if (entryName in setOf("nav_notification_icon", "right_icon", "lockscreen_notification_icon")) {
                    foundIcon = view.drawable?.let(::drawableToBitmap)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val childIcon = walkNotificationView(context, view.getChildAt(i), lines)
                if (foundIcon == null && childIcon != null) foundIcon = childIcon
            }
        }
        return foundIcon
    }

    /**
     * Conservative visual fallback for the Maps maneuver glyph. Ambiguous icons remain
     * UNKNOWN rather than sending a guessed command. Text remains preferred for roundabouts,
     * U-turns and exact maneuver semantics.
     */
    private fun maneuverFromIcon(bitmap: Bitmap?): NavManeuver {
        if (bitmap == null) return NavManeuver.UNKNOWN
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, ICON_SAMPLE, ICON_SAMPLE, true) }.getOrNull()
            ?: return NavManeuver.UNKNOWN
        return try {
            val bg = scaled.getPixel(0, 0)
            val full = measureInk(scaled, bg, ICON_SAMPLE)
            val top = measureInk(scaled, bg, (ICON_SAMPLE * 0.45).roundToInt())
            val fullTotal = full.first + full.second
            val topTotal = top.first + top.second
            if (fullTotal < 20 || topTotal < 8) return NavManeuver.UNKNOWN

            val fullRatio = dominance(full.first, full.second)
            val topRatio = dominance(top.first, top.second)
            if (fullRatio < 1.05 && topRatio < 1.35) return NavManeuver.STRAIGHT

            // A real turn needs a visible arrowhead imbalance; otherwise do not guess.
            if (topRatio < 1.18) return NavManeuver.UNKNOWN
            val left = top.first > top.second
            when {
                fullRatio >= 1.40 || topRatio >= 3.00 -> if (left) NavManeuver.SHARP_LEFT else NavManeuver.SHARP_RIGHT
                fullRatio >= 1.12 || topRatio >= 1.80 -> if (left) NavManeuver.LEFT else NavManeuver.RIGHT
                else -> if (left) NavManeuver.SLIGHT_LEFT else NavManeuver.SLIGHT_RIGHT
            }
        } catch (_: Throwable) {
            NavManeuver.UNKNOWN
        } finally {
            if (scaled !== bitmap) runCatching { scaled.recycle() }
        }
    }

    private fun measureInk(bitmap: Bitmap, bg: Int, endY: Int): Pair<Long, Long> {
        var left = 0L
        var right = 0L
        val half = bitmap.width / 2
        for (y in 0 until endY.coerceIn(1, bitmap.height)) {
            for (x in 0 until bitmap.width) {
                if (!isInk(bitmap.getPixel(x, y), bg)) continue
                if (x < half) left++ else right++
            }
        }
        return left to right
    }

    private fun isInk(px: Int, bg: Int): Boolean {
        if (Color.alpha(px) < 24) return false
        val da = Color.alpha(px) - Color.alpha(bg)
        val dr = Color.red(px) - Color.red(bg)
        val dg = Color.green(px) - Color.green(bg)
        val db = Color.blue(px) - Color.blue(bg)
        return (da * da + dr * dr + dg * dg + db * db) > 4096
    }

    private fun dominance(a: Long, b: Long): Double =
        maxOf(a, b).toDouble() / minOf(a, b).coerceAtLeast(1).toDouble()

    private fun drawableToBitmap(drawable: Drawable): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(ICON_SAMPLE, ICON_SAMPLE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    private fun clean(value: CharSequence?): String = value?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
}
'''

s = s[:start] + new_adapter + s[end:]

old = "val model = GoogleMapsSourceAdapter.parse(n) ?: return"
new = "val model = GoogleMapsSourceAdapter.parse(this, n) ?: return"
if s.count(old) != 1:
    raise SystemExit(f"MAPS FIX FAIL: parse call expected once, found {s.count(old)}")
s = s.replace(old, new, 1)

old = 'val fingerprint = "${model.rawInstruction}|${model.distanceMeters}|${model.arrivalHour}:${model.arrivalMinute}"'
new = 'val fingerprint = "${model.rawInstruction}|${model.maneuver}|${model.distanceMeters}|${model.arrivalHour}:${model.arrivalMinute}"'
if s.count(old) != 1:
    raise SystemExit("MAPS FIX FAIL: fingerprint anchor")
s = s.replace(old, new, 1)

old = 'AppLog.add("MAPS NAV V1.6: TBT reale accettato maneuver=${model.maneuver} distance=${model.distanceMeters}m")'
new = 'AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m")'
if s.count(old) != 1:
    raise SystemExit("MAPS FIX FAIL: accepted-log anchor")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")

checks = [
    "GOOGLE_MAPS_NOTIFICATION_$sourceKind",
    "Notification.EXTRA_SUMMARY_TEXT",
    "notification.tickerText",
    "nav_notification_icon",
    "maneuverFromIcon",
    "GoogleMapsSourceAdapter.parse(this, n)",
    "source=${model.source} maneuver=${model.maneuver}",
]
for marker in checks:
    if marker not in s:
        raise SystemExit(f"MAPS FIX VERIFY FAIL: {marker}")
print("MAPS MANEUVER FIX OK: all text surfaces + RemoteViews/icon fallback; BLE untouched")
