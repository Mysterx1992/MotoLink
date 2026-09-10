package it.motolink.diag.trofeo500

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private lateinit var wifi: WifiManager
    private lateinit var cm: ConnectivityManager
    private lateinit var nsd: NsdManager
    private var logView: TextView? = null
    private lateinit var ssidInput: EditText
    private lateinit var passInput: EditText
    private var logFile: File? = null
    private val logText = StringBuilder()

    private var wifiReceiver: BroadcastReceiver? = null
    private var bleCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var requestedNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var processBoundForTest = false

    private val vogeBle = linkedMapOf<String, ScanResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        nsd = getSystemService(NsdManager::class.java)
        buildUi()
        ensurePermissions()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Trofeo 500 — Diagnostica Wi‑Fi / Bluetooth"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Diagnostica read-only: non invia comandi al TFT. Inserisci SSID e password mostrati sul quadro. La password non viene mai scritta nel log."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        })

        ssidInput = EditText(this).apply {
            hint = "SSID esatto (es. VOGE-5G-xxxx)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(ssidInput)

        passInput = EditText(this).apply {
            hint = "Password Wi-Fi (non salvata nel log)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passInput)

        root.addView(Button(this).apply {
            text = "1. AVVIA DIAGNOSI PASSIVA"
            setOnClickListener { startPassiveDiagnosis() }
        })
        root.addView(Button(this).apply {
            text = "2. TESTA CONNESSIONE WIFI TFT"
            setOnClickListener { testTargetWifi() }
        })
        root.addView(Button(this).apply {
            text = "CONDIVIDI LOG"
            setOnClickListener { shareLog() }
        })
        root.addView(Button(this).apply {
            text = "PULISCI LOG"
            setOnClickListener { resetLog() }
        })

        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
        resetLog()
    }

    private fun ensurePermissions(): Boolean {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                wanted += Manifest.permission.BLUETOOTH_SCAN
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                wanted += Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 100)
            return false
        }
        return true
    }

    private fun resetLog() {
        val dir = File(filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        logFile = File(dir, "Trofeo500_Diagnostic_$stamp.txt")
        logText.setLength(0)
        append("Trofeo 500 Wi-Fi/Bluetooth Diagnostic V1")
        append("Generato: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())}")
        append("Modalità: read-only; nessun comando applicativo inviato al TFT")
        append("La password Wi-Fi non viene registrata")
        append("---")
    }

    private fun append(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(Date())}  $msg\n"
        synchronized(logText) {
            logText.append(line)
            runCatching { logFile?.writeText(logText.toString(), Charsets.UTF_8) }
        }
        runOnUiThread {
            logView?.text = logText.toString()
        }
    }

    private fun startPassiveDiagnosis() {
        if (!ensurePermissions()) {
            toast("Concedi i permessi richiesti e premi di nuovo AVVIA DIAGNOSI")
            return
        }
        append("=== DIAGNOSI PASSIVA AVVIATA ===")
        append("Telefono: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
        val lm = getSystemService(LocationManager::class.java)
        append("Servizi posizione Android: ${if (runCatching { lm.isLocationEnabled }.getOrDefault(false)) "ON" else "OFF"}")
        append("Wi-Fi: enabled=${wifi.isWifiEnabled}; 5GHzSupported=${runCatching { wifi.is5GHzBandSupported }.getOrDefault(false)}")
        networkSnapshot("rete attiva")
        bluetoothSnapshot()
        startBleScan()
        startWifiScan()
        main.postDelayed({
            if (isCurrentlyOnTargetWifi()) {
                append("SSID target risulta già collegato: avvio discovery _EasyConn._tcp. read-only")
                startNsdDiscovery(12_000)
            } else {
                append("SSID target non risulta collegato: per testare l'associazione usa il pulsante TESTA CONNESSIONE WIFI TFT")
            }
        }, 13_000)
        main.postDelayed({ append("=== FINE FASE PASSIVA ===") }, 26_000)
    }

    private fun bluetoothSnapshot() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            append("Bluetooth: hardware non disponibile")
            return
        }
        append("Bluetooth: enabled=${adapter.isEnabled}; state=${adapter.state}")
        try {
            val bonded = adapter.bondedDevices.orEmpty()
            append("Bluetooth paired/bonded count=${bonded.size}")
            val candidates = bonded.filter { isVogeLike(runCatching { it.name }.getOrNull()) }
            if (candidates.isEmpty()) {
                append("Bluetooth paired: nessun dispositivo con nome VOGE/EasyConn/Carbit/TFT")
            }
            candidates.forEach { d ->
                val name = runCatching { d.name }.getOrNull() ?: "(senza nome)"
                val uuids = runCatching { d.uuids?.joinToString { it.uuid.toString() } }.getOrNull().orEmpty()
                append("Bluetooth paired candidate: name=$name type=${d.type} addr=${maskMac(d.address)} cachedUUIDs=${uuids.ifBlank { "none" }}")
                requestSdpUuids(d)
            }
        } catch (t: Throwable) {
            append("Bluetooth paired FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }

    private fun requestSdpUuids(device: BluetoothDevice) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val d = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != device.address) return
                val arr = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, android.os.ParcelUuid::class.java)
                } else {
                    @Suppress("DEPRECATION") intent?.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)?.mapNotNull { it as? android.os.ParcelUuid }?.toTypedArray()
                }
                append("Bluetooth SDP: name=${runCatching { device.name }.getOrNull()} UUIDs=${arr?.joinToString { it.uuid.toString() } ?: "none"}")
                runCatching { unregisterReceiver(this) }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID), RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))
            val started = device.fetchUuidsWithSdp()
            append("Bluetooth SDP request: name=${runCatching { device.name }.getOrNull()} started=$started")
            main.postDelayed({ runCatching { unregisterReceiver(receiver) } }, 10_000)
        } catch (t: Throwable) {
            append("Bluetooth SDP FAIL: ${t.javaClass.simpleName}")
            runCatching { unregisterReceiver(receiver) }
        }
    }

    private fun startBleScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            append("BLE scan: non disponibile o Bluetooth OFF")
            return
        }
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            append("BLE scan: scanner non disponibile")
            return
        }
        vogeBle.clear()
        var total = 0
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                total++
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (isVogeLike(name)) {
                    val key = result.device.address
                    val old = vogeBle[key]
                    if (old == null || result.rssi > old.rssi) vogeBle[key] = result
                }
            }
            override fun onScanFailed(errorCode: Int) {
                append("BLE scan FAIL error=$errorCode")
            }
        }
        bleCallback = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            append("BLE scan: avviato per 12 s (solo advertising)")
            main.postDelayed({
                runCatching { scanner.stopScan(cb) }
                bleCallback = null
                append("BLE scan: completato totalResults=$total VOGE-like=${vogeBle.size}")
                vogeBle.values.sortedByDescending { it.rssi }.forEach { r ->
                    val rec = r.scanRecord
                    val name = rec?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: "(senza nome)"
                    val su = rec?.serviceUuids?.joinToString { it.uuid.toString() }.orEmpty()
                    val mids = buildList {
                        val md = rec?.manufacturerSpecificData
                        if (md != null) for (i in 0 until md.size()) add("0x${md.keyAt(i).toString(16)}:${md.valueAt(i)?.size ?: 0}B")
                    }.joinToString()
                    val sd = rec?.serviceData?.keys?.joinToString { it.uuid.toString() }.orEmpty()
                    append("BLE candidate: name=$name rssi=${r.rssi} addr=${maskMac(r.device.address)} services=${su.ifBlank { "none" }} manufacturer=${mids.ifBlank { "none" }} serviceDataKeys=${sd.ifBlank { "none" }}")
                }
                vogeBle.values.maxByOrNull { it.rssi }?.let { discoverGattReadOnly(it.device) }
            }, 12_000)
        } catch (t: Throwable) {
            append("BLE scan exception: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }

    private fun discoverGattReadOnly(device: BluetoothDevice) {
        append("BLE GATT read-only: connect a ${runCatching { device.name }.getOrNull()} ${maskMac(device.address)}")
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                append("BLE GATT state: status=$status newState=$newState")
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    val ok = runCatching { g.discoverServices() }.getOrDefault(false)
                    append("BLE GATT discoverServices started=$ok")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt(g)
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                append("BLE GATT services: status=$status count=${g.services.size}")
                g.services.forEach { s ->
                    append("  GATT service ${s.uuid}")
                    s.characteristics.forEach { c ->
                        append("    char ${c.uuid} properties=0x${c.properties.toString(16)} permissions=0x${c.permissions.toString(16)}")
                    }
                }
                append("BLE GATT read-only: fine discovery; nessuna read/write/notify eseguita")
                closeGatt(g)
            }
        }
        try {
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE)
            else @Suppress("DEPRECATION") device.connectGatt(this, false, cb)
            main.postDelayed({
                gatt?.let {
                    append("BLE GATT timeout guard: chiudo connessione diagnostica")
                    closeGatt(it)
                }
            }, 12_000)
        } catch (t: Throwable) {
            append("BLE GATT connect FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }

    private fun closeGatt(g: BluetoothGatt) {
        runCatching { g.disconnect() }
        runCatching { g.close() }
        if (gatt === g) gatt = null
    }

    private fun startWifiScan() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                append("Wi-Fi scan broadcast: resultsUpdated=$updated")
                dumpWifiScanResults()
                runCatching { unregisterReceiver(this) }
                if (wifiReceiver === this) wifiReceiver = null
            }
        }
        wifiReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            val started = wifi.startScan()
            append("Wi-Fi scan: startScan=$started")
            main.postDelayed({
                if (wifiReceiver === receiver) {
                    append("Wi-Fi scan: timeout broadcast; leggo cache disponibile")
                    dumpWifiScanResults()
                    runCatching { unregisterReceiver(receiver) }
                    wifiReceiver = null
                }
            }, 12_000)
        } catch (t: Throwable) {
            append("Wi-Fi scan FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            runCatching { unregisterReceiver(receiver) }
            wifiReceiver = null
        }
    }

    private fun dumpWifiScanResults() {
        try {
            val target = targetSsid()
            val all = wifi.scanResults.orEmpty()
            val matches = all.filter {
                val s = it.SSID.orEmpty()
                s.contains("VOGE", true) || s.contains("EasyConn", true) || s.contains("Carbit", true) || (target.isNotBlank() && s == target)
            }.sortedByDescending { it.level }
            append("Wi-Fi scan: total=${all.size}; target/VOGE-like=${matches.size}")
            matches.forEach { r ->
                append("Wi-Fi candidate: SSID=${r.SSID} RSSI=${r.level}dBm freq=${r.frequency}MHz width=${r.channelWidth} capabilities=${r.capabilities.take(160)}")
            }
            if (target.isNotBlank()) {
                append("Wi-Fi target '$target' visible=${matches.any { it.SSID == target }}")
            }
        } catch (t: Throwable) {
            append("Wi-Fi scan results FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }

    private fun networkSnapshot(label: String, network: Network? = cm.activeNetwork) {
        try {
            val info = wifi.connectionInfo
            val ssid = info?.ssid?.trim('"').orEmpty()
            append("$label: Wi-Fi SSID=${ssid.ifBlank { "unknown" }} RSSI=${info?.rssi ?: 0} linkSpeed=${info?.linkSpeed ?: 0}Mbps freq=${info?.frequency ?: 0}MHz")
            if (network == null) {
                append("$label: activeNetwork=null")
                return
            }
            val caps = cm.getNetworkCapabilities(network)
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BT")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETH")
            }
            append("$label: transports=${transports.joinToString().ifBlank { "unknown" }} internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            dumpLinkProperties(label, cm.getLinkProperties(network))
        } catch (t: Throwable) {
            append("$label snapshot FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }

    private fun dumpLinkProperties(label: String, lp: LinkProperties?) {
        if (lp == null) {
            append("$label: LinkProperties=null")
            return
        }
        append("$label: iface=${lp.interfaceName}; addresses=${lp.linkAddresses.joinToString { it.toString() }}")
        append("$label: dns=${lp.dnsServers.joinToString { it.hostAddress ?: it.toString() }}")
        val routes = lp.routes.joinToString(" | ") { r -> "dst=${r.destination} gw=${r.gateway?.hostAddress ?: "-"} iface=${r.interface}" }
        append("$label: routes=${routes.ifBlank { "none" }}")
    }

    private fun testTargetWifi() {
        if (!ensurePermissions()) {
            toast("Concedi i permessi richiesti e premi di nuovo TESTA CONNESSIONE")
            return
        }
        val ssid = targetSsid()
        val pass = passInput.text?.toString().orEmpty()
        if (ssid.isBlank()) {
            toast("Inserisci l'SSID esatto mostrato sul TFT")
            return
        }
        if (pass.length < 8) {
            toast("Inserisci la password Wi-Fi mostrata sul TFT")
            return
        }
        append("=== TEST ASSOCIAZIONE WIFI AVVIATO target='$ssid' ===")
        append("Password: presente (${pass.length} caratteri), NON registrata")
        cleanupRequestedNetwork()
        try {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(pass)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    append("Wi-Fi target: onAvailable -> ASSOCIAZIONE RIUSCITA")
                    processBoundForTest = cm.bindProcessToNetwork(network)
                    append("Wi-Fi target: bindProcessToNetwork=$processBoundForTest")
                    main.postDelayed({
                        networkSnapshot("rete TFT richiesta", network)
                        startNsdDiscovery(15_000)
                    }, 800)
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    append("Wi-Fi target: capabilities changed internet=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                }
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    dumpLinkProperties("rete TFT richiesta", linkProperties)
                }
                override fun onUnavailable() {
                    append("Wi-Fi target: onUnavailable -> ASSOCIAZIONE/APPROVAZIONE NON RIUSCITA entro timeout")
                    cleanupRequestedNetwork()
                }
                override fun onLost(network: Network) {
                    append("Wi-Fi target: onLost -> collegamento TFT perso")
                    cleanupRequestedNetwork()
                }
            }
            requestedNetworkCallback = cb
            cm.requestNetwork(req, cb, 30_000)
            append("Android mostrerà, se necessario, una richiesta di connessione al Wi-Fi del TFT. Confermare sul telefono.")
            main.postDelayed({
                if (requestedNetworkCallback === cb) {
                    append("Wi-Fi target: fine finestra diagnostica; rilascio richiesta rete")
                    cleanupRequestedNetwork()
                }
            }, 50_000)
        } catch (t: Throwable) {
            append("Wi-Fi target request FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}")
            cleanupRequestedNetwork()
        }
    }

    private fun startNsdDiscovery(durationMs: Long) {
        stopNsd()
        multicastLock = wifi.createMulticastLock("Trofeo500Diag-mDNS").apply {
            setReferenceCounted(false)
            acquire()
        }
        var found = 0
        var resolved = 0
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                append("mDNS: discovery started type=$serviceType")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.equals("_EasyConn._tcp.", true)) return
                found++
                append("mDNS: EasyConn found name=${serviceInfo.serviceName}")
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            append("mDNS: resolve FAIL name=${serviceInfo.serviceName} error=$errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            resolved++
                            @Suppress("DEPRECATION") val host = serviceInfo.host
                            val attrs = runCatching { serviceInfo.attributes.keys.sorted().joinToString() }.getOrDefault("")
                            append("mDNS: RESOLVED EasyConn name=${serviceInfo.serviceName} host=${host?.hostAddress ?: "null"} port=${serviceInfo.port} TXTkeys=${attrs.ifBlank { "none" }}")
                            if (host != null && serviceInfo.port > 0) probeTcpConnectOnly(host.hostAddress ?: return, serviceInfo.port)
                        }
                    })
                }.onFailure { append("mDNS resolve exception: ${it.javaClass.simpleName}") }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                append("mDNS: service lost name=${serviceInfo.serviceName}")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                append("mDNS: discovery stopped")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                append("mDNS: start FAIL error=$errorCode")
                stopNsd()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                append("mDNS: stop FAIL error=$errorCode")
            }
        }
        nsdListener = listener
        try {
            nsd.discoverServices("_EasyConn._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            main.postDelayed({
                append("mDNS summary: EasyConn found=$found resolved=$resolved")
                stopNsd()
            }, durationMs)
        } catch (t: Throwable) {
            append("mDNS start exception: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            stopNsd()
        }
    }

    private fun probeTcpConnectOnly(host: String, port: Int) {
        io.execute {
            val s = Socket()
            try {
                val t0 = System.currentTimeMillis()
                s.connect(InetSocketAddress(host, port), 2500)
                append("TCP connect-only: $host:$port OPEN (${System.currentTimeMillis() - t0}ms); nessun payload inviato")
            } catch (t: Throwable) {
                append("TCP connect-only: $host:$port FAIL ${t.javaClass.simpleName}: ${t.message.orEmpty().take(100)}")
            } finally {
                runCatching { s.close() }
            }
        }
    }

    private fun stopNsd() {
        val l = nsdListener
        nsdListener = null
        if (l != null) runCatching { nsd.stopServiceDiscovery(l) }
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun cleanupRequestedNetwork() {
        requestedNetworkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestedNetworkCallback = null
        if (processBoundForTest) {
            runCatching { cm.bindProcessToNetwork(null) }
            processBoundForTest = false
        }
    }

    private fun targetSsid(): String = ssidInput.text?.toString()?.trim().orEmpty()

    private fun isCurrentlyOnTargetWifi(): Boolean {
        val target = targetSsid()
        if (target.isBlank()) return false
        val current = runCatching { wifi.connectionInfo?.ssid?.trim('"') }.getOrNull().orEmpty()
        return current == target
    }

    private fun isVogeLike(name: String?): Boolean {
        val n = name.orEmpty().lowercase(Locale.ROOT)
        return n.contains("voge") || n.contains("easyconn") || n.contains("carbit") || n.contains("tft")
    }

    private fun maskMac(mac: String?): String {
        val p = mac.orEmpty().split(':')
        return if (p.size == 6) "**:**:**:${p[3]}:${p[4]}:${p[5]}" else "masked"
    }

    private fun shareLog() {
        val f = logFile
        if (f == null || !f.exists()) {
            toast("Nessun log disponibile")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Trofeo 500 diagnostica Wi-Fi/Bluetooth")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Condividi log diagnostico"))
        } catch (t: Throwable) {
            append("Share FAIL: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
            toast("Impossibile condividere il log")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        wifiReceiver?.let { runCatching { unregisterReceiver(it) } }
        wifiReceiver = null
        val adapter = BluetoothAdapter.getDefaultAdapter()
        bleCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        bleCallback = null
        gatt?.let { closeGatt(it) }
        stopNsd()
        cleanupRequestedNetwork()
        io.shutdownNow()
    }
}
