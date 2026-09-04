package it.motolink.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * In-memory bridge between Android MediaCodec and the EasyConn :10920 poll loop.
 *
 * V0.6.2 uses I/P frames. Arbitrary latest-frame replacement is not safe because P frames
 * depend on prior reference pictures. A small ordered queue absorbs short scheduling jitter.
 * If pressure persists, new dependent frames are ignored until the natural 1 s IDR arrives;
 * that IDR becomes a safe catch-up point without an active IDR-request feedback loop.
 *
 * Privacy: encoded screen AUs live only in RAM. Nothing is persisted or uploaded.
 */
object H264FrameBus {
    private val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
    private const val MAX_QUEUE = 6
    private const val SYNTHETIC_REPEAT_NS = 1_000_000_000L

    private val pending = ArrayDeque<ByteArray>()
    private var codecConfig: ByteArray? = null
    private var awaitIdr = true
    private var syncRequestPending = false
    private var catchupOnNextIdr = false
    private var consumerActive = false
    private var consumerGeneration = 0L
    private var stickySyntheticIdr: ByteArray? = null
    private var lastSyntheticSentNs = 0L
    private var inputFrames = 0L
    private var acceptedFrames = 0L
    private var droppedFrames = 0L
    private var queueResets = 0L

    @Synchronized
    fun resetAll() {
        pending.clear()
        codecConfig = null
        awaitIdr = true
        syncRequestPending = false
        catchupOnNextIdr = false
        consumerActive = false
        consumerGeneration = 0L
        stickySyntheticIdr = null
        lastSyntheticSentNs = 0L
        inputFrames = 0L
        acceptedFrames = 0L
        droppedFrames = 0L
        queueResets = 0L
    }

    /**
     * Called whenever the HU creates/recreates the :10920 consumer.
     * Returns a generation token. Only the newest token is allowed to consume frames or
     * deactivate the bridge, so a late finally{} from an older socket cannot kill a newer
     * reconnect that is already streaming.
     */
    @Synchronized
    fun prepareForConsumer(): Long {
        droppedFrames += pending.size.toLong()
        pending.clear()
        consumerGeneration = if (consumerGeneration == Long.MAX_VALUE) 1L else consumerGeneration + 1L
        consumerActive = true
        catchupOnNextIdr = false
        val placeholder = stickySyntheticIdr
        if (placeholder != null) {
            // A real Android screen lock can invalidate MediaProjection. Keep a synthetic
            // key-frame sticky so a TFT reconnect still receives the lock notice instead
            // of a black screen. No screen content is captured while the device is locked.
            pending.addLast(placeholder.copyOf())
            lastSyntheticSentNs = 0L
            awaitIdr = false
            syncRequestPending = false
        } else {
            awaitIdr = true
            // Exactly one sync request per consumer attach. Do not create a resync storm
            // while the head unit is not connected to :10920.
            syncRequestPending = true
        }
        return consumerGeneration
    }

    /**
     * Called when a :10920 socket closes. Returns true only if that socket still owns the
     * active generation. A stale disconnect is ignored.
     */
    @Synchronized
    fun consumerDisconnected(generation: Long): Boolean {
        if (!consumerActive || generation != consumerGeneration) return false
        droppedFrames += pending.size.toLong()
        pending.clear()
        consumerActive = false
        awaitIdr = true
        syncRequestPending = false
        catchupOnNextIdr = false
        return true
    }

    /** MirrorService consumes this and invokes MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME. */
    @Synchronized
    fun consumeSyncRequest(): Boolean {
        if (!syncRequestPending) return false
        syncRequestPending = false
        return true
    }

    /** Stores SPS/PPS from MediaCodec output-format or BUFFER_FLAG_CODEC_CONFIG. */
    @Synchronized
    fun updateCodecConfig(vararg chunks: ByteArray?) {
        val out = ArrayList<Byte>()
        for (chunk in chunks) {
            if (chunk == null || chunk.isEmpty()) continue
            val annex = toAnnexB(chunk) ?: continue
            val clean = stripLeadingAud(annex)
            if (hasNalType(clean, 7) || hasNalType(clean, 8)) {
                clean.forEach { out.add(it) }
            }
        }
        if (out.isNotEmpty()) codecConfig = out.toByteArray()
    }

    /** Pushes one MediaCodec output sample. Returns true when accepted for HU delivery. */
    @Synchronized
    fun pushEncodedSample(sample: ByteArray, keyFrame: Boolean): Boolean {
        if (sample.isEmpty()) return false
        inputFrames++

        // While the explicit lock placeholder is active, never allow late encoder output
        // from the invalidated MediaProjection to overwrite the notice shown on the TFT.
        if (stickySyntheticIdr != null) {
            droppedFrames++
            return false
        }

        // MediaProjection may be started well before the Voge opens :10920. During that
        // interval we deliberately discard encoded pictures without queueing or requesting
        // IDRs. The first consumer attach will request exactly one fresh sync frame.
        if (!consumerActive) {
            droppedFrames++
            return false
        }

        var annex = toAnnexB(sample) ?: run {
            droppedFrames++
            return false
        }
        annex = stripLeadingAud(annex)

        val idr = keyFrame || hasNalType(annex, 5)
        if (idr) {
            val cfg = codecConfig
            if (cfg != null && (!hasNalType(annex, 7) || !hasNalType(annex, 8))) {
                annex = concat(cfg, annex)
            }
        }

        // On reconnect/resync, never begin with a dependent P frame.
        if (awaitIdr) {
            if (!idr) {
                droppedFrames++
                return false
            }
            awaitIdr = false
        }

        // V0.6.2: transient queue pressure must NOT trigger repeated active IDR requests.
        // That feedback loop was visible as hundreds of syncReq/resync events and caused
        // the video to pause and then catch up. Preserve the already queued decode chain,
        // stop accepting new dependent P frames, and wait for the encoder's natural 1 s IDR.
        // When that IDR arrives it is safe to discard any remaining stale GOP and jump
        // straight to the fresh independent picture.
        if (catchupOnNextIdr) {
            if (!idr) {
                droppedFrames++
                return false
            }
            droppedFrames += pending.size.toLong()
            pending.clear()
            queueResets++
            catchupOnNextIdr = false
            awaitIdr = false
        } else if (pending.size >= MAX_QUEUE) {
            if (!idr) {
                catchupOnNextIdr = true
                droppedFrames++
                return false
            }
            // A natural IDR is already available: use it as the safe catch-up point.
            droppedFrames += pending.size.toLong()
            pending.clear()
            queueResets++
            awaitIdr = false
        }

        val au = concat(aud, annex).copyOf()
        pending.addLast(au)
        acceptedFrames++
        return true
    }


    /**
     * Activates a pre-encoded, self-contained Annex-B IDR as a sticky synthetic frame.
     * Used only after Android has invalidated MediaProjection because the real keyguard
     * was engaged. The frame contains no captured user content and can be delivered even
     * if the TFT reconnects after the lock event.
     */
    @Synchronized
    fun activateSyntheticLockPlaceholder(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false
        var annex = toAnnexB(sample) ?: return false
        annex = stripLeadingAud(annex)
        if (!hasNalType(annex, 5)) return false

        val au = concat(aud, annex).copyOf()
        stickySyntheticIdr = au
        lastSyntheticSentNs = 0L
        droppedFrames += pending.size.toLong()
        pending.clear()
        catchupOnNextIdr = false
        awaitIdr = false
        syncRequestPending = false
        inputFrames++
        if (consumerActive) {
            pending.addLast(au.copyOf())
            acceptedFrames++
        }
        return true
    }

    @Synchronized
    fun lockPlaceholderActive(): Boolean = stickySyntheticIdr != null

    /** Called once per HU poll. Only the newest :10920 generation may consume frames. */
    @Synchronized
    fun nextFrameForPoll(generation: Long, nowNs: Long = System.nanoTime()): ByteArray? {
        if (!consumerActive || generation != consumerGeneration) return null
        val sticky = stickySyntheticIdr
        if (pending.isNotEmpty()) {
            val out = pending.removeFirst()
            if (sticky != null) lastSyntheticSentNs = nowNs
            return out
        }
        if (sticky != null && consumerActive &&
            (lastSyntheticSentNs == 0L || nowNs - lastSyntheticSentNs >= SYNTHETIC_REPEAT_NS)) {
            lastSyntheticSentNs = nowNs
            return sticky.copyOf()
        }
        return null
    }

    @Synchronized
    fun stats(): String =
        "input=$inputFrames accepted=$acceptedFrames pending=${pending.size} dropped=$droppedFrames catchup=$queueResets waitNaturalIdr=$catchupOnNextIdr awaitIdr=$awaitIdr consumer=$consumerActive consumerGen=$consumerGeneration lockPlaceholder=${stickySyntheticIdr != null}"

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun startsWithStartCode(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            ((data[2] == 1.toByte()) || (data[2] == 0.toByte() && data[3] == 1.toByte()))

    /** Detects Annex-B; otherwise decodes a 4-byte big-endian AVCC sample. */
    private fun toAnnexB(sample: ByteArray): ByteArray? {
        if (startsWithStartCode(sample)) return sample.copyOf()

        val out = ArrayList<Byte>(sample.size + 32)
        var pos = 0
        var nalCount = 0
        while (pos + 4 <= sample.size) {
            val len = ByteBuffer.wrap(sample, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            pos += 4
            if (len <= 0 || pos + len > sample.size) {
                nalCount = 0
                break
            }
            out.add(0); out.add(0); out.add(0); out.add(1)
            for (i in pos until pos + len) out.add(sample[i])
            pos += len
            nalCount++
        }
        if (nalCount > 0 && pos == sample.size) return out.toByteArray()

        // Conservative fallback for a single raw NAL unit.
        if (sample.isNotEmpty() && (sample[0].toInt() and 0x1f) in 1..23) {
            return concat(byteArrayOf(0, 0, 0, 1), sample)
        }
        return null
    }

    private fun stripLeadingAud(data: ByteArray): ByteArray {
        val nal = firstNal(data) ?: return data
        if (nal.first != 9) return data
        val nextStart = findStartCode(data, nal.second)
        return if (nextStart >= 0) data.copyOfRange(nextStart, data.size) else ByteArray(0)
    }

    /** Returns Pair(nalType, index after NAL header). */
    private fun firstNal(data: ByteArray): Pair<Int, Int>? {
        val start = findStartCode(data, 0)
        if (start < 0) return null
        val sc = if (start + 3 < data.size && data[start + 2] == 1.toByte()) 3 else 4
        val header = start + sc
        if (header >= data.size) return null
        return Pair(data[header].toInt() and 0x1f, header + 1)
    }

    private fun hasNalType(data: ByteArray, wanted: Int): Boolean {
        var pos = 0
        while (true) {
            val start = findStartCode(data, pos)
            if (start < 0) return false
            val sc = if (start + 2 < data.size && data[start + 2] == 1.toByte()) 3 else 4
            val header = start + sc
            if (header < data.size && (data[header].toInt() and 0x1f) == wanted) return true
            pos = header + 1
        }
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i + 3 <= data.size) {
            if (i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i
            if (i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            i++
        }
        return -1
    }
}
