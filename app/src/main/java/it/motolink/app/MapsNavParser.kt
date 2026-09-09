package it.motolink.app

import android.app.Notification
import android.os.Bundle
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object MapsNavParser {
    private val distancePattern = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b")
    private val etaPattern = Pattern.compile("(?i)arrivo\\s*:?\\s*(\\d{1,2})[:.](\\d{2})")

    fun parse(extras: Bundle?): VogeNavModel {
        val title = value(extras, Notification.EXTRA_TITLE)
        val text = value(extras, Notification.EXTRA_TEXT)
        val big = value(extras, Notification.EXTRA_BIG_TEXT)
        val sub = value(extras, Notification.EXTRA_SUB_TEXT)
        val all = listOf(title, text, big).filter { it.isNotBlank() }.joinToString(" ")
        val normalized = normalize(all)
        val model = VogeNavModel()
        model.sourceTitle = title
        model.sourceSubText = sub
        model.nextRoadDirection = direction(normalized)
        model.roadFlag = if (normalized.contains("rotonda") || normalized.contains("rotatoria")) 2 else 0
        model.naviOnOff = 0
        model.nextRoadName = extractRoad(title)
        val distance = extractDistanceMeters(all)
        if (distance >= 0) {
            model.curRoadRemainDistM = distance
            // Maps notifications observed so far do not expose reliable total route distance.
            model.routeRemainDistM = distance
            model.realDistance = true
        }
        val etaSec = secondsUntilEta(sub)
        if (etaSec >= 0) {
            model.routeRemainTimeSec = etaSec
            model.arriveRemainSec = etaSec
        }
        return model
    }

    fun extractDistanceMeters(source: String?): Int {
        if (source.isNullOrBlank()) return -1
        val matcher = distancePattern.matcher(source)
        if (!matcher.find()) return -1
        return runCatching {
            var value = matcher.group(1).replace(',', '.').toDouble()
            if (matcher.group(2).equals("km", true)) value *= 1000.0
            kotlin.math.round(value).toInt().coerceAtLeast(0)
        }.getOrDefault(-1)
    }

    fun direction(normalized: String?): Int {
        val s = normalized.orEmpty()
        val left = s.contains("sinistra") || s.contains("left")
        val right = s.contains("destra") || s.contains("right")
        if (s.contains("inversione") || s.contains("u-turn") || s.contains("uturn")) return if (right) 3 else 2
        if ((s.contains("leggermente") || s.contains("mantieni") || s.contains("tieni") || s.contains("slight")) && left) return 6
        if ((s.contains("leggermente") || s.contains("mantieni") || s.contains("tieni") || s.contains("slight")) && right) return 7
        if ((s.contains("bruscamente") || s.contains("stretta") || s.contains("sharp")) && left) return 8
        if ((s.contains("bruscamente") || s.contains("stretta") || s.contains("sharp")) && right) return 9
        if (left) return 4
        if (right) return 5
        if (s.contains("rotonda") || s.contains("rotatoria") || s.contains("roundabout")) return 1
        if (s.contains("procedi") || s.contains("prosegui") || s.contains("dritto") || s.contains("diritto") || s.contains("continue") || s.contains("straight")) return 1
        return 0
    }

    private fun extractRoad(title: String): String {
        if (title.isBlank()) return ""
        val lower = title.lowercase(Locale.ITALIAN)
        val anchors = listOf(" verso ", " su ", " in ", " per ", " onto ", " toward ")
        var best = -1
        var anchor = ""
        anchors.forEach { candidate ->
            val pos = lower.lastIndexOf(candidate)
            if (pos > best) {
                best = pos
                anchor = candidate
            }
        }
        var road = if (best >= 0) title.substring(best + anchor.length) else title
        road = road.replace(Regex("(?i)^(svolta|gira|procedi|prosegui|mantieni|tieni|turn|continue)\\s+"), "").trim()
        return road.take(80)
    }

    private fun secondsUntilEta(sub: String): Int {
        if (sub.isBlank()) return -1
        val matcher = etaPattern.matcher(sub)
        if (!matcher.find()) return -1
        return runCatching {
            val hh = matcher.group(1).toInt()
            val mm = matcher.group(2).toInt()
            val now = Calendar.getInstance()
            val eta = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            ((eta.timeInMillis - now.timeInMillis) / 1000L).coerceIn(0L, 0xFFFFL).toInt()
        }.getOrDefault(-1)
    }

    private fun value(bundle: Bundle?, key: String): String = runCatching {
        bundle?.get(key)?.toString()?.trim().orEmpty()
    }.getOrDefault("")

    private fun normalize(source: String): String = Normalizer
        .normalize(source.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
}
