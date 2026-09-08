package it.motolink.vogenavbridge;

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagLog {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);

    private DiagLog() {}

    public static File diagDir(Context c) {
        File d = new File(c.getFilesDir(), "diag");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File iconDir(Context c) {
        File d = new File(diagDir(c), "maps_icons");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File logFile(Context c) {
        return new File(diagDir(c), "log.txt");
    }

    public static void log(Context c, String tag, String msg) {
        synchronized (LOCK) {
            try (FileWriter fw = new FileWriter(logFile(c), true)) {
                fw.write(TS.format(new Date()));
                fw.write(" [");
                fw.write(clean(tag));
                fw.write("] ");
                fw.write(clean(msg));
                fw.write("\n");
            } catch (IOException ignored) {
            }
        }
    }

    public static void ensureHeader(Context c) {
        synchronized (LOCK) {
            File f = logFile(c);
            if (f.exists() && f.length() > 0) return;
            log(c, "META", "MotoLink VOGE Nav Bridge Diagnostic 1.0-test");
            log(c, "META", "package=it.motolink.vogenavbridge internet_permission=ABSENT mapbox=ABSENT");
            log(c, "META", "device=" + clean(Build.MANUFACTURER) + " " + clean(Build.MODEL) + " sdk=" + Build.VERSION.SDK_INT + " android=" + clean(Build.VERSION.RELEASE));
            log(c, "META", "VOGE lineage: VOGE Global 1.1.18; nav frames 0x6A..0x6E; BLE GATT candidate UUID discovery");
            log(c, "PRIVACY", "Il log puo contenere il testo delle indicazioni stradali mostrato da Google Maps. Nessun dato viene caricato automaticamente.");
        }
    }

    public static void clear(Context c) {
        synchronized (LOCK) {
            deleteRecursive(diagDir(c));
            diagDir(c).mkdirs();
            iconDir(c).mkdirs();
        }
        ensureHeader(c);
        log(c, "UI", "log_cleared");
    }

    public static String tail(Context c, int maxChars) {
        synchronized (LOCK) {
            File f = logFile(c);
            if (!f.exists()) return "";
            try (FileInputStream in = new FileInputStream(f)) {
                long len = f.length();
                int wanted = (int) Math.min(len, Math.max(1, maxChars * 2L));
                if (len > wanted) in.skip(len - wanted);
                byte[] b = in.readAllBytes();
                String s = new String(b, StandardCharsets.UTF_8);
                return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
            } catch (IOException e) {
                return "log_error=" + e.getClass().getSimpleName();
            }
        }
    }

    public static File exportZip(Context c) throws IOException {
        ensureHeader(c);
        File shared = new File(c.getFilesDir(), "shared");
        if (!shared.exists()) shared.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(shared, "MotoLink_VOGE_NavBridge_Log_" + stamp + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            addRecursive(zos, diagDir(c), diagDir(c).getAbsolutePath().length() + 1);
        }
        return out;
    }

    public static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static String clean(Object o) {
        if (o == null) return "null";
        String s = String.valueOf(o).replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 1200 ? s.substring(0, 1200) + "..." : s;
    }

    public static String redactMac(String mac) {
        if (mac == null || mac.length() < 5) return "unknown";
        return "xx:xx:xx:xx:" + mac.substring(Math.max(0, mac.length() - 5));
    }

    private static void addRecursive(ZipOutputStream zos, File f, int baseLen) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) addRecursive(zos, c, baseLen);
            return;
        }
        String name = f.getAbsolutePath().substring(baseLen).replace(File.separatorChar, '/');
        zos.putNextEntry(new ZipEntry(name));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
