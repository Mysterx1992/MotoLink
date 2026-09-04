package it.motolink.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementa soltanto l'EC init iniziale, sufficiente per verificare che la Voge
 * parli la variante EasyConn attesa. Non legge dati personali.
 *
 * Layout ricostruito indipendentemente e confrontato con l'implementazione
 * open-source Ridedaemon: header 16 byte little-endian, cmd 0x10, ack 0x11.
 */
object EasyConnInitClient {
    data class Result(val ok: Boolean, val responseCode: Int?, val responseBody: String?)

    // HARD STOP support: close any bounded EC INIT socket that happens to be in-flight
    // when the rider presses STOP. No init socket is allowed to linger after teardown.
    private val activeSockets = CopyOnWriteArrayList<Socket>()

    fun cancelAll() {
        activeSockets.forEach { try { it.close() } catch (_: Throwable) {} }
        activeSockets.clear()
    }

    fun performP2pMdnsRespond(
        host: InetAddress,
        port: Int,
        packageName: String,
        localBindAddress: InetAddress
    ): Result {
        val body = JSONObject()
            .put("phoneType", "Android")
            .put("packageName", packageName)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val command = 0x70000010
        val total = body.size + 16
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(command)
            putInt(total)
            putInt(command xor total)
            putInt(0)
        }.array()

        val socket = Socket()
        activeSockets += socket
        try {
            // Wifi Direct is a second local interface and is not necessarily Android's
            // default Network. Bind the client socket to the P2P IPv4 so the EC INIT
            // cannot accidentally leave through the normal Wi-Fi/cellular route.
            socket.bind(InetSocketAddress(localBindAddress, 0))
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(header)
                write(body)
                flush()
            }

            val input = socket.getInputStream()
            val responseHeader = readExact(input, 16)
            val bb = ByteBuffer.wrap(responseHeader).order(ByteOrder.LITTLE_ENDIAN)
            val responseCode = bb.int
            val responseLen = bb.int
            val magic = bb.int
            bb.int
            if (responseLen < 16 || responseLen > 65535 || magic != (responseCode xor responseLen)) {
                return Result(false, responseCode, "header risposta non valido")
            }
            val responseBodyBytes = readExact(input, responseLen - 16)
            val raw = responseBodyBytes.toString(Charsets.UTF_8)
                .substringBefore('\n')
                .take(300)
            val statusTrue = runCatching { JSONObject(raw).optBoolean("status", false) }
                .getOrElse { raw.contains("true", ignoreCase = true) }
            return Result(responseCode == 0x70000011 && statusTrue, responseCode, raw.ifBlank { null })
        } finally {
            activeSockets.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    fun perform(host: InetAddress, port: Int, packageName: String): Result {
        val body = JSONObject()
            .put("phoneType", "Android")
            .put("packageName", packageName)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val total = body.size + 16
        val header = ByteArray(16)
        putLe16(header, 0, 0x10)
        putLe16(header, 3, 0x70)
        putLe16(header, 4, total)
        putLe16(header, 8, total xor 16)
        putLe16(header, 11, 0x70)

        val socket = Socket()
        activeSockets += socket
        try {
            socket.connect(InetSocketAddress(host, port), 4000)
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(header)
                write(body)
                flush()
            }

            val input = socket.getInputStream()
            val responseHeader = readExact(input, 16)
            val responseCode = le16(responseHeader, 0)
            val responseLen = le16(responseHeader, 4)
            if (responseLen < 16 || responseLen > 65535) {
                return Result(false, responseCode, "lunghezza risposta non valida: $responseLen")
            }
            val responseBodyBytes = readExact(input, responseLen - 16)
            val raw = responseBodyBytes.toString(Charsets.UTF_8)
                .substringBefore('\n')
                .take(300)
            return Result(responseCode == 0x11, responseCode, raw.ifBlank { null })
        } finally {
            activeSockets.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun putLe16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xff).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun le16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xff) or ((src[offset + 1].toInt() and 0xff) shl 8)

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val out = ByteArrayOutputStream(size)
        val buf = ByteArray(minOf(4096, maxOf(1, size)))
        while (out.size() < size) {
            val n = input.read(buf, 0, minOf(buf.size, size - out.size()))
            if (n < 0) throw EOFException("EOF dopo ${out.size()}/$size byte")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
