package it.motolink.vogenavbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQ_BT = 41;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<BleBridge.ScanEntry> visibleEntries = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private Spinner deviceSpinner;
    private TextView notificationStatus;
    private TextView bleStatus;
    private TextView mapsStatus;
    private TextView logView;
    private CheckBox liveCheck;
    private boolean active;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagLog.ensureHeader(this);
        DiagLog.log(this, "UI", "activity_created");
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        refreshLoop.run();
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refreshLoop);
        super.onPause();
    }

    private View buildUi() {
        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = text("MotoLink VOGE Nav Bridge Test", 24, true);
        root.addView(title);
        root.addView(text("APK diagnostica separata da MotoLink ufficiale · package it.motolink.vogenavbridge", 13, false));
        root.addView(text("Nessun Mapbox · nessun permesso Internet · nessun upload automatico. Il log può contenere i nomi delle strade letti dalle notifiche di Google Maps.", 13, false));
        root.addView(space());

        notificationStatus = text("Accesso notifiche: ...", 16, true);
        root.addView(notificationStatus);
        Button notificationAccess = button("1 · APRI ACCESSO NOTIFICHE");
        notificationAccess.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Throwable t) { toast("Impossibile aprire Accesso notifiche"); }
        });
        root.addView(notificationAccess);

        Button openMaps = button("2 · APRI GOOGLE MAPS");
        openMaps.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
            if (i != null) startActivity(i); else toast("Google Maps non trovato");
        });
        root.addView(openMaps);
        root.addView(space());

        root.addView(text("Bluetooth moto", 19, true));
        root.addView(text("Prima del test chiudi VOGE Global, così le due app non competono per la stessa connessione BLE.", 13, false));
        Button perm = button("3 · CONCEDI PERMESSI BLUETOOTH");
        perm.setOnClickListener(v -> requestBtPermissions());
        root.addView(perm);

        Button scan = button("4 · SCANSIONA BLE (12 s)");
        scan.setOnClickListener(v -> {
            if (!hasBtPermissions()) {
                requestBtPermissions();
                toast("Concedi i permessi, poi premi di nuovo Scansiona");
            } else {
                BleBridge.get(this).startScan(12000L);
            }
        });
        root.addView(scan);

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button connect = button("5 · CONNETTI DISPOSITIVO SELEZIONATO");
        connect.setOnClickListener(v -> {
            int p = deviceSpinner.getSelectedItemPosition();
            if (p < 0 || p >= visibleEntries.size()) {
                toast("Prima esegui la scansione e seleziona la moto");
                return;
            }
            BleBridge.get(this).connect(visibleEntries.get(p));
        });
        root.addView(connect);

        Button disconnect = button("DISCONNETTI BLE");
        disconnect.setOnClickListener(v -> BleBridge.get(this).disconnect());
        root.addView(disconnect);

        bleStatus = text("BLE: ...", 14, false);
        root.addView(bleStatus);
        root.addView(space());

        root.addView(text("Test protocollo VOGE", 19, true));
        root.addView(text("Dati completamente sintetici: Via Nazionale → Via del Corso, Roma · svolta a destra · 300 m. NON usa il tuo percorso Maps.", 13, false));
        Button roma = button("TEST ROMA → TFT · 300 m DESTRA");
        roma.setOnClickListener(v -> {
            if (!BleBridge.get(this).isReady()) {
                toast("Connetti prima la moto e attendi 'VOGE write pronta'");
                return;
            }
            DiagLog.log(this, "UI", "rome_test_pressed");
            BleBridge.get(this).sendRomeTest();
            toast("Sequenza VOGE Roma inviata: controlla il TFT");
        });
        root.addView(roma);
        root.addView(space());

        root.addView(text("Google Maps → VOGE", 19, true));
        mapsStatus = text("Ultima Maps: nessun dato", 14, false);
        root.addView(mapsStatus);

        Button maps300 = button("INVIA ULTIMA MAPS → TFT CON DISTANZA TEST 300 m");
        maps300.setOnClickListener(v -> sendLatestMapsWithTestDistance());
        root.addView(maps300);

        liveCheck = new CheckBox(this);
        liveCheck.setText("Bridge Maps LIVE (solo se Maps espone una distanza reale)");
        liveCheck.setTextSize(15);
        liveCheck.setChecked(BridgeRuntime.isLiveBridge());
        liveCheck.setOnCheckedChangeListener((b, checked) -> BridgeRuntime.setLiveBridge(this, checked));
        root.addView(liveCheck);
        root.addView(text("Sicurezza diagnostica: se la notifica non contiene una distanza reale o la manovra non è riconosciuta, il LIVE registra il motivo ma NON invia dati inventati al TFT.", 12, false));
        root.addView(space());

        root.addView(text("Log diagnostico", 19, true));
        Button share = button("CONDIVIDI LOG ZIP");
        share.setOnClickListener(v -> shareLog());
        root.addView(share);

        Button clear = button("AZZERA LOG");
        clear.setOnClickListener(v -> {
            DiagLog.clear(this);
            toast("Log azzerato");
            refreshNow();
        });
        root.addView(clear);

        logView = text("", 11, false);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView);
        return scroll;
    }

    private void sendLatestMapsWithTestDistance() {
        NavModel src = BridgeRuntime.getLatestMaps();
        if (src == null) {
            toast("Non ho ancora ricevuto una notifica di navigazione Google Maps");
            return;
        }
        if (src.nextRoadDirection == 0) {
            toast("Manovra Maps non riconosciuta: non invio");
            DiagLog.log(this, "TX_BLOCK", "MAPS_TEST_300M direction unknown title='" + DiagLog.clean(src.sourceTitle) + "'");
            return;
        }
        if (!BleBridge.get(this).isReady()) {
            toast("Connetti prima la moto");
            return;
        }
        NavModel m = new NavModel();
        m.routeRemainDistM = src.routeRemainDistM > 300 ? src.routeRemainDistM : 2400;
        m.routeRemainTimeSec = src.routeRemainTimeSec > 0 ? src.routeRemainTimeSec : 600;
        m.arriveRemainSec = src.arriveRemainSec > 0 ? src.arriveRemainSec : m.routeRemainTimeSec;
        m.curRoadRemainDistM = 300;
        m.curRoadName = src.curRoadName;
        m.nextRoadDirection = src.nextRoadDirection;
        m.nextRoadName = src.nextRoadName;
        m.roadFlag = src.roadFlag;
        m.naviOnOff = 0;
        m.annularDegrees = src.annularDegrees;
        m.realDistance = false;
        m.sourceTitle = src.sourceTitle;
        m.sourceSubText = src.sourceSubText;
        DiagLog.log(this, "TX_MODEL", "MAPS_TEST_300M distance=SYNTHETIC_300m title='" + DiagLog.clean(src.sourceTitle) + "'");
        BleBridge.get(this).sendModel(m, "MAPS_TITLE_WITH_SYNTHETIC_300M");
        toast("Invio Maps con distanza diagnostica 300 m");
    }

    private void shareLog() {
        try {
            File zip = DiagLog.exportZip(this);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", zip);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "MotoLink VOGE Nav Bridge diagnostic log");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            DiagLog.log(this, "UI", "share_log file=" + zip.getName());
            startActivity(Intent.createChooser(send, "Condividi log MotoLink"));
        } catch (Throwable t) {
            DiagLog.log(this, "UI", "share_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
            toast("Errore condivisione log: " + t.getClass().getSimpleName());
        }
    }

    private void requestBtPermissions() {
        ArrayList<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (p.isEmpty()) toast("Permessi Bluetooth già concessi");
        else requestPermissions(p.toArray(new String[0]), REQ_BT);
    }

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            DiagLog.log(this, "PERM", "bluetooth granted=" + hasBtPermissions());
            toast(hasBtPermissions() ? "Permessi Bluetooth OK" : "Permessi Bluetooth non completi");
            refreshNow();
        }
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refreshNow();
            if (active) handler.postDelayed(this, 1000L);
        }
    };

    private void refreshNow() {
        if (notificationStatus == null) return;
        boolean notif = NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        notificationStatus.setText("Accesso notifiche Maps: " + (notif ? "ON ✓" : "OFF"));

        BleBridge ble = BleBridge.get(this);
        bleStatus.setText("BLE: " + ble.getStatus()
                + "\nDispositivo: " + ble.getConnectedLabel()
                + "\nWRITE: " + ble.getWriteUuid()
                + "\nNOTIFY: " + ble.getNotifyUuid());

        List<BleBridge.ScanEntry> latest = ble.getScanEntries();
        if (!sameEntries(latest)) {
            int old = deviceSpinner.getSelectedItemPosition();
            visibleEntries.clear();
            visibleEntries.addAll(latest);
            ArrayList<String> labels = new ArrayList<>();
            for (BleBridge.ScanEntry e : latest) labels.add(e.toString());
            deviceAdapter.clear();
            deviceAdapter.addAll(labels);
            deviceAdapter.notifyDataSetChanged();
            if (!labels.isEmpty()) deviceSpinner.setSelection(Math.max(0, Math.min(old, labels.size() - 1)));
        }

        NavModel m = BridgeRuntime.getLatestMaps();
        if (m == null) {
            mapsStatus.setText("Ultima Maps: nessuna notifica ricevuta");
        } else {
            mapsStatus.setText("Titolo: " + m.sourceTitle
                    + "\nSub: " + m.sourceSubText
                    + "\nManovra: " + VogeNavPacketEncoder.directionName(m.nextRoadDirection) + " (" + m.nextRoadDirection + ")"
                    + "\nProssima strada: " + m.nextRoadName
                    + "\nDistanza: " + (m.realDistance ? m.curRoadRemainDistM + " m REALE" : "NON esposta / non riconosciuta")
                    + "\nLIVE: " + (BridgeRuntime.isLiveBridge() ? "ON" : "OFF"));
        }
        if (liveCheck != null && liveCheck.isChecked() != BridgeRuntime.isLiveBridge()) liveCheck.setChecked(BridgeRuntime.isLiveBridge());
        if (logView != null) logView.setText(DiagLog.tail(this, 9000));
    }

    private boolean sameEntries(List<BleBridge.ScanEntry> latest) {
        if (latest.size() != visibleEntries.size()) return false;
        for (int i = 0; i < latest.size(); i++) if (!latest.get(i).toString().equals(visibleEntries.get(i).toString())) return false;
        return true;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(5), 0, dp(5));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextIsSelectable(true);
        return t;
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
