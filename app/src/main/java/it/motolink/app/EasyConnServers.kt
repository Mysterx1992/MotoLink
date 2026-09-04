package it.motolink.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.view.WindowManager
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * EasyConn control/media server used by TrofeoLink.
 * Keeps both PXC channels alive with the proven 2 s heartbeat cadence and serves
 * the H264 stream expected by the Voge. Session identifiers and crypto material
 * are ephemeral and remain in RAM; screen frames are never written to storage.
 */
class EasyConnServers(private val context: Context) {
    companion object {
        private const val PXC_HANDSHAKE = 0x00010000L
        private const val PXC_HANDSHAKE_OK = 0x00010001L
        private const val PXC_HUD_CONFIG = 0x00010010L
        private const val PXC_PHONE_CONFIG = 0x00010011L
        private const val PXC_HU_QUERY_TIME = 0x00010450L
        private const val PXC_HU_QUERY_TIME_ACK = 0x00010451L
        private const val PXC_SPEED_CONFIG = 0x00010690L
        private const val PXC_SPEED_OK = 0x00010691L
        private const val PXC_CLIENT_SET = 0x000103e0L
        private const val PXC_CLIENT_OK = 0x000103e1L
        private const val PXC_CHECK_SN_RESULT = 0x000201c0L
        private const val PXC_CHECK_SN_DONE = 0x000201c1L
        private const val PXC_HEARTBEAT = 0x70000000L
        private const val PXC_HEARTBEAT_OK = 0x70000001L
        private const val PXC_HEARTBEAT_INTERVAL_MS = 2_000L

        // Secondo canale PXC osservato direttamente sulla Voge.
        private const val PXC_SECONDARY_HELLO = 0x00020000L
        private const val PXC_SECONDARY_HELLO_OK = 0x00020001L
        // PHONE -> HU application foreground state.
        private const val PXC_APPSTATUS_FOREGROUND = 0x00020020L
        private const val PXC_APPSTATUS_FOREGROUND_OK = 0x00020021L
        // HU -> PHONE mirror lifecycle. These commands are notifications and are not auto-ACKed.
        private const val PXC_R2A_MIRROR_START = 0x00030020L
        private const val PXC_R2A_MIRROR_STOP = 0x00030030L

        private const val MEDIA_INIT = 0x0010
        private const val MEDIA_ACK = 0x0011
        private const val MEDIA_PING = 0x0040
        private const val MEDIA_PONG = 0x0041
        private const val MEDIA_SCREEN_CONFIG = 0x0060
        private const val MEDIA_VIEW_STATE = 0x0061
        private const val MEDIA_CHECK = 0x0070
        private const val MEDIA_RECEIVE = 0x0071
        private const val MEDIA_STREAM_POLL = 0x0072
    }

    private val executor = Executors.newCachedThreadPool()
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val clientSockets = CopyOnWriteArrayList<Socket>()
    private val running = AtomicBoolean(false)
    @Volatile var lastStartErrorMessage: String? = null
        private set

    @Volatile private var sessionCrypto: SessionCrypto? = null
    @Volatile private var sessionPhoneUuid: String = ""
    private val pxcConnectionSequence = AtomicInteger(0)
    @Volatile private var mediaPollLogged = false
    @Volatile private var negotiatedWidth = 800
    @Volatile private var negotiatedHeight = 480
    @Volatile private var secondaryPxc: SecondaryPxcLink? = null
    @Volatile private var expectedPeerAddress: InetAddress? = null

    private data class SecondaryPxcLink(
        val socket: Socket,
        val output: OutputStream,
        val writeLock: Any,
        val connectionId: Int,
        val appStatusSent: AtomicBoolean = AtomicBoolean(false)
    )

    data class Channel(val port: Int, val name: String)

    private val channels = listOf(
        Channel(10922, "PXC control"),
        Channel(10921, "Media control"),
        Channel(10920, "H264 stream")
    )

    /**
     * Restricts the three inbound EasyConn listener ports to the TFT address resolved for
     * the active session. The value lives only in RAM.
     */
    fun setExpectedPeer(address: InetAddress?) {
        expectedPeerAddress = address
        if (address != null) {
            AppLog.add("EasyConn peer atteso impostato per la sessione")
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        lastStartErrorMessage = null
        return try {
            sessionCrypto = SessionCrypto.create()
            sessionPhoneUuid = UUID.randomUUID().toString()
            pxcConnectionSequence.set(0)
            mediaPollLogged = false
            negotiatedWidth = 800
            negotiatedHeight = 480
            AdaptiveDisplayTarget.reset()
            AdaptiveViewAreaTarget.reset()
            secondaryPxc = null

            channels.forEach { channel ->
                // SO_REUSEADDR must be set BEFORE bind. Binding in the constructor and
                // enabling reuseAddress only afterwards can still leave the port unavailable;
                // a fast clean restart could therefore still fail with EADDRINUSE.
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(channel.port))
                serverSockets += server
                AppLog.add("LISTEN ${channel.port} (${channel.name})")
                executor.execute { acceptLoop(server, channel) }
            }
            AppLog.add("Sessione EasyConn pronta (heartbeat CAR_CTRL + CAR_DATA attivo)")
            true
        } catch (t: Throwable) {
            lastStartErrorMessage = t.message ?: t.javaClass.simpleName
            AppLog.add("Listener EasyConn fallito: ${lastStartErrorMessage}")
            stop()
            false
        }
    }

    private fun acceptLoop(server: ServerSocket, channel: Channel) {
        while (running.get()) {
            try {
                val client = server.accept()
                val expected = expectedPeerAddress
                if (expected == null || client.inetAddress != expected) {
                    AppLog.add("${channel.port}: connessione rifiutata da peer non autorizzato")
                    try { client.close() } catch (_: Throwable) {}
                    continue
                }
                clientSockets += client
                executor.execute {
                    try {
                        when (channel.port) {
                            10922 -> handlePxc(client)
                            10921 -> handleMediaControl(client)
                            10920 -> handleMediaStream(client)
                        }
                    } finally {
                        clientSockets.remove(client)
                        try { client.close() } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) AppLog.add("${channel.port} accept error: ${t.javaClass.simpleName}")
                return
            }
        }
    }

    private fun handlePxc(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        val connectionId = pxcConnectionSequence.incrementAndGet()
        AppLog.add("IN 10922 PXC#$connectionId control <- $remote")

        val connectionAlive = AtomicBoolean(true)
        val heartbeatRx = AtomicInteger(0)
        val heartbeatTx = AtomicInteger(0)
        val heartbeatAckRx = AtomicInteger(0)
        val proactiveHeartbeatStarted = AtomicBoolean(false)

        try {
            // PXC CAR_CTRL on the Trofeo can remain RX-idle while the phone keeps
            // transmitting the required heartbeat. A read timeout can incorrectly destroy
            // a healthy control channel. Use blocking reads:
            // EOF/reset still closes immediately, and stop() closes the socket to
            // unblock this reader deterministically.
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val writeLock = Any()
            var phoneConfigSent = false

            // Clock synchronization uses a staged CLIENT_SET completion.
            // This preserves the head-unit sequence that accepts the time update
            // while avoiding a permanent wait on units that request the ACK first.
            val clientSetOrderLock = Any()
            var pendingClientSetBody: ByteArray? = null
            var pendingClientAckSent = false
            var pendingClientSetCompleted = false
            var queryTimeSeen = false

            fun sendPxc(command: Long, body: ByteArray = ByteArray(0)) {
                synchronized(writeLock) {
                    writePxc(output, command, body)
                }
            }

            fun buildClientSetResult(body: ByteArray): ByteArray {
                val req = try { JSONObject(body.toString(Charsets.UTF_8)) } catch (_: Throwable) { JSONObject() }
                val serial = req.optString("sn", "")
                val clientSet = req.optString("client_set", "easy_conn").ifBlank { "easy_conn" }
                return JSONObject()
                    .put("isOk", true)
                    .put("errCode", 0)
                    .put("errMsg", "")
                    .put("id", serial)
                    .put("client_set", clientSet)
                    .toString().toByteArray(Charsets.UTF_8)
            }

            fun completePendingClientSet(reason: String) {
                synchronized(clientSetOrderLock) {
                    val pending = pendingClientSetBody ?: return
                    if (pendingClientSetCompleted) return
                    if (!pendingClientAckSent) {
                        sendPxc(PXC_CLIENT_OK)
                        pendingClientAckSent = true
                    }
                    sendPxc(PXC_CHECK_SN_RESULT, buildClientSetResult(pending))
                    pendingClientSetCompleted = true
                    pendingClientSetBody = null
                    AppLog.add("CLOCK: CLIENT_SET completato $reason (clockFirst=$queryTimeSeen)")
                }
            }

            fun stagePendingClientSetFallbacks() {
                executor.execute {
                    try { Thread.sleep(120L) } catch (_: InterruptedException) { return@execute }
                    if (!running.get() || !connectionAlive.get() || socket.isClosed) return@execute
                    synchronized(clientSetOrderLock) {
                        if (pendingClientSetBody != null && !pendingClientSetCompleted && !queryTimeSeen && !pendingClientAckSent) {
                            try {
                                sendPxc(PXC_CLIENT_OK)
                                pendingClientAckSent = true
                                AppLog.add("CLOCK: CLIENT_SET ACK rilasciato; completamento in attesa di QUERY_TIME")
                            } catch (_: Throwable) {}
                        }
                    }
                }

                executor.execute {
                    try { Thread.sleep(650L) } catch (_: InterruptedException) { return@execute }
                    if (!running.get() || !connectionAlive.get() || socket.isClosed) return@execute
                    try { completePendingClientSet("[fallback]") } catch (_: Throwable) {}
                }
            }

            fun startProactiveHeartbeat(channelLabel: String) {
                if (!proactiveHeartbeatStarted.compareAndSet(false, true)) return
                AppLog.add(
                    "PXC#$connectionId $channelLabel heartbeat TX attivo " +
                        "(0x70000000 ogni ${PXC_HEARTBEAT_INTERVAL_MS}ms)"
                )
                executor.execute {
                    while (running.get() && connectionAlive.get() && !socket.isClosed) {
                        try {
                            Thread.sleep(PXC_HEARTBEAT_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            return@execute
                        }
                        if (!running.get() || !connectionAlive.get() || socket.isClosed) break
                        try {
                            sendPxc(PXC_HEARTBEAT)
                            val n = heartbeatTx.incrementAndGet()
                            if (n <= 3 || n % 15 == 0) {
                                AppLog.add("PXC#$connectionId $channelLabel HEARTBEAT TX #$n")
                            }
                        } catch (t: Throwable) {
                            if (running.get() && connectionAlive.get()) {
                                AppLog.add(
                                    "PXC#$connectionId $channelLabel heartbeat TX fallito: " +
                                        t.javaClass.simpleName
                                )
                            }
                            break
                        }
                    }
                }
            }

            while (running.get() && !socket.isClosed) {
                val header = readExact(input, 16)
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = bb.int.toLong() and 0xffffffffL
                val size = bb.int.toLong() and 0xffffffffL
                val magic = bb.int.toLong() and 0xffffffffL
                bb.int // token: non contiene dati necessari alla nostra risposta

                if (size < 16 || size > 1024 * 1024) {
                    AppLog.add("PXC#$connectionId size non valida=$size")
                    return
                }
                val body = if (size > 16) readExact(input, (size - 16).toInt()) else ByteArray(0)
                val expectedMagic = size xor command
                if (magic != expectedMagic) {
                    AppLog.add("PXC#$connectionId magic mismatch cmd=${hex32(command)}")
                    return
                }

                when (command) {
                    PXC_HANDSHAKE -> {
                        sendPxc(PXC_HANDSHAKE_OK)
                        AppLog.add("PXC#$connectionId HANDSHAKE OK (CAR_CTRL)")
                        startProactiveHeartbeat("CAR_CTRL")
                    }

                    PXC_HUD_CONFIG -> {
                        val hud = parseHudConfig(body) ?: return
                        if (!phoneConfigSent) {
                            val phoneBody = buildPhoneConfig(hud)
                            sendPxc(PXC_PHONE_CONFIG, phoneBody)
                            phoneConfigSent = true
                            AppLog.add("PXC#$connectionId PHONE_CONFIG -> 0x00010011 (${phoneBody.size}B; identificativi effimeri)")
                        }
                    }

                    PXC_HU_QUERY_TIME -> {
                        val reply = buildHuQueryTimeReply()
                        sendPxc(PXC_HU_QUERY_TIME_ACK, reply)
                        synchronized(clientSetOrderLock) { queryTimeSeen = true }
                        AppLog.add("CLOCK: QUERY_TIME 0x00010450 -> 0x00010451 sul PXC#$connectionId")
                        completePendingClientSet("[after-query-time]")
                    }

                    PXC_SPEED_CONFIG -> {
                        sendPxc(PXC_SPEED_OK)
                        AppLog.add("PXC#$connectionId SPEED_CONFIG ACK")
                    }

                    PXC_CLIENT_SET -> {
                        synchronized(clientSetOrderLock) {
                            if (queryTimeSeen) {
                                sendPxc(PXC_CLIENT_OK)
                                sendPxc(PXC_CHECK_SN_RESULT, buildClientSetResult(body))
                                AppLog.add("CLOCK: CLIENT_SET completato dopo QUERY_TIME")
                            } else {
                                pendingClientSetBody = body.copyOf()
                                pendingClientAckSent = false
                                pendingClientSetCompleted = false
                                AppLog.add("CLOCK: CLIENT_SET differito in attesa di QUERY_TIME")
                                stagePendingClientSetFallbacks()
                            }
                        }
                    }

                    PXC_CHECK_SN_DONE -> {
                        AppLog.add("PXC#$connectionId CHECK_SN_DONE ricevuto")
                    }

                    PXC_SECONDARY_HELLO -> {
                        sendPxc(PXC_SECONDARY_HELLO_OK)
                        secondaryPxc = SecondaryPxcLink(socket, output, writeLock, connectionId)
                        AppLog.add("PXC#$connectionId SECONDARY HELLO 0x00020000 -> ACK (CAR_DATA)")
                        startProactiveHeartbeat("CAR_DATA")
                        sendAppStatusForegroundIfReady()
                    }

                    PXC_APPSTATUS_FOREGROUND_OK -> {
                        AppLog.add("PXC#$connectionId APPSTATUS_FOREGROUND ACK 0x00020021 ricevuto")
                    }

                    PXC_R2A_MIRROR_START -> {
                        if (body.size >= 16) {
                            val mb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                            val width = mb.int
                            val height = mb.int
                            AdaptiveDisplayTarget.update(width, height, "PXC_MIRROR_START")
                            AppLog.add(
                                "PXC#$connectionId MIRROR_START 0x00030020 inbound ${width}x${height} " +
                                    "payload=${body.size}B; nessun ACK"
                            )
                        } else {
                            AppLog.add(
                                "PXC#$connectionId MIRROR_START 0x00030020 inbound payload=${body.size}B; nessun ACK"
                            )
                        }
                    }

                    PXC_R2A_MIRROR_STOP -> {
                        AppLog.add(
                            "PXC#$connectionId MIRROR_STOP 0x00030030 inbound payload=${body.size}B; nessun ACK"
                        )
                    }

                    PXC_HEARTBEAT -> {
                        val n = heartbeatRx.incrementAndGet()
                        sendPxc(PXC_HEARTBEAT_OK)
                        if (n <= 3 || n % 10 == 0) {
                            AppLog.add("PXC#$connectionId HEARTBEAT RX #$n -> ACK")
                        }
                    }

                    PXC_HEARTBEAT_OK -> {
                        val n = heartbeatAckRx.incrementAndGet()
                        if (n <= 3 || n % 15 == 0) {
                            AppLog.add("PXC#$connectionId HEARTBEAT ACK RX #$n")
                        }
                    }

                    else -> {
                        // EasyConn usa la convenzione richiesta-even / risposta-odd.
                        if (command and 1L == 0L) {
                            sendPxc(command + 1L)
                            AppLog.add("PXC#$connectionId ${hex32(command)} payload=${body.size}B -> ACK ${hex32(command + 1L)}")
                        } else {
                            AppLog.add("PXC#$connectionId risposta non caratterizzata ${hex32(command)} payload=${body.size}B ignorata")
                        }
                    }
                }
            }
        } catch (_: EOFException) {
            AppLog.add("10922 PXC#$connectionId chiuso dalla Voge")
        } catch (t: Throwable) {
            if (running.get()) AppLog.add("10922 PXC#$connectionId errore: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        } finally {
            connectionAlive.set(false)
            val link = secondaryPxc
            if (link != null && link.socket === socket) secondaryPxc = null
            AppLog.add(
                "PXC#$connectionId fine: hbRx=${heartbeatRx.get()} " +
                    "hbTx=${heartbeatTx.get()} hbAckRx=${heartbeatAckRx.get()}"
            )
        }
    }

    /**
     * Sends the application foreground/display state once on the secondary PXC channel.
     * This command is separate from the HU-originated mirror start/stop notifications.
     */
    private fun sendAppStatusForegroundIfReady() {
        val link = secondaryPxc ?: run {
            AppLog.add("APPSTATUS_FOREGROUND differito: PXC secondario non ancora pronto")
            return
        }
        if (!link.appStatusSent.compareAndSet(false, true)) return
        if (link.socket.isClosed) {
            AppLog.add("APPSTATUS_FOREGROUND non inviato: PXC secondario già chiuso")
            return
        }

        val dm = context.resources.displayMetrics
        val displayWidth = dm.widthPixels
        val displayHeight = dm.heightPixels
        val rotation = try {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        } catch (t: Throwable) {
            AppLog.add("APPSTATUS_FOREGROUND rotation fallback: ${t.javaClass.simpleName}")
            0
        }

        val body = JSONObject()
            .put("mode", 1)
            .put("enableAOAHid", true)
            .put("displayRotation", rotation)
            .put("width", displayWidth)
            .put("height", displayHeight)
            .put("enableAccessibility", false)
            .toString()
            .toByteArray(Charsets.UTF_8)

        try {
            synchronized(link.writeLock) {
                writePxc(link.output, PXC_APPSTATUS_FOREGROUND, body)
            }
            AppLog.add(
                "APPSTATUS_FOREGROUND -> 0x00020020 JSON mode=1 rotation=$rotation " +
                    "size=${displayWidth}x${displayHeight} payload=${body.size}B"
            )
        } catch (t: Throwable) {
            AppLog.add("APPSTATUS_FOREGROUND invio fallito: ${t.javaClass.simpleName}")
        }
    }

    private data class HudTechnical(
        val pxcVersion: String,
        val huid: String
    )

    private fun parseHudConfig(body: ByteArray): HudTechnical? {
        return try {
            val json = JSONObject(body.toString(Charsets.UTF_8))
            val keys = listOf(
                "HUName", "carBrand", "carModel", "channel", "pxcVersion", "sdkVersion",
                "screenType", "supportScreenMirroring", "supportMirrorReconnect",
                "supportLandscapeAdaptive", "transportType"
            )
            val technical = keys.mapNotNull { key ->
                if (json.has(key)) "$key=${json.opt(key)}" else null
            }
            AppLog.add("HUD_CONFIG ${technical.joinToString(" | ").take(600)}")

            val huid = json.optString("HUID", "")
            if (huid.isNotEmpty()) AppLog.add("HUD_CONFIG: HUID usato solo in RAM per compatibilità crittografica")
            if (json.has("btAddress")) AppLog.add("HUD_CONFIG: btAddress ignorato (valore non letto nel log/non salvato)")
            if (huid.isEmpty()) {
                AppLog.add("HUD_CONFIG senza HUID: impossibile costruire PHONE_CONFIG")
                null
            } else {
                HudTechnical(
                    pxcVersion = json.optString("pxcVersion", "1.0.2").ifBlank { "1.0.2" },
                    huid = huid
                )
            }
        } catch (_: Throwable) {
            AppLog.add("HUD_CONFIG JSON non decodificato (${body.size}B; contenuto non mostrato)")
            null
        }
    }

    private fun buildPhoneConfig(hud: HudTechnical): ByteArray {
        val crypto = sessionCrypto ?: throw IllegalStateException("session crypto missing")
        val encryptedHuid = crypto.privateEncryptHuidBase64(hud.huid)

        // Valori volutamente generici: non leggiamo marca/modello/nome Bluetooth/Android ID.
        // I campi di compatibilità sono mantenuti invariati rispetto alla base distribuita.
        val startupClock = buildStartupClockFields()
        val json = JSONObject()
            .put("pxcVersion", hud.pxcVersion)
            .put("phoneUUID", sessionPhoneUuid)
            .put("phoneBrand", "VogeMirror")
            .put("phoneModel", "Android")
            .put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
            .put("phoneOs", "Android")
            .put("package", "net.easyconn.carman.neutral")
            .put("versionCode", 813)
            .put("token", 0)
            .put("pubkey", crypto.publicKeyBase64)
            .put("encryptedHUID", encryptedHuid)
            .put("bluetoothName", "VogeMirror")
            .put("currentHUTime", startupClock.currentHuTimeSeconds)
            .put("currentTime", startupClock.currentTimeMillis)
            .put("time", startupClock.utcTimeMillis)
            .put("currentTimeZone", startupClock.timeZoneOffsetHours)
            .put("supportH264IFrame", true)
            .put("appVersionFingerPrint", "V:8.3(813)--ONLINE")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private data class StartupClockFields(
        val currentHuTimeSeconds: Int,
        val currentTimeMillis: Long,
        val utcTimeMillis: Long,
        val timeZoneOffsetHours: Int
    )

    /**
     * Startup fields used during PHONE_CONFIG. These values intentionally keep
     * the established head-unit handshake unchanged. The actual QUERY_TIME
     * reply below carries the DST-aware local-time correction.
     */
    private fun buildStartupClockFields(): StartupClockFields {
        val now = System.currentTimeMillis()
        val rawOffset = java.util.TimeZone.getDefault().rawOffset
        return StartupClockFields(
            currentHuTimeSeconds = (now / 1000L).toInt(),
            currentTimeMillis = now,
            utcTimeMillis = now - rawOffset.toLong(),
            timeZoneOffsetHours = rawOffset / 3_600_000
        )
    }

    /**
     * Returns UTC epoch milliseconds together with the phone's active local
     * offset, including daylight-saving time. The IANA zone id allows the
     * head-unit to keep the local-time interpretation consistent across DST.
     */
    private fun buildHuQueryTimeReply(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = java.util.TimeZone.getDefault()
        val activeOffsetMillis = zone.getOffset(now).toLong()
        return JSONObject()
            .put("currentTime", now + activeOffsetMillis)
            .put("time", now)
            .put("currentTimeZone", zone.id)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun handleMediaControl(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        AppLog.add("IN 10921 Media control <- $remote")
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            while (running.get() && !socket.isClosed) {
                val h = readExact(input, 8)
                val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                val command = bb.short.toInt() and 0xffff
                val size = bb.short.toInt() and 0xffff
                bb.int // padding, intentionally ignored

                if (size > 64 * 1024) {
                    AppLog.add("10921 size non valida=$size")
                    return
                }
                val payload = if (size > 0) readExact(input, size) else ByteArray(0)

                // Read-only diagnostic/adaptation: some compatible T-Boxes expose the rider-usable
                // projection canvas as viewAreaConfig/.../safeArea. Never alter the wire reply here.
                if (AdaptiveViewAreaTarget.observe(payload, "MEDIA_0x${command.toString(16).padStart(4, '0')}")) {
                    AdaptiveViewAreaTarget.snapshot()?.let { area ->
                        // V9 keeps the clean-room geometry rule: if the dashboard explicitly exposes a
                        // rider-usable video area, treat that live area as the candidate encoder
                        // canvas before MediaProjection starts. No model-specific dimensions.
                        AdaptiveDisplayTarget.update(area.width, area.height, "VIEW_AREA:${area.source}")
                    }
                    runCatching {
                        context.startService(Intent(context, MirrorService::class.java).apply {
                            action = MirrorService.ACTION_ADAPTATION_UPDATE
                        })
                    }
                }

                when (command) {
                    MEDIA_INIT -> {
                        describeMediaCaptureRequest(payload)?.let {
                            AppLog.add("SOURCE NATIVE V15 CAPTURE_CONFIG: $it")
                        }
                        val ack = buildMediaCaptureAck(payload)
                        writeMediaControl(output, MEDIA_ACK, ack)
                        val ab = ByteBuffer.wrap(ack).order(ByteOrder.LITTLE_ENDIAN)
                        val encoder = ab.int.toLong() and 0xffffffffL
                        val width = ab.short.toInt() and 0xffff
                        val height = ab.short.toInt() and 0xffff
                        val extended = ack[8].toInt() and 0xff
                        negotiatedWidth = width
                        negotiatedHeight = height
                        AdaptiveDisplayTarget.update(width, height, "MEDIA_INIT")
                        AppLog.add("MEDIA INIT OK: encoder=$encoder ${width}x$height extended=$extended")
                    }

                    MEDIA_SCREEN_CONFIG -> {
                        AppLog.add("MEDIA SCREEN_CONFIG: payload=${payload.size}B; viewAreaLive=${AdaptiveViewAreaTarget.snapshot()?.let { "${it.width}x${it.height}" } ?: "NONE"}")
                        val viewState = JSONObject()
                            .put("viewAreaConfig", JSONObject().put("state", 0))
                            .put("supportFunction", 0)
                            .toString().toByteArray(Charsets.UTF_8)
                        writeMediaControl(output, MEDIA_VIEW_STATE, viewState)
                        AppLog.add("MEDIA SCREEN_CONFIG ACK")
                    }

                    MEDIA_CHECK -> {
                        writeMediaControl(output, MEDIA_RECEIVE, ByteArray(0))
                        AppLog.add("MEDIA CHECK OK: la Voge richiede avvio video")
                    }

                    MEDIA_PING -> writeMediaControl(output, MEDIA_PONG, ByteArray(0))

                    else -> {
                        writeMediaControl(output, (command + 1) and 0xffff, ByteArray(0))
                        AppLog.add("MEDIA cmd=0x${command.toString(16).padStart(4, '0')} -> ACK generico")
                    }
                }
            }
        } catch (_: EOFException) {
            AppLog.add("10921: Media control chiuso dalla Voge")
        } catch (t: Throwable) {
            if (running.get()) AppLog.add("10921 errore: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    private fun describeMediaCaptureRequest(request: ByteArray): String? {
        if (request.size < 4) return null
        val b = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int): String = if (request.size >= at + 2) ((b.getShort(at).toInt() and 0xffff).toString()) else "?"
        fun i32(at: Int): String = if (request.size >= at + 4) b.getInt(at).toString() else "?"
        fun u8(at: Int): String = if (request.size > at) (request[at].toInt() and 0xff).toString() else "?"
        return "size=${request.size}B device=${u16(0)}x${u16(2)} fps=${i32(4)} " +
            "encoder=${i32(8)} codec=${i32(12)} bitrate=${i32(20)} " +
            "screenMode=${u8(24)} touchMode=${u8(25)} orientation=${u8(26)} " +
            "displayId=${u8(27)} videoType=${u8(28)} extend=${u8(29)}"
    }

    private fun buildMediaCaptureAck(request: ByteArray): ByteArray {
        var encoder = 2L
        var width = 800
        var height = 384
        var extendedProtocol = 1

        if (request.size >= 4) {
            val rb = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN)
            val requestedWidth = rb.short.toInt() and 0xffff
            val requestedHeight = rb.short.toInt() and 0xffff
            val alignedWidth = requestedWidth and 0xfff0
            val alignedHeight = requestedHeight and 0xfff0
            if (alignedWidth >= 16) width = alignedWidth
            if (alignedHeight >= 16) height = alignedHeight
        }
        if (request.size >= 12) {
            val requestedEncoder = ByteBuffer.wrap(request, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            if (requestedEncoder != 0L) encoder = requestedEncoder
        }
        if (request.size >= 30) extendedProtocol = request[29].toInt() and 0xff

        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(encoder.toInt())
            putShort(width.toShort())
            putShort(height.toShort())
            put(extendedProtocol.toByte())
        }.array()
    }

    private fun writeMediaControl(out: OutputStream, command: Int, payload: ByteArray) {
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(command.toShort())
        h.putShort(payload.size.toShort())
        h.putInt(0)
        out.write(h.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun handleMediaStream(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        AppLog.add("IN 10920 H264 stream <- $remote")
        // Keep the generation in the whole function scope so the finally block
        // can disconnect only this exact H264 consumer.
        val consumerGeneration = H264FrameBus.prepareForConsumer()
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            var polls = 0L
            var framesSent = 0L
            var bytesSent = 0L
            var frameCounter = 0
            var firstFrameLogged = false
            var lastStatsAt = System.currentTimeMillis()
            val idle = byteArrayOf(0, 0, 0, 0)

            while (running.get() && !socket.isClosed) {
                val h = readExact(input, 8)
                val cmd = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                polls++

                if (cmd != MEDIA_STREAM_POLL) {
                    output.write(idle)
                    output.flush()
                    AppLog.add("H264 poll inatteso cmd=0x${cmd.toString(16).padStart(4, '0')} -> idle")
                    continue
                }

                if (!mediaPollLogged) {
                    mediaPollLogged = true
                    AppLog.add("H264 POLL 0x0072 attivo: Voge pronta a ricevere frame")
                }

                val au = H264FrameBus.nextFrameForPoll(consumerGeneration)
                if (au == null) {
                    output.write(idle)
                    output.flush()
                } else {
                    // EasyConn legacy framing: 4B totalLen + 4B frameIndex + Annex-B AU.
                    // totalLen includes the 4-byte frameIndex but not its own 4-byte length.
                    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(4 + au.size)
                        putInt(frameCounter)
                    }.array()
                    sendChunked(output, header, au)
                    framesSent++
                    bytesSent += au.size
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        AppLog.add("H264 FIRST FRAME -> Voge idx=$frameCounter ${au.size}B (Annex-B/AUD)")
                    }
                    frameCounter = (frameCounter + 1) and 0x7fffffff
                }

                val now = System.currentTimeMillis()
                if (now - lastStatsAt >= 5000) {
                    AppLog.add("H264 stream vivo: polls=$polls frame=$framesSent payload=${bytesSent / 1024}KiB | ${H264FrameBus.stats()}")
                    lastStatsAt = now
                }
            }
        } catch (_: EOFException) {
            AppLog.add("10920: H264 stream chiuso dalla Voge")
        } catch (t: Throwable) {
            if (running.get()) AppLog.add("10920 errore: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        } finally {
            if (!H264FrameBus.consumerDisconnected(consumerGeneration)) {
                AppLog.add("10920: chiusura consumer precedente ignorata (nuova generazione già attiva)")
            }
        }
    }

    private fun sendChunked(out: OutputStream, header: ByteArray, body: ByteArray) {
        val packet = ByteArray(header.size + body.size)
        System.arraycopy(header, 0, packet, 0, header.size)
        System.arraycopy(body, 0, packet, header.size, body.size)

        // Matches the known EasyConn pacing: 0x1000-byte chunks with ~3 ms spacing.
        var offset = 0
        val chunkSize = 0x1000
        while (offset < packet.size) {
            val end = minOf(packet.size, offset + chunkSize)
            out.write(packet, offset, end - offset)
            out.flush()
            offset = end
            if (offset < packet.size) Thread.sleep(3)
        }
    }

    private fun writePxc(out: OutputStream, command: Long, body: ByteArray) {
        val size = 16L + body.size
        val magic = size xor command
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command.toInt())
        bb.putInt(size.toInt())
        bb.putInt(magic.toInt())
        bb.putInt(0)
        out.write(bb.array())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.getAndSet(false)
        clientSockets.forEach { try { it.close() } catch (_: Throwable) {} }
        clientSockets.clear()
        serverSockets.forEach { try { it.close() } catch (_: Throwable) {} }
        serverSockets.clear()
        secondaryPxc = null
        expectedPeerAddress = null
        sessionCrypto = null
        sessionPhoneUuid = ""
        if (wasRunning) AppLog.add("Listener EasyConn fermati; identità effimera eliminata")
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = input.read(out, off, size - off)
            if (n < 0) throw EOFException("EOF $off/$size")
            off += n
        }
        return out
    }

    private fun hex32(value: Long): String = "0x${value.toString(16).padStart(8, '0')}"

    private class SessionCrypto private constructor(
        private val keyPair: KeyPair
    ) {
        val publicKeyBase64: String = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

        fun privateEncryptHuidBase64(huid: String): String {
            val privateKey = keyPair.private as RSAPrivateKey
            val msg = huid.toByteArray(Charsets.UTF_8)
            val k = (privateKey.modulus.bitLength() + 7) / 8
            require(msg.size <= k - 11) { "HUID troppo lungo per RSA legacy" }

            // Compatibilità legacy EasyConn: PKCS#1 v1.5 block type 1 + operazione
            // con l'esponente privato. Non è usato come nuovo schema di sicurezza.
            val em = ByteArray(k)
            em[0] = 0x00
            em[1] = 0x01
            val psLen = k - msg.size - 3
            for (i in 0 until psLen) em[2 + i] = 0xff.toByte()
            em[2 + psLen] = 0x00
            System.arraycopy(msg, 0, em, 3 + psLen, msg.size)

            val m = BigInteger(1, em)
            val c = m.modPow(privateKey.privateExponent, privateKey.modulus)
            var raw = c.toByteArray()
            if (raw.size == k + 1 && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
            if (raw.size < k) {
                val padded = ByteArray(k)
                System.arraycopy(raw, 0, padded, k - raw.size, raw.size)
                raw = padded
            }
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        }

        companion object {
            fun create(): SessionCrypto {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(1024)
                return SessionCrypto(generator.generateKeyPair())
            }
        }
    }
}
