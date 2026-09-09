package it.motolink.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.Calendar
import java.util.Locale

object VogeNavPacketEncoder {
    const val CMD_HEARTBEAT = 0x5A
    const val CMD_NAV = 0x6A
    const val CMD_NEXT_1 = 0x6B
    const val CMD_NEXT_2 = 0x6C
    const val CMD_CUR_1 = 0x6D
    const val CMD_CUR_2 = 0x6E

    fun heartbeatFrame(context: Context): ByteArray {
        val p = base(CMD_HEARTBEAT)
        val battery = runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra("level", 0) ?: 0
        }.getOrDefault(0).coerceIn(0, 255)
        val now = Calendar.getInstance()
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        p[2] = 0
        p[3] = battery.toByte()
        p[4] = 0x32
        p[5] = hour24.toByte()
        p[6] = now.get(Calendar.MINUTE).toByte()
        p[7] = now.get(Calendar.SECOND).toByte()
        p[8] = if (hour24 >= 12) 1 else 0
        p[9] = 0
        p[10] = 0x02
        return checksum(p)
    }

    fun allFrames(m: VogeNavModel): List<ByteArray> = listOf(
        navFrame(m),
        textFrame(CMD_NEXT_1, m.nextRoadName, false),
        textFrame(CMD_NEXT_2, m.nextRoadName, true),
        textFrame(CMD_CUR_1, m.curRoadName, false),
        textFrame(CMD_CUR_2, m.curRoadName, true)
    )

    fun navFrame(m: VogeNavModel): ByteArray {
        val p = base(CMD_NAV)
        putBe(p, 2, m.curRoadRemainDistM.coerceIn(0, 0xFFFFFF), 3)
        putBe(p, 5, m.routeRemainTimeSec.coerceIn(0, 0xFFFF), 2)
        putBe(p, 7, m.routeRemainDistM.coerceIn(0, 0xFFFFFF), 3)
        p[10] = m.nextRoadDirection.coerceIn(0, 255).toByte()
        val deltaSec = if (m.arriveRemainSec > 0) m.arriveRemainSec else m.routeRemainTimeSec
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() + deltaSec.coerceAtLeast(0).toLong() * 1000L
        }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        p[11] = hour24.toByte()
        p[12] = cal.get(Calendar.MINUTE).toByte()
        p[13] = if (hour24 >= 12) 1 else 0
        p[14] = m.roadFlag.coerceIn(0, 255).toByte()
        p[15] = m.naviOnOff.coerceIn(0, 255).toByte()
        p[16] = m.annularDegrees.coerceIn(0, 255).toByte()
        return checksum(p)
    }

    private fun textFrame(command: Int, text: String?, secondChunk: Boolean): ByteArray {
        val p = base(command)
        p[2] = if (Locale.getDefault().language.equals("zh", true)) 1 else 0
        val utf = (text ?: "").toByteArray(Charsets.UTF_8)
        val src = if (secondChunk) 15 else 0
        val count = minOf(15, (utf.size - src).coerceAtLeast(0))
        if (count > 0) utf.copyInto(p, destinationOffset = 3, startIndex = src, endIndex = src + count)
        return checksum(p)
    }

    private fun base(command: Int): ByteArray = ByteArray(20).also {
        it[0] = 0x01
        it[1] = command.toByte()
        it[19] = 0x04
    }

    private fun putBe(dest: ByteArray, offset: Int, value: Int, count: Int) {
        for (i in 0 until count) {
            val shift = (count - 1 - i) * 8
            dest[offset + i] = ((value shr shift) and 0xFF).toByte()
        }
    }

    private fun checksum(p: ByteArray): ByteArray {
        p[p.lastIndex - 1] = 0
        var x = 0
        p.forEach { x = x xor (it.toInt() and 0xFF) }
        p[p.lastIndex - 1] = x.toByte()
        return p
    }
}
