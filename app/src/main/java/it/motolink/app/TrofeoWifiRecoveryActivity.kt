package it.motolink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import android.text.InputType
import android.view.View
import android.widget.*

/** Experimental, opt-in diagnostics: phone is Wi-Fi AP, Trofeo 500 is the client.
 * No GATT characteristic writes and no modification to hotspot configuration.
 * Android's client-side Wi-Fi association is NOT TFT reconnection proof. */
@Suppress("DEPRECATION")
class TrofeoWifiRecoveryActivity : Activity() {
    companion object { private const val PERMISSION_REQUEST = 8113 }
    private val handler = Handler(Looper.getMainLooper())
    private val bt by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var running = false
    private var generation = 0
    private var classicReceiver: BroadcastReceiver? = null
    private var leScanner: BluetoothLeScanner? = null
    private var leCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var candidate: BluetoothDevice? = null
    private lateinit var ssid: EditText
    private lateinit var password: EditText
    private lateinit var state: TextView
    private lateinit var events: TextView
    private lateinit var go: Button
    private lateinit var confirm: Button

    private fun px(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = px(15).toFloat(); setStroke(px(1), 0xFF48604A.toInt())
    }
    private fun label(s: String, sp: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    private fun add(p: LinearLayout, v: View, top: Int = 9) {
        p.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })
    }
    private fun button(name: String, primary: Boolean = true) = Button(this).apply {
        text = name; isAllCaps = false
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        background = shape(if (primary) 0xFF64F04A.toInt() else 0xFF1A2A20.toInt())
        minHeight = px(52)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(20), px(22), px(20), px(24))
            setBackgroundColor(0xFF080C09.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })
        add(root, label("RIPRISTINO WI-FI", 25f, 0xFF64F04A.toInt(), true), 0)
        add(root, label("VOGE Trofeo 500", 17f, Color.WHITE, true))
        add(root, label(
            "Il telefono crea l'hotspot e la moto vi si collega. Usare solo a moto ferma " +
            "quando il TFT non riesce a ricollegarsi. Il test può interrompere temporaneamente " +
            "connessioni Bluetooth o Wi-Fi. Il normale START di MotoLink deve essere fermo.",
            14f, 0xFFCED6D0.toInt()), 12)
        add(root, label("SSID Wi-Fi per il secondo test (facoltativo)", 14f, Color.WHITE, true), 18)
        ssid = EditText(this).apply {
            hint = "Solo se il TFT indica una rete Wi-Fi"
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFFAFBBAD.toInt())
            background = shape(0xFF19221B.toInt()); setPadding(px(12), px(10), px(12), px(10))
        }
        add(root, ssid, 6)
        add(root, label("Password della rete (facoltativa)", 14f, Color.WHITE, true), 13)
        password = EditText(this).apply {
            hint = "Non viene salvata né registrata"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(0xFFAFBBAD.toInt())
            background = shape(0xFF19221B.toInt()); setPadding(px(12), px(10), px(12), px(10))
        }
        add(root, password, 6)
        add(root, label(
            "Per la Trofeo 500 standard, lasciare vuoti i campi: la moto è il client " +
            "dell'hotspot del telefono. La prova client→rete del vecchio secondo pulsante " +
            "viene eseguita solo se si inseriscono SSID e password.", 12f, 0xFFB7C7BA.toInt()), 8)
        go = button("AVVIA").also { add(root, it, 21) }
        go.setOnClickListener { startRequested() }
        state = label("Pronto • nessun test avviato", 15f, 0xFF64F04A.toInt(), true)
        add(root, state, 17)
        events = label("REGISTRO TECNICO", 12f, 0xFFCBD2CC.toInt())
        events.setTextIsSelectable(true)
        add(root, ScrollView(this).apply {
            minimumHeight = px(120); background = shape(0xFF111A13.toInt())
            setPadding(px(12), px(12), px(12), px(12))
            addView(events)
        }, 10)
        confirm = button("VERIFICA RISULTATO SUL TFT", false)
        confirm.isEnabled = false
        confirm.setOnClickListener { verifyOnTft() }
        add(root, confirm, 14)
        add(root, button("CHIUDI", false).apply { setOnClickListener { finish() } }, 10)
    }

    private fun log(s: String) {
        handler.post {
            if (isFinishing || isDestroyed) return@post
            events.append("\n• " + s)
            AppLog.add("TROFEO500 WIFI RECOVERY LAB: " + s)
        }
    }
    private fun startRequested() {
        if (running) return
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        else begin()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == PERMISSION_REQUEST) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) begin()
            else { state.text = "Permessi insufficienti"; log("Permessi negati: nessuna operazione eseguita") }
        }
    }
    private fun begin() {
        if (running) return
        val adapter = bt
        if (adapter == null || !adapter.isEnabled) {
            state.text = "Attiva il Bluetooth del telefono"
            return
        }
        val name = ssid.text.toString().trim()
        val pwd = password.text.toString()
        if ((name.isNotBlank() && pwd.length < 8) || (name.isBlank() && pwd.isNotEmpty())) {
            state.text = "Inserisci SSID e password (almeno 8 caratteri), o lascia entrambi vuoti"
            return
        }
        candidate = null; running = true
        val token = ++generation
        go.isEnabled = false; confirm.isEnabled = false
        ssid.isEnabled = false; password.isEnabled = false
        state.text = "Fase 1/2 • Bluetooth e scansione Wi-Fi"
        log("Avvio. Topologia telefono=hotspot, TFT=client. Nessuna scrittura Bluetooth.")
        classic(adapter)
        ble(adapter, token)
        try { log("Wi-Fi Android: scansione richiesta=" + (wifi?.startScan() == true)) }
        catch (e: Exception) { log("Wi-Fi scan: " + e.javaClass.simpleName) }
        handler.postDelayed({
            if (!running || token != generation) return@postDelayed
            stopScans()
            if (name.isBlank()) {
                log("Fase 2/2: SSID vuoto, omessa richiesta Wi-Fi client→rete (non equivale al collegamento TFT→hotspot).")
                complete(token)
            } else legacyWifi(token, name, pwd)
        }, 16000L)
    }
    private fun isVoge(n: String?) = n?.contains("VOGE", true) == true || n?.contains("TROFEO", true) == true
    @Suppress("MissingPermission")
    private fun deviceName(d: BluetoothDevice): String? = try { d.name } catch (_: Exception) { null }
    @Suppress("MissingPermission")
    private fun classic(adapter: BluetoothAdapter) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, event: Intent?) {
                when (event?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                            event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null && isVoge(deviceName(d))) log("Bluetooth Classic: VOGE rilevata")
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> log("Bluetooth Classic: ricerca conclusa")
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33)
                registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(classicReceiver, filter)
            log("Bluetooth Classic: discovery=" + adapter.startDiscovery())
            adapter.bondedDevices.filter { isVoge(deviceName(it)) }.forEach {
                log("SDP device associato: fetchUuidsWithSdp=" + it.fetchUuidsWithSdp())
            }
        } catch (e: Exception) { log("Bluetooth Classic: " + e.javaClass.simpleName) }
    }
    @Suppress("MissingPermission")
    private fun ble(adapter: BluetoothAdapter, token: Int) {
        try {
            leScanner = adapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult?) {
                    if (!running || generation != token || candidate != null) return
                    val r = result ?: return
                    if (isVoge(r.scanRecord?.deviceName ?: deviceName(r.device))) {
                        candidate = r.device
                        log("BLE: candidato VOGE rilevato, RSSI " + r.rssi + " dBm")
                    }
                }
                override fun onScanFailed(code: Int) { log("BLE: scan fallita " + code) }
            }
            leCallback = cb
            leScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            log("BLE: scan a bassa latenza per 12 secondi")
            handler.postDelayed({
                if (generation != token || !running) return@postDelayed
                stopBle()
                candidate?.let { gattDiscovery(it, token) }
                    ?: log("BLE: nessun candidato VOGE, GATT non eseguito")
            }, 12000L)
        } catch (e: Exception) { log("BLE: " + e.javaClass.simpleName) }
    }
    @Suppress("MissingPermission")
    private fun gattDiscovery(d: BluetoothDevice, token: Int) {
        try {
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (generation != token) { g.close(); return }
                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        log("GATT collegato, discoverServices read-only")
                        try { g.discoverServices() } catch (e: Exception) { log("GATT discovery: " + e.javaClass.simpleName) }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        log("GATT: disconnesso status=" + status)
                        try { g.close() } catch (_: Exception) {}
                    }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    log("GATT: status=" + status + ", servizi=" + g.services.size + "; nessuna scrittura")
                    try { g.close() } catch (_: Exception) {}
                }
            }
            gatt = d.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed({ if (generation == token) closeGatt() }, 9500L)
        } catch (e: Exception) { log("GATT: " + e.javaClass.simpleName) }
    }
    private fun legacyWifi(token: Int, name: String, pwd: String) {
        state.text = "Fase 2/2 • richiesta Wi-Fi Android"
        log("Secondo test: Android tenta associazione lato TELEFONO, non impartisce comandi al TFT.")
        try {
            val spec = WifiNetworkSpecifier.Builder().setSsid(name).setWpa2Passphrase(pwd).build()
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { log("Android: associato alla rete richiesta (NON conferma che il TFT sia tornato online)") }
                override fun onUnavailable() { log("Android: associazione non disponibile nel tempo limite") }
                override fun onLost(network: Network) { log("Android: rete di test disconnessa") }
            }
            wifiCallback = cb
            connectivity.requestNetwork(req, cb, 25000)
            log("Android potrebbe mostrare la richiesta di autorizzazione rete.")
            handler.postDelayed({ if (generation == token && running) complete(token) }, 27000L)
        } catch (e: Exception) { log("Richiesta Wi-Fi: " + e.javaClass.simpleName); complete(token) }
    }
    private fun complete(token: Int) {
        if (!running || token != generation) return
        running = false
        stopScans(); closeGatt(); releaseWifi()
        password.text.clear()
        state.text = "Procedura terminata • controlla il Wi-Fi del TFT"
        go.isEnabled = true; ssid.isEnabled = true; password.isEnabled = true
        confirm.isEnabled = true
        log("Diagnostica conclusa. Risultato TFT non deducibile automaticamente.")
    }
    private fun verifyOnTft() {
        AlertDialog.Builder(this)
            .setTitle("Verifica Trofeo 500")
            .setMessage("Il simbolo Wi-Fi è tornato sul TFT e la moto si collega all'hotspot del telefono?")
            .setNegativeButton("NO") { _, _ ->
                state.text = "Collegamento ancora non ripristinato"
                log("Esito riferito dall'utente: connessione NON ripristinata")
            }
            .setPositiveButton("SÌ") { _, _ ->
                state.text = "Successo! Connessione confermata dall'utente"
                log("Esito riferito dall'utente: Wi-Fi TFT ripristinato")
                AlertDialog.Builder(this).setTitle("Successo!")
                    .setMessage("Hai confermato il ripristino della connessione Wi-Fi del TFT.")
                    .setPositiveButton("OK", null).show()
            }.show()
    }
    @Suppress("MissingPermission")
    private fun stopBle() {
        leCallback?.let { try { leScanner?.stopScan(it) } catch (_: Exception) {} }
        leCallback = null; leScanner = null
    }
    @Suppress("MissingPermission")
    private fun stopScans() {
        stopBle()
        try { bt?.cancelDiscovery() } catch (_: Exception) {}
        classicReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        classicReceiver = null
    }
    private fun closeGatt() {
        val g = gatt; gatt = null
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }
    private fun releaseWifi() {
        wifiCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        wifiCallback = null
    }
    override fun onDestroy() {
        generation++; running = false
        stopScans(); closeGatt(); releaseWifi()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
