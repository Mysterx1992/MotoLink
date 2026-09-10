package it.motolink.diag.trofeo500;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone diagnostic for the Voge Trofeo 500 Wi-Fi / Bluetooth path.
 *
 * Safety boundary:
 * - passive radio/network inspection by default;
 * - BLE GATT service discovery only (no characteristic read/write/notify);
 * - EasyConn mDNS discovery and TCP connect-only probe (no application payload);
 * - explicit Wi-Fi association test only after the user presses the dedicated button;
 * - Wi-Fi password is used in memory only and is never written to the diagnostic log.
 */
public class MainActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private WifiManager wifi;
    private ConnectivityManager cm;
    private NsdManager nsd;
    private BluetoothAdapter bt;

    private EditText ssidInput;
    private EditText passInput;
    private TextView logView;

    private final StringBuilder logText = new StringBuilder();
    private File logFile;

    private BroadcastReceiver wifiScanReceiver;
    private BroadcastReceiver btReceiver;
    private ScanCallback bleScanCallback;
    private BluetoothGatt diagnosticGatt;
    private NsdManager.DiscoveryListener nsdListener;
    private WifiManager.MulticastLock multicastLock;
    private ConnectivityManager.NetworkCallback requestedNetworkCallback;
    private boolean processBoundForTest = false;

    private final Map<String, android.bluetooth.le.ScanResult> bleCandidates = new LinkedHashMap<>();
    private int bleTotalResults = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        bt = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        resetLog();
        ensurePermissions();
    }

    private void buildUi() {
        int pad = (int) (16f * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Trofeo 500 - Diagnostica Wi-Fi / Bluetooth");
        title.setTextSize(22f);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Diagnostica tecnica read-only. Inserisci SSID e password esatti mostrati sul TFT. La password non viene mai scritta nel log.");
        intro.setTextSize(14f);
        intro.setPadding(0, pad / 2, 0, pad);
        root.addView(intro);

        ssidInput = new EditText(this);
        ssidInput.setHint("SSID esatto (es. VOGE-5G-xxxx)");
        ssidInput.setSingleLine(true);
        root.addView(ssidInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        passInput = new EditText(this);
        passInput.setHint("Password Wi-Fi (non salvata nel log)");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passInput.setSingleLine(true);
        root.addView(passInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button passive = new Button(this);
        passive.setText("1. AVVIA DIAGNOSI PASSIVA");
        passive.setOnClickListener(v -> startPassiveDiagnosis());
        root.addView(passive);

        Button wifiTest = new Button(this);
        wifiTest.setText("2. TESTA CONNESSIONE WIFI TFT");
        wifiTest.setOnClickListener(v -> testTargetWifi());
        root.addView(wifiTest);

        Button share = new Button(this);
        share.setText("CONDIVIDI LOG");
        share.setOnClickListener(v -> shareLog());
        root.addView(share);

        Button clear = new Button(this);
        clear.setText("PULISCI LOG");
        clear.setOnClickListener(v -> resetLog());
        root.addView(clear);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, pad, 0, pad);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private boolean ensurePermissions() {
        ArrayList<String> wanted = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), 100);
            return false;
        }
        return true;
    }

    private void resetLog() {
        File dir = new File(getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
        logFile = new File(dir, "Trofeo500_Diagnostic_" + stamp + ".txt");
        synchronized (logText) {
            logText.setLength(0);
        }
        append("Trofeo 500 Wi-Fi/Bluetooth Diagnostic V1");
        append("Generato: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(new Date()));
        append("Modalita: read-only; nessun comando applicativo viene inviato al TFT");
        append("La password Wi-Fi NON viene registrata");
        append("---");
    }

    private void append(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(new Date()) + "  " + msg + "\n";
        synchronized (logText) {
            logText.append(line);
            if (logFile != null) {
                try (FileWriter fw = new FileWriter(logFile, false)) {
                    fw.write(logText.toString());
                } catch (Throwable ignored) {
                }
            }
        }
        main.post(() -> {
            if (logView != null) logView.setText(logText.toString());
        });
    }

    private void startPassiveDiagnosis() {
        if (!ensurePermissions()) {
            toast("Concedi i permessi richiesti e poi premi di nuovo AVVIA DIAGNOSI");
            return;
        }
        append("=== DIAGNOSI PASSIVA AVVIATA ===");
        append("Telefono: " + Build.MANUFACTURER + " " + Build.MODEL + "; Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT);

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean locationOn = false;
        try { locationOn = lm != null && lm.isLocationEnabled(); } catch (Throwable ignored) {}
        append("Servizi posizione Android: " + (locationOn ? "ON" : "OFF"));

        boolean wifiEnabled = false;
        boolean fiveG = false;
        try { wifiEnabled = wifi != null && wifi.isWifiEnabled(); } catch (Throwable ignored) {}
        try { fiveG = wifi != null && wifi.is5GHzBandSupported(); } catch (Throwable ignored) {}
        append("Wi-Fi: enabled=" + wifiEnabled + "; 5GHzSupported=" + fiveG);

        networkSnapshot("rete attiva", cm != null ? cm.getActiveNetwork() : null);
        bluetoothSnapshot();
        startBluetoothDiscovery();
        startBleScan();
        startWifiScan();

        main.postDelayed(() -> {
            if (isCurrentlyOnTargetWifi()) {
                append("SSID target risulta gia collegato: avvio discovery _EasyConn._tcp. read-only");
                startNsdDiscovery(12000L);
            } else {
                append("SSID target non risulta collegato: usa TESTA CONNESSIONE WIFI TFT per provare l'associazione");
            }
        }, 13000L);

        main.postDelayed(() -> append("=== FINE FASE PASSIVA ==="), 27000L);
    }

    private void bluetoothSnapshot() {
        if (bt == null) {
            append("Bluetooth: hardware non disponibile");
            return;
        }
        boolean enabled = false;
        int state = -1;
        try {
            enabled = bt.isEnabled();
            state = bt.getState();
        } catch (Throwable t) {
            append("Bluetooth state FAIL: " + shortError(t));
            return;
        }
        append("Bluetooth: enabled=" + enabled + "; state=" + state);
        if (!enabled) return;

        try {
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            append("Bluetooth paired/bonded count=" + (bonded == null ? 0 : bonded.size()));
            int candidates = 0;
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = safeBtName(d);
                    if (!isVogeLike(name)) continue;
                    candidates++;
                    StringBuilder uuids = new StringBuilder();
                    ParcelUuid[] arr = null;
                    try { arr = d.getUuids(); } catch (Throwable ignored) {}
                    if (arr != null) {
                        for (ParcelUuid u : arr) {
                            if (uuids.length() > 0) uuids.append(',');
                            uuids.append(u.getUuid());
                        }
                    }
                    int type = -1;
                    try { type = d.getType(); } catch (Throwable ignored) {}
                    append("Bluetooth paired candidate: name=" + name + " type=" + type + " addr=" + maskMac(d.getAddress()) + " cachedUUIDs=" + (uuids.length() == 0 ? "none" : uuids));
                }
            }
            if (candidates == 0) append("Bluetooth paired: nessun candidato VOGE/EasyConn/Carbit/TFT per nome");
        } catch (Throwable t) {
            append("Bluetooth paired FAIL: " + shortError(t));
        }
    }

    private void startBluetoothDiscovery() {
        if (bt == null) return;
        try {
            if (!bt.isEnabled()) return;
        } catch (Throwable t) {
            return;
        }
        stopBluetoothDiscovery();
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    @SuppressWarnings("deprecation")
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null) return;
                    String name = safeBtName(d);
                    if (!isVogeLike(name)) return;
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    append("Bluetooth Classic candidate: name=" + name + " rssi=" + rssi + " addr=" + maskMac(d.getAddress()));
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    append("Bluetooth Classic discovery: completata");
                    stopBluetoothDiscovery();
                } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                    @SuppressWarnings("deprecation")
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || !isVogeLike(safeBtName(d))) return;
                    @SuppressWarnings("deprecation")
                    android.os.Parcelable[] raw = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    StringBuilder sb = new StringBuilder();
                    if (raw != null) {
                        for (android.os.Parcelable p : raw) {
                            if (p instanceof ParcelUuid) {
                                if (sb.length() > 0) sb.append(',');
                                sb.append(((ParcelUuid) p).getUuid());
                            }
                        }
                    }
                    append("Bluetooth SDP UUIDs: name=" + safeBtName(d) + " UUIDs=" + (sb.length() == 0 ? "none" : sb));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_UUID);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(btReceiver, f);

            boolean started = bt.startDiscovery();
            append("Bluetooth Classic discovery: start=" + started + " (12 s, nessun pairing automatico)");

            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (isVogeLike(safeBtName(d))) {
                        boolean sdp = false;
                        try { sdp = d.fetchUuidsWithSdp(); } catch (Throwable ignored) {}
                        append("Bluetooth SDP request: name=" + safeBtName(d) + " started=" + sdp);
                    }
                }
            }
            main.postDelayed(this::stopBluetoothDiscovery, 12500L);
        } catch (Throwable t) {
            append("Bluetooth Classic discovery FAIL: " + shortError(t));
            stopBluetoothDiscovery();
        }
    }

    private void stopBluetoothDiscovery() {
        try { if (bt != null && bt.isDiscovering()) bt.cancelDiscovery(); } catch (Throwable ignored) {}
        if (btReceiver != null) {
            try { unregisterReceiver(btReceiver); } catch (Throwable ignored) {}
            btReceiver = null;
        }
    }

    private void startBleScan() {
        if (bt == null) {
            append("BLE scan: BluetoothAdapter non disponibile");
            return;
        }
        try {
            if (!bt.isEnabled() || bt.getBluetoothLeScanner() == null) {
                append("BLE scan: Bluetooth OFF o scanner non disponibile");
                return;
            }
        } catch (Throwable t) {
            append("BLE scan setup FAIL: " + shortError(t));
            return;
        }

        bleCandidates.clear();
        bleTotalResults = 0;
        ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                bleTotalResults++;
                String name = null;
                try {
                    ScanRecord rec = result.getScanRecord();
                    if (rec != null) name = rec.getDeviceName();
                    if (name == null) name = safeBtName(result.getDevice());
                } catch (Throwable ignored) {}
                if (!isVogeLike(name)) return;
                String key;
                try { key = result.getDevice().getAddress(); } catch (Throwable t) { key = String.valueOf(result.hashCode()); }
                android.bluetooth.le.ScanResult old = bleCandidates.get(key);
                if (old == null || result.getRssi() > old.getRssi()) bleCandidates.put(key, result);
            }

            @Override
            public void onScanFailed(int errorCode) {
                append("BLE scan FAIL error=" + errorCode);
            }
        };
        bleScanCallback = cb;
        try {
            bt.getBluetoothLeScanner().startScan(null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
            append("BLE scan: avviato per 12 s (advertising only)");
            main.postDelayed(() -> finishBleScan(cb), 12000L);
        } catch (Throwable t) {
            append("BLE scan exception: " + shortError(t));
            bleScanCallback = null;
        }
    }

    private void finishBleScan(ScanCallback cb) {
        try { if (bt != null && bt.getBluetoothLeScanner() != null) bt.getBluetoothLeScanner().stopScan(cb); } catch (Throwable ignored) {}
        if (bleScanCallback == cb) bleScanCallback = null;
        append("BLE scan: completato totalResults=" + bleTotalResults + " VOGE-like=" + bleCandidates.size());

        List<android.bluetooth.le.ScanResult> list = new ArrayList<>(bleCandidates.values());
        Collections.sort(list, Comparator.comparingInt(android.bluetooth.le.ScanResult::getRssi).reversed());
        for (android.bluetooth.le.ScanResult r : list) {
            ScanRecord rec = r.getScanRecord();
            String name = rec != null ? rec.getDeviceName() : null;
            if (name == null) name = safeBtName(r.getDevice());
            String services = "none";
            if (rec != null && rec.getServiceUuids() != null && !rec.getServiceUuids().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ParcelUuid u : rec.getServiceUuids()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(u.getUuid());
                }
                services = sb.toString();
            }
            String manufacturer = "none";
            if (rec != null) {
                SparseArray<byte[]> md = rec.getManufacturerSpecificData();
                if (md != null && md.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < md.size(); i++) {
                        if (sb.length() > 0) sb.append(',');
                        byte[] b = md.valueAt(i);
                        sb.append("0x").append(Integer.toHexString(md.keyAt(i))).append(':').append(b == null ? 0 : b.length).append('B');
                    }
                    manufacturer = sb.toString();
                }
            }
            String serviceDataKeys = "none";
            if (rec != null && rec.getServiceData() != null && !rec.getServiceData().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ParcelUuid u : rec.getServiceData().keySet()) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(u.getUuid());
                }
                serviceDataKeys = sb.toString();
            }
            append("BLE candidate: name=" + name + " rssi=" + r.getRssi() + " addr=" + maskMac(r.getDevice().getAddress()) +
                    " services=" + services + " manufacturer=" + manufacturer + " serviceDataKeys=" + serviceDataKeys);
        }

        if (!list.isEmpty()) discoverGattReadOnly(list.get(0).getDevice());
    }

    private void discoverGattReadOnly(BluetoothDevice device) {
        closeDiagnosticGatt();
        append("BLE GATT read-only: connect a " + safeBtName(device) + " " + maskMac(device.getAddress()));
        BluetoothGattCallback cb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                append("BLE GATT state: status=" + status + " newState=" + newState);
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    boolean started = false;
                    try { started = gatt.discoverServices(); } catch (Throwable ignored) {}
                    append("BLE GATT discoverServices started=" + started);
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGattInstance(gatt);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                List<BluetoothGattService> services = gatt.getServices();
                append("BLE GATT services: status=" + status + " count=" + (services == null ? 0 : services.size()));
                if (services != null) {
                    for (BluetoothGattService s : services) {
                        append("  GATT service " + s.getUuid());
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            append("    char " + c.getUuid() + " properties=0x" + Integer.toHexString(c.getProperties()) +
                                    " permissions=0x" + Integer.toHexString(c.getPermissions()));
                        }
                    }
                }
                append("BLE GATT read-only: fine discovery; nessuna characteristic read/write/notify eseguita");
                closeGattInstance(gatt);
            }
        };
        try {
            diagnosticGatt = device.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
            main.postDelayed(() -> {
                if (diagnosticGatt != null) {
                    append("BLE GATT timeout guard: chiudo la connessione diagnostica");
                    closeDiagnosticGatt();
                }
            }, 12000L);
        } catch (Throwable t) {
            append("BLE GATT connect FAIL: " + shortError(t));
            closeDiagnosticGatt();
        }
    }

    private void closeGattInstance(BluetoothGatt gatt) {
        try { gatt.disconnect(); } catch (Throwable ignored) {}
        try { gatt.close(); } catch (Throwable ignored) {}
        if (diagnosticGatt == gatt) diagnosticGatt = null;
    }

    private void closeDiagnosticGatt() {
        BluetoothGatt g = diagnosticGatt;
        diagnosticGatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Throwable ignored) {}
            try { g.close(); } catch (Throwable ignored) {}
        }
    }

    private void startWifiScan() {
        stopWifiScanReceiver();
        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean updated = intent != null && intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                append("Wi-Fi scan broadcast: resultsUpdated=" + updated);
                dumpWifiScanResults();
                stopWifiScanReceiver();
            }
        };
        try {
            IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiScanReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wifiScanReceiver, f);
            boolean started = wifi.startScan();
            append("Wi-Fi scan: startScan=" + started);
            main.postDelayed(() -> {
                if (wifiScanReceiver != null) {
                    append("Wi-Fi scan: timeout broadcast; leggo i risultati/cache disponibili");
                    dumpWifiScanResults();
                    stopWifiScanReceiver();
                }
            }, 12000L);
        } catch (Throwable t) {
            append("Wi-Fi scan FAIL: " + shortError(t));
            stopWifiScanReceiver();
        }
    }

    private void stopWifiScanReceiver() {
        if (wifiScanReceiver != null) {
            try { unregisterReceiver(wifiScanReceiver); } catch (Throwable ignored) {}
            wifiScanReceiver = null;
        }
    }

    private void dumpWifiScanResults() {
        try {
            String target = targetSsid();
            List<android.net.wifi.ScanResult> all = wifi.getScanResults();
            if (all == null) all = Collections.emptyList();
            ArrayList<android.net.wifi.ScanResult> matches = new ArrayList<>();
            for (android.net.wifi.ScanResult r : all) {
                String s = r.SSID == null ? "" : r.SSID;
                if (isVogeLike(s) || (!target.isEmpty() && target.equals(s))) matches.add(r);
            }
            matches.sort(Comparator.comparingInt((android.net.wifi.ScanResult r) -> r.level).reversed());
            append("Wi-Fi scan: total=" + all.size() + "; target/VOGE-like=" + matches.size());
            boolean visible = false;
            for (android.net.wifi.ScanResult r : matches) {
                if (target.equals(r.SSID)) visible = true;
                append("Wi-Fi candidate: SSID=" + r.SSID + " RSSI=" + r.level + "dBm freq=" + r.frequency +
                        "MHz width=" + r.channelWidth + " capabilities=" + safeLimit(r.capabilities, 180));
            }
            if (!target.isEmpty()) append("Wi-Fi target '" + target + "' visible=" + visible);
        } catch (Throwable t) {
            append("Wi-Fi scan results FAIL: " + shortError(t));
        }
    }

    private void networkSnapshot(String label, Network network) {
        try {
            String ssid = "unknown";
            int rssi = 0;
            int speed = 0;
            int freq = 0;
            if (wifi != null && wifi.getConnectionInfo() != null) {
                ssid = cleanSsid(wifi.getConnectionInfo().getSSID());
                rssi = wifi.getConnectionInfo().getRssi();
                speed = wifi.getConnectionInfo().getLinkSpeed();
                freq = wifi.getConnectionInfo().getFrequency();
            }
            append(label + ": Wi-Fi SSID=" + ssid + " RSSI=" + rssi + " linkSpeed=" + speed + "Mbps freq=" + freq + "MHz");
            if (network == null) {
                append(label + ": activeNetwork=null");
                return;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            ArrayList<String> transports = new ArrayList<>();
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("BT");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETH");
            }
            append(label + ": transports=" + (transports.isEmpty() ? "unknown" : join(transports)) +
                    " internet=" + (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) +
                    " validated=" + (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)));
            dumpLinkProperties(label, cm.getLinkProperties(network));
        } catch (Throwable t) {
            append(label + " snapshot FAIL: " + shortError(t));
        }
    }

    private void dumpLinkProperties(String label, LinkProperties lp) {
        if (lp == null) {
            append(label + ": LinkProperties=null");
            return;
        }
        StringBuilder addresses = new StringBuilder();
        for (android.net.LinkAddress a : lp.getLinkAddresses()) {
            if (addresses.length() > 0) addresses.append(',');
            addresses.append(a);
        }
        StringBuilder dns = new StringBuilder();
        for (java.net.InetAddress d : lp.getDnsServers()) {
            if (dns.length() > 0) dns.append(',');
            dns.append(d.getHostAddress());
        }
        append(label + ": iface=" + lp.getInterfaceName() + "; addresses=" + (addresses.length() == 0 ? "none" : addresses));
        append(label + ": dns=" + (dns.length() == 0 ? "none" : dns));
        StringBuilder routes = new StringBuilder();
        for (RouteInfo r : lp.getRoutes()) {
            if (routes.length() > 0) routes.append(" | ");
            String gateway = r.getGateway() == null ? "-" : r.getGateway().getHostAddress();
            routes.append("dst=").append(r.getDestination()).append(" gw=").append(gateway).append(" iface=").append(r.getInterface());
        }
        append(label + ": routes=" + (routes.length() == 0 ? "none" : routes));
    }

    private void testTargetWifi() {
        if (!ensurePermissions()) {
            toast("Concedi i permessi richiesti e poi premi di nuovo TESTA CONNESSIONE");
            return;
        }
        String ssid = targetSsid();
        String pass = passInput.getText() == null ? "" : passInput.getText().toString();
        if (ssid.isEmpty()) {
            toast("Inserisci l'SSID esatto mostrato sul TFT");
            return;
        }
        if (pass.length() < 8) {
            toast("Inserisci la password Wi-Fi mostrata sul TFT");
            return;
        }
        append("=== TEST ASSOCIAZIONE WIFI AVVIATO target='" + ssid + "' ===");
        append("Password: presente (" + pass.length() + " caratteri), NON registrata");
        cleanupRequestedNetwork();

        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build();

            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    append("Wi-Fi target: onAvailable -> ASSOCIAZIONE RIUSCITA");
                    boolean bound = false;
                    try { bound = cm.bindProcessToNetwork(network); } catch (Throwable ignored) {}
                    processBoundForTest = bound;
                    append("Wi-Fi target: bindProcessToNetwork=" + bound);
                    main.postDelayed(() -> {
                        networkSnapshot("rete TFT richiesta", network);
                        startNsdDiscovery(15000L);
                    }, 800L);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    append("Wi-Fi target: capabilities internet=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) +
                            " validated=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    dumpLinkProperties("rete TFT richiesta", lp);
                }

                @Override
                public void onUnavailable() {
                    append("Wi-Fi target: onUnavailable -> ASSOCIAZIONE/APPROVAZIONE NON RIUSCITA entro timeout");
                    cleanupRequestedNetwork();
                }

                @Override
                public void onLost(Network network) {
                    append("Wi-Fi target: onLost -> collegamento TFT perso");
                    cleanupRequestedNetwork();
                }
            };
            requestedNetworkCallback = cb;
            cm.requestNetwork(req, cb, 30000);
            append("Android puo mostrare una richiesta di connessione al Wi-Fi del TFT: confermare sul telefono.");
            main.postDelayed(() -> {
                if (requestedNetworkCallback == cb) {
                    append("Wi-Fi target: fine finestra diagnostica; rilascio richiesta rete");
                    cleanupRequestedNetwork();
                }
            }, 50000L);
        } catch (Throwable t) {
            append("Wi-Fi target request FAIL: " + shortError(t));
            cleanupRequestedNetwork();
        }
    }

    private void startNsdDiscovery(long durationMs) {
        stopNsd();
        if (wifi == null || nsd == null) {
            append("mDNS: servizi Android non disponibili");
            return;
        }
        try {
            multicastLock = wifi.createMulticastLock("Trofeo500Diag-mDNS");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable t) {
            append("mDNS multicast lock FAIL: " + shortError(t));
        }

        final int[] found = {0};
        final int[] resolved = {0};
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                append("mDNS: discovery started type=" + serviceType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String type = serviceInfo.getServiceType();
                if (type == null || !type.equalsIgnoreCase("_EasyConn._tcp.")) return;
                found[0]++;
                append("mDNS: EasyConn found name=" + serviceInfo.getServiceName());
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            append("mDNS: resolve FAIL name=" + serviceInfo.getServiceName() + " error=" + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            resolved[0]++;
                            String host = info.getHost() == null ? null : info.getHost().getHostAddress();
                            Set<String> keys = info.getAttributes() == null ? Collections.emptySet() : info.getAttributes().keySet();
                            append("mDNS: RESOLVED EasyConn name=" + info.getServiceName() + " host=" + host + " port=" + info.getPort() +
                                    " TXTkeys=" + (keys.isEmpty() ? "none" : join(new ArrayList<>(keys))));
                            if (host != null && info.getPort() > 0) probeTcpConnectOnly(host, info.getPort());
                        }
                    });
                } catch (Throwable t) {
                    append("mDNS resolve exception: " + shortError(t));
                }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                append("mDNS: service lost name=" + serviceInfo.getServiceName());
            }
            @Override public void onDiscoveryStopped(String serviceType) {
                append("mDNS: discovery stopped");
            }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                append("mDNS: start FAIL error=" + errorCode);
                stopNsd();
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                append("mDNS: stop FAIL error=" + errorCode);
            }
        };
        nsdListener = listener;
        try {
            nsd.discoverServices("_EasyConn._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            main.postDelayed(() -> {
                append("mDNS summary: EasyConn found=" + found[0] + " resolved=" + resolved[0]);
                stopNsd();
            }, durationMs);
        } catch (Throwable t) {
            append("mDNS start exception: " + shortError(t));
            stopNsd();
        }
    }

    private void probeTcpConnectOnly(String host, int port) {
        io.execute(() -> {
            Socket socket = new Socket();
            try {
                long t0 = System.currentTimeMillis();
                socket.connect(new InetSocketAddress(host, port), 2500);
                append("TCP connect-only: " + host + ":" + port + " OPEN (" + (System.currentTimeMillis() - t0) + "ms); nessun payload inviato");
            } catch (Throwable t) {
                append("TCP connect-only: " + host + ":" + port + " FAIL " + shortError(t));
            } finally {
                try { socket.close(); } catch (Throwable ignored) {}
            }
        });
    }

    private void stopNsd() {
        NsdManager.DiscoveryListener l = nsdListener;
        nsdListener = null;
        if (l != null && nsd != null) {
            try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) {}
        }
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) {}
            multicastLock = null;
        }
    }

    private void cleanupRequestedNetwork() {
        ConnectivityManager.NetworkCallback cb = requestedNetworkCallback;
        requestedNetworkCallback = null;
        if (cb != null && cm != null) {
            try { cm.unregisterNetworkCallback(cb); } catch (Throwable ignored) {}
        }
        if (processBoundForTest && cm != null) {
            try { cm.bindProcessToNetwork(null); } catch (Throwable ignored) {}
            processBoundForTest = false;
        }
    }

    private String targetSsid() {
        return ssidInput == null || ssidInput.getText() == null ? "" : ssidInput.getText().toString().trim();
    }

    private boolean isCurrentlyOnTargetWifi() {
        String target = targetSsid();
        if (target.isEmpty() || wifi == null) return false;
        try {
            String current = cleanSsid(wifi.getConnectionInfo().getSSID());
            return target.equals(current);
        } catch (Throwable t) {
            return false;
        }
    }

    private String cleanSsid(String s) {
        if (s == null) return "unknown";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private boolean isVogeLike(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("voge") || n.contains("easyconn") || n.contains("carbit") || n.contains("tft");
    }

    private String safeBtName(BluetoothDevice d) {
        if (d == null) return "(null)";
        try {
            String n = d.getName();
            return n == null || n.trim().isEmpty() ? "(senza nome)" : n.trim();
        } catch (Throwable t) {
            return "(nome non accessibile)";
        }
    }

    private String maskMac(String mac) {
        if (mac == null) return "masked";
        String[] p = mac.split(":");
        if (p.length == 6) return "**:**:**:" + p[3] + ":" + p[4] + ":" + p[5];
        return "masked";
    }

    private String shortError(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null) m = "";
        return t.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + safeLimit(m, 160));
    }

    private String safeLimit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    private void shareLog() {
        if (logFile == null || !logFile.exists()) {
            toast("Nessun log disponibile");
            return;
        }
        try {
            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", logFile);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "Trofeo 500 diagnostica Wi-Fi/Bluetooth");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Condividi log diagnostico"));
        } catch (Throwable t) {
            append("Share FAIL: " + shortError(t));
            toast("Impossibile condividere il log");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        stopWifiScanReceiver();
        stopBluetoothDiscovery();
        if (bleScanCallback != null && bt != null) {
            try {
                if (bt.getBluetoothLeScanner() != null) bt.getBluetoothLeScanner().stopScan(bleScanCallback);
            } catch (Throwable ignored) {}
            bleScanCallback = null;
        }
        closeDiagnosticGatt();
        stopNsd();
        cleanupRequestedNetwork();
        io.shutdownNow();
        super.onDestroy();
    }
}
