package it.motolink.diag.trofeo500;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trofeo 500 hotspot diagnostic.
 *
 * Expected topology for this motorcycle:
 *   Android phone = Wi-Fi hotspot / EasyConn server side
 *   Trofeo 500 TFT = Wi-Fi client that should join the phone hotspot
 *
 * The diagnostic NEVER changes hotspot credentials and NEVER sends EasyConn
 * application payloads to the TFT. It only observes local interfaces/neighbours,
 * performs mDNS discovery and accepts/logs inbound TCP connection attempts on
 * the three known EasyConn ports (10920/10921/10922) without replying.
 */
public class MainActivity extends Activity {
    private static final int REQ_BT = 42;
    private static final long MONITOR_MS = 90_000L;
    private static final String EXPECTED_SSID = "VOGE-36f";
    private static final String EASYCONN_TYPE = "_EasyConn._tcp.";
    private static final int[] PORTS = {10920, 10921, 10922};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<ServerSocket> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private final AtomicInteger inboundCount = new AtomicInteger();
    private final AtomicInteger mdnsCount = new AtomicInteger();

    private TextView logView;
    private Button startButton;
    private Button stopButton;
    private ConnectivityManager cm;
    private WifiManager wifi;
    private NsdManager nsd;
    private BluetoothAdapter bt;
    private volatile boolean monitoring = false;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private String lastInterfaceSnapshot = "";
    private String lastNeighbourSnapshot = "";
    private String lastNetworkSnapshot = "";
    private boolean hotspotLikeInterfaceSeen = false;
    private boolean neighbourEvidenceSeen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cm = getSystemService(ConnectivityManager.class);
        wifi = getApplicationContext().getSystemService(WifiManager.class);
        nsd = getSystemService(NsdManager.class);
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        bt = bm == null ? null : bm.getAdapter();
        buildUi();
        append("Trofeo 500 Hotspot Diagnostic V1.1.0");
        append("Generato: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(new Date()));
        append("Topologia attesa: telefono = HOTSPOT; TFT Trofeo 500 = CLIENT Wi-Fi");
        append("SSID hotspot atteso sul telefono: " + EXPECTED_SSID);
        append("La password hotspot NON viene letta, memorizzata o registrata");
        append("Nessun payload applicativo EasyConn viene inviato al TFT");
        append("---");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Trofeo 500 - Diagnostica Hotspot");
        title.setTextSize(22f);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("1) Sul telefono configura/attiva l'hotspot con SSID VOGE-36f e la password mostrata dal TFT.\n" +
                "2) Chiudi MotoLink durante il test.\n" +
                "3) Accendi TFT e Bluetooth.\n" +
                "4) Premi AVVIA MONITOR e attendi 90 secondi.\n" +
                "5) Condividi il log.\n\n" +
                "Il test non cambia la configurazione del TFT e non risponde ai protocolli EasyConn.");
        info.setTextSize(15f);
        info.setPadding(0, dp(10), 0, dp(10));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        Button settingsButton = new Button(this);
        settingsButton.setText("APRI IMPOSTAZIONI HOTSPOT");
        settingsButton.setOnClickListener(v -> openHotspotSettings());
        root.addView(settingsButton, new LinearLayout.LayoutParams(-1, -2));

        startButton = new Button(this);
        startButton.setText("AVVIA MONITOR HOTSPOT (90 s)");
        startButton.setOnClickListener(v -> startMonitor());
        root.addView(startButton, new LinearLayout.LayoutParams(-1, -2));

        stopButton = new Button(this);
        stopButton.setText("FERMA MONITOR");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopMonitor("fermato manualmente"));
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, -2));

        Button shareButton = new Button(this);
        shareButton.setText("CONDIVIDI LOG");
        shareButton.setOnClickListener(v -> shareLog());
        root.addView(shareButton, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sp.topMargin = dp(8);
        root.addView(scroll, sp);

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openHotspotSettings() {
        try {
            startActivity(new Intent("android.settings.TETHER_SETTINGS"));
        } catch (Throwable first) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Throwable second) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    private boolean ensureBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        return false;
    }

    private void startMonitor() {
        if (monitoring) return;
        inboundCount.set(0);
        mdnsCount.set(0);
        hotspotLikeInterfaceSeen = false;
        neighbourEvidenceSeen = false;
        lastInterfaceSnapshot = "";
        lastNeighbourSnapshot = "";
        lastNetworkSnapshot = "";
        monitoring = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        append("=== MONITOR HOTSPOT AVVIATO ===");
        append("Telefono: " + Build.MANUFACTURER + " " + Build.MODEL + "; Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT);
        append("NOTA: Android non consente a una normale app di leggere in modo affidabile SSID/password dell'hotspot di sistema.");
        append("Verificare manualmente che l'hotspot attivo sia esattamente '" + EXPECTED_SSID + "'.");

        dumpBluetoothBonded();
        logChangedNetworkSnapshot(true);
        logChangedInterfaceSnapshot(true);
        logChangedNeighbourSnapshot(true);
        startTcpListeners();
        startMdns();

        main.postDelayed(pollRunnable, 2500L);
        main.postDelayed(() -> {
            if (monitoring) stopMonitor("fine automatica 90 s");
        }, MONITOR_MS);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!monitoring) return;
            logChangedNetworkSnapshot(false);
            logChangedInterfaceSnapshot(false);
            logChangedNeighbourSnapshot(false);
            main.postDelayed(this, 3000L);
        }
    };

    private void stopMonitor(String reason) {
        if (!monitoring && listeners.isEmpty() && discoveryListener == null) return;
        monitoring = false;
        main.removeCallbacks(pollRunnable);
        stopMdns();
        stopTcpListeners();
        logChangedNetworkSnapshot(false);
        logChangedInterfaceSnapshot(false);
        logChangedNeighbourSnapshot(false);
        append("=== RISULTATO MONITOR ===");
        append("incoming EasyConn TCP = " + inboundCount.get());
        append("mDNS EasyConn trovato = " + mdnsCount.get());
        append("interfaccia hotspot-like osservata = " + hotspotLikeInterfaceSeen);
        append("evidenza neighbour/client locale osservata = " + neighbourEvidenceSeen);
        if (inboundCount.get() > 0 || mdnsCount.get() > 0) {
            append("VERDETTO DIAGNOSTICO: il TFT ha raggiunto la rete locale del telefono almeno una volta.");
        } else if (hotspotLikeInterfaceSeen) {
            append("VERDETTO DIAGNOSTICO: hotspot/interfaccia locale presente, ma nessuna evidenza EasyConn dal TFT durante la finestra.");
            append("Questo e' compatibile con TFT che non si associa al Wi-Fi, oppure con EasyConn che non parte: il log neighbour aiuta a separare i due casi.");
        } else {
            append("VERDETTO DIAGNOSTICO: non e' stata identificata con certezza un'interfaccia hotspot; verificare che hotspot VOGE-36f fosse realmente attivo durante tutto il test.");
        }
        append("=== MONITOR TERMINATO: " + reason + " ===");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void dumpBluetoothBonded() {
        if (bt == null) {
            append("Bluetooth: adapter non disponibile");
            return;
        }
        append("Bluetooth: enabled=" + bt.isEnabled());
        if (!ensureBtPermission()) {
            append("Bluetooth: concedere permesso Dispositivi nelle vicinanze; il monitor hotspot continua comunque.");
            return;
        }
        try {
            Set<BluetoothDevice> bonded = bt.getBondedDevices();
            append("Bluetooth paired/bonded count=" + (bonded == null ? 0 : bonded.size()));
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    String name = d.getName();
                    if (name != null && name.toUpperCase(Locale.ROOT).contains("VOGE")) {
                        append("Bluetooth VOGE paired: name=" + name + " type=" + d.getType() + " addr=" + maskMac(d.getAddress()));
                    }
                }
            }
        } catch (Throwable t) {
            append("Bluetooth bonded FAIL: " + shortError(t));
        }
    }

    private void startTcpListeners() {
        for (int port : PORTS) {
            io.execute(() -> {
                try {
                    ServerSocket server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress("0.0.0.0", port));
                    listeners.add(server);
                    append("LISTEN " + port + " attivo (solo osservazione; nessuna risposta applicativa)");
                    while (monitoring && !server.isClosed()) {
                        Socket s = server.accept();
                        int n = inboundCount.incrementAndGet();
                        String remote = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
                        append("EASYCONN INBOUND #" + n + " port=" + port + " from=" + remote + ":" + s.getPort());
                        neighbourEvidenceSeen = true;
                        try {
                            s.setSoTimeout(350);
                            Thread.sleep(120L);
                            int available = s.getInputStream().available();
                            if (available > 0) {
                                byte[] buf = new byte[Math.min(64, available)];
                                int read = s.getInputStream().read(buf);
                                if (read > 0) append("EASYCONN RX PASSIVO port=" + port + " bytes=" + read + " hex=" + toHex(buf, read));
                            } else {
                                append("EASYCONN port=" + port + " connesso ma nessun payload gia' disponibile nei primi 120 ms");
                            }
                        } catch (Throwable t) {
                            append("EASYCONN RX PASSIVO port=" + port + " note=" + shortError(t));
                        } finally {
                            try { s.close(); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable t) {
                    if (monitoring) append("LISTEN " + port + " FAIL: " + shortError(t));
                }
            });
        }
    }

    private void stopTcpListeners() {
        for (ServerSocket s : listeners) {
            try { s.close(); } catch (Throwable ignored) {}
        }
        listeners.clear();
    }

    private void startMdns() {
        if (nsd == null || wifi == null) {
            append("mDNS: servizio Android non disponibile");
            return;
        }
        try {
            multicastLock = wifi.createMulticastLock("Trofeo500HotspotDiag");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable t) {
            append("mDNS multicast lock FAIL: " + shortError(t));
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) {
                append("mDNS discovery started type=" + serviceType);
            }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                int n = mdnsCount.incrementAndGet();
                append("mDNS EasyConn candidate #" + n + ": name=" + serviceInfo.getServiceName() + " type=" + serviceInfo.getServiceType());
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            append("mDNS resolve FAIL error=" + errorCode + " name=" + serviceInfo.getServiceName());
                        }

                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            InetAddress host = resolved.getHost();
                            String ip = host == null ? "?" : host.getHostAddress();
                            append("mDNS EasyConn RESOLVED name=" + resolved.getServiceName() + " host=" + ip + " port=" + resolved.getPort());
                            neighbourEvidenceSeen = true;
                        }
                    });
                } catch (Throwable t) {
                    append("mDNS resolve request FAIL: " + shortError(t));
                }
            }

            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {
                append("mDNS EasyConn lost: " + serviceInfo.getServiceName());
            }

            @Override public void onDiscoveryStopped(String serviceType) {
                append("mDNS discovery stopped");
            }

            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                append("mDNS start FAIL error=" + errorCode);
                stopMdns();
            }

            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                append("mDNS stop FAIL error=" + errorCode);
            }
        };
        try {
            nsd.discoverServices(EASYCONN_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable t) {
            append("mDNS discoverServices FAIL: " + shortError(t));
            stopMdns();
        }
    }

    private synchronized void stopMdns() {
        NsdManager.DiscoveryListener l = discoveryListener;
        discoveryListener = null;
        if (l != null && nsd != null) {
            try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) {}
        }
        if (multicastLock != null) {
            try { if (multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) {}
            multicastLock = null;
        }
    }

    private void logChangedNetworkSnapshot(boolean force) {
        String now = buildNetworkSnapshot();
        if (force || !now.equals(lastNetworkSnapshot)) {
            append("NETWORK SNAPSHOT:\n" + now);
            lastNetworkSnapshot = now;
        }
    }

    private String buildNetworkSnapshot() {
        StringBuilder sb = new StringBuilder();
        try {
            Network[] networks = cm == null ? new Network[0] : cm.getAllNetworks();
            if (networks == null || networks.length == 0) return "  nessuna Network Android disponibile";
            for (Network n : networks) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                LinkProperties lp = cm.getLinkProperties(n);
                sb.append("  net=").append(n).append(" transport=");
                if (c == null) sb.append("?");
                else {
                    List<String> ts = new ArrayList<>();
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) ts.add("WIFI");
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) ts.add("CELLULAR");
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) ts.add("VPN");
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) ts.add("BT");
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ts.add("ETH");
                    sb.append(ts.isEmpty() ? "OTHER" : join(ts));
                    sb.append(" internet=").append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                    sb.append(" validated=").append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                }
                if (lp != null) {
                    sb.append(" iface=").append(lp.getInterfaceName());
                    sb.append(" addr=");
                    for (LinkAddress a : lp.getLinkAddresses()) sb.append(a).append(',');
                    sb.append(" routes=");
                    for (RouteInfo r : lp.getRoutes()) {
                        sb.append('[').append(r.getDestination()).append(" gw=")
                                .append(r.getGateway() == null ? "-" : r.getGateway().getHostAddress()).append(']');
                    }
                }
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("  FAIL ").append(shortError(t));
        }
        return sb.toString().trim();
    }

    private void logChangedInterfaceSnapshot(boolean force) {
        String now = buildInterfaceSnapshot();
        if (force || !now.equals(lastInterfaceSnapshot)) {
            append("INTERFACE SNAPSHOT:\n" + now);
            lastInterfaceSnapshot = now;
        }
    }

    private String buildInterfaceSnapshot() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return "  nessuna interfaccia";
            List<NetworkInterface> list = Collections.list(en);
            list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (NetworkInterface ni : list) {
                if (!ni.isUp()) continue;
                String name = ni.getName();
                List<String> addrs = new ArrayList<>();
                Enumeration<InetAddress> ia = ni.getInetAddresses();
                while (ia.hasMoreElements()) {
                    InetAddress a = ia.nextElement();
                    if (!a.isLoopbackAddress()) addrs.add(a.getHostAddress());
                }
                if (addrs.isEmpty()) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                boolean hot = lower.contains("ap") || lower.contains("softap") || lower.contains("swlan") || lower.contains("tether") || lower.contains("rndis");
                if (hot) hotspotLikeInterfaceSeen = true;
                sb.append("  ").append(name).append(" mtu=").append(ni.getMTU()).append(" addr=").append(join(addrs));
                if (hot) sb.append(" [HOTSPOT-LIKE]");
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("  FAIL ").append(shortError(t));
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "  nessuna interfaccia IP non-loopback rilevata" : out;
    }

    private void logChangedNeighbourSnapshot(boolean force) {
        io.execute(() -> {
            String now = buildNeighbourSnapshot();
            main.post(() -> {
                if (force || !now.equals(lastNeighbourSnapshot)) {
                    append("NEIGHBOUR SNAPSHOT:\n" + now);
                    if (!now.contains("nessun neighbour") && !now.contains("non disponibile") && !now.contains("FAIL")) {
                        neighbourEvidenceSeen = true;
                    }
                    lastNeighbourSnapshot = now;
                }
            });
        });
    }

    private String buildNeighbourSnapshot() {
        String ipOut = runCommand(new String[]{"ip", "neigh", "show"});
        if (ipOut != null && !ipOut.trim().isEmpty()) return safeLimit(ipOut.trim(), 6000);
        try {
            File arp = new File("/proc/net/arp");
            if (arp.canRead()) {
                BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(arp)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                br.close();
                String s = sb.toString().trim();
                if (!s.isEmpty()) return safeLimit(s, 6000);
            }
        } catch (Throwable ignored) {}
        return "  neighbour table non disponibile all'app (normale su alcuni Android recenti)";
    }

    private String runCommand(String[] cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null && sb.length() < 6000) sb.append(line).append('\n');
            try { p.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private void shareLog() {
        try {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Impossibile creare cartella log");
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(new Date());
            File file = new File(dir, "Trofeo500_Hotspot_Diagnostic_" + ts + ".txt");
            FileOutputStream out = new FileOutputStream(file);
            out.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Condividi log Trofeo 500"));
        } catch (Throwable t) {
            Toast.makeText(this, "Condivisione log fallita: " + shortError(t), Toast.LENGTH_LONG).show();
        }
    }

    private void append(String message) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY).format(new Date());
        String line = ts + "  " + message + "\n";
        synchronized (log) { log.append(line); }
        main.post(() -> {
            if (logView != null) {
                logView.append(line);
                View parent = (View) logView.getParent();
                if (parent != null) parent.post(() -> parent.scrollTo(0, logView.getBottom()));
            }
        });
    }

    private String maskMac(String mac) {
        if (mac == null || mac.length() < 5) return "?";
        String[] p = mac.split(":");
        if (p.length != 6) return "**:**:**:**:**:**";
        return "**:**:**:" + p[3] + ":" + p[4] + ":" + p[5];
    }

    private String toHex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, b.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xff));
        }
        return sb.toString();
    }

    private String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(v);
        }
        return sb.toString();
    }

    private String shortError(Throwable t) {
        if (t == null) return "?";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + safeLimit(m, 240));
    }

    private String safeLimit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override
    protected void onDestroy() {
        stopMonitor("activity destroy");
        io.shutdownNow();
        super.onDestroy();
    }
}
