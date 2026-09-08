package it.motolink.vogenavbridge;

import android.app.Notification;
import android.os.Bundle;

import java.text.Normalizer;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapsNavParser {
    private static final Pattern DIST = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b");
    private static final Pattern ETA = Pattern.compile("(?i)arrivo\\s*:?\\s*(\\d{1,2})[:.](\\d{2})");

    private MapsNavParser() {}

    public static NavModel parse(Bundle b) {
        NavModel m = new NavModel();
        String title = val(b, Notification.EXTRA_TITLE);
        String text = val(b, Notification.EXTRA_TEXT);
        String big = val(b, Notification.EXTRA_BIG_TEXT);
        String sub = val(b, Notification.EXTRA_SUB_TEXT);
        m.sourceTitle = title;
        m.sourceSubText = sub;

        String all = join(title, text, big);
        String n = norm(all);
        m.nextRoadDirection = direction(n);
        m.roadFlag = (n.contains("rotonda") || n.contains("rotatoria")) ? 2 : 0;
        m.annularDegrees = 0;
        m.naviOnOff = 0;
        m.nextRoadName = extractRoad(title);
        m.curRoadName = "";

        int dist = extractDistanceMeters(all);
        if (dist >= 0) {
            m.curRoadRemainDistM = dist;
            m.routeRemainDistM = dist; // diagnostica: il totale rotta non e' esposto in modo affidabile dalla notifica
            m.realDistance = true;
        } else {
            m.curRoadRemainDistM = 0;
            m.routeRemainDistM = 0;
            m.realDistance = false;
        }

        int etaSec = secondsUntilEta(sub);
        if (etaSec >= 0) {
            m.routeRemainTimeSec = etaSec;
            m.arriveRemainSec = etaSec;
        }
        return m;
    }

    public static int extractDistanceMeters(String s) {
        if (s == null) return -1;
        Matcher mm = DIST.matcher(s);
        if (!mm.find()) return -1;
        try {
            double v = Double.parseDouble(mm.group(1).replace(',', '.'));
            if ("km".equalsIgnoreCase(mm.group(2))) v *= 1000.0;
            return Math.max(0, (int) Math.round(v));
        } catch (Exception e) {
            return -1;
        }
    }

    public static int direction(String normalized) {
        String s = normalized == null ? "" : normalized;
        boolean left = s.contains("sinistra");
        boolean right = s.contains("destra");
        if (s.contains("inversione") || s.contains("u-turn") || s.contains("uturn")) return right ? 3 : 2;
        if ((s.contains("leggermente") || s.contains("mantieni") || s.contains("tieni")) && left) return 6;
        if ((s.contains("leggermente") || s.contains("mantieni") || s.contains("tieni")) && right) return 7;
        if ((s.contains("bruscamente") || s.contains("stretta")) && left) return 8;
        if ((s.contains("bruscamente") || s.contains("stretta")) && right) return 9;
        if (left) return 4;
        if (right) return 5;
        if (s.contains("rotonda") || s.contains("rotatoria")) return 1;
        if (s.contains("procedi") || s.contains("prosegui") || s.contains("dritto") || s.contains("diritto")) return 1;
        return 0;
    }

    public static String extractRoad(String title) {
        if (title == null) return "";
        String lower = title.toLowerCase(Locale.ITALIAN);
        String[] anchors = {" verso ", " su ", " in ", " per "};
        int best = -1;
        String anchor = null;
        for (String a : anchors) {
            int p = lower.lastIndexOf(a);
            if (p > best) { best = p; anchor = a; }
        }
        String road = best >= 0 && anchor != null ? title.substring(best + anchor.length()) : title;
        road = road.replaceAll("(?i)^(svolta|gira|procedi|prosegui|mantieni|tieni)\\s+", "").trim();
        return road.length() > 80 ? road.substring(0, 80) : road;
    }

    private static int secondsUntilEta(String sub) {
        if (sub == null) return -1;
        Matcher m = ETA.matcher(sub);
        if (!m.find()) return -1;
        try {
            int hh = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            Calendar now = Calendar.getInstance();
            Calendar eta = (Calendar) now.clone();
            eta.set(Calendar.HOUR_OF_DAY, hh);
            eta.set(Calendar.MINUTE, min);
            eta.set(Calendar.SECOND, 0);
            eta.set(Calendar.MILLISECOND, 0);
            if (eta.before(now)) eta.add(Calendar.DAY_OF_YEAR, 1);
            long sec = (eta.getTimeInMillis() - now.getTimeInMillis()) / 1000L;
            return (int) Math.max(0, Math.min(0xFFFF, sec));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String val(Bundle b, String key) {
        if (b == null) return "";
        Object v;
        try { v = b.get(key); } catch (Throwable t) { return ""; }
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String join(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String s : values) if (s != null && !s.trim().isEmpty()) sb.append(' ').append(s);
        return sb.toString().trim();
    }

    private static String norm(String s) {
        String x = s == null ? "" : s.toLowerCase(Locale.ITALIAN);
        return Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
