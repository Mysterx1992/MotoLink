package it.motolink.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Technical local log for support.
 *
 * - stored only in app-internal storage;
 * - no cloud/analytics/network upload;
 * - retained until the user explicitly presses Pulisci;
 * - a share copy is created only when the user explicitly chooses Condividi.
 */
object AppLog {
    private const val LOG_DIR = "logs"
    private const val SHARE_DIR = "shared_logs"
    private const val SESSION_MARKER = "mirror_session_open.marker"

    // UI-only cap: the persisted .txt log remains complete.
    const val UI_VISIBLE_LINE_LIMIT = 50

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var appContext: Context? = null
    private var crashHandlerInstalled = false
    private val mirrorSessionOpen = AtomicBoolean(false)

    // UI de-duplication only. The persisted .txt keeps every technical sample.
    private var uiVideoAliveAnnounced = false
    private var uiConnectionAliveAnnounced = false

    @Synchronized
    fun install(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        cleanupOldLogs()
        installCrashHandlerIfNeeded()

        val marker = markerFile()
        if (marker?.exists() == true) {
            add("AVVIO: la sessione precedente non risulta chiusa normalmente")
            marker.delete()
        }
    }

    @Synchronized
    fun add(message: String) {
        // TOP_RESUMED is an Android lifecycle diagnostic, not a rider-facing event.
        // Keep the lifecycle callback functional but do not persist this high-frequency noise.
        if (message.startsWith("MOTOLINK TOP RESUMED:", ignoreCase = true)) return
        val now = Date()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(now)
        val readable = readableMessage(message)
        val presented = if (readable != null && readable != message) {
            "$readable | Dettagli tecnici: $message"
        } else if (isTechnicalOnly(message)) {
            "Controllo tecnico per assistenza: funzionamento interno registrato. | Dettagli tecnici: $message"
        } else {
            message
        }
        val line = "$ts  $presented"
        appendLine(line, now)
        if (shouldNotifyUi(message)) listeners.forEach { it(line) }
    }

    /**
     * High-frequency health samples stay complete in the local .txt, but the rider-facing
     * Supporto > Log feed shows each generic "video alive" / "connection alive" status only
     * once per mirror session. This prevents needless UI rebuilds without weakening diagnostics.
     */
    private fun shouldNotifyUi(message: String): Boolean {
        val videoAlive = message.startsWith("Encoder vivo") || message.startsWith("H264 stream vivo")
        if (videoAlive) {
            if (uiVideoAliveAnnounced) return false
            uiVideoAliveAnnounced = true
            return true
        }

        val connectionAlive = message.startsWith("PXC#", ignoreCase = true) &&
            message.contains("HEARTBEAT", ignoreCase = true)
        if (connectionAlive) {
            if (uiConnectionAliveAnnounced) return false
            uiConnectionAliveAnnounced = true
            return true
        }
        return true
    }

    /**
     * Human-first wording for the events that matter to a rider. The exact original message is
     * still kept after "Dettagli tecnici:" so support diagnostics lose no information.
     */
    private fun readableMessage(message: String): String? {
        val m = message.trim()
        return when {
            m.startsWith("LOG:") -> "Registro eventi cancellato: il nuovo log parte da questo momento."
            m.startsWith("RETE UPSTREAM CAMBIO:") -> "Connessione internet cambiata: ${m.substringAfter(":").trim()}."
            m.startsWith("RETE UPSTREAM APP:") -> "Connessione internet disponibile per MotoLink: ${m.substringAfter(":").trim()}."
            m.startsWith("RETE UPSTREAM START:") -> "Avvio collegamento usando ${m.substringAfter(":").trim()}."
            m.contains("pulsante principale START premuto", true) -> "Hai premuto START: MotoLink sta avviando il collegamento con la moto."
            m.contains("pulsante principale STOP", true) -> "Hai premuto STOP: MotoLink sta chiudendo video e collegamento con la moto."
            m.startsWith("MediaProjection: START") -> "Android ha aperto la scelta dello schermo o dell'app da condividere."
            m == "SESSIONE MIRROR START" -> "Mirroring avviato: MotoLink sta preparando il video per il display della moto."
            m.startsWith("SESSIONE MIRROR STOP") -> "Mirroring terminato: MotoLink sta chiudendo la sessione in modo sicuro."
            m.startsWith("EasyConn trovato") -> "Moto rilevata sulla rete: il servizio EasyConn è disponibile."
            m.startsWith("EC INIT OK") -> "Collegamento con la moto riuscito: il sistema EasyConn è compatibile."
            m.startsWith("Sessione EasyConn pronta") -> "Canali di comunicazione con la moto pronti."
            m.startsWith("MEDIA INIT OK") -> {
                val size = Regex("""\b\d{2,4}x\d{2,4}\b""").find(m)?.value ?: "dimensione comunicata dalla moto"
                "Display moto rilevato: area video $size."
            }
            m.contains("area live acquisita", true) -> {
                val size = Regex("""\b\d{2,4}x\d{2,4}\b""").find(m)?.value ?: "ricevuta"
                "Adattamento automatico: la moto ha comunicato un'area video $size e MotoLink la userà come riferimento."
            }
            m.startsWith("VIEW AREA LIVE:") -> {
                val size = Regex("""\b\d{2,4}x\d{2,4}\b""").find(m)?.value ?: "comunicata"
                "La moto ha indicato una zona utile del display di $size: MotoLink la userà per l'adattamento automatico."
            }
            m.contains("attendo MEDIA_INIT/view-area", true) ->
                "MotoLink sta chiedendo alla moto la dimensione reale dell'area video prima di avviare il mirroring."
            m.startsWith("SOURCE NATIVE V15 RESOLVE") ->
                "Dimensioni video definite: MotoLink ha preparato l'adattamento tra telefono e display moto."
            m.contains("compositor GPU pronto", true) ->
                "Motore grafico pronto: l'immagine può essere adattata al display della moto."
            m.startsWith("MEDIA CHECK OK") -> "La moto è pronta e ha richiesto l'avvio del video."
            m.startsWith("H264 sync-frame richiesto") -> "Sincronizzazione video richiesta per mantenere il mirroring stabile."
            m.contains("nessuna area live", true) && m.contains("fallback", true) ->
                "La moto non ha comunicato in tempo la dimensione video: MotoLink usa temporaneamente il formato di sicurezza 800×480."
            m.startsWith("SOURCE NATIVE V15 READY") -> "Video pronto: l'immagine del telefono viene adattata e inviata al display della moto."
            m.startsWith("SOURCE NATIVE LANDSCAPE V15 AUTO") -> {
                val target = Regex("""target=(\d+x\d+)""").find(m)?.groupValues?.getOrNull(1) ?: "display moto"
                val view = Regex("""viewport=(\d+x\d+@-?\d+,-?\d+)""").find(m)?.groupValues?.getOrNull(1) ?: "calcolata"
                "Adattamento orizzontale automatico: display $target, area immagine $view."
            }
            m.startsWith("ADATTAMENTO V15 MIGRAZIONE") ->
                "Misura Maps salvata come nuova base automatica; il verticale resta separato."
            m.startsWith("ADATTAMENTO V15 STATE") -> {
                val orientation = if (m.contains("LANDSCAPE")) "orizzontale" else "verticale"
                val view = Regex("""viewport_effettivo=(\d+x\d+@-?\d+,-?\d+)""").find(m)?.groupValues?.getOrNull(1)
                "Adattamento $orientation attivo${view?.let { ": area effettiva $it" } ?: ""}."
            }
            m.startsWith("ADATTAMENTO V15 EDGE") -> {
                val arrow = Regex("(↑|↓|←|→)").find(m)?.value ?: "bordo"
                val step = Regex("""step=([+-]?\d+)px""").find(m)?.groupValues?.getOrNull(1) ?: "5"
                val mode = if (m.contains("RESTRINGI")) "ristretto" else "allargato"
                val view = Regex("""viewport_effettivo=(\d+x\d+@-?\d+,-?\d+)""").find(m)?.groupValues?.getOrNull(1)
                "Adattamento manuale: bordo $arrow $mode di $step px${view?.let { "; area risultante $it" } ?: ""}."
            }
            m.contains("PANEL: mostrato") -> "Pannello Adattamento visibile: puoi spostarlo e regolare i quattro bordi."
            m.contains("PANEL: nascosto") -> "Pannello Adattamento chiuso."
            m.startsWith("APP PREFERITA: aperta") -> "App aperta sul telefono: ${m.substringAfter("'").substringBeforeLast("'")}."
            m.startsWith("ROTATION ATOMIC V15: candidato SOURCE") ->
                "Rotazione dello schermo rilevata: MotoLink sta preparando la nuova inquadratura."
            m.startsWith("ROTATION ATOMIC V15: COMMIT SOURCE") -> {
                val orientation = if (m.contains("LANDSCAPE")) "orizzontale" else "verticale"
                "Rotazione completata: ora il mirroring è in modalità $orientation."
            }
            m.startsWith("H264 FIRST FRAME") -> "Video iniziato: il primo fotogramma è arrivato alla moto."
            m.startsWith("Encoder vivo") || m.startsWith("H264 stream vivo") -> "Video attivo: controllo periodico del flusso completato."
            m.contains("HEARTBEAT", true) -> "Connessione con la moto attiva: controllo periodico ricevuto correttamente."
            m.startsWith("PROX SENSOR SERVICE: NEAR") -> "Sensore di prossimità: telefono coperto, gestione schermo attiva."
            m.startsWith("PROX SENSOR SERVICE: FAR") -> "Sensore di prossimità: telefono scoperto, schermo ripristinabile."
            m.startsWith("HARD STOP COMPLETATO") -> "Arresto completato: video, rete e collegamenti MotoLink sono stati chiusi correttamente."
            m.contains("errore", true) || m.contains("fail", true) || m.contains("mancante", true) ||
                m.contains("non valida", true) || m.contains("non disponibile", true) ->
                "Attenzione: MotoLink ha rilevato un problema; consulta i dettagli tecnici o condividi il log con l'assistenza."
            else -> null
        }
    }

    private fun isTechnicalOnly(message: String): Boolean {
        val prefixes = arrayOf(
            "PXC#", "IN 109", "LISTEN 109", "HUD_CONFIG", "SOURCE NATIVE V15 CAPTURE_CONFIG",
            "DISPLAY MANUALE", "PROX POWER PATH", "PROX CONTINUOUS", "CLOCK:", "MEDIA cmd=",
            "H264 codec config", "ADAPTIVE DISPLAY:"
        )
        return prefixes.any { message.startsWith(it) }
    }

    @Synchronized
    fun markMirrorSessionStarted() {
        // One logical session = one START marker, even if UI callbacks are duplicated.
        if (!mirrorSessionOpen.compareAndSet(false, true)) return
        uiVideoAliveAnnounced = false
        uiConnectionAliveAnnounced = false
        markerFile()?.apply {
            parentFile?.mkdirs()
            writeText(System.currentTimeMillis().toString())
        }
        add("SESSIONE MIRROR START")
    }

    @Synchronized
    fun markMirrorSessionStopped(reason: String) {
        // Always clear the persistent marker, but emit STOP only once per session.
        // This prevents callback/UI races from amplifying into a log feedback loop.
        val wasOpen = mirrorSessionOpen.getAndSet(false)
        markerFile()?.delete()
        if (wasOpen) add("SESSIONE MIRROR STOP: $reason")
    }

    @Synchronized
    fun createShareFile(): File? {
        val ctx = appContext ?: return null
        cleanupOldLogs()
        val sourceDir = File(ctx.filesDir, LOG_DIR)
        val sources = sourceDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", true) }
            ?.sortedBy { it.name }
            .orEmpty()

        val shareDir = File(ctx.cacheDir, SHARE_DIR).apply { mkdirs() }
        shareDir.listFiles()?.forEach { old ->
            if (System.currentTimeMillis() - old.lastModified() > 24L * 60L * 60L * 1000L) old.delete()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val out = File(shareDir, "MotoLink_Log_$stamp.txt")
        out.bufferedWriter().use { writer ->
            writer.appendLine("MotoLink ${appVersionName()} - Log locale leggibile")
            writer.appendLine("Generato: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())}")
            writer.appendLine("Conservazione: il Log resta locale finché l’utente non preme Pulisci")
            writer.appendLine("Le frasi iniziali spiegano l'evento in modo semplice. Dopo 'Dettagli tecnici:' restano i dati completi per l'assistenza.")
            writer.appendLine()
            if (sources.isEmpty()) {
                writer.appendLine("Nessun evento registrato.")
            } else {
                sources.forEachIndexed { index, file ->
                    if (index > 0) writer.appendLine()
                    writer.appendLine("===== ${file.name} =====")
                    file.forEachLine { writer.appendLine(it) }
                }
            }
        }
        return out
    }


    private fun appVersionName(): String {
        val ctx = appContext ?: return "V1.1"
        return runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "V1.1"
        }.getOrDefault("V1.1")
    }

    @Synchronized
    fun recentLines(limit: Int = 0): List<String> {
        val ctx = appContext ?: return emptyList()
        // limit <= 0 means all persisted lines. Positive limits are used only by
        // explicitly bounded consumers such as the privacy-redacted AI attachment.
        val files = File(ctx.filesDir, LOG_DIR).listFiles()
            ?.filter { it.isFile && it.extension.equals("txt", true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return emptyList()
        val out = ArrayDeque<String>()
        files.forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    out.addLast(line)
                    if (limit > 0) while (out.size > limit) out.removeFirst()
                }
            }
        }
        return out.toList()
    }

    @Synchronized
    fun clearLogs() {
        val ctx = appContext ?: return
        File(ctx.filesDir, LOG_DIR).listFiles()?.forEach { if (it.isFile) it.delete() }
        add("LOG: registro locale pulito dall'utente")
    }

    fun subscribe(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun appendLine(line: String, now: Date = Date()) {
        val ctx = appContext ?: return
        val dir = File(ctx.filesDir, LOG_DIR).apply { mkdirs() }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(now)
        val file = File(dir, "MotoLink_$day.txt")
        try {
            file.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
            // Logging must never destabilize mirroring.
        }
    }

    @Synchronized
    private fun cleanupOldLogs() {
        // Deliberately empty: Log retention is user-controlled.
        // Only clearLogs(), invoked by the Pulisci action, deletes persisted Log files.
    }

    private fun markerFile(): File? = appContext?.let { File(it.filesDir, SESSION_MARKER) }

    @Synchronized
    private fun installCrashHandlerIfNeeded() {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                appendLine("CRASH NON GESTITO thread=${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message ?: "-"}")
                sw.toString().lineSequence().take(30).forEach { appendLine("CRASH> $it") }
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
        crashHandlerInstalled = true
    }
}
