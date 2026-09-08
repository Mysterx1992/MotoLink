package it.motolink.vogenavbridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public final class VogeNavPacketEncoder {
    public static final int CMD_NAV = 0x6A;
    public static final int CMD_NEXT_1 = 0x6B;
    public static final int CMD_NEXT_2 = 0x6C;
    public static final int CMD_CUR_1 = 0x6D;
    public static final int CMD_CUR_2 = 0x6E;

    private VogeNavPacketEncoder() {}

    public static NavModel romeTestModel() {
        NavModel m = new NavModel();
        m.routeRemainDistM = 2400;
        m.routeRemainTimeSec = 600;
        m.arriveRemainSec = 600;
        m.curRoadRemainDistM = 300;
        m.curRoadName = "Via Nazionale";
        m.nextRoadDirection = 5; // VOGE: right turn
        m.nextRoadName = "Via del Corso";
        m.roadFlag = 0;
        m.naviOnOff = 0;
        m.annularDegrees = 0;
        m.realDistance = true;
        m.sourceTitle = "TEST ROMA: svolta a destra verso Via del Corso";
        m.sourceSubText = "Dati sintetici, non provenienti da Maps";
        return m;
    }

    public static List<byte[]> allFrames(NavModel m) {
        List<byte[]> out = new ArrayList<>();
        out.add(navFrame(m));
        out.add(textFrame(CMD_NEXT_1, m.nextRoadName, false));
        out.add(textFrame(CMD_NEXT_2, m.nextRoadName, true));
        out.add(textFrame(CMD_CUR_1, m.curRoadName, false));
        out.add(textFrame(CMD_CUR_2, m.curRoadName, true));
        return out;
    }

    public static byte[] navFrame(NavModel m) {
        byte[] p = base(CMD_NAV);
        putBe(p, 2, clamp(m.curRoadRemainDistM, 0, 0xFFFFFF), 3);
        putBe(p, 5, clamp(m.routeRemainTimeSec, 0, 0xFFFF), 2);
        putBe(p, 7, clamp(m.routeRemainDistM, 0, 0xFFFFFF), 3);
        p[10] = (byte) clamp(m.nextRoadDirection, 0, 255);

        long deltaSec = m.arriveRemainSec > 0 ? m.arriveRemainSec : m.routeRemainTimeSec;
        long arriveMs = System.currentTimeMillis() + Math.max(0, deltaSec) * 1000L;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(arriveMs);
        int hour24 = cal.get(Calendar.HOUR_OF_DAY);
        p[11] = (byte) hour24;
        p[12] = (byte) cal.get(Calendar.MINUTE);
        p[13] = (byte) (hour24 >= 12 ? 1 : 0);
        p[14] = (byte) clamp(m.roadFlag, 0, 255);
        p[15] = (byte) clamp(m.naviOnOff, 0, 255);
        p[16] = (byte) clamp(m.annularDegrees, 0, 255);
        return checksum(p);
    }

    public static byte[] textFrame(int command, String text, boolean secondChunk) {
        byte[] p = base(command);
        p[2] = (byte) ("zh".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? 1 : 0);
        byte[] utf = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        int src = secondChunk ? 15 : 0;
        int count = Math.min(15, Math.max(0, utf.length - src));
        if (count > 0) System.arraycopy(utf, src, p, 3, count);
        return checksum(p);
    }

    public static String directionName(int code) {
        switch (code) {
            case 1: return "STRAIGHT";
            case 2: return "UTURN_LEFT";
            case 3: return "UTURN_RIGHT";
            case 4: return "LEFT";
            case 5: return "RIGHT";
            case 6: return "SLIGHT_LEFT";
            case 7: return "SLIGHT_RIGHT";
            case 8: return "SHARP_LEFT";
            case 9: return "SHARP_RIGHT";
            default: return "NONE/UNKNOWN";
        }
    }

    private static byte[] base(int command) {
        byte[] p = new byte[20];
        p[0] = 0x01;
        p[1] = (byte) command;
        p[19] = 0x04;
        return p;
    }

    private static void putBe(byte[] dest, int offset, int value, int count) {
        for (int i = 0; i < count; i++) {
            int shift = (count - 1 - i) * 8;
            dest[offset + i] = (byte) ((value >> shift) & 0xFF);
        }
    }

    private static byte[] checksum(byte[] p) {
        p[p.length - 2] = 0;
        int x = 0;
        for (byte b : p) x ^= (b & 0xFF);
        p[p.length - 2] = (byte) x;
        return p;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
