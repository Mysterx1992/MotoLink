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
import kotlin.math.min

/**
 * V7 diagnostic-only passthrough between MediaProjection and the H.264 encoder.
 *
 * Important: the MediaProjection VirtualDisplay is still created at the T-Box negotiated
 * width/height exactly as in V6. This renderer does not crop, scale, pan, zoom or apply any
 * motorcycle-specific rule. It copies the already composed T-Box-sized Android frame 1:1 to
 * the encoder and, at a low sampling rate, reads the rendered frame to measure contiguous dark
 * bands touching the four outer edges.
 *
 * The measurement is deliberately observational only. No detected band is removed in V7.
 */
class ActiveAreaDiagnosticRenderer(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val onDiagnostic: (Diagnostic) -> Unit
) {
    data class Diagnostic(
        val sampleIndex: Long,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val activeWidth: Int,
        val activeHeight: Int,
        val stableCount: Int,
        val confidence: String,
        val darkThreshold: Int,
        val darkRatioThreshold: Float
    )

    private val thread = HandlerThread("MotoLink-V7ActiveArea").apply { start() }
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
    private val texMatrix = FloatArray(16)

    private var readback = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private var lastDiagnosticAt = 0L
    private var sampleIndex = 0L
    private var lastStableSignature = ""
    private var stableCount = 0
    private var lastStableLogAt = 0L

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
                throw IllegalStateException("ActiveAreaDiagnosticRenderer init timeout")
            }
            initError?.let { throw IllegalStateException("ActiveAreaDiagnosticRenderer init failed", it) }
            return projectionSurface ?: throw IllegalStateException("ActiveAreaDiagnosticRenderer surface missing")
        }

    init {
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                initError = t
                AppLog.add("ACTIVE AREA V7 INIT ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            } finally {
                ready.countDown()
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
            "ACTIVE AREA V7 PASSTHROUGH READY: input=${width}x${height} output=${width}x${height}; " +
                "transform=SurfaceTexture; crop=OFF zoom=OFF pan=OFF correction=OFF"
        )
    }

    private fun renderFrame() {
        if (released.get()) return
        val st = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

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
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val now = SystemClock.elapsedRealtime()
            if (now - lastDiagnosticAt >= DIAGNOSTIC_PERIOD_MS) {
                lastDiagnosticAt = now
                analyzeCurrentFrame(now)
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            AppLog.add("ACTIVE AREA V7 RENDER ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    private fun analyzeCurrentFrame(now: Long) {
        try {
            readback.position(0)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) {
                AppLog.add("ACTIVE AREA V7 READBACK ERROR: glError=0x${Integer.toHexString(glError)}")
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
            onDiagnostic(
                Diagnostic(
                    sampleIndex = sampleIndex,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    activeWidth = activeW,
                    activeHeight = activeH,
                    stableCount = stableCount,
                    confidence = confidence,
                    darkThreshold = DARK_LUMA_THRESHOLD,
                    darkRatioThreshold = DARK_SAMPLE_RATIO
                )
            )

            // Emit an explicit stable marker at most once every 4 seconds so a log can be searched
            // quickly without flooding it. The normal SAMPLE line is emitted by MirrorService.
            if (stableCount >= 3 && now - lastStableLogAt >= STABLE_LOG_PERIOD_MS) {
                lastStableLogAt = now
                AppLog.add(
                    "ACTIVE AREA V7 STABLE: L=$left T=$top R=$right B=$bottom " +
                        "active=${activeW}x${activeH} confidence=$confidence " +
                        "threshold=${DARK_LUMA_THRESHOLD}/${String.format(Locale.US, "%.2f", DARK_SAMPLE_RATIO)}"
                )
            }
        } catch (t: Throwable) {
            AppLog.add("ACTIVE AREA V7 ANALYZE ERROR: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    // glReadPixels origin is bottom-left. The user-facing TOP value is therefore scanned from
    // y=height-1 downwards; BOTTOM starts at y=0.
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

    private fun isDarkRow(y: Int): Boolean {
        var dark = 0
        var total = 0
        var x = 0
        while (x < width) {
            if (isDarkPixel(x, y)) dark += 1
            total += 1
            x += SAMPLE_STRIDE
        }
        return total > 0 && dark.toFloat() / total.toFloat() >= DARK_SAMPLE_RATIO
    }

    private fun isDarkColumn(x: Int): Boolean {
        var dark = 0
        var total = 0
        var y = 0
        while (y < height) {
            if (isDarkPixel(x, y)) dark += 1
            total += 1
            y += SAMPLE_STRIDE
        }
        return total > 0 && dark.toFloat() / total.toFloat() >= DARK_SAMPLE_RATIO
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
        private const val SAMPLE_STRIDE = 4
        private const val DARK_LUMA_THRESHOLD = 24
        private const val DARK_MAX_CHANNEL = 36
        private const val DARK_SAMPLE_RATIO = 0.88f
        private const val MAX_EDGE_SCAN_FRACTION = 0.35f

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
            varying vec2 vTexCoord;
            void main() {
                vec4 tc = uTexMatrix * vec4(vTexCoord, 0.0, 1.0);
                gl_FragColor = texture2D(sTexture, tc.xy);
            }
        """
    }
}
