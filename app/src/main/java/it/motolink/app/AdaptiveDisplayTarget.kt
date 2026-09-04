package it.motolink.app

/**
 * Runtime target geometry advertised by the connected EasyConn head unit.
 *
 * The state is intentionally process-local and session-local: no model/profile or
 * resolution is persisted. 800x480 remains only the conservative fallback for
 * head units that do not advertise a usable media geometry.
 */
object AdaptiveDisplayTarget {
    data class Snapshot(
        val width: Int,
        val height: Int,
        val source: String,
        val updatedAtMs: Long
    ) {
        val aspect: Float get() = width.toFloat() / height.toFloat()
    }

    private const val FALLBACK_WIDTH = 800
    private const val FALLBACK_HEIGHT = 480
    private const val MIN_DIMENSION = 16
    private const val MAX_DIMENSION = 4096

    @Volatile
    private var current = Snapshot(
        FALLBACK_WIDTH,
        FALLBACK_HEIGHT,
        "fallback",
        0L
    )

    @Synchronized
    fun reset() {
        current = Snapshot(FALLBACK_WIDTH, FALLBACK_HEIGHT, "fallback", 0L)
    }

    @Synchronized
    fun update(width: Int, height: Int, source: String): Snapshot {
        val w = normalize(width)
        val h = normalize(height)
        if (w == null || h == null) {
            AppLog.add("ADAPTIVE DISPLAY: geometria ignorata ${width}x${height} source=$source")
            return current
        }
        val next = Snapshot(w, h, source, android.os.SystemClock.elapsedRealtime())
        current = next
        AppLog.add("ADAPTIVE DISPLAY: target moto ${w}x${h} source=$source")
        return next
    }

    fun snapshot(): Snapshot = current

    private fun normalize(value: Int): Int? {
        if (value < MIN_DIMENSION || value > MAX_DIMENSION) return null
        // H.264 implementations are generally happiest on even dimensions. The
        // EasyConn media ACK already aligns normal requests; this is only a guard.
        return value and 0xfffe
    }
}
