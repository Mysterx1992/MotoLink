package it.motolink.app

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * V8 universal auto active-area cover renderer.
 *
 * Pipeline:
 *   MediaProjection -> fixed T-Box-sized VirtualDisplay -> SurfaceTexture -> this renderer -> encoder.
 *
 * Every ~900 ms the renderer first draws the RAW frame 1:1 (without swapping it), reads it back,
 * and measures contiguous near-black bands touching the four outer edges using the frozen V7
 * detector. Only after a geometry signature has remained stable for multiple samples can V8 use
 * the measured active rectangle as a crop source. The final rectangle is then COVER-cropped to the
 * target aspect ratio and rendered over the full encoder canvas without stretch.
 *
 * Important universal guard: automatic removal is enabled only when source-content orientation and
 * T-Box canvas orientation agree. When they differ (e.g. portrait phone app on a landscape TFT),
 * V8 preserves the 1:1 Android composition instead of destructively zooming a legitimate pillarbox.
 * There are no motorcycle/model/app specific values.
 */
class AutoActiveAreaCoverRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    initialSourceWidth: Int,
    initialSourceHeight: Int,
    private val onDiagnostic: (Diagnostic) -> Unit
) {
    data class Diagnostic(
        val sampleIndex: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val activeWidth: Int,
        val activeHeight: Int,
        val stableCount: Int,
        val confidence: String,
        val correctionApplied: Boolean,
        val correctionChanged: Boolean,
        val correctionReason: String,
        val cropLeftPx: Float,
        val cropTopPx: Float,
        val cropRightPx: Float,
        val cropBottomPx: Float,
        val transitionGuard: Boolean
    )

    private data class CropRect(
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float
    ) {
        fun isIdentity(): Boolean = left <= 0.0001f && bottom <= 0.0001f &&
            right >= 0.9999f && top >= 0.9999f
    }

    private val thread = HandlerThread("MotoLink-V8AutoActiveArea").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    @Volatile private var initError: Throwable? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var projectionSurface: Surface? = null
    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var uCropRect = -1
    private val texMatrix = FloatArray(16)

    private var readback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private var lastDiagnosticAt = 0L
    private var sampleIndex = 0L
    private var lastStableSignature = ""
    private var stableCount = 0
    private var lastStableLogAt = 0L

    private var sourceWidth = initialSourceWidth.coerceAtLeast(16)
    private var sourceHeight = initialSourceHeight.coerceAtLeast(16)
    private var sourceGeneration = 0L

    private var appliedCrop = IDENTITY_CROP
    private var appliedSignature = "IDENTITY"

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(-1f); put(-1f); put(0f); put(0f)
            put( 1f); put(-1f); put(1f); put(0f)
            put(-1f); put( 1f); put(0f); put(1f)
            put( 1f); put( 1f); put(1f); put(1f)
            position(0)
        }

    val inputSurface: Surface
        get() {
            if (!ready.await(4, TimeUnit.SECONDS)) {
                throw IllegalStateException("AutoActiveAreaCoverRenderer init timeout")
            }
            initError?.let { throw IllegalStateException("AutoActiveAreaCoverRenderer init failed", it) }
            return projectionSurface ?: throw IllegalStateException("AutoActiveAreaCoverRenderer surface missing")
        }

    init {
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                initError = t
                AppLog.add("ACTIVE AREA V8 INIT ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            } finally {
                ready.countDown()
            }
        }
    }

    fun setSourceGeometry(newWidth: Int, newHeight: Int) {
        if (newWidth < 16 || newHeight < 16 || released.get()) return
        handler.post {
            if (newWidth == sourceWidth && newHeight == sourceHeight) return@post
            sourceWidth = newWidth
            sourceHeight = newHeight
            sourceGeneration += 1L
            lastStableSignature = ""
            stableCount = 0
            // Never carry a crop calculated for one orientation/application geometry across a
            // resize. The raw frame is shown until a fresh stable measurement is available.
            if (!appliedCrop.isIdentity()) {
                appliedCrop = IDENTITY_CROP
                appliedSignature = "IDENTITY"
                AppLog.add(
                    "ACTIVE AREA V8 RESET: source=${sourceWidth}x${sourceHeight}; " +
                        "correzione sospesa fino a nuova misura stabile"
                )
            }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                runCatching { projectionSurface?.release() }
                projectionSurface = null
                runCatching { surfaceTexture?.release() }
                surfaceTexture = null
                if (program != 0) GLES20.glDeleteProgram(program)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay" }
        val versions = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "eglInitialize" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig"
        }
        val config = configs[0] ?: error("EGLConfig missing")
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        require(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCropRect = GLES20.glGetUniformLocation(program, "uCropRect")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).also { st ->
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        projectionSurface = Surface(surfaceTexture)
        AppLog.add(
            "ACTIVE AREA V8 READY: input=${width}x${height} output=${width}x${height}; " +
                "raw-detector=ON autoCover=ON noStretch=true modelProfiles=NONE"
        )
    }

    private fun renderFrame() {
        if (released.get()) return
        val st = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            val now = SystemClock.elapsedRealtime()
            if (now - lastDiagnosticAt >= DIAGNOSTIC_PERIOD_MS) {
                lastDiagnosticAt = now
                // Draw raw 1:1 into the current back buffer for measurement only. It is never
                // swapped to the TFT; after readback the same frame is redrawn with the stable
                // correction (if any) and only that final render is swapped/encoded.
                draw(IDENTITY_CROP)
                analyzeRawCurrentFrame(now)
            }

            draw(appliedCrop)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            AppLog.add("ACTIVE AREA V8 RENDER ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    private fun draw(crop: CropRect) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform4f(uCropRect, crop.left, crop.bottom, crop.right, crop.top)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun analyzeRawCurrentFrame(now: Long) {
        try {
            readback.position(0)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) {
                AppLog.add("ACTIVE AREA V8 READBACK ERROR: glError=0x${Integer.toHexString(glError)}")
                return
            }

            val maxVertical = max(1, (height * MAX_EDGE_SCAN_FRACTION).toInt())
            val maxHorizontal = max(1, (width * MAX_EDGE_SCAN_FRACTION).toInt())
            val bottom = scanBottom(maxVertical)
            val top = scanTop(maxVertical)
            val left = scanLeft(maxHorizontal)
            val right = scanRight(maxHorizontal)

            val activeW = (width - left - right).coerceAtLeast(0)
            val activeH = (height - top - bottom).coerceAtLeast(0)
            val signature = "${quantize(left)}:${quantize(top)}:${quantize(right)}:${quantize(bottom)}"
            if (signature == lastStableSignature) {
                stableCount += 1
            } else {
                lastStableSignature = signature
                stableCount = 1
            }

            sampleIndex += 1
            val confidence = when {
                stableCount >= 5 -> "HIGH"
                stableCount >= 3 -> "MEDIUM"
                else -> "LOW"
            }

            val sameOrientation = orientationMatches(sourceWidth, sourceHeight, width, height)
            val transitionGuard = edgeTransitionGuard(left, top, right, bottom)
            // Medium-confidence activation requires a clear edge transition. If the exact same
            // geometry survives to HIGH confidence (>=5 samples), stability itself is accepted as
            // the stronger guard so dark-themed content cannot permanently block a real letterbox.
            val transitionAccepted = transitionGuard || stableCount >= 5
            val sufficientArea = activeW >= (width * MIN_ACTIVE_FRACTION).toInt() &&
                activeH >= (height * MIN_ACTIVE_FRACTION).toInt()
            val bordersPresent = left + top + right + bottom >= MIN_TOTAL_BORDER_PX

            var correctionReason = when {
                !sameOrientation -> "ORIENTATION_MISMATCH_PRESERVE"
                stableCount < REQUIRED_STABLE_SAMPLES -> "WAIT_STABLE"
                !sufficientArea -> "ACTIVE_AREA_TOO_SMALL"
                bordersPresent && !transitionAccepted -> "EDGE_TRANSITION_WAIT_HIGH"
                !bordersPresent -> "FULL_FRAME"
                else -> "STABLE_ACTIVE_AREA"
            }

            val desiredCrop = when {
                !sameOrientation -> IDENTITY_CROP
                stableCount < REQUIRED_STABLE_SAMPLES -> appliedCrop
                !sufficientArea -> IDENTITY_CROP
                bordersPresent && !transitionAccepted -> IDENTITY_CROP
                !bordersPresent -> IDENTITY_CROP
                else -> computeCoverCrop(left, top, right, bottom)
            }

            val desiredSignature = cropSignature(desiredCrop)
            val correctionChanged = desiredSignature != appliedSignature &&
                (stableCount >= REQUIRED_STABLE_SAMPLES || !sameOrientation)
            if (correctionChanged) {
                appliedCrop = desiredCrop
                appliedSignature = desiredSignature
                if (desiredCrop.isIdentity() && correctionReason == "WAIT_STABLE") {
                    correctionReason = "WAIT_STABLE"
                }
                AppLog.add(
                    if (desiredCrop.isIdentity()) {
                        "ACTIVE AREA V8 APPLY: OFF reason=$correctionReason source=${sourceWidth}x${sourceHeight} " +
                            "canvas=${width}x${height}"
                    } else {
                        val px = cropToPixels(desiredCrop)
                        "ACTIVE AREA V8 APPLY: ON reason=$correctionReason raw=L$left/T$top/R$right/B$bottom " +
                            "coverCrop=L${fmt(px[0])}/T${fmt(px[1])}/R${fmt(px[2])}/B${fmt(px[3])} " +
                            "source=${sourceWidth}x${sourceHeight} canvas=${width}x${height} noStretch=true"
                    }
                )
            }

            val cropPx = cropToPixels(appliedCrop)
            onDiagnostic(
                Diagnostic(
                    sampleIndex = sampleIndex,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    activeWidth = activeW,
                    activeHeight = activeH,
                    stableCount = stableCount,
                    confidence = confidence,
                    correctionApplied = !appliedCrop.isIdentity(),
                    correctionChanged = correctionChanged,
                    correctionReason = correctionReason,
                    cropLeftPx = cropPx[0],
                    cropTopPx = cropPx[1],
                    cropRightPx = cropPx[2],
                    cropBottomPx = cropPx[3],
                    transitionGuard = transitionGuard
                )
            )

            if (stableCount >= REQUIRED_STABLE_SAMPLES && now - lastStableLogAt >= STABLE_LOG_PERIOD_MS) {
                lastStableLogAt = now
                AppLog.add(
                    "ACTIVE AREA V8 STABLE: L=$left T=$top R=$right B=$bottom active=${activeW}x${activeH} " +
                        "confidence=$confidence orientationMatch=$sameOrientation transitionGuard=$transitionGuard " +
                        "correction=${if (appliedCrop.isIdentity()) "OFF" else "ON"} reason=$correctionReason"
                )
            }
        } catch (t: Throwable) {
            AppLog.add("ACTIVE AREA V8 ANALYZE ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    /**
     * Convert the detected active rectangle to a centered COVER crop inside that rectangle.
     * The detector can be asymmetric (e.g. L85/R14), so the active rectangle itself remains
     * asymmetrically positioned; only the additional aspect-ratio cover crop is symmetric within
     * the detected active area.
     */
    private fun computeCoverCrop(left: Int, top: Int, right: Int, bottom: Int): CropRect {
        var l = left.toFloat()
        var r = (width - right).toFloat()
        var b = bottom.toFloat()
        var t = (height - top).toFloat()
        var aw = (r - l).coerceAtLeast(1f)
        var ah = (t - b).coerceAtLeast(1f)

        val targetAspect = width.toFloat() / height.toFloat()
        val activeAspect = aw / ah
        if (activeAspect > targetAspect) {
            val wantedW = ah * targetAspect
            val extra = (aw - wantedW).coerceAtLeast(0f)
            l += extra / 2f
            r -= extra / 2f
        } else if (activeAspect < targetAspect) {
            val wantedH = aw / targetAspect
            val extra = (ah - wantedH).coerceAtLeast(0f)
            b += extra / 2f
            t -= extra / 2f
        }

        l = l.coerceIn(0f, width - 1f)
        r = r.coerceIn(l + 1f, width.toFloat())
        b = b.coerceIn(0f, height - 1f)
        t = t.coerceIn(b + 1f, height.toFloat())

        return CropRect(
            left = l / width.toFloat(),
            bottom = b / height.toFloat(),
            right = r / width.toFloat(),
            top = t / height.toFloat()
        )
    }

    private fun cropToPixels(crop: CropRect): FloatArray {
        return floatArrayOf(
            crop.left * width,
            (1f - crop.top) * height,
            (1f - crop.right) * width,
            crop.bottom * height
        )
    }

    private fun cropSignature(crop: CropRect): String {
        val p = cropToPixels(crop)
        return "${(p[0] * 2).toInt()}:${(p[1] * 2).toInt()}:${(p[2] * 2).toInt()}:${(p[3] * 2).toInt()}"
    }

    private fun orientationMatches(sw: Int, sh: Int, tw: Int, th: Int): Boolean {
        val sourceLandscape = sw >= sh
        val targetLandscape = tw >= th
        return sourceLandscape == targetLandscape
    }

    private fun edgeTransitionGuard(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        fun ok(edge: Int, ratio: Float): Boolean = edge <= TRANSITION_MIN_EDGE_PX || ratio <= MAX_INSIDE_DARK_RATIO
        val leftRatio = if (left > 0 && left < width) darkRatioColumn(left) else 0f
        val rightX = width - 1 - right
        val rightRatio = if (right > 0 && rightX in 0 until width) darkRatioColumn(rightX) else 0f
        val bottomRatio = if (bottom > 0 && bottom < height) darkRatioRow(bottom) else 0f
        val topY = height - 1 - top
        val topRatio = if (top > 0 && topY in 0 until height) darkRatioRow(topY) else 0f
        return ok(left, leftRatio) && ok(right, rightRatio) && ok(top, topRatio) && ok(bottom, bottomRatio)
    }

    // glReadPixels origin is bottom-left. User-facing TOP scans from y=height-1 downwards.
    private fun scanBottom(limit: Int): Int {
        var count = 0
        while (count < limit && isDarkRow(count)) count += 1
        return count
    }

    private fun scanTop(limit: Int): Int {
        var count = 0
        while (count < limit && isDarkRow(height - 1 - count)) count += 1
        return count
    }

    private fun scanLeft(limit: Int): Int {
        var count = 0
        while (count < limit && isDarkColumn(count)) count += 1
        return count
    }

    private fun scanRight(limit: Int): Int {
        var count = 0
        while (count < limit && isDarkColumn(width - 1 - count)) count += 1
        return count
    }

    private fun isDarkRow(y: Int): Boolean = darkRatioRow(y) >= DARK_SAMPLE_RATIO
    private fun isDarkColumn(x: Int): Boolean = darkRatioColumn(x) >= DARK_SAMPLE_RATIO

    private fun darkRatioRow(y: Int): Float {
        var dark = 0
        var total = 0
        var x = 0
        while (x < width) {
            if (isDarkPixel(x, y)) dark += 1
            total += 1
            x += SAMPLE_STRIDE
        }
        return if (total > 0) dark.toFloat() / total.toFloat() else 0f
    }

    private fun darkRatioColumn(x: Int): Float {
        var dark = 0
        var total = 0
        var y = 0
        while (y < height) {
            if (isDarkPixel(x, y)) dark += 1
            total += 1
            y += SAMPLE_STRIDE
        }
        return if (total > 0) dark.toFloat() / total.toFloat() else 0f
    }

    private fun isDarkPixel(x: Int, y: Int): Boolean {
        val index = (y * width + x) * 4
        val r = readback.get(index).toInt() and 0xff
        val g = readback.get(index + 1).toInt() and 0xff
        val b = readback.get(index + 2).toInt() and 0xff
        val maxChannel = max(r, max(g, b))
        val luma = (r * 54 + g * 183 + b * 19) shr 8
        return luma <= DARK_LUMA_THRESHOLD && maxChannel <= DARK_MAX_CHANNEL
    }

    private fun quantize(v: Int): Int = ((v + 1) / 2) * 2
    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        val log = GLES20.glGetProgramInfoLog(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            GLES20.glDeleteProgram(p)
            error("GL program link failed: $log")
        }
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("GL shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val DIAGNOSTIC_PERIOD_MS = 900L
        private const val STABLE_LOG_PERIOD_MS = 4000L
        private const val REQUIRED_STABLE_SAMPLES = 3
        private const val SAMPLE_STRIDE = 4
        private const val DARK_LUMA_THRESHOLD = 24
        private const val DARK_MAX_CHANNEL = 36
        private const val DARK_SAMPLE_RATIO = 0.88f
        private const val MAX_EDGE_SCAN_FRACTION = 0.35f
        private const val MIN_ACTIVE_FRACTION = 0.55f
        private const val MIN_TOTAL_BORDER_PX = 4
        private const val TRANSITION_MIN_EDGE_PX = 2
        private const val MAX_INSIDE_DARK_RATIO = 0.78f

        private val IDENTITY_CROP = CropRect(0f, 0f, 1f, 1f)

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec4 uCropRect;
            varying vec2 vTexCoord;
            void main() {
                vec2 cropped = mix(uCropRect.xy, uCropRect.zw, vTexCoord);
                vec4 tc = uTexMatrix * vec4(cropped, 0.0, 1.0);
                gl_FragColor = texture2D(sTexture, tc.xy);
            }
        """
    }
}
