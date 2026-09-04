package it.motolink.app

import android.content.Context

/**
 * Live screen-calibration state for the mirror compositor.
 *
 * The encoder geometry is NEVER changed here: EasyConn still receives the MEDIA_INIT geometry.
 * AUTO may use a read-only runtime safe-area observation when the T-Box exposes one. Manual
 * width/height and four non-negative TFT margins let us measure the rider-usable projection area.
 * Aspect ratio is preserved; zoom is relative to FIT inside that calibrated area.
 */
object MirrorZoomConfig {
    private const val PREFS_NAME = "trofeolink_prefs"
    private const val KEY_VIEWPORT_AUTO = "mirror_zoom_viewport_auto"
    private const val KEY_VIEWPORT_WIDTH = "mirror_zoom_viewport_width"
    private const val KEY_VIEWPORT_HEIGHT = "mirror_zoom_viewport_height"
    private const val KEY_ZOOM_PERCENT = "mirror_zoom_percent"
    private const val KEY_OFFSET_X = "mirror_zoom_offset_x"
    private const val KEY_OFFSET_Y = "mirror_zoom_offset_y"
    private const val KEY_MARGIN_LEFT = "mirror_safe_margin_left"
    private const val KEY_MARGIN_TOP = "mirror_safe_margin_top"
    private const val KEY_MARGIN_RIGHT = "mirror_safe_margin_right"
    private const val KEY_MARGIN_BOTTOM = "mirror_safe_margin_bottom"

    const val DEFAULT_ZOOM_PERCENT = 100
    const val MIN_ZOOM_PERCENT = 70
    const val MAX_ZOOM_PERCENT = 400
    const val MAX_MARGIN_PX = 200

    data class Snapshot(
        val viewportAuto: Boolean,
        val requestedViewportWidth: Int,
        val requestedViewportHeight: Int,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val viewportSource: String,
        val zoomPercent: Int,
        val offsetX: Int,
        val offsetY: Int,
        val marginLeft: Int,
        val marginTop: Int,
        val marginRight: Int,
        val marginBottom: Int,
    ) {
        val safeWidth: Int get() = (viewportWidth - marginLeft - marginRight).coerceAtLeast(16)
        val safeHeight: Int get() = (viewportHeight - marginTop - marginBottom).coerceAtLeast(16)
        val label: String
            get() {
                val base = if (viewportAuto) "AUTO ${viewportWidth}×${viewportHeight}" else "${viewportWidth}×${viewportHeight}"
                val margins = if (marginLeft == 0 && marginTop == 0 && marginRight == 0 && marginBottom == 0) "" else
                    " • L$marginLeft T$marginTop R$marginRight B$marginBottom"
                return "$base • ${zoomPercent}%$margins"
            }
    }

    fun load(context: Context, targetWidth: Int, targetHeight: Int): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(KEY_VIEWPORT_AUTO, true)
        val requestedW = prefs.getInt(KEY_VIEWPORT_WIDTH, 800).coerceAtLeast(16)
        val requestedH = prefs.getInt(KEY_VIEWPORT_HEIGHT, 480).coerceAtLeast(16)
        val zoom = prefs.getInt(KEY_ZOOM_PERCENT, DEFAULT_ZOOM_PERCENT).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        val offsetX = prefs.getInt(KEY_OFFSET_X, 0).coerceIn(-300, 300)
        val offsetY = prefs.getInt(KEY_OFFSET_Y, 0).coerceIn(-300, 300)
        val left = prefs.getInt(KEY_MARGIN_LEFT, 0).coerceIn(0, MAX_MARGIN_PX)
        val top = prefs.getInt(KEY_MARGIN_TOP, 0).coerceIn(0, MAX_MARGIN_PX)
        val right = prefs.getInt(KEY_MARGIN_RIGHT, 0).coerceIn(0, MAX_MARGIN_PX)
        val bottom = prefs.getInt(KEY_MARGIN_BOTTOM, 0).coerceIn(0, MAX_MARGIN_PX)

        val physicalW = targetWidth.coerceAtLeast(16)
        val physicalH = targetHeight.coerceAtLeast(16)
        val runtime = AdaptiveViewAreaTarget.snapshot()?.takeIf { it.width <= physicalW && it.height <= physicalH }
        val viewportW: Int
        val viewportH: Int
        val source: String
        if (auto) {
            viewportW = runtime?.width ?: physicalW
            viewportH = runtime?.height ?: physicalH
            source = runtime?.source ?: "MEDIA_INIT"
        } else {
            viewportW = requestedW.coerceAtMost(physicalW)
            viewportH = requestedH.coerceAtMost(physicalH)
            source = "MANUAL"
        }

        // A bad calibration can never collapse the usable area below 16 px.
        val safeLeft = left.coerceAtMost((viewportW - 16).coerceAtLeast(0))
        val safeRight = right.coerceAtMost((viewportW - safeLeft - 16).coerceAtLeast(0))
        val safeTop = top.coerceAtMost((viewportH - 16).coerceAtLeast(0))
        val safeBottom = bottom.coerceAtMost((viewportH - safeTop - 16).coerceAtLeast(0))

        return Snapshot(
            viewportAuto = auto,
            requestedViewportWidth = requestedW,
            requestedViewportHeight = requestedH,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            viewportSource = source,
            zoomPercent = zoom,
            offsetX = offsetX,
            offsetY = offsetY,
            marginLeft = safeLeft,
            marginTop = safeTop,
            marginRight = safeRight,
            marginBottom = safeBottom,
        )
    }

    fun save(
        context: Context,
        viewportAuto: Boolean,
        viewportWidth: Int,
        viewportHeight: Int,
        zoomPercent: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        marginLeft: Int = 0,
        marginTop: Int = 0,
        marginRight: Int = 0,
        marginBottom: Int = 0,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIEWPORT_AUTO, viewportAuto)
            .putInt(KEY_VIEWPORT_WIDTH, viewportWidth.coerceAtLeast(16))
            .putInt(KEY_VIEWPORT_HEIGHT, viewportHeight.coerceAtLeast(16))
            .putInt(KEY_ZOOM_PERCENT, zoomPercent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT))
            .putInt(KEY_OFFSET_X, offsetX.coerceIn(-300, 300))
            .putInt(KEY_OFFSET_Y, offsetY.coerceIn(-300, 300))
            .putInt(KEY_MARGIN_LEFT, marginLeft.coerceIn(0, MAX_MARGIN_PX))
            .putInt(KEY_MARGIN_TOP, marginTop.coerceIn(0, MAX_MARGIN_PX))
            .putInt(KEY_MARGIN_RIGHT, marginRight.coerceIn(0, MAX_MARGIN_PX))
            .putInt(KEY_MARGIN_BOTTOM, marginBottom.coerceIn(0, MAX_MARGIN_PX))
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(KEY_VIEWPORT_AUTO, true)
        val w = prefs.getInt(KEY_VIEWPORT_WIDTH, 800).coerceAtLeast(16)
        val h = prefs.getInt(KEY_VIEWPORT_HEIGHT, 480).coerceAtLeast(16)
        val zoom = prefs.getInt(KEY_ZOOM_PERCENT, DEFAULT_ZOOM_PERCENT).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        val ox = prefs.getInt(KEY_OFFSET_X, 0).coerceIn(-300, 300)
        val oy = prefs.getInt(KEY_OFFSET_Y, 0).coerceIn(-300, 300)
        val l = prefs.getInt(KEY_MARGIN_LEFT, 0).coerceIn(0, MAX_MARGIN_PX)
        val t = prefs.getInt(KEY_MARGIN_TOP, 0).coerceIn(0, MAX_MARGIN_PX)
        val r = prefs.getInt(KEY_MARGIN_RIGHT, 0).coerceIn(0, MAX_MARGIN_PX)
        val b = prefs.getInt(KEY_MARGIN_BOTTOM, 0).coerceIn(0, MAX_MARGIN_PX)
        val pos = if (ox == 0 && oy == 0) "" else " • X${if (ox >= 0) "+" else ""}$ox Y${if (oy >= 0) "+" else ""}$oy"
        val edges = if (l == 0 && t == 0 && r == 0 && b == 0) "" else " • L$l T$t R$r B$b"
        val runtime = AdaptiveViewAreaTarget.snapshot()
        val base = if (auto && runtime != null) "AUTO ${runtime.width}×${runtime.height}" else if (auto) "AUTO display" else "${w}×${h}"
        return "$base • ${zoom}%$pos$edges"
    }
}
