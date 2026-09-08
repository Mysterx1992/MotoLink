package it.motolink.vogenavbridge;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class MapsNotificationListenerService extends NotificationListenerService {
    private static final String MAPS = "com.google.android.apps.maps";
    private String lastLiveFingerprint = "";

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.ensureHeader(this);
        DiagLog.log(this, "MAPS", "listener_service_created");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        DiagLog.log(this, "MAPS", "listener_connected");
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && MAPS.equals(sbn.getPackageName())) process(sbn, "ACTIVE_ON_CONNECT");
                }
            }
        } catch (Throwable t) {
            DiagLog.log(this, "MAPS", "active_query_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
        BridgeRuntime.notifyState(this);
    }

    @Override
    public void onListenerDisconnected() {
        DiagLog.log(this, "MAPS", "listener_disconnected");
        BridgeRuntime.notifyState(this);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && MAPS.equals(sbn.getPackageName())) process(sbn, "POSTED");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && MAPS.equals(sbn.getPackageName())) {
            DiagLog.log(this, "MAPS", "removed key=" + DiagLog.clean(sbn.getKey()));
            BridgeRuntime.notifyState(this);
        }
    }

    private void process(StatusBarNotification sbn, String source) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;
            Bundle e = n.extras == null ? Bundle.EMPTY : n.extras;
            DiagLog.log(this, "MAPS", "event=" + source
                    + " key=" + DiagLog.clean(sbn.getKey())
                    + " id=" + sbn.getId()
                    + " category=" + DiagLog.clean(n.category)
                    + " flags=0x" + Integer.toHexString(n.flags)
                    + " when=" + n.when);

            String[] keys = {
                    Notification.EXTRA_TITLE,
                    Notification.EXTRA_TEXT,
                    Notification.EXTRA_BIG_TEXT,
                    Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_INFO_TEXT,
                    Notification.EXTRA_SUMMARY_TEXT,
                    Notification.EXTRA_TEMPLATE,
                    "android.progress",
                    "android.progressMax",
                    "android.progressIndeterminate",
                    "android.progressSegments",
                    "android.progressPoints",
                    "android.progressTrackerIcon",
                    "android.styledByProgress",
                    "android.shortCriticalText"
            };
            for (String key : keys) {
                if (e.containsKey(key)) DiagLog.log(this, "MAPS_EXTRA", key + "=" + describe(e.get(key), 0));
            }
            for (String key : e.keySet()) {
                boolean known = false;
                for (String k : keys) if (k.equals(key)) { known = true; break; }
                if (!known) DiagLog.log(this, "MAPS_EXTRA_OTHER", key + "=" + describe(e.get(key), 0));
            }

            saveIcon(n.getLargeIcon(), "large", sbn.getPostTime());
            saveIcon(n.getSmallIcon(), "small", sbn.getPostTime());

            NavModel model = MapsNavParser.parse(e);
            BridgeRuntime.setLatestMaps(this, model);
            DiagLog.log(this, "MAPS_MODEL", model.summary()
                    + " title='" + DiagLog.clean(model.sourceTitle) + "'"
                    + " sub='" + DiagLog.clean(model.sourceSubText) + "'"
                    + " directionName=" + VogeNavPacketEncoder.directionName(model.nextRoadDirection));

            if (BridgeRuntime.isLiveBridge()) {
                if (!model.realDistance) {
                    DiagLog.log(this, "BRIDGE_SKIP", "Maps LIVE NOT SENT: distanza reale assente nella notifica");
                } else if (model.nextRoadDirection == 0) {
                    DiagLog.log(this, "BRIDGE_SKIP", "Maps LIVE NOT SENT: manovra non riconosciuta");
                } else {
                    String fp = model.sourceTitle + "|" + model.curRoadRemainDistM + "|" + model.nextRoadDirection + "|" + model.nextRoadName;
                    if (!fp.equals(lastLiveFingerprint)) {
                        lastLiveFingerprint = fp;
                        BleBridge.get(this).sendModel(model, "MAPS_LIVE");
                    }
                }
            }
        } catch (Throwable t) {
            DiagLog.log(this, "MAPS", "process_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
    }

    private String describe(Object v, int depth) {
        if (v == null) return "null";
        if (depth > 3) return v.getClass().getSimpleName() + "(...)";
        if (v instanceof Bundle) {
            Bundle b = (Bundle) v;
            StringBuilder sb = new StringBuilder("Bundle{");
            boolean first = true;
            for (String k : b.keySet()) {
                if (!first) sb.append(", ");
                first = false;
                Object child;
                try { child = b.get(k); } catch (Throwable t) { child = "<error>"; }
                sb.append(k).append('=').append(describe(child, depth + 1));
            }
            return sb.append('}').toString();
        }
        if (v instanceof ArrayList) {
            ArrayList<?> a = (ArrayList<?>) v;
            StringBuilder sb = new StringBuilder("ArrayList[");
            int limit = Math.min(a.size(), 20);
            for (int i = 0; i < limit; i++) {
                if (i > 0) sb.append(", ");
                sb.append(describe(a.get(i), depth + 1));
            }
            if (a.size() > limit) sb.append(", ... size=").append(a.size());
            return sb.append(']').toString();
        }
        if (v instanceof Parcelable[]) {
            Parcelable[] a = (Parcelable[]) v;
            StringBuilder sb = new StringBuilder("ParcelableArray[");
            for (int i = 0; i < Math.min(a.length, 20); i++) {
                if (i > 0) sb.append(", ");
                sb.append(describe(a[i], depth + 1));
            }
            return sb.append(']').toString();
        }
        return v.getClass().getSimpleName() + "(" + DiagLog.clean(v) + ")";
    }

    private void saveIcon(Icon icon, String type, long postTime) {
        if (icon == null) return;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d == null) return;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = ((BitmapDrawable) d).getBitmap();
            } else {
                int w = Math.max(1, d.getIntrinsicWidth());
                int h = Math.max(1, d.getIntrinsicHeight());
                w = Math.min(w, 512);
                h = Math.min(h, 512);
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, w, h);
                d.draw(c);
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File out = new File(DiagLog.iconDir(this), stamp + "_" + type + "_" + postTime + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            DiagLog.log(this, "MAPS_ICON", type + " saved=" + out.getName() + " size=" + bmp.getWidth() + "x" + bmp.getHeight() + " iconType=" + icon.getType());
        } catch (Throwable t) {
            DiagLog.log(this, "MAPS_ICON", type + " save_error=" + t.getClass().getSimpleName() + ":" + DiagLog.clean(t.getMessage()));
        }
    }
}
