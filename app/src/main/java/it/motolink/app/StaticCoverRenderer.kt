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
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-pass GPU compositor used between MediaProjection and the H.264 encoder.
 *
 * V15 source-native capture: accepted V12 FIT anchor with independent manual edge extension.
 *
 * Landscape no longer CENTER_CROPs the source. The complete phone/app frame is always sampled
 * (cropScale=1x1, zoom ignored) and aspect-preserving FIT is performed inside the current
 * Adattamento rectangle. This restores full UI visibility while preserving the orientation-split floating
 * per-edge controls and the calibrated destination rectangle.
 *
 * Landscape always renders to the entire EasyConn-negotiated encoder canvas. It never shrinks
 * the destination viewport and never exposes black merely because the user selected 95% or a
 * stored margin. Zoom/X/Y become SOURCE-framing controls in landscape. Rotation is committed
 * atomically: resize callbacks are debounced by MirrorService, this renderer holds the last
 * valid frame, SurfaceTexture is resized before VirtualDisplay, transition frames are consumed
 * but not encoded, then the new geometry is committed and an IDR is requested. Portrait keeps
 * the pre-V5 path so the already-working physical behavior is not retuned in this experiment.
 */
class StaticCoverRenderer(
    private val encoderSurface: Surface,
    initialSourceWidth: Int,
    initialSourceHeight: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
    initialViewportWidth: Int,
    initialViewportHeight: Int,
    initialZoomPercent: Int,
    initialOffsetX: Int = 0,
    initialOffsetY: Int = 0,
    initialMarginLeft: Int = 0,
    initialMarginTop: Int = 0,
    initialMarginRight: Int = 0,
    initialMarginBottom: Int = 0,
    private val onGeometryCommitted: ((Int, Int) -> Unit)? = null
) {
    private val thread = HandlerThread("MotoLink-StaticCover").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    @Volatile private var initError: Throwable? = null

    @Volatile private var sourceWidth = initialSourceWidth.coerceAtLeast(16)
    @Volatile private var sourceHeight = initialSourceHeight.coerceAtLeast(16)
    @Volatile private var viewportWidth = initialViewportWidth.coerceAtLeast(16)
    @Volatile private var viewportHeight = initialViewportHeight.coerceAtLeast(16)
    @Volatile private var zoomPercent = initialZoomPercent.coerceIn(70, 400)
    @Volatile private var offsetX = initialOffsetX.coerceIn(-300, 300)
    @Volatile private var offsetY = initialOffsetY.coerceIn(-300, 300)
    @Volatile private var marginLeft = initialMarginLeft.coerceIn(0, 200)
    @Volatile private var marginTop = initialMarginTop.coerceIn(0, 200)
    @Volatile private var marginRight = initialMarginRight.coerceIn(0, 200)
    @Volatile private var marginBottom = initialMarginBottom.coerceIn(0, 200)

    // V15 manual adaptation. When enabled in landscape, a calibrated target-relative base
    // frame is applied first; these deltas then adjust its four edges independently.
    @Volatile private var adaptationEnabled = false
    @Volatile private var adaptLeftPx = 0
    @Volatile private var adaptTopPx = 0
    @Volatile private var adaptRightPx = 0
    @Volatile private var adaptBottomPx = 0

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var projectionSurface: Surface? = null
    private var program: Int = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexMatrix = -1
    private var uCropScale = -1
    private var uCropOffset = -1
    private var uDisplayScale = -1

    private val texMatrix = FloatArray(16)
    private var lastSourceCropSignature: String = ""

    // All of these fields are owned by the GL handler thread. They are deliberately not
    // written directly from MediaProjection callbacks.
    private var geometryHold = false
    private var geometryPrepared = false
    private var producerResizeReady = false
    private var pendingSourceWidth = sourceWidth
    private var pendingSourceHeight = sourceHeight
    private var transitionFramesToDrop = 0
    private var heldFrameCount = 0

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            // x, y, u, v — triangle strip
            put(-1f); put(-1f); put(0f); put(0f)
            put( 1f); put(-1f); put(1f); put(0f)
            put(-1f); put( 1f); put(0f); put(1f)
            put( 1f); put( 1f); put(1f); put(1f)
            position(0)
        }

    val inputSurface: Surface
        get() {
            if (!ready.await(4, TimeUnit.SECONDS)) {
                throw IllegalStateException("StaticCoverRenderer init timeout")
            }
            initError?.let { throw IllegalStateException("StaticCoverRenderer init failed", it) }
            return projectionSurface ?: throw IllegalStateException("StaticCoverRenderer surface missing")
        }

    init {
        handler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                initError = t
            } finally {
                ready.countDown()
            }
        }
    }

    fun enterGeometryHold(candidateWidth: Int, candidateHeight: Int) {
        if (candidateWidth < 16 || candidateHeight < 16 || released.get()) return
        handler.post {
            if (!geometryHold) {
                AppLog.add(
                    "ROTATION ATOMIC V15: HOLD ultimo frame valido; candidato=${candidateWidth}x${candidateHeight}"
                )
            }
            geometryHold = true
            geometryPrepared = false
            producerResizeReady = false
            transitionFramesToDrop = 0
            heldFrameCount = 0
            pendingSourceWidth = candidateWidth
            pendingSourceHeight = candidateHeight
        }
    }

    fun prepareSourceResize(width: Int, height: Int, onPrepared: () -> Unit) {
        if (width < 16 || height < 16 || released.get()) return
        handler.post {
            pendingSourceWidth = width
            pendingSourceHeight = height
            geometryHold = true
            geometryPrepared = true
            producerResizeReady = false
            transitionFramesToDrop = 0
            heldFrameCount = 0
            surfaceTexture?.setDefaultBufferSize(width, height)
            AppLog.add(
                "ROTATION ATOMIC V15: SurfaceTexture preparata ${width}x${height}; " +
                    "VirtualDisplay resize successivo"
            )
            onPrepared()
        }
    }

    fun notifyProducerResized(width: Int, height: Int) {
        if (released.get()) return
        handler.post {
            if (!geometryPrepared || width != pendingSourceWidth || height != pendingSourceHeight) {
                AppLog.add(
                    "ROTATION ATOMIC V15: producer resize ignorato ${width}x${height}; " +
                        "pending=${pendingSourceWidth}x${pendingSourceHeight} prepared=$geometryPrepared"
                )
                return@post
            }
            producerResizeReady = true
            transitionFramesToDrop = 3
            AppLog.add(
                "ROTATION ATOMIC V15: producer allineato ${width}x${height}; drop 3 frame transizione"
            )
        }
    }

    fun cancelGeometryTransition(reason: String) {
        if (released.get()) return
        handler.post {
            geometryHold = false
            geometryPrepared = false
            producerResizeReady = false
            transitionFramesToDrop = 0
            heldFrameCount = 0
            pendingSourceWidth = sourceWidth
            pendingSourceHeight = sourceHeight
            AppLog.add("ROTATION ATOMIC V15: HOLD annullato ($reason)")
        }
    }



    fun updateTuning(
        viewportW: Int, viewportH: Int, zoom: Int, x: Int, y: Int,
        left: Int, top: Int, right: Int, bottom: Int
    ) {
        if (released.get()) return
        viewportWidth = viewportW.coerceIn(16, targetWidth)
        viewportHeight = viewportH.coerceIn(16, targetHeight)
        zoomPercent = zoom.coerceIn(70, 400)
        offsetX = x.coerceIn(-300, 300)
        offsetY = y.coerceIn(-300, 300)
        marginLeft = left.coerceIn(0, 200)
        marginTop = top.coerceIn(0, 200)
        marginRight = right.coerceIn(0, 200)
        marginBottom = bottom.coerceIn(0, 200)
        handler.post {
            AppLog.add(
                "SCREEN CAL LIVE APPLIED: viewport=${viewportWidth}x${viewportHeight} zoom=${zoomPercent}% " +
                    "X=${offsetX} Y=${offsetY} L=${marginLeft} T=${marginTop} R=${marginRight} B=${marginBottom}"
            )
        }
    }

    fun updateEdgeAdaptation(left: Int, top: Int, right: Int, bottom: Int, enabled: Boolean) {
        if (released.get()) return
        adaptationEnabled = enabled
        adaptLeftPx = if (enabled) left.coerceIn(MirrorAdaptationConfig.MIN_EDGE_PX, MirrorAdaptationConfig.MAX_EDGE_PX) else 0
        adaptTopPx = if (enabled) top.coerceIn(MirrorAdaptationConfig.MIN_EDGE_PX, MirrorAdaptationConfig.MAX_EDGE_PX) else 0
        adaptRightPx = if (enabled) right.coerceIn(MirrorAdaptationConfig.MIN_EDGE_PX, MirrorAdaptationConfig.MAX_EDGE_PX) else 0
        adaptBottomPx = if (enabled) bottom.coerceIn(MirrorAdaptationConfig.MIN_EDGE_PX, MirrorAdaptationConfig.MAX_EDGE_PX) else 0
        handler.post {
            AppLog.add(
                "ADATTAMENTO V15 RENDER: enabled=$enabled activeDelta=" +
                    "L${adaptLeftPx}/T${adaptTopPx}/R${adaptRightPx}/B${adaptBottomPx}; " +
                    "profilo attivo deciso da MirrorService prima del geometry COMMIT"
            )
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        handler.post {
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                projectionSurface?.release()
                projectionSurface = null
                surfaceTexture?.release()
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
        uCropScale = GLES20.glGetUniformLocation(program, "uCropScale")
        uCropOffset = GLES20.glGetUniformLocation(program, "uCropOffset")
        uDisplayScale = GLES20.glGetUniformLocation(program, "uDisplayScale")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId).also { st ->
            st.setDefaultBufferSize(sourceWidth, sourceHeight)
            st.setOnFrameAvailableListener({ renderFrame() }, handler)
        }
        projectionSurface = Surface(surfaceTexture)
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        AppLog.add(
            "SOURCE NATIVE V15: compositor GPU pronto source=${sourceWidth}x${sourceHeight} " +
                "target=${targetWidth}x${targetHeight}; landscape=UNIVERSAL_AUTO_BASE portrait=FIT; sourceCrop=OFF zoom=IGNORED adaptation=ready"
        )
    }

    private fun renderFrame() {
        if (released.get()) return
        val st = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // During an orientation transition we keep consuming SurfaceTexture frames so the
            // producer cannot block, but we do not swap the encoder EGL surface. The TFT therefore
            // keeps the last valid H264 frame instead of seeing a portrait/landscape matrix race.
            if (geometryHold) {
                heldFrameCount += 1
                if (!producerResizeReady) return
                if (transitionFramesToDrop > 0) {
                    transitionFramesToDrop -= 1
                    return
                }

                sourceWidth = pendingSourceWidth.coerceAtLeast(16)
                sourceHeight = pendingSourceHeight.coerceAtLeast(16)
                geometryHold = false
                geometryPrepared = false
                producerResizeReady = false
                heldFrameCount = 0
                lastSourceCropSignature = ""
                AppLog.add(
                    "ROTATION ATOMIC V15: geometry COMMIT ${sourceWidth}x${sourceHeight}; " +
                        "buffer+producer stabilizzati"
                )
                onGeometryCommitted?.invoke(sourceWidth, sourceHeight)
            }

            // V15 uses the frozen Maps frame as a runtime-TBox-scaled landscape base and applies later edge extras independently.
            val sourceLandscape = sourceWidth > sourceHeight
            val targetLandscape = targetWidth > targetHeight
            val useLandscapeFullCanvas = sourceLandscape && targetLandscape

            var scaleX = 1f
            var scaleY = 1f
            var texOffsetX = 0f
            var texOffsetY = 0f

            if (useLandscapeFullCanvas) {
                // V15: full phone source, no source crop/legacy zoom. When Adattamento is ON,
                // start from the user's accepted 800x480 Maps frame scaled to the runtime T-Box
                // dimensions. Manual arrows are EXTRA edges and never move the opposite side.
                scaleX = 1f
                scaleY = 1f
                texOffsetX = 0f
                texOffsetY = 0f

                val baseFrame = if (adaptationEnabled) {
                    MirrorAdaptationConfig.landscapeAutoFrameFor(targetWidth, targetHeight)
                } else {
                    // OFF = neutral full-content FIT over the complete target.
                    val fit = kotlin.math.min(
                        targetWidth.toFloat() / sourceWidth.toFloat(),
                        targetHeight.toFloat() / sourceHeight.toFloat()
                    )
                    val w = kotlin.math.max(1, kotlin.math.round(sourceWidth.toFloat() * fit).toInt())
                    val h = kotlin.math.max(1, kotlin.math.round(sourceHeight.toFloat() * fit).toInt())
                    MirrorAdaptationConfig.AutoFrame(
                        x = (targetWidth - w) / 2,
                        y = (targetHeight - h) / 2,
                        width = w,
                        height = h,
                    )
                }

                val extraLeft = if (adaptationEnabled) adaptLeftPx else 0
                val extraTop = if (adaptationEnabled) adaptTopPx else 0
                val extraRight = if (adaptationEnabled) adaptRightPx else 0
                val extraBottom = if (adaptationEnabled) adaptBottomPx else 0

                val viewportX = baseFrame.x - extraLeft
                val viewportY = baseFrame.y - extraBottom
                val contentW = (baseFrame.width + extraLeft + extraRight).coerceAtLeast(16)
                val contentH = (baseFrame.height + extraTop + extraBottom).coerceAtLeast(16)

                val signature = "${sourceWidth}x${sourceHeight}|AUTO=${baseFrame.width}x${baseFrame.height}@${baseFrame.x},${baseFrame.y}|" +
                    "EXTRA=L$extraLeft/T$extraTop/R$extraRight/B$extraBottom|VIEW=${contentW}x${contentH}@${viewportX},${viewportY}|enabled=$adaptationEnabled"
                if (signature != lastSourceCropSignature) {
                    lastSourceCropSignature = signature
                    AppLog.add(
                        "SOURCE NATIVE LANDSCAPE V15 AUTO: source=${sourceWidth}x${sourceHeight} FULL; " +
                            "target=${targetWidth}x${targetHeight}; autoBase=${baseFrame.width}x${baseFrame.height}@${baseFrame.x},${baseFrame.y}; " +
                            "edgeExtra=L$extraLeft/T$extraTop/R$extraRight/B$extraBottom; " +
                            "viewport=${contentW}x${contentH}@${viewportX},${viewportY}; sourceCrop=OFF zoom=IGNORED; " +
                            "geometrySource=TBOX_RUNTIME; edgeAnchored=true"
                    )
                }

                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glViewport(viewportX, viewportY, contentW, contentH)
            } else {
                // Portrait uses aspect-preserving FIT because a portrait phone cannot fill a landscape TFT
                // without destroying most of the vertical content. The manual edge panel can still
                // enlarge/reduce individual boundaries when the rider explicitly wants it.
                val baseW = viewportWidth.coerceIn(16, targetWidth)
                val baseH = viewportHeight.coerceIn(16, targetHeight)
                val left = marginLeft.coerceIn(0, (baseW - 16).coerceAtLeast(0))
                val right = marginRight.coerceIn(0, (baseW - left - 16).coerceAtLeast(0))
                val top = marginTop.coerceIn(0, (baseH - 16).coerceAtLeast(0))
                val bottom = marginBottom.coerceIn(0, (baseH - top - 16).coerceAtLeast(0))
                val safeW = (baseW - left - right).coerceAtLeast(16)
                val safeH = (baseH - top - bottom).coerceAtLeast(16)
                val baseX = ((targetWidth - baseW) / 2) + offsetX
                val baseBottom = ((targetHeight - baseH) / 2) - offsetY
                val safeX = baseX + left
                val safeY = baseBottom + bottom

                val portraitZoom = 1f // V15: legacy Zoom removed/ignored
                val fit = kotlin.math.min(
                    safeW.toFloat() / sourceWidth.toFloat(),
                    safeH.toFloat() / sourceHeight.toFloat()
                )
                val contentW = kotlin.math.max(1, kotlin.math.round(sourceWidth.toFloat() * fit * portraitZoom).toInt())
                val contentH = kotlin.math.max(1, kotlin.math.round(sourceHeight.toFloat() * fit * portraitZoom).toInt())
                val viewportX = safeX + ((safeW - contentW) / 2)
                val viewportY = safeY + ((safeH - contentH) / 2)

                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val clipX = kotlin.math.max(0, safeX)
                val clipY = kotlin.math.max(0, safeY)
                val clipR = kotlin.math.min(targetWidth, safeX + safeW)
                val clipT = kotlin.math.min(targetHeight, safeY + safeH)
                val clipW = (clipR - clipX).coerceAtLeast(0)
                val clipH = (clipT - clipY).coerceAtLeast(0)
                if (clipW <= 0 || clipH <= 0) {
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    return
                }
                val adaptedViewportX = viewportX - adaptLeftPx
                val adaptedViewportY = viewportY - adaptBottomPx
                val adaptedViewportW = (contentW + adaptLeftPx + adaptRightPx).coerceAtLeast(16)
                val adaptedViewportH = (contentH + adaptTopPx + adaptBottomPx).coerceAtLeast(16)
                GLES20.glViewport(adaptedViewportX, adaptedViewportY, adaptedViewportW, adaptedViewportH)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(clipX, clipY, clipW, clipH)
            }

            GLES20.glUseProgram(program)

            vertices.position(0)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)

            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uCropScale, scaleX, scaleY)
            GLES20.glUniform2f(uCropOffset, texOffsetX, texOffsetY)
            GLES20.glUniform2f(uDisplayScale, 1f, 1f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (t: Throwable) {
            AppLog.add("SOURCE NATIVE V15 render error: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

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
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uDisplayScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition.xy * uDisplayScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform mat4 uTexMatrix;
            uniform vec2 uCropScale;
            uniform vec2 uCropOffset;
            varying vec2 vTexCoord;
            void main() {
                vec2 cropped = uCropOffset + vTexCoord * uCropScale;
                vec4 tc = uTexMatrix * vec4(cropped, 0.0, 1.0);
                gl_FragColor = texture2D(sTexture, tc.xy);
            }
        """
    }
}
