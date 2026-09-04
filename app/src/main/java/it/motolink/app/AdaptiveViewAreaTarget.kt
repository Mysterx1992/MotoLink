package it.motolink.app

import org.json.JSONObject

/**
 * Runtime projection-area observer for EasyConn media-control payloads.
 *
 * This is intentionally read-only: it never changes a protocol reply. When a payload exposes a
 * viewAreaConfig/viewAreas/safeArea width+height, MotoLink can use those dimensions as the AUTO
 * compositor viewport while the H.264 encoder keeps the geometry negotiated by MEDIA_INIT.
 */
object AdaptiveViewAreaTarget {
    data class Snapshot(val width: Int, val height: Int, val source: String)

    @Volatile private var current: Snapshot? = null

    fun reset() {
        current = null
    }

    fun snapshot(): Snapshot? = current

    fun observe(payload: ByteArray, source: String): Boolean {
        if (payload.isEmpty()) return false
        val text = runCatching { payload.toString(Charsets.UTF_8).trim() }.getOrDefault("")
        if (!text.startsWith("{")) return false
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val area = findArea(root) ?: return false
        val width = area.first
        val height = area.second
        if (width !in 16..4096 || height !in 16..4096) return false
        val next = Snapshot(width, height, source)
        if (current == next) return false
        current = next
        AppLog.add("VIEW AREA LIVE: safeArea=${width}x${height} source=$source")
        return true
    }

    private fun findArea(root: JSONObject): Pair<Int, Int>? {
        fun readSafe(container: JSONObject?): Pair<Int, Int>? {
            val safe = container?.optJSONObject("safeArea") ?: return null
            val w = safe.optInt("width", 0)
            val h = safe.optInt("height", 0)
            return if (w > 0 && h > 0) w to h else null
        }

        readSafe(root)?.let { return it }
        val config = root.optJSONObject("viewAreaConfig")
        readSafe(config)?.let { return it }
        val areas = config?.optJSONArray("viewAreas") ?: root.optJSONArray("viewAreas")
        if (areas != null) {
            for (i in 0 until areas.length()) {
                readSafe(areas.optJSONObject(i))?.let { return it }
            }
        }
        val data = root.optJSONObject("data")
        if (data != null && data !== root) {
            readSafe(data)?.let { return it }
            val nested = data.optJSONObject("viewAreaConfig")
            readSafe(nested)?.let { return it }
            val nestedAreas = nested?.optJSONArray("viewAreas")
            if (nestedAreas != null) {
                for (i in 0 until nestedAreas.length()) {
                    readSafe(nestedAreas.optJSONObject(i))?.let { return it }
                }
            }
        }
        return null
    }
}
