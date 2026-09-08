package it.motolink.vogenavbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BleBridge {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;

    private static final String[] SERVICE_HINTS = {
            "fe695f12-fd7b-4f9b-98cc-ee6cf57a776e",
            "00003719-0000-1000-8000-00805f9b34fb",
            "5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e",
            "5456534d",
            "0000fff0",
            "0000b360"
    };
    private static final String[] WRITE_HINTS = {
            "00005352",
            "0000fff1",
            "6052202a-2928-4131-a2d0-456d5673ed2f"
    };
    private static final String[] NOTIFY_HINTS = {
            "0000b362",
            "0000fff1",
            "ab9938d5-c354-4e2b-94f4-364e16ebcd33",
            "00005354",
            "0000fff2"
    };

    public static final class ScanEntry {
        public final BluetoothDevice device;
        public final String name;
        public final String redactedAddress;
        public final int rssi;

        ScanEntry(BluetoothDevice device, String name, String redactedAddress, int rssi) {
            this.device = device;
            this.name = name;
            this.redactedAddress = redactedAddress;
            this.rssi = rssi;
        }

        @Override public String toString() {
            String n = name == null ? "" : name.trim();
            return (n.isEmpty() ? "BLE senza nome" : n) + "  [" + redactedAddress + "]  RSSI " + rssi;
        }
    }

    private static volatile BleBridge instance;

    public static BleBridge get(Context c) {
        if (instance == null) {
            synchronized (BleBridge.class) {
                if (instance == null) instance = new BleBridge(c.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ScanEntry> scanMap = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private boolean scanning;
    private boolean writeBusy;
    private boolean heartbeatRunning;
    private String status = "BLE non connesso";
    private String connectedLabel = "-";

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override public void run() {
            synchronized (BleBridge.this) {
                if (!heartbeatRunning || !isReady()) return;
                if (!queueContainsCommandLocked(VogeNavPacketEncoder.CMD_HEARTBEAT)) {
                    int battery = readBatteryLevel();
                    byte[] frame = VogeNavPacketEncoder.heartbeatFrame(battery);
                    txQueue.addLast(frame);
                    DiagLog.log(app, "HB_QUEUE", "cmd=0x5A battery=" + battery + " hex=" + DiagLog.hex(frame));
                }
            }
            main.post(BleBridge.this::sendNext);
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private BleBridge(Context app) {
        this.app = app;
        DiagLog.ensureHeader(app);
    }

    public synchronized String getStatus() { return status; }
    public synchronized String getConnectedLabel() { return connectedLabel; }
    public synchronized String getWriteUuid() { return writeChar == null ? "-" : writeChar.getUuid().toString(); }
    public synchronized String getNotifyUuid() { return notifyChar == null ? "-" : notifyChar.getUuid().toString(); }
    public synchronized boolean isReady() { return gatt != null && writeChar != null; }
    public synchronized boolean isScanning() { return scanning; }

    public List<ScanEntry> getScanEntries() {
        synchronized (scanMap) { return new ArrayList<>(scanMap.values()); }
    }

    @SuppressLint("MissingPermission")
    public void startScan(long durationMs) {
        if (!hasScanPermission()) {
            setStatus("Permesso scansione Bluetooth mancante");
            DiagLog.log(app, "BLE", "scan_denied_permission");
            return;
        }
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("Bluetooth spento o non disponibile");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("Scanner BLE non disponibile");
            return;
        }
        stopScan();
        scanMap.clear();
        scanning = true;
        setStatus("Scansione BLE in corso...");
        DiagLog.log(app, "BLE", "scan_start durationMs=" + durationMs);
        try {
            scanner.startScan(scanCallback);
            main.postDelayed(this::stopScan, Math.max(3000L, durationMs));
        } catch (Throwable t) {
            scanning = false;
            setStatus("Errore scansione: " + t.getClass().getSimpleName());
            DiagLog.log(app, "BLE", "scan_start_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null && hasScanPermission()) scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        setStatus("Scansione terminata: " + scanMap.size() + " dispositivi");
        DiagLog.log(app, "BLE", "scan_stop count=" + scanMap.size());
    }

    @SuppressLint("MissingPermission")
    public void connect(ScanEntry entry) {
        if (entry == null || entry.device == null) return;
        if (!hasConnectPermission()) {
            setStatus("Permesso connessione Bluetooth mancante");
            return;
        }
        disconnect();
        setStatus("Connessione a " + safeName(entry.device) + "...");
        connectedLabel = entry.toString();
        DiagLog.log(app, "BLE", "connect_request name='" + safeName(entry.device) + "' addr=" + DiagLog.redactMac(safeAddress(entry.device)));
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = entry.device.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = entry.device.connectGatt(app, false, gattCallback);
            }
        } catch (Throwable t) {
            setStatus("Connessione fallita: " + t.getClass().getSimpleName());
            DiagLog.log(app, "BLE", "connect_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void disconnect() {
        stopHeartbeatLocked();
        txQueue.clear();
        writeBusy = false;
        writeChar = null;
        notifyChar = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null && hasConnectPermission()) {
            try { old.disconnect(); } catch (Throwable ignored) {}
            try { old.close(); } catch (Throwable ignored) {}
        }
        connectedLabel = "-";
        setStatus("BLE non connesso");
    }

    public void sendRomeTest() {
        sendModel(VogeNavPacketEncoder.romeTestModel(), "ROMA_SYNTHETIC_300M_RIGHT");
    }

    public synchronized void sendModel(NavModel model, String reason) {
        if (model == null) return;
        if (!isReady()) {
            DiagLog.log(app, "TX_BLOCK", reason + " write characteristic non pronta");
            setStatus("TX bloccato: characteristic VOGE non pronta");
            return;
        }
        List<byte[]> frames = VogeNavPacketEncoder.allFrames(model);
        txQueue.clear();
        txQueue.addAll(frames);
        DiagLog.log(app, "TX_MODEL", "reason=" + reason + " " + model.summary());
        for (byte[] p : frames) {
            DiagLog.log(app, "TX_PLAN", reason + " cmd=0x" + String.format(Locale.US, "%02X", p[1] & 0xFF) + " hex=" + DiagLog.hex(p));
        }
        main.post(this::sendNext);
    }

    private synchronized void startHeartbeat() {
        if (heartbeatRunning || !isReady()) return;
        heartbeatRunning = true;
        main.removeCallbacks(heartbeatRunnable);
        DiagLog.log(app, "HEARTBEAT", "START cmd=0x5A intervalMs=" + HEARTBEAT_INTERVAL_MS + " source=VOGE_GLOBAL_1.1.18");
        main.post(heartbeatRunnable);
    }

    private void stopHeartbeatLocked() {
        if (heartbeatRunning) DiagLog.log(app, "HEARTBEAT", "STOP cmd=0x5A");
        heartbeatRunning = false;
        main.removeCallbacks(heartbeatRunnable);
    }

    private boolean queueContainsCommandLocked(int command) {
        for (byte[] p : txQueue) {
            if (p != null && p.length > 1 && (p[1] & 0xFF) == command) return true;
        }
        return false;
    }

    private int readBatteryLevel() {
        try {
            Intent battery = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return 0;
            return Math.max(0, Math.min(255, battery.getIntExtra("level", 0)));
        } catch (Throwable t) {
            DiagLog.log(app, "HEARTBEAT", "battery_read_error=" + t.getClass().getSimpleName());
            return 0;
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void sendNext() {
        if (writeBusy || txQueue.isEmpty()) return;
        BluetoothGatt localGatt = gatt;
        BluetoothGattCharacteristic localChar = writeChar;
        if (localGatt == null || localChar == null || !hasConnectPermission()) {
            DiagLog.log(app, "TX", "aborted_not_ready remaining=" + txQueue.size());
            txQueue.clear();
            return;
        }
        byte[] p = txQueue.pollFirst();
        int writeType = (localChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (localChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        writeBusy = true;
        boolean accepted;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = localGatt.writeCharacteristic(localChar, p, writeType);
                accepted = result == 0;
                DiagLog.log(app, "TX", "cmd=0x" + String.format(Locale.US, "%02X", p[1] & 0xFF) + " api33_result=" + result + " hex=" + DiagLog.hex(p));
            } else {
                localChar.setWriteType(writeType);
                localChar.setValue(p);
                accepted = localGatt.writeCharacteristic(localChar);
                DiagLog.log(app, "TX", "cmd=0x" + String.format(Locale.US, "%02X", p[1] & 0xFF) + " accepted=" + accepted + " hex=" + DiagLog.hex(p));
            }
        } catch (Throwable t) {
            accepted = false;
            DiagLog.log(app, "TX", "write_exception=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
        if (!accepted) {
            writeBusy = false;
            main.postDelayed(this::sendNext, 150L);
        } else {
            main.postDelayed(() -> {
                synchronized (BleBridge.this) {
                    if (writeBusy) {
                        DiagLog.log(app, "TX", "callback_timeout_continue");
                        writeBusy = false;
                        sendNext();
                    }
                }
            }, 800L);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String addr = safeAddress(d);
            String name = safeName(d);
            if ((name == null || name.trim().isEmpty()) && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            ScanEntry e = new ScanEntry(d, name, DiagLog.redactMac(addr), result.getRssi());
            scanMap.put(addr, e);
            DiagLog.log(app, "BLE_SCAN", "name='" + DiagLog.clean(name) + "' addr=" + DiagLog.redactMac(addr) + " rssi=" + result.getRssi());
            BridgeRuntime.notifyState(app);
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("Scansione fallita: " + errorCode);
            DiagLog.log(app, "BLE", "scan_failed code=" + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            DiagLog.log(app, "BLE", "connection_state status=" + statusCode + " state=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                setStatus("BLE connesso, scopro servizi...");
                try { g.discoverServices(); } catch (Throwable t) {
                    DiagLog.log(app, "BLE", "discover_error=" + t.getClass().getSimpleName());
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (BleBridge.this) {
                    stopHeartbeatLocked();
                    if (gatt == g) {
                        writeChar = null;
                        notifyChar = null;
                        txQueue.clear();
                        writeBusy = false;
                    }
                }
                setStatus("BLE disconnesso (status " + statusCode + ")");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            DiagLog.log(app, "BLE", "services_discovered status=" + statusCode);
            BluetoothGattCharacteristic bestWrite = null;
            BluetoothGattCharacteristic bestNotify = null;
            try {
                for (BluetoothGattService s : g.getServices()) {
                    String su = s.getUuid().toString().toLowerCase(Locale.US);
                    boolean serviceHint = matches(su, SERVICE_HINTS);
                    DiagLog.log(app, "BLE_SERVICE", "uuid=" + su + " knownVogeHint=" + serviceHint);
                    for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        String cu = c.getUuid().toString().toLowerCase(Locale.US);
                        int p = c.getProperties();
                        boolean writable = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
                        boolean notifiable = (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                        boolean wh = matches(cu, WRITE_HINTS);
                        boolean nh = matches(cu, NOTIFY_HINTS);
                        DiagLog.log(app, "BLE_CHAR", "service=" + su + " uuid=" + cu + " props=0x" + Integer.toHexString(p)
                                + " writable=" + writable + " notify=" + notifiable + " writeHint=" + wh + " notifyHint=" + nh);
                        if (bestWrite == null && writable && wh) bestWrite = c;
                        if (bestNotify == null && notifiable && nh) bestNotify = c;
                    }
                }

                synchronized (BleBridge.this) {
                    writeChar = bestWrite;
                    notifyChar = bestNotify;
                }

                if (bestWrite == null) {
                    setStatus("Connesso, ma nessuna write characteristic VOGE nota trovata");
                    DiagLog.log(app, "BLE", "NO_KNOWN_VOGE_WRITE_CHARACTERISTIC");
                } else {
                    setStatus("VOGE write pronta: " + bestWrite.getUuid());
                    DiagLog.log(app, "BLE", "selected_write=" + bestWrite.getUuid());
                }

                if (bestNotify != null) {
                    DiagLog.log(app, "BLE", "selected_notify=" + bestNotify.getUuid());
                    boolean local = g.setCharacteristicNotification(bestNotify, true);
                    DiagLog.log(app, "BLE", "set_notify=" + local);
                    BluetoothGattDescriptor d = bestNotify.getDescriptor(CCCD);
                    if (d != null) {
                        byte[] enable = (bestNotify.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                        if (Build.VERSION.SDK_INT >= 33) {
                            int r = g.writeDescriptor(d, enable);
                            DiagLog.log(app, "BLE", "cccd_write_api33=" + r);
                        } else {
                            d.setValue(enable);
                            boolean r = g.writeDescriptor(d);
                            DiagLog.log(app, "BLE", "cccd_write=" + r);
                        }
                    }
                }
            } catch (Throwable t) {
                setStatus("Errore servizi BLE: " + t.getClass().getSimpleName());
                DiagLog.log(app, "BLE", "service_parse_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
            }

            if (bestWrite != null) {
                main.postDelayed(BleBridge.this::startHeartbeat, 750L);
            }
            BridgeRuntime.notifyState(app);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            DiagLog.log(app, "TX_ACK", "uuid=" + c.getUuid() + " status=" + statusCode);
            synchronized (BleBridge.this) { writeBusy = false; }
            main.postDelayed(BleBridge.this::sendNext, 100L);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int statusCode) {
            DiagLog.log(app, "BLE", "descriptor_write uuid=" + d.getUuid() + " status=" + statusCode);
            if (statusCode == BluetoothGatt.GATT_SUCCESS) main.post(BleBridge.this::startHeartbeat);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            logRx(c, c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            logRx(c, value);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            DiagLog.log(app, "RX_READ", "uuid=" + c.getUuid() + " status=" + statusCode + " hex=" + DiagLog.hex(c.getValue()));
        }
    };

    private void logRx(BluetoothGattCharacteristic c, byte[] value) {
        DiagLog.log(app, "RX", "uuid=" + c.getUuid() + " hex=" + DiagLog.hex(value));
        BridgeRuntime.notifyState(app);
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try { return d == null ? "" : d.getName(); } catch (Throwable t) { return ""; }
    }

    @SuppressLint("MissingPermission")
    private String safeAddress(BluetoothDevice d) {
        try { return d == null ? "unknown" : d.getAddress(); } catch (Throwable t) { return "unknown"; }
    }

    private static boolean matches(String value, String[] hints) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.US);
        for (String h : hints) {
            if (v.contains(h.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private synchronized void setStatus(String s) {
        status = s;
        DiagLog.log(app, "BLE_STATE", s);
        BridgeRuntime.notifyState(app);
    }
}
