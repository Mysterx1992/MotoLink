package it.motolink.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Manual Trofeo 500 connectivity recovery. No packet writes, pairing or hotspot changes. */
@SuppressLint("MissingPermission")
class TrofeoWifiRecovery(
    private val context: Context,
    private val ssid: String,
    private val update: (String, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var cancelled = false
    private var bleCallback: ScanCallback? = null
    private var diagnosticGatt: BluetoothGatt? = null
    private var bike: BluetoothDevice? = null
    private var startedClassic = false
    private var wifiStarted = false

    private fun log(message: String, done: Boolean = false) {
        if (cancelled) return
        AppLog.add("TROFEO WIFI RECOVERY: $message")
        update(message, done)
    }
    private fun isBike(name: String?): Boolean =
        name?.contains("VOGE", ignoreCase = true) == true ||
        name?.contains("TROFEO", ignoreCase = true) == true

    fun start() {
        val bt = adapter ?: run { log("Bluetooth non disponibile", true); return }
        if (!bt.isEnabled) { log("Attiva Bluetooth", true); return }
        if (Build.VERSION.SDK_INT >= 31 &&
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            log("Permessi Bluetooth mancanti", true); return
        }
        log("1/3 · Scansione Bluetooth Classic e SDP…")
        try {
            startedClassic = bt.startDiscovery()
            bt.bondedDevices?.filter { isBike(it.name) }?.forEach {
                if (bike == null) bike = it
                runCatching { it.fetchUuidsWithSdp() }
            }
        } catch (e: Exception) { log("Classic: ${e.javaClass.simpleName}") }
        handler.postDelayed({
            if (cancelled) return@postDelayed
            if (startedClassic) runCatching { bt.cancelDiscovery() }
            beginBle()
        }, 12500)
    }

    private fun beginBle() {
        if (cancelled) return
        log("2/3 · Ricerca BLE e GATT diagnostico…")
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { beginWifi(); return }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (!cancelled && isBike(name)) bike = result.device
            }
            override fun onScanFailed(errorCode: Int) { log("BLE scan error: $errorCode") }
        }
        bleCallback = cb
        runCatching { scanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
            .onFailure { log("Scansione BLE non avviata") }
        handler.postDelayed({
            runCatching { scanner.stopScan(cb) }
            bleCallback = null
            if (cancelled) return@postDelayed
            val target = bike
            if (target == null) { log("Nessun dispositivo VOGE disponibile"); beginWifi(); return@postDelayed }
            try {
                diagnosticGatt = target.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (cancelled) return
                        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { gatt.discoverServices() }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            runCatching { gatt.close() }
                        }
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        log("Servizi GATT disponibili: status=$status")
                        runCatching { gatt.disconnect(); gatt.close() }
                        if (diagnosticGatt === gatt) diagnosticGatt = null
                        handler.post { beginWifi() }
                    }
                }, BluetoothDevice.TRANSPORT_LE)
            } catch (e: Exception) { log("GATT: ${e.javaClass.simpleName}"); beginWifi(); return@postDelayed }
            handler.postDelayed({
                if (!cancelled && diagnosticGatt != null) {
                    runCatching { diagnosticGatt?.disconnect(); diagnosticGatt?.close() }
                    diagnosticGatt = null
                    beginWifi()
                }
            }, 10000)
        }, 12000)
    }

    private fun beginWifi() {
        if (cancelled || wifiStarted) return
        wifiStarted = true
        log("3/3 · Scansione Wi-Fi. Telefono = hotspot, moto = client.")
        val wm = wifi
        if (wm == null) { log("Wi-Fi non disponibile; verifica manualmente il TFT.", true); return }
        runCatching { wm.startScan() }
        handler.postDelayed({
            if (cancelled) return@postDelayed
            // STA association from phone to bike is deliberately NOT attempted:
            // it may disrupt the phone's active AP and reverses Trofeo 500 topology.
            val count = runCatching { wm.scanResults?.size ?: 0 }.getOrDefault(0)
            log("Procedura completata (reti visibili: $count). Controlla la connessione sul TFT.", true)
        }, 2500)
    }

    fun stop() {
        if (cancelled) return
        cancelled = true
        runCatching { if (startedClassic) adapter?.cancelDiscovery() }
        runCatching { bleCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        runCatching { diagnosticGatt?.disconnect(); diagnosticGatt?.close() }
    }
}
