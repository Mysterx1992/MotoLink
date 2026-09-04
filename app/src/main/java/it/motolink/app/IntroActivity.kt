package it.motolink.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * MotoLink video intro.
 * Uses TextureView instead of VideoView/SurfaceView to avoid black-frame issues
 * seen on some Android devices. The bundled clip is silent H.264 1080x1920/24fps.
 *
 * V1.0 voice test: the local "MotoLink Connect" clip starts on the first video
 * frame where the RIDE / CONNECT / MIRRORING caption begins to appear.
 */
class IntroActivity : Activity(), TextureView.SurfaceTextureListener {
    companion object {
        // First visible lower-caption frame in motolink_intro.mp4: 29 / 24 fps.
        private const val VOICE_START_DELAY_MS = 1_208L
    }

    private lateinit var textureView: TextureView
    private var player: MediaPlayer? = null
    private var voicePlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var launched = false
    private var videoCompleted = false
    private var voiceCompleted = false
    private var videoWidth = 1080
    private var videoHeight = 1920
    private val mainHandler = Handler(Looper.getMainLooper())
    private val voiceStartRunnable = Runnable { startVoice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val introEnabled = getSharedPreferences("trofeolink_prefs", MODE_PRIVATE).getBoolean("v1_intro_enabled", true)
        if (!introEnabled) {
            launchMain()
            return
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        title = "MotoLink"

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { launchMain() }
        }

        textureView = TextureView(this).apply {
            surfaceTextureListener = this@IntroActivity
            isOpaque = true
        }
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        startVideo()
    }

    private fun startVideo() {
        if (launched || player != null || surface == null) return
        try {
            val afd = resources.openRawResourceFd(R.raw.motolink_intro)
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(surface)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = false
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    applyFitCenterTransform()
                }
            }
            mp.setOnPreparedListener {
                videoCompleted = false
                voiceCompleted = false
                applyFitCenterTransform()
                it.start()
                mainHandler.removeCallbacks(voiceStartRunnable)
                mainHandler.postDelayed(voiceStartRunnable, VOICE_START_DELAY_MS)
            }
            mp.setOnCompletionListener {
                videoCompleted = true
                maybeFinishIntro()
            }
            mp.setOnErrorListener { _, _, _ ->
                launchMain()
                true
            }
            mp.prepareAsync()
        } catch (_: Throwable) {
            launchMain()
        }
    }

    private fun startVoice() {
        if (launched || voicePlayer != null) return
        try {
            val vp = MediaPlayer.create(this, R.raw.motolink_connect_voice)
            if (vp == null) {
                voiceCompleted = true
                maybeFinishIntro()
                return
            }
            voicePlayer = vp
            vp.setOnCompletionListener {
                voiceCompleted = true
                releaseVoicePlayer()
                maybeFinishIntro()
            }
            vp.setOnErrorListener { _, _, _ ->
                voiceCompleted = true
                releaseVoicePlayer()
                maybeFinishIntro()
                true
            }
            vp.start()
        } catch (_: Throwable) {
            voiceCompleted = true
            releaseVoicePlayer()
            maybeFinishIntro()
        }
    }

    private fun maybeFinishIntro() {
        if (videoCompleted && voiceCompleted) launchMain()
    }

    /**
     * Preserve the full approved artwork without cropping the sides on tall phones.
     * Any unused space stays the same black/green app background.
     */
    private fun applyFitCenterTransform() {
        if (!::textureView.isInitialized || textureView.width <= 0 || textureView.height <= 0) return
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        val videoW = videoWidth.toFloat()
        val videoH = videoHeight.toFloat()
        if (videoW <= 0f || videoH <= 0f) return

        val scale = minOf(viewW / videoW, viewH / videoH)
        val scaledW = videoW * scale
        val scaledH = videoH * scale
        val sx = scaledW / viewW
        val sy = scaledH / viewH
        val matrix = Matrix()
        matrix.setScale(sx, sy, viewW / 2f, viewH / 2f)
        textureView.setTransform(matrix)
    }

    private fun launchMain() {
        if (launched) return
        launched = true
        mainHandler.removeCallbacks(voiceStartRunnable)
        releasePlayer()
        releaseVoicePlayer()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.reset() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    private fun releaseVoicePlayer() {
        try { voicePlayer?.stop() } catch (_: Throwable) {}
        try { voicePlayer?.reset() } catch (_: Throwable) {}
        try { voicePlayer?.release() } catch (_: Throwable) {}
        voicePlayer = null
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyFitCenterTransform()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        mainHandler.removeCallbacks(voiceStartRunnable)
        releasePlayer()
        releaseVoicePlayer()
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onBackPressed() {
        launchMain()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(voiceStartRunnable)
        releasePlayer()
        releaseVoicePlayer()
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
        super.onDestroy()
    }
}
