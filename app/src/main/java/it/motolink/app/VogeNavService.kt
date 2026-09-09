package it.motolink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

class VogeNavService : Service() {
    companion object {
        const val ACTION_START = "it.motolink.app.V16_VOGE_NAV_START"
        const val ACTION_STOP = "it.motolink.app.V16_VOGE_NAV_STOP"
        private const val EXTRA_ADDRESS = "v16_ble_address"
        private const val EXTRA_NAME = "v16_ble_name"
        private const val CHANNEL_ID = "motolink_v16_ble_nav"
        private const val NOTIFICATION_ID = 1601
        private const val HEARTBEAT_INTERVAL_MS = 1_000L
        private const val FRAME_GAP_MS = 100L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile private var instance: VogeNavService? = null
        @Volatile private var running = false

        fun isRunning(): Boolean = running

        fun start(context: Context, profile: BikeProfile) {
            val intent = Intent(context, VogeNavService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ADDRESS, profile.bleAddress)
                putExtra(EXTRA_NAME, profile.bleName)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, VogeNavService::class.java).setAction(ACTION_STOP)) }
            instance?.shutdown("STOP MotoLink")
        }

        fun submitModel(model: VogeNavModel) {
            instance?.enqueueModel(model)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val txQueue = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeBusy = false
    private var heartbeatRunning = false
    private var expectedName: String? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            synchronized(this@VogeNavService) {
                if (!heartbeatRunning || writeChar == null || gatt == null) return
                if (txQueue.isEmpty() && !writeBusy) {
                    txQueue.addLast(VogeNavPacketEncoder.heartbeatFrame(this@VogeNavService))
                }
            }
            sendNext()
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("ACTION_STOP")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Connessione BLE VOGE…"))
                val address = intent.getStringExtra(EXTRA_ADDRESS)?.trim().orEmpty()
                expectedName = intent.getStringExtra(EXTRA_NAME)
                if (address.isBlank()) {
                    AppLog.add("V1.6 BLE NAV: profilo senza indirizzo BLE; START interrotto")
                    updateNotification("Profilo BLE da riconfigurare")
                } else {
                    connect(address)
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
        if (!hasConnectPermission()) {
            AppLog.add("V1.6 BLE NAV: permesso BLUETOOTH_CONNECT assente")
            updateNotification("Permesso Bluetooth mancante")
            return
        }
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AppLog.add("V1.6 BLE NAV: Bluetooth spento/non disponibile")
            updateNotification("Bluetooth non disponibile")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            AppLog.add("V1.6 BLE NAV: dispositivo salvato non risolvibile")
            updateNotification("BLE VOGE non trovato")
            return
        }
        runCatching { gatt?.close() }
        writeChar = null
        notifyChar = null
        writeBusy = false
        txQueue.clear()
        AppLog.add("V1.6 BLE NAV: connessione al profilo VOGE salvato")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                AppLog.add("V1.6 BLE NAV: GATT connesso; discovery servizi")
                updateNotification("BLE connesso · preparo navigazione")
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                heartbeatRunning = false
                main.removeCallbacks(heartbeatRunnable)
                writeChar = null
                notifyChar = null
                writeBusy = false
                txQueue.clear()
                AppLog.add("V1.6 BLE NAV: GATT disconnesso status=$status")
                updateNotification("BLE VOGE disconnesso")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.add("V1.6 BLE NAV: discovery servizi fallita status=$status")
                updateNotification("Servizi BLE non disponibili")
                return
            }
            val service = g.getService(VogeBleProbe.SERVICE_UUID)
            val write = service?.getCharacteristic(VogeBleProbe.WRITE_UUID)
            val notify = service?.getCharacteristic(VogeBleProbe.NOTIFY_UUID)
            if (service == null || write == null) {
                AppLog.add("V1.6 BLE NAV: UUID VOGE navigation non trovati")
                updateNotification("Protocollo VOGE non trovato")
                return
            }
            synchronized(this@VogeNavService) {
                writeChar = write
                notifyChar = notify
            }
            if (notify != null) enableNotifications(g, notify)
            heartbeatRunning = true
            main.removeCallbacks(heartbeatRunnable)
            main.post(heartbeatRunnable)
            updateNotification("VOGE BLE pronto · avvia una rotta in Maps")
            AppLog.add("V1.6 BLE NAV READY: TX/RX VOGE pronti; heartbeat 0x5A serializzato")
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != VogeBleProbe.WRITE_UUID) return
            synchronized(this@VogeNavService) { writeBusy = false }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.add("V1.6 BLE NAV: write ACK fallito status=$status")
            }
            main.postDelayed({ sendNext() }, FRAME_GAP_MS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            val cmd = if (value.size > 1) value[1].toInt() and 0xFF else -1
            AppLog.add("V1.6 BLE NAV RX: cmd=${if (cmd >= 0) "0x%02X".format(Locale.US, cmd) else "-"}; bytes=${value.size}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        runCatching {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor: BluetoothGattDescriptor? = characteristic.getDescriptor(CCCD)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            }
        }
    }

    @Synchronized
    private fun enqueueModel(model: VogeNavModel) {
        if (writeChar == null || gatt == null) {
            AppLog.add("V1.6 MAPS→VOGE: update ignorato, BLE non pronto")
            return
        }
        txQueue.removeAll { frame -> frame.size > 1 && (frame[1].toInt() and 0xFF) == VogeNavPacketEncoder.CMD_HEARTBEAT }
        VogeNavPacketEncoder.allFrames(model).forEach { txQueue.addLast(it) }
        AppLog.add("V1.6 MAPS→VOGE: TX pianificato direzione=${model.nextRoadDirection} distanza=${model.curRoadRemainDistM}m frames=5")
        sendNext()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun sendNext() {
        if (writeBusy || txQueue.isEmpty()) return
        val localGatt = gatt ?: return
        val localWrite = writeChar ?: return
        if (!hasConnectPermission()) return
        val frame = txQueue.removeFirst()
        val command = if (frame.size > 1) frame[1].toInt() and 0xFF else -1
        val writeType = if (
            localWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            localWrite.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeBusy = true
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                localGatt.writeCharacteristic(localWrite, frame, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                localWrite.writeType = writeType
                @Suppress("DEPRECATION")
                localWrite.value = frame
                @Suppress("DEPRECATION")
                localGatt.writeCharacteristic(localWrite)
            }
        }.getOrDefault(false)
        AppLog.add("V1.6 BLE NAV TX: cmd=${if (command >= 0) "0x%02X".format(Locale.US, command) else "-"}; accepted=$accepted")
        if (!accepted) {
            writeBusy = false
            main.postDelayed({ sendNext() }, 180L)
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            main.postDelayed({
                synchronized(this@VogeNavService) { writeBusy = false }
                sendNext()
            }, FRAME_GAP_MS)
        } else {
            main.postDelayed({
                synchronized(this@VogeNavService) {
                    if (writeBusy) {
                        AppLog.add("V1.6 BLE NAV: callback write timeout; continuo coda")
                        writeBusy = false
                    }
                }
                sendNext()
            }, 900L)
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MotoLink BLE Navigation", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MotoLink V1.6 · Navigazione BLE")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    @SuppressLint("MissingPermission")
    private fun shutdown(reason: String) {
        heartbeatRunning = false
        main.removeCallbacks(heartbeatRunnable)
        synchronized(this) {
            txQueue.clear()
            writeBusy = false
            writeChar = null
            notifyChar = null
        }
        val local = gatt
        gatt = null
        if (local != null && hasConnectPermission()) {
            runCatching { local.disconnect() }
            runCatching { local.close() }
        }
        AppLog.add("V1.6 BLE NAV STOP: $reason")
    }

    override fun onDestroy() {
        shutdown("service destroy")
        running = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
