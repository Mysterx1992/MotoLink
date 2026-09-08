package it.motolink.vogenavbridge;

import android.content.Context;
import android.content.Intent;

public final class BridgeRuntime {
    public static final String ACTION_STATE = "it.motolink.vogenavbridge.STATE";
    private static volatile NavModel latestMaps;
    private static volatile boolean liveBridge;

    private BridgeRuntime() {}

    public static NavModel getLatestMaps() {
        return latestMaps;
    }

    public static void setLatestMaps(Context c, NavModel model) {
        latestMaps = model;
        notifyState(c);
    }

    public static boolean isLiveBridge() {
        return liveBridge;
    }

    public static void setLiveBridge(Context c, boolean enabled) {
        liveBridge = enabled;
        DiagLog.log(c, "BRIDGE", "live=" + enabled);
        notifyState(c);
    }

    public static void notifyState(Context c) {
        if (c == null) return;
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(c.getPackageName());
        c.sendBroadcast(i);
    }
}
