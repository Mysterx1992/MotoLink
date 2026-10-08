package it.motolink.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * V1.7 roundabout classifier.
 *
 * The bundled OpenDash / KTM-Nav-GEN3 model is used only to recover the
 * roundabout exit sector. The input glyph is normalized to white ink on black
 * before inference so Android/Maps theme colours do not suppress the symbol.
 *
 * Roundabout detection itself remains independent from language: textual Maps
 * wording and the geometric closed-loop detector can confirm that the current
 * maneuver is a roundabout. Once confirmed, a conservative low-confidence
 * fallback may use the best roundabout-only class even when the global model
 * confidence is below the normal threshold.
 */
internal object V16ManeuverClassifier {
    private const val INPUT = 96
    private const val CONFIDENCE = 0.40f
    private const val CONFIRMED_MIN_SCORE = 0.018f
    private const val CONFIRMED_MIN_MARGIN = 0.0015f

    private val LABELS = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 21,
        27, 29, 31, 33, 35, 37, 39, 41,
        43, 45, 47, 49, 51, 53, 55, 57,
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var unavailable = false
    private val strictCache = ConcurrentHashMap<Int, RoundaboutEncoding>()

    data class RoundaboutEncoding(
        val annularDegrees: Int,
        val directionCode: Int,
    )

    fun classifyRoundaboutEncoding(
        context: Context,
        bitmap: Bitmap,
        roundaboutConfirmed: Boolean = false,
    ): RoundaboutEncoding? {
        val hash = hashOf(bitmap)
        if (!roundaboutConfirmed) strictCache[hash]?.let { return it }

        val model = ensureModel(context.applicationContext) ?: return null
        val scores = try {
            val input = preprocess(bitmap)
            Array(1) { FloatArray(LABELS.size) }.also { model.run(input, it) }[0]
        } catch (t: Throwable) {
            AppLog.add("MAPS NAV V1.7 ML: inference fallita ${t.javaClass.simpleName}")
            return null
        }

        var bestOverall = 0
        for (i in 1 until scores.size) if (scores[i] > scores[bestOverall]) bestOverall = i
        val bestOverallCode = LABELS[bestOverall]
        val bestOverallScore = scores[bestOverall]

        val roundaboutIndexes = LABELS.indices.filter { isRoundaboutCode(LABELS[it]) }
        val rankedRoundabouts = roundaboutIndexes.sortedByDescending { scores[it] }
        val bestRoundabout = rankedRoundabouts.firstOrNull() ?: return null
        val secondRoundabout = rankedRoundabouts.getOrNull(1)
        val roundaboutCode = LABELS[bestRoundabout]
        val roundaboutScore = scores[bestRoundabout]
        val roundaboutMargin = roundaboutScore - (secondRoundabout?.let { scores[it] } ?: 0f)

        val strict = isRoundaboutCode(bestOverallCode) && bestOverallScore >= CONFIDENCE
        val confirmedFallback = roundaboutConfirmed &&
            roundaboutScore >= CONFIRMED_MIN_SCORE &&
            roundaboutMargin >= CONFIRMED_MIN_MARGIN

        val chosenCode = when {
            strict -> bestOverallCode
            confirmedFallback -> roundaboutCode
            else -> {
                AppLog.add(
                    "MAPS NAV V1.7 ML: nessuna uscita rotonda affidabile " +
                        "globalCode=$bestOverallCode global=${"%.4f".format(java.util.Locale.US, bestOverallScore)} " +
                        "roundCode=$roundaboutCode round=${"%.4f".format(java.util.Locale.US, roundaboutScore)} " +
                        "margin=${"%.4f".format(java.util.Locale.US, roundaboutMargin)} confirmed=$roundaboutConfirmed"
                )
                return null
            }
        }

        val result = encodingForCode(chosenCode) ?: return null
        val mode = if (strict) "STRICT" else "CONFIRMED"
        AppLog.add(
            "MAPS NAV V1.7 ML: mode=$mode code=$chosenCode -> " +
                "VOGE annular=${result.annularDegrees} direction=${result.directionCode}"
        )
        if (strict) strictCache[hash] = result
        return result
    }

    private fun isRoundaboutCode(code: Int): Boolean =
        code in 26..41 || code in 42..57

    private fun encodingForCode(code: Int): RoundaboutEncoding? {
        val section16 = when (code) {
            in 26..41 -> code - 25
            in 42..57 -> code - 41
            else -> return null
        }
        if (section16 !in setOf(2, 4, 6, 8, 10, 12, 14, 16)) return null

        val vogeSector = (section16 * 12.0 / 16.0).roundToInt().coerceIn(1, 12)
        val direction = when (section16) {
            2 -> 7
            4 -> 5
            6 -> 9
            8 -> 1
            10 -> 6
            12 -> 4
            14 -> 8
            16 -> 2
            else -> return null
        }
        return RoundaboutEncoding(vogeSector, direction)
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
                AppLog.add("MAPS NAV V1.7 ML: modello non disponibile ${t.javaClass.simpleName}")
                unavailable = true
                null
            }
        }
    }

    /**
     * Theme-independent preprocessing.
     *
     * Maps may render a dark, light or tinted glyph. Recover ink relative to the
     * corner background and feed the model white ink on black.
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val bg = scaled.getPixel(0, 0)
        val buffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until INPUT) {
            for (x in 0 until INPUT) {
                val px = scaled.getPixel(x, y)
                val v = if (isInk(px, bg)) 1f else 0f
                buffer.putFloat(v)
                buffer.putFloat(v)
                buffer.putFloat(v)
            }
        }
        buffer.rewind()
        if (scaled !== bitmap) scaled.recycle()
        return buffer
    }

    private fun isInk(px: Int, bg: Int): Boolean {
        if (Color.alpha(px) < 24) return false
        val da = Color.alpha(px) - Color.alpha(bg)
        val dr = Color.red(px) - Color.red(bg)
        val dg = Color.green(px) - Color.green(bg)
        val db = Color.blue(px) - Color.blue(bg)
        return (da * da + dr * dr + dg * dg + db * db) > 1600
    }

    private fun hashOf(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.contentHashCode()
    }
}
