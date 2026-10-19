package it.motolink.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * User-triggered, finite and reversible diagnostic.
 * Trofeo 500: telephone = hotspot AP; TFT = STA/client.
 * The old inverse-direction WifiNetworkSpecifier test is deliberately NOT
 * repeated while protecting the phone hotspot. It could disrupt the AP.
 * No pairing, no GATT write, no hotspot change and no TCP write.
 */
internal class Trofeo500WifiRecovery(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    companion object {
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val main = Handler(Looper.getMainLooper())
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var cancelled = false
    private var completed = false
    private var btReceiver: BroadcastReceiver? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var selected: BluetoothDevice? = null
    private var classicFound = 0
    private var bleFound = 0
    private var awaitingGatt = false

    private fun log(message: String) {
        if (cancelled) return
        AppLog.add("TROFEO500 WIFI RECOVERY: " + message)
        main.post { if (!cancelled) onStatus(message) }
    }

    @SuppressLint("MissingPermission")
    private fun motoName(device: BluetoothDevice): String =
        runCatching { device.name.orEmpty() }.getOrDefault("")

    private fun matches(name: String): Boolean =
        name.startsWith("VOGE", true) || name.contains("TROFEO", true) ||
        name.startsWith("VALICO", true)

    @SuppressLint("MissingPermission")
    fun start() {
        if (cancelled || completed) return
        val adapter = bluetooth
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth spento: attivalo prima della diagnosi.")
            finish()
            return
        }
        log("1/3 - Ricerca Bluetooth Classic e richiesta SDP ai dispositivi moto.")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (cancelled || i?.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && matches(motoName(device))) classicFound++
            }
        }
        btReceiver = receiver
        runCatching {
            context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            log("Ricerca Classic avviata=" + adapter.startDiscovery())
            for (device in adapter.bondedDevices.orEmpty()) {
                if (matches(motoName(device))) {
                    log("SDP richiesto a moto associata=" +
                        runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false))
                }
            }
        }.onFailure { log("Classic limitato: " + it.javaClass.simpleName) }
        main.postDelayed({
            stopClassic()
            if (!cancelled) startBle()
        }, 12_500L)
    }

    @SuppressLint("MissingPermission")
    private fun stopClassic() {
        runCatching { bluetooth?.cancelDiscovery() }
        val r = btReceiver
        btReceiver = null
        if (r != null) runCatching { context.unregisterReceiver(r) }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        if (cancelled || completed) return
        log("2/3 - Classic concluso: moto rilevate=" + classicFound + ". Scansione BLE.")
        val scanner = bluetooth?.bluetoothLeScanner
        if (scanner == null) {
            log("Scanner BLE non disponibile.")
            wifiStage()
            return
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (cancelled) return
                val d = result?.device ?: return
                val name = result.scanRecord?.deviceName ?: motoName(d)
                if (matches(name)) {
                    bleFound++
                    if (selected == null) selected = d
                }
            }
            override fun onScanFailed(code: Int) {
                log("BLE scan fallito: " + code)
            }
        }
        scanCallback = cb
        runCatching {
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.onFailure { log("BLE scan non avviato: " + it.javaClass.simpleName) }
        main.postDelayed({
            runCatching { scanner.stopScan(cb) }
            if (scanCallback === cb) scanCallback = null
            if (!cancelled) {
                log("BLE rilevamenti moto=" + bleFound)
                val d = selected
                if (d != null) discoverGatt(d) else wifiStage()
            }
        }, 12_000L)
    }

    @SuppressLint("MissingPermission")
    private fun discoverGatt(device: BluetoothDevice) {
        log("GATT: connessione e scoperta servizi senza leggere o scrivere caratteristiche.")
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (cancelled) { runCatching { g.close() }; return }
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED)
                    runCatching { g.discoverServices() }
                else if (state == BluetoothProfile.STATE_DISCONNECTED)
                    main.post { if (!cancelled && awaitingGatt) { closeGatt(); wifiStage() } }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                log("GATT servizi riconosciuti=" + (g.services?.size ?: 0) + ", status=" + status)
                main.post { if (!cancelled && awaitingGatt) { closeGatt(); wifiStage() } }
            }
        }
        awaitingGatt = true
        runCatching {
            gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        }.onFailure {
            log("GATT non avviato: " + it.javaClass.simpleName)
            closeGatt()
            wifiStage()
        }
        main.postDelayed({
            if (!cancelled && awaitingGatt) {
                closeGatt()
                wifiStage()
            }
        }, 12_000L)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        awaitingGatt = false
        val old = gatt
        gatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    @Suppress("DEPRECATION")
    private fun wifiStage() {
        if (cancelled || completed) return
        log("3/3 - Verifica radio Wi-Fi ed esecuzione scansione di sistema.")
        runCatching {
            val wifiClient = wifi?.isWifiEnabled
            val scan = wifi?.startScan() ?: false
            val active = connectivity?.activeNetwork
            val caps = active?.let { connectivity?.getNetworkCapabilities(it) }
            val defaultWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            log("Radio STA=" + wifiClient + "; scansione=" + scan +
                "; rete predefinita Wi-Fi=" + defaultWifi)
        }.onFailure { log("Diagnostica Wi-Fi limitata da Android: " + it.javaClass.simpleName) }
        log("Hotspot del telefono preservato; nessun tentativo di trasformare il telefono in client Wi-Fi.")
        main.postDelayed({ finish() }, 2_000L)
    }

    @SuppressLint("MissingPermission")
    private fun finish() {
        if (cancelled || completed) return
        completed = true
        stopClassic()
        val s = scanCallback
        scanCallback = null
        if (s != null) runCatching { bluetooth?.bluetoothLeScanner?.stopScan(s) }
        closeGatt()
        log("Procedura conclusa: controllare simbolo Wi-Fi e collegamento hotspot sul TFT.")
        main.post { if (!cancelled) onFinished() }
    }

    @SuppressLint("MissingPermission")
    fun cancel() {
        if (cancelled) return
        cancelled = true
        main.removeCallbacksAndMessages(null)
        stopClassic()
        val s = scanCallback
        scanCallback = null
        if (s != null) runCatching { bluetooth?.bluetoothLeScanner?.stopScan(s) }
        closeGatt()
        AppLog.add("TROFEO500 WIFI RECOVERY: diagnosi interrotta; risorse rilasciate")
    }
}
