package it.motolink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal network client for MotoLink support AI.
 *
 * Security model:
 * - The AI provider secret never exists in the APK;
 * - the APK contains only Supabase public project values;
 * - an invisible anonymous Supabase Auth session provides a short-lived JWT;
 * - the Edge Function is configured with verify_jwt=true;
 * - no chat/diagnostic payload is persisted by this client.
 */
class MotoLinkAiClient(context: Context) {
    data class Reply(val answer: String, val source: String, val supportWhatsapp: Boolean = false)

    private class HttpError(val status: Int, message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val prefs = appContext.getSharedPreferences("motolink_ai_session", Context.MODE_PRIVATE)

    private val supabaseUrl: String
        get() = appContext.getString(R.string.motolink_ai_supabase_url).trim().trimEnd('/')
    private val publishableKey: String
        get() = appContext.getString(R.string.motolink_ai_supabase_publishable_key).trim()

    fun isConfigured(): Boolean =
        supabaseUrl.startsWith("https://") &&
            !supabaseUrl.contains("YOUR_PROJECT_REF", ignoreCase = true) &&
            publishableKey.isNotBlank() &&
            !publishableKey.contains("REPLACE_ME", ignoreCase = true)

    fun ask(question: String, diagnostics: String?, appVersion: String): Result<Reply> = runCatching {
        require(isConfigured()) { "Backend IA MotoLink non ancora configurato." }
        val token = ensureAccessToken()
        try {
            invokeAssistant(token, question, diagnostics, appVersion)
        } catch (e: HttpError) {
            if (e.status == 401) {
                clearSession()
                invokeAssistant(ensureAccessToken(), question, diagnostics, appVersion)
            } else {
                throw e
            }
        }
    }

    private fun ensureAccessToken(): String {
        val now = System.currentTimeMillis()
        val access = prefs.getString("access_token", null)
        val expiresAt = prefs.getLong("expires_at_ms", 0L)
        if (!access.isNullOrBlank() && expiresAt > now + 60_000L) return access

        val refresh = prefs.getString("refresh_token", null)
        if (!refresh.isNullOrBlank()) {
            runCatching { return refreshSession(refresh) }
        }
        return createAnonymousSession()
    }

    private fun createAnonymousSession(): String {
        val conn = open("$supabaseUrl/auth/v1/signup", "POST").apply {
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Authorization", "Bearer $publishableKey")
        }
        writeJson(conn, JSONObject())
        return parseAndStoreSession(conn)
    }

    private fun refreshSession(refreshToken: String): String {
        val conn = open("$supabaseUrl/auth/v1/token?grant_type=refresh_token", "POST").apply {
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Authorization", "Bearer $publishableKey")
        }
        writeJson(conn, JSONObject().put("refresh_token", refreshToken))
        return parseAndStoreSession(conn)
    }

    private fun parseAndStoreSession(conn: HttpURLConnection): String {
        val status = conn.responseCode
        val body = readBody(conn)
        if (status !in 200..299) throw HttpError(status, friendlyServerError(status, body))
        val json = JSONObject(body)
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        if (access.isBlank()) throw IllegalStateException("Sessione anonima Supabase non valida.")
        prefs.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at_ms", System.currentTimeMillis() + expiresIn * 1000L)
            .apply()
        return access
    }

    private fun invokeAssistant(
        accessToken: String,
        question: String,
        diagnostics: String?,
        appVersion: String
    ): Reply {
        val conn = open("$supabaseUrl/functions/v1/motolink-assistant", "POST").apply {
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("X-Client-Info", "motolink-android/$appVersion")
        }
        val payload = JSONObject()
            .put("question", question.take(1_200))
            .put("app_version", appVersion.take(100))
        if (!diagnostics.isNullOrBlank()) payload.put("diagnostics", diagnostics.take(6_000))
        writeJson(conn, payload)

        val status = conn.responseCode
        val body = readBody(conn)
        if (status !in 200..299) throw HttpError(status, friendlyServerError(status, body))
        val json = JSONObject(body)
        val answer = json.optString("answer").trim()
        if (answer.isBlank()) throw IllegalStateException("Risposta IA vuota.")
        return Reply(answer, json.optString("source", "ai"), json.optBoolean("support_whatsapp", false))
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val target = URL(url)
        val network = resolveInternetNetwork()
        return (network.openConnection(target) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 25_000
            doInput = true
            doOutput = method != "GET"
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
    }

    /**
     * MotoLink can bind the whole process to the motorcycle Wi-Fi, which intentionally has no
     * Internet capability. AI traffic must therefore use a separate validated Internet network
     * (normally cellular, VPN, Ethernet or another validated transport) on a per-connection basis.
     */
    private fun resolveInternetNetwork(): Network {
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!usable) null else network to caps
        }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "Connessione Internet non disponibile. L'Assistente richiede Internet; il mirroring MotoLink continua a funzionare normalmente."
            )
        }
        fun score(caps: NetworkCapabilities): Int = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 40
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 10
            else -> 1
        }
        return candidates.maxBy { (_, caps) -> score(caps) }.first
    }

    private fun writeJson(conn: HttpURLConnection, json: JSONObject) {
        conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun friendlyServerError(status: Int, body: String): String {
        val serverMessage = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return when (status) {
            401, 403 -> "Sessione Assistente non valida. Riprova."
            429 -> "Limite gratuito temporaneamente raggiunto. Riprova più tardi."
            503 -> serverMessage.ifBlank { "Assistente temporaneamente non disponibile. Riprova più tardi." }
            else -> serverMessage.ifBlank { "Errore server Assistente ($status)." }
        }
    }

    private fun clearSession() {
        prefs.edit().clear().apply()
    }
}
