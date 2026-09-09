package it.motolink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

object VogeBleProbe {
    val SERVICE_UUID: UUID = UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e")
    val WRITE_UUID: UUID = UUID.fromString("6052202a-2928-4131-a2d0-456d5673ed2f")
    val NOTIFY_UUID: UUID = UUID.fromString("ab9938d5-c354-4e2b-94f4-364e16ebcd33")

    data class Result(
        val address: String,
        val name: String?,
        val serviceUuid: String,
        val writeUuid: String,
        val notifyUuid: String?
    )

    fun hasPermissions(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermissions(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                requestCode
            )
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), requestCode)
        }
    }

    fun probe(
        activity: Activity,
        timeoutMs: Long = 12_000L,
        onSuccess: (Result) -> Unit,
        onFailure: (String) -> Unit
    ) {
        Session(activity, timeoutMs, onSuccess, onFailure).start()
    }

    private class Session(
        private val activity: Activity,
        private val timeoutMs: Long,
        private val onSuccess: (Result) -> Unit,
        private val onFailure: (String) -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        private var scanner: BluetoothLeScanner? = null
        private var gatt: BluetoothGatt? = null
        private var selectedDevice: BluetoothDevice? = null
        private var selectedName: String? = null
        private var selectedWrite: BluetoothGattCharacteristic? = null
        private var selectedNotify: BluetoothGattCharacteristic? = null
        private var finished = false
        private var connecting = false

        private val timeoutRunnable = Runnable { fail("Nessun BLE VOGE compatibile rilevato entro ${timeoutMs / 1000}s.") }

        @SuppressLint("MissingPermission")
        fun start() {
            if (!hasPermissions(activity)) {
                fail("Permessi Bluetooth mancanti.")
                return
            }
            val manager = activity.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                fail("Bluetooth spento o non disponibile.")
                return
            }
            scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                fail("Scanner BLE non disponibile.")
                return
            }
            AppLog.add("V1.6 BLE PROBE: ricerca VOGE avviata")
            runCatching { scanner?.startScan(scanCallback) }
                .onFailure { fail("Impossibile avviare la scansione BLE.") }
            main.postDelayed(timeoutRunnable, timeoutMs.coerceAtLeast(4_000L))
        }

        private val scanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (finished || connecting || result?.device == null) return
                val device = result.device
                val name = runCatching { device.name }.getOrNull()
                    ?: result.scanRecord?.deviceName
                    ?: ""
                if (!name.contains("VOGE", ignoreCase = true)) return
                connecting = true
                selectedDevice = device
                selectedName = name.takeIf { it.isNotBlank() }
                runCatching { scanner?.stopScan(this) }
                AppLog.add("V1.6 BLE PROBE: candidato VOGE rilevato; verifica GATT")
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(activity, false, gattCallback)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                fail("Scansione BLE fallita (codice $errorCode).")
            }
        }

        private val gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (finished) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    AppLog.add("V1.6 BLE PROBE: GATT connesso; discovery servizi")
                    if (!g.discoverServices()) fail("Discovery servizi BLE non avviata.")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Il BLE VOGE si è disconnesso durante la verifica.")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (finished) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Discovery servizi BLE fallita.")
                    return
                }
                val service: BluetoothGattService = g.getService(SERVICE_UUID) ?: run {
                    fail("Dispositivo BLE trovato ma servizio navigazione VOGE assente.")
                    return
                }
                val write = service.getCharacteristic(WRITE_UUID) ?: run {
                    fail("Servizio VOGE trovato ma characteristic TX assente.")
                    return
                }
                selectedWrite = write
                selectedNotify = service.getCharacteristic(NOTIFY_UUID)
                val frame = VogeNavPacketEncoder.heartbeatFrame(activity)
                val writeType = if (
                    write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                    write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                ) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                val accepted = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(write, frame, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        write.writeType = writeType
                        @Suppress("DEPRECATION")
                        write.value = frame
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(write)
                    }
                }.getOrDefault(false)
                if (!accepted) {
                    fail("Characteristic VOGE trovata ma heartbeat 0x5A non accettato.")
                } else {
                    AppLog.add("V1.6 BLE PROBE: heartbeat 0x5A accettato dal GATT; attendo ACK")
                    main.postDelayed({
                        if (!finished && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) succeed()
                    }, 500L)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (finished || characteristic.uuid != WRITE_UUID) return
                if (status == BluetoothGatt.GATT_SUCCESS) succeed()
                else fail("Heartbeat VOGE inviato ma ACK GATT non riuscito (status $status).")
            }
        }

        @SuppressLint("MissingPermission")
        private fun succeed() {
            if (finished) return
            val device = selectedDevice ?: run {
                fail("BLE VOGE non disponibile dopo la verifica.")
                return
            }
            finished = true
            main.removeCallbacks(timeoutRunnable)
            runCatching { scanner?.stopScan(scanCallback) }
            val address = runCatching { device.address }.getOrDefault("")
            val result = Result(
                address = address,
                name = selectedName,
                serviceUuid = SERVICE_UUID.toString(),
                writeUuid = WRITE_UUID.toString(),
                notifyUuid = selectedNotify?.uuid?.toString()
            )
            closeGatt()
            AppLog.add("V1.6 BLE PROBE PASS: servizio/characteristic VOGE e heartbeat verificati")
            activity.runOnUiThread { onSuccess(result) }
        }

        @SuppressLint("MissingPermission")
        private fun fail(message: String) {
            if (finished) return
            finished = true
            main.removeCallbacks(timeoutRunnable)
            runCatching { scanner?.stopScan(scanCallback) }
            closeGatt()
            AppLog.add("V1.6 BLE PROBE FAIL: $message")
            activity.runOnUiThread { onFailure(message) }
        }

        @SuppressLint("MissingPermission")
        private fun closeGatt() {
            val local = gatt
            gatt = null
            if (local != null && hasPermissions(activity)) {
                runCatching { local.disconnect() }
                runCatching { local.close() }
            }
        }
    }
}
