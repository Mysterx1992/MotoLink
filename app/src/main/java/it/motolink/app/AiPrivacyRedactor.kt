package it.motolink.app

import android.content.Context

/**
 * Local privacy filter for the optional MotoLink AI support request.
 *
 * Nothing is uploaded automatically. The user must explicitly choose Supporto > Log > Condividi > Assistente.
 * The raw AppLog remains local; only a short, relevant, redacted excerpt is returned by this helper.
 */
object AiPrivacyRedactor {
    private val macRegex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
    private val uuidRegex = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val secretKvRegex = Regex(
        "(?i)\\b(ssid|bssid|password|passphrase|wifiPassword|token|pairingToken|secret|api[_-]?key|authorization|machineId|productId|huid|phoneUuid)\\s*[:=]\\s*([^\\s,;]+)"
    )
    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{12,}")
    private val longTokenRegex = Regex("(?i)\\b[A-Za-z0-9_-]{40,}\\b")

    private val relevantMarkers = listOf(
        "error", "errore", "fail", "fall", "timeout", "warn", "eccezione", "crash",
        "easyconn", "pxc", "h264", "first frame", "sessione mirror", "connession", "recovery",
        "wifi", "wi-fi", "network", "rete", "qr", "pair", "proximity", "prox", "overlay",
        "clock", "tft", "media projection", "encoder", "permission", "permesso"
    )

    fun buildDiagnostics(context: Context, maxLines: Int = 24, maxChars: Int = 6_000): String {
        val recent = AppLog.recentLines(100)
        if (recent.isEmpty()) return "Nessuna diagnostica locale disponibile."

        val relevant = recent.filter { line ->
            relevantMarkers.any { marker -> line.contains(marker, ignoreCase = true) }
        }
        val chosen = (if (relevant.isNotEmpty()) relevant else recent)
            .takeLast(maxLines.coerceIn(4, 40))
            .map(::redactLine)
            .filter { it.isNotBlank() }

        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "sconosciuta"
        }.getOrDefault("sconosciuta")

        val body = buildString {
            appendLine("MotoLink versione: $version")
            appendLine("Estratto diagnostico locale redatto: ${chosen.size} righe")
            chosen.forEach { appendLine(it) }
        }.trim()

        return if (body.length <= maxChars) body else body.takeLast(maxChars).let {
            "[estratto troncato per privacy/dimensione]\n$it"
        }
    }

    fun redactLine(raw: String): String {
        var text = raw
        if (text.contains("WIFI:", ignoreCase = true) || text.contains("rawPayload", ignoreCase = true)) {
            return "[payload QR/Wi-Fi rimosso dal filtro privacy]"
        }
        text = macRegex.replace(text, "[MAC_RIMOSSO]")
        text = uuidRegex.replace(text, "[UUID_RIMOSSO]")
        text = ipv4Regex.replace(text, "[IP_RIMOSSO]")
        text = secretKvRegex.replace(text) { match -> "${match.groupValues[1]}=[RIMOSSO]" }
        text = bearerRegex.replace(text, "Bearer [RIMOSSO]")
        text = longTokenRegex.replace(text, "[TOKEN_RIMOSSO]")
        return text.take(600)
    }
}
