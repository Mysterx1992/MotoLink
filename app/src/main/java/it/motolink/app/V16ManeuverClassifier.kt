package it.motolink.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * V1.6 vc16 roundabout-exit classifier.
 *
 * The model and preprocessing approach come from OpenDash / KTM-Nav-GEN3
 * (MIT License, Copyright (c) 2026 OpenDash contributors). MotoLink uses the
 * classifier only to recover the roundabout exit sector from the Google Maps
 * notification glyph. Every other maneuver keeps the already-tested vc15 path.
 *
 * OpenDash labels roundabouts as 16-sector RH/LH dash codes (26..57). VOGE
 * Global 1.1.7 uses a 12-sector `annularDegrees` field in opcode 0x6A. We keep
 * the sector index, collapse 16 -> 12, and intentionally ignore the RH/LH bank:
 * VOGE handles the road-side convention separately from this exit-angle field.
 */
internal object V16ManeuverClassifier {
    private const val INPUT = 96
    private const val CONFIDENCE = 0.40f

    // Exact output order from the bundled OpenDash model.
    private val LABELS = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 21,
        27, 29, 31, 33, 35, 37, 39, 41,
        43, 45, 47, 49, 51, 53, 55, 57,
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var unavailable = false
    private val cache = ConcurrentHashMap<Int, Int>()

    fun classifyRoundaboutSector(context: Context, bitmap: Bitmap): Int? {
        val code = classifyCode(context, bitmap) ?: return null
        val section16 = when (code) {
            in 26..41 -> code - 25
            in 42..57 -> code - 41
            else -> return null
        }
        val vogeSector = (section16 * 12.0 / 16.0).roundToInt().coerceIn(1, 12)
        AppLog.add("MAPS NAV V1.6 ML: class=$code section16=$section16 -> VOGE annular=$vogeSector")
        return vogeSector
    }

    private fun classifyCode(context: Context, bitmap: Bitmap): Int? {
        val hash = hashOf(bitmap)
        cache[hash]?.let { return it }
        val model = ensureModel(context.applicationContext) ?: return null
        val code = try {
            val input = preprocess(bitmap)
            val output = Array(1) { FloatArray(LABELS.size) }
            model.run(input, output)
            val scores = output[0]
            var best = 0
            for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
            val confidence = scores[best]
            if (confidence < CONFIDENCE) {
                AppLog.add("MAPS NAV V1.6 ML: nessuna classe affidabile confidence=$confidence")
                null
            } else {
                LABELS[best].also {
                    AppLog.add("MAPS NAV V1.6 ML: code=$it confidence=$confidence")
                }
            }
        } catch (t: Throwable) {
            AppLog.add("MAPS NAV V1.6 ML: inference fallita ${t.javaClass.simpleName}")
            null
        }
        if (code != null) cache[hash] = code
        return code
    }

    private fun ensureModel(context: Context): Interpreter? {
        interpreter?.let { return it }
        if (unavailable) return null
        synchronized(this) {
            interpreter?.let { return it }
            return try {
                val bytes = context.resources.openRawResource(R.raw.maneuver_model).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes)
                buffer.rewind()
                Interpreter(buffer, Interpreter.Options().setNumThreads(1)).also { interpreter = it }
            } catch (t: Throwable) {
                AppLog.add("MAPS NAV V1.6 ML: modello non disponibile ${t.javaClass.simpleName}")
                unavailable = true
                null
            }
        }
    }

    /** Matches the OpenDash model preprocessing: icon composited on black, 96x96 RGB float [0,1]. */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val onBlack = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(onBlack)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        val scaled = Bitmap.createScaledBitmap(onBlack, INPUT, INPUT, true)
        val buffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                val px = scaled.getPixel(x, y)
                buffer.putFloat(Color.red(px) / 255f)
                buffer.putFloat(Color.green(px) / 255f)
                buffer.putFloat(Color.blue(px) / 255f)
            }
        }
        buffer.rewind()
        if (scaled !== onBlack) scaled.recycle()
        onBlack.recycle()
        return buffer
    }

    private fun hashOf(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
}
