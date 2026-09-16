package it.motolink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class BikeConnectionType {
    UNSET,
    AUTOMATIC,
    HOTSPOT_EASYCONN,
    BLE_NAV;

    companion object {
        fun parse(raw: String?): BikeConnectionType = runCatching {
            valueOf(raw?.trim()?.uppercase(Locale.US).orEmpty())
        }.getOrDefault(HOTSPOT_EASYCONN)
    }
}

enum class NavManeuver {
    UNKNOWN, STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, ROUNDABOUT
}

data class TurnByTurnInstruction(
    val maneuver: NavManeuver,
    val distanceMeters: Int?,
    val currentRoad: String = "",
    val nextRoad: String = "",
    val routeRemainDistanceMeters: Int? = null,
    val routeRemainTimeSeconds: Int? = null,
    val arrivalHour: Int? = null,
    val arrivalMinute: Int? = null,
    val roadFlag: Int = 0,
    val annularDegrees: Int = 0,
    val directionOverride: Int? = null,
    val source: String = "",
    val rawInstruction: String = ""
) {
    val hasRealDistance: Boolean get() = distanceMeters != null && distanceMeters >= 0
}

object VogeNavPacketEncoder {
    private const val CMD_HEARTBEAT = 0x5A
    private const val CMD_NAV = 0x6A
    private const val CMD_NEXT_1 = 0x6B
    private const val CMD_NEXT_2 = 0x6C
    private const val CMD_CUR_1 = 0x6D
    private const val CMD_CUR_2 = 0x6E

    fun heartbeatFrame(context: Context): ByteArray {
        val p = base(CMD_HEARTBEAT)
        p[2] = 0
        p[3] = batteryLevel(context).coerceIn(0, 255).toByte()
        p[4] = 0x32
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        p[5] = hour.toByte()
        p[6] = now.get(Calendar.MINUTE).toByte()
        p[7] = now.get(Calendar.SECOND).toByte()
        p[8] = if (hour >= 12) 1 else 0
        p[9] = 0
        p[10] = 0x02
        return checksum(p)
    }

    fun navigationFrames(model: TurnByTurnInstruction): List<ByteArray> {
        require(model.hasRealDistance) { "real maneuver distance required" }
        require(model.maneuver != NavManeuver.UNKNOWN) { "known maneuver required" }
        return listOf(
            navFrame(model),
            textFrame(CMD_NEXT_1, model.nextRoad, false),
            textFrame(CMD_NEXT_2, model.nextRoad, true),
            textFrame(CMD_CUR_1, model.currentRoad, false),
            textFrame(CMD_CUR_2, model.currentRoad, true)
        )
    }

    private fun navFrame(m: TurnByTurnInstruction): ByteArray {
        val p = base(CMD_NAV)
        putBe(p, 2, (m.distanceMeters ?: 0).coerceIn(0, 0xFFFFFF), 3)
        putBe(p, 5, (m.routeRemainTimeSeconds ?: 0).coerceIn(0, 0xFFFF), 2)
        putBe(p, 7, (m.routeRemainDistanceMeters ?: 0).coerceIn(0, 0xFFFFFF), 3)
        p[10] = (m.directionOverride ?: directionCode(m.maneuver)).coerceIn(0, 9).toByte()
        val h = m.arrivalHour?.coerceIn(0, 23) ?: 0
        val min = m.arrivalMinute?.coerceIn(0, 59) ?: 0
        p[11] = h.toByte()
        p[12] = min.toByte()
        p[13] = if (h >= 12) 1 else 0
        p[14] = m.roadFlag.coerceIn(0, 4).toByte()
        p[15] = 0 // navigation active; semantics inferred from physically validated path
        p[16] = m.annularDegrees.coerceIn(0, 255).toByte()
        p[17] = 0
        return checksum(p)
    }

    private fun textFrame(command: Int, text: String, secondChunk: Boolean): ByteArray {
        val p = base(command)
        p[2] = if (Locale.getDefault().language.equals("zh", true)) 1 else 0
        val utf = text.toByteArray(StandardCharsets.UTF_8)
        val src = if (secondChunk) 15 else 0
        val count = (utf.size - src).coerceIn(0, 15)
        if (count > 0) System.arraycopy(utf, src, p, 3, count)
        return checksum(p)
    }

    private fun directionCode(m: NavManeuver): Int = when (m) {
        NavManeuver.STRAIGHT, NavManeuver.ROUNDABOUT -> 1
        NavManeuver.UTURN_LEFT -> 2
        NavManeuver.UTURN_RIGHT -> 3
        NavManeuver.LEFT -> 4
        NavManeuver.RIGHT -> 5
        NavManeuver.SLIGHT_LEFT -> 6
        NavManeuver.SLIGHT_RIGHT -> 7
        NavManeuver.SHARP_LEFT -> 8
        NavManeuver.SHARP_RIGHT -> 9
        NavManeuver.UNKNOWN -> 0
    }

    private fun base(command: Int): ByteArray = ByteArray(20).also {
        it[0] = 0x01
        it[1] = command.toByte()
        it[19] = 0x04
    }

    private fun putBe(dest: ByteArray, offset: Int, value: Int, count: Int) {
        repeat(count) { i ->
            val shift = (count - 1 - i) * 8
            dest[offset + i] = ((value shr shift) and 0xFF).toByte()
        }
    }

    private fun checksum(p: ByteArray): ByteArray {
        p[18] = 0
        var x = 0
        p.forEach { x = x xor (it.toInt() and 0xFF) }
        p[18] = x.toByte()
        return p
    }

    private fun batteryLevel(context: Context): Int = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        i?.getIntExtra("level", 0) ?: 0
    }.getOrDefault(0)
}

/**
 * Production BLE transport for the physically validated VOGE navigation GATT service.
 * Exactly one GATT write may be in flight. Heartbeats never cut into a navigation sequence.
 */
object VogeBleNavigationManager {
    val SERVICE_UUID: UUID = UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e")
    val TX_UUID: UUID = UUID.fromString("6052202a-2928-4131-a2d0-456d5673ed2f")
    val RX_UUID: UUID = UUID.fromString("ab9938d5-c354-4e2b-94f4-364e16ebcd33")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val API33_TRANSIENT_BUSY = 201
    private const val HEARTBEAT_MS = 1000L
    private const val NAV_PACE_MS = 100L
    private const val WRITE_WATCHDOG_MS = 5000L

    interface Callback {
        fun onState(label: String) {}
        fun onReady(deviceName: String, address: String) {}
        fun onUnavailable(reason: String) {}
    }

    private enum class Kind { HEARTBEAT, NAV }
    private data class Tx(val bytes: ByteArray, val kind: Kind, val attempts: Int = 0)
    private data class Candidate(val device: BluetoothDevice, val name: String, val rssi: Int)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Tx>()
    private val candidates = LinkedHashMap<String, Candidate>()
    private var app: Context? = null
    private var callback: Callback? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var inFlight: Tx? = null
    private var heartbeatActive = false
    private var transportVerified = false
    private var wanted = false
    private var automatic = false
    private var selectedAddress: String? = null
    private var selectedName: String = "VOGE"
    private var scanGeneration = 0L
    private var writeGeneration = 0L
    private var reconnectGeneration = 0L
    private var automaticFallbackSent = false
    private var reconnectAttempt = 0
    private var writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var noResponseFallbackTried = false

    val isReady: Boolean get() = synchronized(this) { gatt != null && txChar != null && transportVerified }

    @SuppressLint("MissingPermission")
    fun start(context: Context, savedAddress: String?, auto: Boolean, cb: Callback) {
        stopInternal(notify = false)
        app = context.applicationContext
        callback = cb
        automatic = auto
        wanted = true
        automaticFallbackSent = false
        writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        noResponseFallbackTried = false
        selectedAddress = savedAddress?.takeIf { it.isNotBlank() }
        cb.onState(if (auto) "Connessione automatica…" else "Ricerca Bluetooth BLE…")
        AppLog.add("BLE NAV V1.6: START mode=${if (auto) "AUTOMATIC" else "BLE_NAV"}; service=$SERVICE_UUID")
        if (selectedAddress != null) {
            val manager = app?.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            val device = runCatching { adapter?.getRemoteDevice(selectedAddress) }.getOrNull()
            if (device != null) {
                connectDevice(device, safeName(device).ifBlank { "VOGE" })
                return
            }
        }
        startScan()
    }

    fun stop() = stopInternal(notify = true)

    fun sendNavigation(model: TurnByTurnInstruction) {
        if (!synchronized(this) { transportVerified }) {
            AppLog.add("BLE NAV V1.6: istruzione reale disponibile ma canale TFT non ancora verificato")
            return
        }
        if (!model.hasRealDistance || model.maneuver == NavManeuver.UNKNOWN) {
            AppLog.add("BLE NAV V1.6 GATE: istruzione non inviata; distanza reale/manovra affidabile assente")
            return
        }
        val frames = runCatching { VogeNavPacketEncoder.navigationFrames(model) }.getOrElse {
            AppLog.add("BLE NAV V1.6 GATE: encoder rifiuta modello ${it.javaClass.simpleName}")
            return
        }
        synchronized(this) {
            if (txChar == null || gatt == null) {
                AppLog.add("BLE NAV V1.6: istruzione reale disponibile ma TFT BLE non pronto")
                return
            }
            val keep = queue.filter { it.kind != Kind.HEARTBEAT }
            queue.clear()
            keep.forEach(queue::addLast)
            frames.forEach { queue.addLast(Tx(it, Kind.NAV)) }
        }
        AppLog.add("BLE NAV V1.6: TBT reale accodato maneuver=${model.maneuver} distance=${model.distanceMeters}m frames=5")
        main.post(::sendNext)
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val context = app ?: return
            synchronized(this@VogeBleNavigationManager) {
                if (!heartbeatActive || !wanted || txChar == null || gatt == null) return
                val navPending = inFlight?.kind == Kind.NAV || queue.any { it.kind == Kind.NAV }
                val heartbeatPending = inFlight?.kind == Kind.HEARTBEAT || queue.any { it.kind == Kind.HEARTBEAT }
                if (!navPending && !heartbeatPending && inFlight == null) {
                    queue.addLast(Tx(VogeNavPacketEncoder.heartbeatFrame(context), Kind.HEARTBEAT))
                }
            }
            sendNext()
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val context = app ?: return
        if (!hasScanPermission(context)) {
            unavailable("Permesso Bluetooth non concesso")
            return
        }
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            unavailable("Bluetooth spento o non disponibile")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            unavailable("Scanner BLE non disponibile")
            return
        }
        candidates.clear()
        val generation = ++scanGeneration
        callback?.onState("Cerco il Bluetooth navigazione della moto…")
        runCatching { scanner?.startScan(scanCallback) }.onFailure {
            unavailable("Scansione BLE non avviata")
            return
        }
        main.postDelayed({
            if (generation != scanGeneration || !wanted) return@postDelayed
            stopScan()
            val best = synchronized(this) { candidates.values.maxByOrNull { it.rssi } }
            if (best == null) {
                unavailable("Nessuna moto BLE compatibile trovata")
            } else {
                connectDevice(best.device, best.name)
            }
        }, 6500L)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanGeneration++
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val d = r.device ?: return
            val name = safeName(d).ifBlank { r.scanRecord?.deviceName.orEmpty() }
            val advertised = r.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            val nameHint = name.startsWith("BLE-VOGE", ignoreCase = true)
            if (!advertised && !nameHint) return
            val address = safeAddress(d)
            if (address.isBlank()) return
            synchronized(this@VogeBleNavigationManager) {
                val old = candidates[address]
                if (old == null || r.rssi > old.rssi) candidates[address] = Candidate(d, name.ifBlank { "VOGE BLE" }, r.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            unavailable("Scansione BLE fallita ($errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice, name: String) {
        val context = app ?: return
        if (!hasConnectPermission(context)) {
            unavailable("Permesso connessione Bluetooth non concesso")
            return
        }
        stopScan()
        closeGattOnly()
        selectedAddress = safeAddress(device)
        selectedName = name.ifBlank { "VOGE BLE" }
        callback?.onState("Connessione Bluetooth alla moto…")
        AppLog.add("BLE NAV V1.6: connectGatt a dispositivo compatibile; indirizzo non scritto nel Log")
        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, false, gattCallback)
        }.getOrElse {
            unavailable("Connessione BLE non avviata")
            null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (synchronized(this@VogeBleNavigationManager) { gatt !== g }) {
                AppLog.add("BLE NAV V1.6: callback GATT obsoleto ignorato status=$status state=$newState")
                runCatching { g.close() }
                return
            }
            AppLog.add("BLE NAV V1.6: connection state status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                callback?.onState("Bluetooth connesso, verifico compatibilità…")
                runCatching { g.discoverServices() }.onFailure { recoverOrFallback("Service discovery non avviata") }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(this@VogeBleNavigationManager) {
                    if (gatt === g) {
                        txChar = null
                        rxChar = null
                        inFlight = null
                        queue.clear()
                        heartbeatActive = false
                        main.removeCallbacks(heartbeat)
                    }
                }
                if (wanted) scheduleReconnect("BLE disconnesso status=$status", forceFreshScan = status != BluetoothGatt.GATT_SUCCESS)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recoverOrFallback("Service discovery fallita ($status)")
                return
            }
            val service: BluetoothGattService = g.getService(SERVICE_UUID) ?: run {
                recoverOrFallback("Servizio navigazione VOGE non presente")
                return
            }
            val tx = service.getCharacteristic(TX_UUID) ?: run {
                recoverOrFallback("Canale TX navigazione VOGE non presente")
                return
            }
            val rx = service.getCharacteristic(RX_UUID) ?: run {
                recoverOrFallback("Canale RX navigazione VOGE non presente")
                return
            }
            synchronized(this@VogeBleNavigationManager) {
                txChar = tx
                rxChar = rx
            }
            val supportsWrite = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val supportsNoResponse = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            AppLog.add("BLE NAV V1.6: TX properties=0x${tx.properties.toString(16)} write=$supportsWrite noResponse=$supportsNoResponse mode=DEFAULT")
            val localNotify = runCatching { g.setCharacteristicNotification(rx, true) }.getOrDefault(false)
            val descriptor = rx.getDescriptor(CCCD)
            if (localNotify && descriptor != null) {
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val accepted = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, value) == 0 else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (!accepted) armInitialHeartbeatProbe("CCCD write non accettata; verifico TX")
            } else {
                armInitialHeartbeatProbe("RX notify non disponibile; verifico TX")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) {
                AppLog.add("BLE NAV V1.6: notify CCCD status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    armInitialHeartbeatProbe("CCCD confermato")
                } else {
                    recoverOrFallback("Abilitazione notifiche BLE fallita ($status)")
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val sent = synchronized(this@VogeBleNavigationManager) {
                val s = inFlight
                inFlight = null
                writeGeneration++
                s
            }
            if (sent == null) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                var becameReady = false
                if (sent.kind == Kind.HEARTBEAT) {
                    synchronized(this@VogeBleNavigationManager) {
                        if (!transportVerified && wanted && gatt != null && txChar != null) {
                            transportVerified = true
                            heartbeatActive = true
                            reconnectAttempt = 0
                            becameReady = true
                        }
                    }
                }
                if (becameReady) {
                    val address = selectedAddress.orEmpty()
                    callback?.onState("Bluetooth navigazione connesso")
                    callback?.onReady(selectedName, address)
                    AppLog.add("BLE NAV V1.6: READY verificato dopo ACK heartbeat 0x5A status=0")
                    main.removeCallbacks(heartbeat)
                    main.postDelayed(heartbeat, 1000L)
                }
                main.postDelayed(::sendNext, if (sent.kind == Kind.NAV) NAV_PACE_MS else 0L)
            } else if (status == 3 && sent.kind == Kind.HEARTBEAT && !transportVerified && tryEnableNoResponseFallback()) {
                AppLog.add("BLE NAV V1.6 VC17: heartbeat DEFAULT rifiutato status=3; ritento una volta con WRITE_TYPE_NO_RESPONSE")
                synchronized(this@VogeBleNavigationManager) { queue.addFirst(sent.copy(attempts = sent.attempts + 1)) }
                main.postDelayed(::sendNext, 180L)
            } else if (status == 3) {
                failWithoutReconnect("Il TFT ha rifiutato la scrittura BLE (status 3)")
            } else {
                AppLog.add("BLE NAV V1.6: write callback status=$status; recupero connessione senza sovrapporre scritture")
                scheduleReconnect("Write GATT fallita ($status)")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == RX_UUID) {
                val command = characteristic.value?.getOrNull(1)?.toInt()?.and(0xFF)
                if (command == 0x4A || command == 0x4B) {
                    // Expected live TFT notifications. Intentionally no payload logging (privacy/minimal log).
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Android 13+ overload. Notifications prove the RX path is alive; payload is not needed for TBT.
        }
    }

    @Synchronized
    private fun armInitialHeartbeatProbe(reason: String) {
        if (!wanted || gatt == null || txChar == null || transportVerified) return
        heartbeatActive = false
        main.removeCallbacks(heartbeat)
        callback?.onState("Bluetooth collegato, verifico canale navigazione…")
        AppLog.add("BLE NAV V1.6: GATT preparato ($reason); attendo 750 ms prima del heartbeat 0x5A di verifica")
        main.postDelayed({
            val context = app ?: return@postDelayed
            synchronized(this) {
                if (!wanted || transportVerified || gatt == null || txChar == null || inFlight != null) return@postDelayed
                val navPending = queue.any { it.kind == Kind.NAV }
                val heartbeatPending = queue.any { it.kind == Kind.HEARTBEAT }
                if (!navPending && !heartbeatPending) {
                    queue.addLast(Tx(VogeNavPacketEncoder.heartbeatFrame(context), Kind.HEARTBEAT))
                }
            }
            sendNext()
        }, 750L)
    }

    @SuppressLint("MissingPermission")
    private fun sendNext() {
        val context = app ?: return
        if (!hasConnectPermission(context)) return
        val localGatt: BluetoothGatt
        val localChar: BluetoothGattCharacteristic
        val tx: Tx
        synchronized(this) {
            if (!wanted || inFlight != null || queue.isEmpty()) return
            localGatt = gatt ?: return
            localChar = txChar ?: return
            tx = queue.removeFirst()
        }
        val writeType = synchronized(this) { writeTypeMode }
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                localGatt.writeCharacteristic(localChar, tx.bytes, writeType)
            } else {
                @Suppress("DEPRECATION")
                localChar.writeType = writeType
                @Suppress("DEPRECATION")
                localChar.value = tx.bytes
                @Suppress("DEPRECATION")
                if (localGatt.writeCharacteristic(localChar)) 0 else API33_TRANSIENT_BUSY
            }
        }.getOrDefault(-1)

        if (result == 0) {
            val generation: Long
            synchronized(this) {
                inFlight = tx
                generation = ++writeGeneration
            }
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // A Write Command has no ATT response by design. Some Android stacks still emit
                // onCharacteristicWrite, others do not. Keep the one-write-in-flight invariant,
                // but release it locally after a short settle window if no callback arrived.
                main.postDelayed({
                    var becameReady = false
                    synchronized(this) {
                        if (generation != writeGeneration || inFlight == null || !wanted) return@postDelayed
                        inFlight = null
                        writeGeneration++
                        if (tx.kind == Kind.HEARTBEAT && !transportVerified) {
                            transportVerified = true
                            heartbeatActive = true
                            reconnectAttempt = 0
                            becameReady = true
                        }
                    }
                    if (becameReady) {
                        val address = selectedAddress.orEmpty()
                        callback?.onState("Bluetooth navigazione connesso")
                        callback?.onReady(selectedName, address)
                        AppLog.add("BLE NAV V1.6 VC17: READY con WRITE_TYPE_NO_RESPONSE; nessun ACK ATT richiesto")
                        main.removeCallbacks(heartbeat)
                        main.postDelayed(heartbeat, 1000L)
                    }
                    main.postDelayed(::sendNext, if (tx.kind == Kind.NAV) NAV_PACE_MS else 0L)
                }, 180L)
            } else {
                main.postDelayed({
                    synchronized(this) {
                        if (generation != writeGeneration || inFlight == null || !wanted) return@postDelayed
                    }
                    AppLog.add("BLE NAV V1.6: callback write assente; riconnetto senza liberare artificialmente writeInFlight")
                    scheduleReconnect("Timeout callback GATT", forceFreshScan = true, minDelayMs = 6000L)
                }, WRITE_WATCHDOG_MS)
            }
            return
        }

        if (result == API33_TRANSIENT_BUSY && tx.attempts < 5) {
            synchronized(this) { queue.addFirst(tx.copy(attempts = tx.attempts + 1)) }
            AppLog.add("BLE NAV V1.6: write busy 201 gestito in coda, retry=${tx.attempts + 1}")
            main.postDelayed(::sendNext, 160L)
        } else {
            AppLog.add("BLE NAV V1.6: write non accettata result=$result; nessuna write concorrente")
            scheduleReconnect("Write non accettata ($result)")
        }
    }

    @Synchronized
    private fun tryEnableNoResponseFallback(): Boolean {
        if (noResponseFallbackTried || writeTypeMode != BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) return false
        val tx = txChar ?: return false
        val supported = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        noResponseFallbackTried = true
        if (!supported) {
            AppLog.add("BLE NAV V1.6 VC17: status=3 ma TX non dichiara WRITE_NO_RESPONSE; fallback non disponibile")
            return false
        }
        writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        AppLog.add("BLE NAV V1.6 VC17: fallback trasporto attivato WRITE_TYPE_NO_RESPONSE")
        return true
    }

    private fun failWithoutReconnect(reason: String) {
        if (!wanted) return
        AppLog.add("BLE NAV V1.6: $reason; sessione fermata senza loop di riconnessione")
        callback?.onUnavailable(reason)
        stopInternal(notify = false)
    }

    private fun scheduleReconnect(reason: String, forceFreshScan: Boolean = false, minDelayMs: Long = 0L) {
        if (!wanted) return
        callback?.onState("Riconnessione Bluetooth…")
        val attempt = synchronized(this) {
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
            reconnectAttempt
        }
        val backoffMs = when (attempt) {
            1 -> 2500L
            2 -> 4000L
            3 -> 6000L
            else -> 10000L
        }
        val delayMs = maxOf(minDelayMs, backoffMs)
        AppLog.add("BLE NAV V1.6: $reason; recovery attempt=$attempt delay=${delayMs}ms freshScan=$forceFreshScan")
        synchronized(this) {
            heartbeatActive = false
            transportVerified = false
            main.removeCallbacks(heartbeat)
            queue.clear()
            inFlight = null
            writeGeneration++
            txChar = null
            rxChar = null
        }
        closeGattOnly()
        val generation = ++reconnectGeneration
        main.postDelayed({
            if (!wanted || generation != reconnectGeneration) return@postDelayed
            val context = app ?: return@postDelayed

            // A lost write callback or a failed connection can leave the Android/TFT
            // link state stale for a few seconds. Re-scanning reacquires a currently
            // advertising device instead of starting another 30 s connect on a stale
            // cached path. From the second consecutive recovery attempt we always scan.
            if (forceFreshScan || attempt >= 2) {
                AppLog.add("BLE NAV V1.6: recovery -> nuova scansione BLE prima di connectGatt")
                if (automatic && !automaticFallbackSent && attempt >= 4) unavailable(reason) else startScan()
                return@postDelayed
            }

            val address = selectedAddress
            if (!address.isNullOrBlank() && hasConnectPermission(context)) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
                if (device != null) {
                    connectDevice(device, selectedName)
                    return@postDelayed
                }
            }
            if (automatic && !automaticFallbackSent) unavailable(reason) else startScan()
        }, delayMs)
    }

    private fun recoverOrFallback(reason: String) {
        if (automatic && !automaticFallbackSent) unavailable(reason) else scheduleReconnect(reason)
    }

    private fun unavailable(reason: String) {
        if (!wanted) return
        if (automatic) automaticFallbackSent = true
        callback?.onUnavailable(reason)
        AppLog.add("BLE NAV V1.6: unavailable=$reason")
        if (automatic) stopInternal(notify = false)
    }

    @SuppressLint("MissingPermission")
    private fun closeGattOnly() {
        val old = gatt
        gatt = null
        if (old != null) {
            runCatching { old.disconnect() }
            runCatching { old.close() }
        }
    }

    private fun stopInternal(notify: Boolean) {
        wanted = false
        reconnectGeneration++
        reconnectAttempt = 0
        scanGeneration++
        writeGeneration++
        heartbeatActive = false
        transportVerified = false
        main.removeCallbacks(heartbeat)
        stopScan()
        synchronized(this) {
            queue.clear()
            inFlight = null
            txChar = null
            rxChar = null
        }
        closeGattOnly()
        if (notify) callback?.onState("Bluetooth navigazione fermato")
        callback = null
        app = null
    }

    private fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String = runCatching { device?.name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun safeAddress(device: BluetoothDevice?): String = runCatching { device?.address.orEmpty() }.getOrDefault("")
}

object GoogleMapsSourceAdapter {
    private val distanceRegex = Regex("(?i)(?:\\btra\\s+|\\bfra\\s+|\\bin\\s+|\\bafter\\s+)?(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi|ft)\\b")
    private val etaRegex = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b")
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val ICON_SAMPLE = 48
    private var lastUnknownIconFingerprint = ""
    private var lastUnknownIconLogAt = 0L

    private data class MapsVisualData(
        val textLines: List<String>,
        val maneuverIcon: Bitmap?,
    )

    private data class RouteSummary(
        val distanceMeters: Int,
        val timeSeconds: Int?,
        val arrivalHour: Int?,
        val arrivalMinute: Int?,
    )

    fun parse(context: Context, notification: Notification): TurnByTurnInstruction? {
        if (notification.category != null && notification.category != "navigation") return null
        val e = notification.extras ?: return null
        val title = clean(e.getCharSequence(Notification.EXTRA_TITLE))
        if (title.isBlank()) return null
        val text = clean(e.getCharSequence(Notification.EXTRA_TEXT))
        val big = clean(e.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val sub = clean(e.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val info = clean(e.getCharSequence(Notification.EXTRA_INFO_TEXT))
        val summary = clean(e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        val ticker = clean(notification.tickerText)

        // Recent Maps versions can expose distance/street as text while the maneuver itself is
        // a graphic. First inspect every text surface, including RemoteViews; then use the
        // notification maneuver glyph only if no textual maneuver can be classified.
        val visual = readVisualData(context, notification)
        val instructionFields = LinkedHashSet<String>().apply {
            listOf(title, text, big, sub, info, summary, ticker).filterTo(this) { it.isNotBlank() }
            visual.textLines.filterTo(this) { it.isNotBlank() }
        }.toList()

        // Google Maps normally exposes a route summary such as
        // "23 min · 17 km · 18:42" separately from the distance to the next maneuver.
        // VOGE byte 7..9 is the whole-route remaining distance, not the turn distance.
        val routeSummary = instructionFields.asSequence().mapNotNull(::routeSummaryFrom).firstOrNull()
        val distance = instructionFields.asSequence()
            .filter { routeSummaryFrom(it) == null }
            .mapNotNull(::explicitDistanceMeters)
            .firstOrNull()
        val textManeuver = instructionFields.asSequence()
            .map(::maneuverFrom)
            .firstOrNull { it != NavManeuver.UNKNOWN }
        val roundaboutEncoding = visual.maneuverIcon?.let {
            V16ManeuverClassifier.classifyRoundaboutEncoding(context, it)
        }
        val roundaboutSector = roundaboutEncoding?.annularDegrees
        val maneuver = textManeuver
            ?: if (roundaboutEncoding != null) NavManeuver.ROUNDABOUT else maneuverFromIcon(visual.maneuverIcon)
        if (maneuver == NavManeuver.UNKNOWN && distance != null && visual.maneuverIcon != null) {
            maybeLogUnknownManeuverIcon(visual.maneuverIcon, distance)
        }
        val sourceKind = when {
            textManeuver != null -> "TEXT"
            roundaboutSector != null -> "ML_ICON"
            maneuver != NavManeuver.UNKNOWN -> "ICON"
            else -> "NONE"
        }
        if (maneuver == NavManeuver.ROUNDABOUT && roundaboutSector == null) {
            AppLog.add("MAPS NAV V1.6 ROUNDABOUT: flag OEM corretto ma settore uscita non classificato; annular=0")
        }
        val eta = routeSummary?.let { summary ->
            summary.arrivalHour?.let { h -> h to (summary.arrivalMinute ?: 0) }
        } ?: instructionFields.asSequence().mapNotNull { line ->
            etaRegex.find(line)?.let { m ->
                m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }
            }
        }.firstOrNull()

        return TurnByTurnInstruction(
            maneuver = maneuver,
            distanceMeters = distance,
            routeRemainDistanceMeters = routeSummary?.distanceMeters,
            routeRemainTimeSeconds = routeSummary?.timeSeconds,
            arrivalHour = eta?.first,
            arrivalMinute = eta?.second,
            roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0,
            annularDegrees = if (maneuver == NavManeuver.ROUNDABOUT) (roundaboutSector ?: 0) else 0,
            directionOverride = if (maneuver == NavManeuver.ROUNDABOUT) roundaboutEncoding?.directionCode else null,
            source = "GOOGLE_MAPS_NOTIFICATION_$sourceKind",
            rawInstruction = title
        )
    }

    private fun routeSummaryFrom(text: String): RouteSummary? {
        val parts = text.split(Regex("\\s*[·•]\\s*")).map(String::trim).filter(String::isNotBlank)
        if (parts.size < 3) return null

        // Garminuino and other Maps-notification parsers observe the stable order
        // duration · remaining distance · ETA. Require both a distance and either time
        // or ETA so an ordinary maneuver sentence containing bullets cannot be mistaken.
        val distance = explicitDistanceMeters(parts[1]) ?: return null
        val timeSeconds = durationSeconds(parts[0])
        val eta = etaRegex.find(parts[2])?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { h -> h to (m.groupValues[2].toIntOrNull() ?: 0) }
        }
        if (timeSeconds == null && eta == null) return null
        return RouteSummary(
            distanceMeters = distance,
            timeSeconds = timeSeconds,
            arrivalHour = eta?.first,
            arrivalMinute = eta?.second,
        )
    }

    private fun durationSeconds(text: String): Int? {
        val hours = Regex("(?i)(\\d+)\\s*(?:h|hr|hrs|ora|ore|hour|hours)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minutes = Regex("(?i)(\\d+)\\s*(?:min|minuto|minuti|minute|minutes)\\b")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (hours == null && minutes == null) return null
        val seconds = (hours ?: 0) * 3600L + (minutes ?: 0) * 60L
        return seconds.coerceIn(0L, 0xFFFFL).toInt()
    }

    private fun explicitDistanceMeters(text: String): Int? {
        val m = distanceRegex.find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (value < 0) return null
        return when (m.groupValues[2].lowercase(Locale.US)) {
            "m" -> value.roundToInt()
            "km" -> (value * 1000.0).roundToInt()
            "mi" -> (value * 1609.344).roundToInt()
            "ft" -> (value * 0.3048).roundToInt()
            else -> null
        }?.coerceIn(0, 0xFFFFFF)
    }

    private fun maneuverFrom(text: String): NavManeuver {
        val s = text.lowercase(Locale.getDefault())
        return when {
            listOf("inversione a u a sinistra", "inversione a sinistra", "u-turn left", "uturn left", "u turn left", "⤺", "↶").any(s::contains) -> NavManeuver.UTURN_LEFT
            listOf("inversione a u a destra", "inversione a destra", "u-turn right", "uturn right", "u turn right", "⤴", "↷").any(s::contains) -> NavManeuver.UTURN_RIGHT
            listOf("rotatoria", "rotonda", "roundabout", "rotary").any(s::contains) -> NavManeuver.ROUNDABOUT
            listOf("svolta bruscamente a sinistra", "gira bruscamente a sinistra", "sharp left", "hard left").any(s::contains) -> NavManeuver.SHARP_LEFT
            listOf("svolta bruscamente a destra", "gira bruscamente a destra", "sharp right", "hard right").any(s::contains) -> NavManeuver.SHARP_RIGHT
            listOf("leggermente a sinistra", "slight left", "mantieni la sinistra", "tieni la sinistra", "keep left", "stay left", "↖", "↙").any(s::contains) -> NavManeuver.SLIGHT_LEFT
            listOf("leggermente a destra", "slight right", "mantieni la destra", "tieni la destra", "keep right", "stay right", "↗", "↘").any(s::contains) -> NavManeuver.SLIGHT_RIGHT
            listOf("svolta a sinistra", "gira a sinistra", "turn left", "a sinistra", "←", "↰").any(s::contains) -> NavManeuver.LEFT
            listOf("svolta a destra", "gira a destra", "turn right", "a destra", "→", "↱").any(s::contains) -> NavManeuver.RIGHT
            listOf("prosegui dritto", "continua dritto", "vai dritto", "continue straight", "go straight", "prosegui", "continua su", "↑").any(s::contains) -> NavManeuver.STRAIGHT
            else -> NavManeuver.UNKNOWN
        }
    }

    private fun readVisualData(context: Context, notification: Notification): MapsVisualData {
        val lines = LinkedHashSet<String>()
        var maneuverIcon: Bitmap? = null

        // Inspect Maps' RemoteViews first: this is where the maneuver glyph is normally
        // rendered. Failure is contained and falls back to largeIcon/extras.
        runCatching {
            val mapsContext = context.createPackageContext(MAPS_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            val builder = Notification.Builder.recoverBuilder(context, notification)
            val remote = builder.createBigContentView() ?: builder.createContentView()
            if (remote != null) {
                val root = remote.apply(mapsContext, null)
                val walked = walkNotificationView(mapsContext, root, lines)
                if (maneuverIcon == null) maneuverIcon = walked
            }
        }

        // Layout-independent fallback used by several Maps versions for the same turn glyph.
        if (maneuverIcon == null) {
            maneuverIcon = runCatching {
                notification.getLargeIcon()?.loadDrawable(context)?.let(::drawableToBitmap)
            }.getOrNull()
        }

        return MapsVisualData(lines.toList(), maneuverIcon)
    }

    private fun walkNotificationView(context: Context, view: View, lines: MutableSet<String>): Bitmap? {
        var foundIcon: Bitmap? = null
        when (view) {
            is TextView -> clean(view.text).takeIf { it.isNotBlank() }?.let(lines::add)
            is ImageView -> {
                val entryName = runCatching {
                    if (view.id > 0) context.resources.getResourceEntryName(view.id) else ""
                }.getOrDefault("")
                if (entryName in setOf("nav_notification_icon", "right_icon", "lockscreen_notification_icon")) {
                    foundIcon = view.drawable?.let(::drawableToBitmap)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val childIcon = walkNotificationView(context, view.getChildAt(i), lines)
                if (foundIcon == null && childIcon != null) foundIcon = childIcon
            }
        }
        return foundIcon
    }

    /**
     * Conservative visual fallback for the Maps maneuver glyph. Ambiguous icons remain
     * UNKNOWN rather than sending a guessed command. Text remains preferred for roundabouts,
     * U-turns and exact maneuver semantics.
     */
    private fun maneuverFromIcon(bitmap: Bitmap?): NavManeuver {
        if (bitmap == null) return NavManeuver.UNKNOWN
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, ICON_SAMPLE, ICON_SAMPLE, true) }.getOrNull()
            ?: return NavManeuver.UNKNOWN
        return try {
            val bg = scaled.getPixel(0, 0)
            if (looksLikeRoundaboutGlyph(scaled, bg)) return NavManeuver.ROUNDABOUT
            val full = measureInk(scaled, bg, ICON_SAMPLE)
            val top = measureInk(scaled, bg, (ICON_SAMPLE * 0.45).roundToInt())
            val fullTotal = full.first + full.second
            val topTotal = top.first + top.second
            if (fullTotal < 20 || topTotal < 8) return NavManeuver.UNKNOWN

            val fullRatio = dominance(full.first, full.second)
            val topRatio = dominance(top.first, top.second)
            if (fullRatio < 1.05 && topRatio < 1.35) return NavManeuver.STRAIGHT

            // A real turn needs a visible arrowhead imbalance; otherwise do not guess.
            if (topRatio < 1.18) return NavManeuver.UNKNOWN
            val left = top.first > top.second
            when {
                fullRatio >= 1.40 || topRatio >= 3.00 -> if (left) NavManeuver.SHARP_LEFT else NavManeuver.SHARP_RIGHT
                fullRatio >= 1.12 || topRatio >= 1.80 -> if (left) NavManeuver.LEFT else NavManeuver.RIGHT
                else -> if (left) NavManeuver.SLIGHT_LEFT else NavManeuver.SLIGHT_RIGHT
            }
        } catch (_: Throwable) {
            NavManeuver.UNKNOWN
        } finally {
            if (scaled !== bitmap) runCatching { scaled.recycle() }
        }
    }

    /**
     * Conservative roundabout detector for Maps' rendered notification glyph.
     * A roundabout icon contains a closed loop near the centre; ordinary turn,
     * slight-turn and U-turn arrows do not. We dilate by one pixel to tolerate
     * antialiasing gaps, flood-fill outside background, then look for a central
     * enclosed background component. If the evidence is weak, return false.
     */
    private fun looksLikeRoundaboutGlyph(bitmap: Bitmap, bg: Int): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return false
        val ink = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) ink[y][x] = isInk(bitmap.getPixel(x, y), bg)
        }

        // One-pixel dilation closes tiny antialiasing gaps without turning a normal
        // open arrow into a large central enclosed area.
        val solid = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!ink[y][x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) solid[ny][nx] = true
                }
            }
        }

        val outside = Array(h) { BooleanArray(w) }
        val q = java.util.ArrayDeque<Int>()
        fun push(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h || solid[y][x] || outside[y][x]) return
            outside[y][x] = true
            q.addLast(y * w + x)
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            val x = v % w
            val y = v / w
            push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
        }

        val visited = Array(h) { BooleanArray(w) }
        var bestArea = 0
        var bestCx = 0.0
        var bestCy = 0.0
        for (sy in 1 until h - 1) {
            for (sx in 1 until w - 1) {
                if (solid[sy][sx] || outside[sy][sx] || visited[sy][sx]) continue
                var area = 0
                var sumX = 0L
                var sumY = 0L
                val holes = java.util.ArrayDeque<Int>()
                visited[sy][sx] = true
                holes.addLast(sy * w + sx)
                while (holes.isNotEmpty()) {
                    val v = holes.removeFirst()
                    val x = v % w
                    val y = v / w
                    area++
                    sumX += x
                    sumY += y
                    fun holePush(nx: Int, ny: Int) {
                        if (nx !in 1 until w - 1 || ny !in 1 until h - 1) return
                        if (solid[ny][nx] || outside[ny][nx] || visited[ny][nx]) return
                        visited[ny][nx] = true
                        holes.addLast(ny * w + nx)
                    }
                    holePush(x - 1, y); holePush(x + 1, y); holePush(x, y - 1); holePush(x, y + 1)
                }
                if (area > bestArea) {
                    bestArea = area
                    bestCx = sumX.toDouble() / area.coerceAtLeast(1)
                    bestCy = sumY.toDouble() / area.coerceAtLeast(1)
                }
            }
        }

        if (bestArea !in 18..260) return false
        val dx = kotlin.math.abs(bestCx - (w - 1) / 2.0)
        val dy = kotlin.math.abs(bestCy - (h - 1) / 2.0)
        if (dx > w * 0.18 || dy > h * 0.18) return false

        // Avoid treating a tiny circular badge as a maneuver. A real roundabout
        // glyph needs substantial ink around the central enclosed area.
        val full = measureInk(bitmap, bg, h)
        return (full.first + full.second) >= 90
    }

    @Synchronized
    private fun maybeLogUnknownManeuverIcon(bitmap: Bitmap, distanceMeters: Int) {
        val fp = iconFingerprint(bitmap) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (fp == lastUnknownIconFingerprint && now - lastUnknownIconLogAt < 15_000L) return
        lastUnknownIconFingerprint = fp
        lastUnknownIconLogAt = now
        AppLog.add("MAPS NAV V1.6 ICON DIAG: manovra non classificata fp=$fp distance=${distanceMeters}m")
    }

    /** 8x8 binary fingerprint of the maneuver glyph only; no route text/image is stored. */
    private fun iconFingerprint(bitmap: Bitmap): String? {
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, ICON_SAMPLE, ICON_SAMPLE, true) }.getOrNull()
            ?: return null
        return try {
            val bg = scaled.getPixel(0, 0)
            var bits = 0UL
            val cell = ICON_SAMPLE / 8
            for (gy in 0 until 8) {
                for (gx in 0 until 8) {
                    var inkCount = 0
                    var total = 0
                    for (y in gy * cell until (gy + 1) * cell) {
                        for (x in gx * cell until (gx + 1) * cell) {
                            total++
                            if (isInk(scaled.getPixel(x, y), bg)) inkCount++
                        }
                    }
                    if (inkCount * 5 >= total) bits = bits or (1UL shl (gy * 8 + gx))
                }
            }
            bits.toString(16).padStart(16, '0')
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled !== bitmap) runCatching { scaled.recycle() }
        }
    }

    private fun measureInk(bitmap: Bitmap, bg: Int, endY: Int): Pair<Long, Long> {
        var left = 0L
        var right = 0L
        val half = bitmap.width / 2
        for (y in 0 until endY.coerceIn(1, bitmap.height)) {
            for (x in 0 until bitmap.width) {
                if (!isInk(bitmap.getPixel(x, y), bg)) continue
                if (x < half) left++ else right++
            }
        }
        return left to right
    }

    private fun isInk(px: Int, bg: Int): Boolean {
        if (Color.alpha(px) < 24) return false
        val da = Color.alpha(px) - Color.alpha(bg)
        val dr = Color.red(px) - Color.red(bg)
        val dg = Color.green(px) - Color.green(bg)
        val db = Color.blue(px) - Color.blue(bg)
        return (da * da + dr * dr + dg * dg + db * db) > 4096
    }

    private fun dominance(a: Long, b: Long): Double =
        maxOf(a, b).toDouble() / minOf(a, b).coerceAtLeast(1).toDouble()

    private fun drawableToBitmap(drawable: Drawable): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(ICON_SAMPLE, ICON_SAMPLE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        bitmap
    }.getOrNull()

    private fun clean(value: CharSequence?): String = value?.toString()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
}

class MapsNavigationListenerService : NotificationListenerService() {
    companion object { private const val MAPS_PACKAGE = "com.google.android.apps.maps" }
    private var lastFingerprint: String? = null
    private var lastRouteRemainDistanceMeters: Int? = null
    private var lastRouteRemainTimeSeconds: Int? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.add("MAPS NAV V1.6: accesso notifiche connesso")
        runCatching { activeNotifications?.filter { it.packageName == MAPS_PACKAGE }?.forEach(::handle) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MAPS_PACKAGE) return
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MAPS_PACKAGE) AppLog.add("MAPS NAV V1.6: navigazione/notifica Maps rimossa")
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val parsed = GoogleMapsSourceAdapter.parse(this, n) ?: return
        parsed.routeRemainDistanceMeters?.takeIf { it > 0 }?.let { lastRouteRemainDistanceMeters = it }
        parsed.routeRemainTimeSeconds?.takeIf { it > 0 }?.let { lastRouteRemainTimeSeconds = it }
        val model = parsed.copy(
            routeRemainDistanceMeters = parsed.routeRemainDistanceMeters ?: lastRouteRemainDistanceMeters,
            routeRemainTimeSeconds = parsed.routeRemainTimeSeconds ?: lastRouteRemainTimeSeconds,
        )
        val fingerprint = "${model.rawInstruction}|${model.maneuver}|${model.distanceMeters}|${model.routeRemainDistanceMeters}|${model.routeRemainTimeSeconds}|${model.arrivalHour}:${model.arrivalMinute}"
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint
        if (!model.hasRealDistance) {
            AppLog.add("MAPS NAV V1.6 GATE: istruzione ricevuta ma distanza reale alla manovra non esposta; TFT non aggiornato")
            return
        }
        if (model.distanceMeters == 0) {
            AppLog.add("MAPS NAV V1.6 ZERO TRANSITION GUARD: distance=0 soppressa; attendo la prossima istruzione positiva per evitare stale 0 sul TFT")
            return
        }
        if (model.maneuver == NavManeuver.UNKNOWN) {
            AppLog.add("MAPS NAV V1.6 GATE: distanza reale presente ma manovra non classificabile; TFT non aggiornato")
            return
        }
        AppLog.add("MAPS NAV V1.6: TBT reale accettato source=${model.source} maneuver=${model.maneuver} distance=${model.distanceMeters}m routeRemain=${model.routeRemainDistanceMeters ?: -1}m routeTime=${model.routeRemainTimeSeconds ?: -1}s roadFlag=${model.roadFlag} annular=${model.annularDegrees} dir=${model.directionOverride ?: -1}")
        VogeBleNavigationManager.sendNavigation(model)
    }
}
