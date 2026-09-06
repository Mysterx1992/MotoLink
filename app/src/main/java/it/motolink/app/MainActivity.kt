package it.motolink.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.CheckBox
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.net.InetAddress
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val REQ_CAPTURE = 7001
        private const val REQ_QR_IMAGE = 7002
        private const val REQ_BIKE_WIFI_PERMISSION = 7003
        private const val REQ_PROFILE_PHOTO = 7004
        private const val REQ_PROFILE_CAMERA = 7005
        private const val REQ_QR_INTERNAL = 7006
        private const val REQ_QR_CAMERA_PERMISSION = 7007
        private const val PREF_INTRO_ENABLED = "v1_intro_enabled"
        private const val PREF_DYNAMIC_BACKGROUND_ENABLED = "v1_dynamic_background_enabled"
        private const val QR_MDNS_FALLBACK_MS = 6_000L
        private const val C_BG = "#000000"
        private const val C_MUTED = "#A8B0AC"
        private const val C_GREEN = "#5BFF2D"
        private const val C_AMBER = "#E8B34F"
        private const val C_DANGER = "#E06060"
        private const val RECOVERY_FIRST_FRAME_TIMEOUT_MS = 4_000L
        private const val RECOVERY_NATURAL_GRACE_MS = 2_000L
        private const val RECOVERY_PERSISTENT_RETRY_MS = 15_000L
        private const val HARD_STOP_SERVICE_VERIFY_MS = 500L
        private const val PREFS_NAME = "trofeolink_prefs"
        private const val PREF_POCKET_MODE_CHOICE_SET = "pocket_mode_choice_set"
        private const val PREF_POCKET_MODE_ENABLED = "pocket_mode_enabled"
        private const val PREF_ONBOARDING_COMPLETE = "onboarding_v6_0_2_complete"
        private const val PREF_GUIDE_NEXT_LAUNCH = "v1_guide_next_launch"
        private const val PREF_SAFETY_NOTICE_SUPPRESSED = "safety_notice_suppressed"
        private const val PREF_QR_CAMERA_PERMISSION_REQUESTED = "v12_qr_camera_permission_requested"
        private val RECOVERY_BACKOFF_MS = longArrayOf(1_000L, 3_000L, 5_000L)
    }

    private enum class RunSelection { NONE, START, STOP }

    private val io = Executors.newSingleThreadExecutor()
    private val aiIo = Executors.newSingleThreadExecutor()
    private lateinit var discovery: EasyConnDiscovery
    private val easyConnServers: EasyConnServers by lazy(LazyThreadSafetyMode.NONE) {
        (application as TrofeoLinkApp).easyConnServers
    }
    private lateinit var bikeNetworkConnector: BikeNetworkConnector
    private lateinit var wifiDirectBikeConnector: WifiDirectBikeConnector
    @Volatile private var wifiDirectLink: WifiDirectBikeConnector.Link? = null
    @Volatile private var lastResolved: EasyConnDiscovery.ResolvedEasyConn? = null

    private lateinit var dashboard: TrofeoDashboardView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startInProgress = false
    private var runSelection = RunSelection.NONE

    // Recovery keeps MediaProjection/encoder alive while only the EasyConn TCP session
    // is rebuilt after an unexpected head-unit disconnect.
    private var mirrorConnectedOnce = false
    private var recoveryActive = false
    private var recoveryFailedWaitingManual = false
    private var recoveryGeneration = 0L
    private var recoveryAttemptIndex = -1
    private var recoveryNaturalH264Observed = false
    private var recoveryNaturalWaitArmed = false
    private var recoveryPersistentMode = false
    private var recoveryDeferredForHiddenContent = false
    private var capturedAppVisible = true
    private var sessionGeneration = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUpstreamLabel: String? = null
    private var waitingForOverlayPermission = false
    private var waitingForAdaptationOverlayPermission = false
    private var pocketSettingsOpenedFromDashboard = false
    private var pocketModeForPendingStart = false
    private var pendingBikeWifiPermission = false
    private var qrTransportFallbackAttempted = false
    private var pendingFavoriteLaunchComponent: String? = null
    private var pendingFavoriteReplaceIndex = 0
    private var lockPlaceholderActive = false
    private var lockRestartPending = false
    private var pendingProfileEditIndex = -1
    private var firstStartProfileSetupPending = false

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (::dashboard.isInitialized) dashboard.appendSupportLog(line)
            reactToLog(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(this)
        window.statusBarColor = color(C_BG)
        window.navigationBarColor = color(C_BG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        title = "MotoLink"

        discovery = EasyConnDiscovery(this, discoveryCallback@{ resolved ->
            if (runSelection != RunSelection.START) return@discoveryCallback
            val sessionToken = sessionGeneration
            lastResolved = resolved
            discovery.stop()
            val recoveryToken = if (recoveryActive) recoveryGeneration else null
            val attempt = if (recoveryActive) recoveryAttemptIndex + 1 else null
            runOnUiThread {
                if (recoveryToken != null) {
                    setHeaderStatus("Riconnessione", "", C_AMBER)
                    setState("Moto ritrovata", "Ripristino EasyConn in corso", C_AMBER, "…")
                } else {
                    setHeaderStatus("Connessione", "", C_AMBER)
                    setState("Connessione…", "Collegamento alla moto in corso", C_AMBER, "LAN")
                }
            }
            io.execute {
                val ok = runInit(resolved, sessionToken, recoveryToken, attempt)
                if (recoveryToken != null) {
                    runOnUiThread {
                        if (!isRecoveryCurrent(recoveryToken)) return@runOnUiThread
                        if (ok) armRecoveryFirstFrameTimeout(recoveryToken, recoveryAttemptIndex)
                        else scheduleRecoveryAttempt(recoveryToken, recoveryAttemptIndex + 1)
                    }
                }
            }
        })
        bikeNetworkConnector = BikeNetworkConnector(this)
        wifiDirectBikeConnector = WifiDirectBikeConnector(this)

        dashboard = TrofeoDashboardView(this).apply {
            onStartClick = { startOneTouch() }
            onStopClick = { stopEverything() }
            onQrClick = { showQrPairingMenu() }
            onPocketModeClick = { openPocketModeSettingsFromDashboard() }
            onFavoriteClick = { index -> startFavoriteApp(index) }
            onFavoriteManageClick = { showFavoriteAppsMenu() }
            onFavoriteAddClick = { addFavoriteFromGrid() }
            onFavoriteReplaceClick = { index -> replaceFavoriteAt(index) }
            onFavoriteRemoveClick = { index -> removeFavoriteAt(index) }
            onLogClick = { showLogShareChoice() }
            onBikeProfileClick = { index -> selectBikeProfile(index) }
            onBikeProfileMenuClick = { index -> showBikeProfileMenu(index) }
            onAssistantSend = { question -> sendAssistantQuestion(question, null) }
            onAssistantInfoClick = { showAssistantInfo() }
            onAssistantWhatsAppClick = { openWhatsAppGroup() }
            onIntroToggleClick = { toggleIntroSetting() }
            onGuideClick = { toggleGuideSetting() }
            onBackgroundToggleClick = { toggleDynamicBackgroundSetting() }
            onAdaptationClick = { toggleAdaptationSetting() }
            onClearLogClick = { clearLocalLog() }
            onCreditsGroupClick = { openWhatsAppGroup() }
            onOnboardingFinished = {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_ONBOARDING_COMPLETE, true)
                    .apply()
                showSafetyNoticeIfNeeded()
            }
        }
        setContentView(dashboard)
        refreshFavoriteApps()
        refreshBikeProfiles()
        refreshPocketModeUi()
        val uiPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        dashboard.updateIntroEnabled(uiPrefs.getBoolean(PREF_INTRO_ENABLED, true))
        dashboard.updateGuideEnabled(uiPrefs.getBoolean(PREF_GUIDE_NEXT_LAUNCH, true))
        dashboard.updateDynamicBackgroundEnabled(uiPrefs.getBoolean(PREF_DYNAMIC_BACKGROUND_ENABLED, true))
        MirrorAdaptationConfig.load(this).also { dashboard.updateAdaptation(it.enabled, MirrorAdaptationConfig.dashboardLabel(this)) }
        dashboard.replaceSupportLogs(AppLog.recentLines(AppLog.UI_VISIBLE_LINE_LIMIT))

        AppLog.subscribe(logListener)
        registerNetworkDiagnostics()
        setRunSelection(RunSelection.NONE)

        // A lock-placeholder transport can legitimately outlive MainActivity.
        // Reattach the UI-side flag after Activity recreation so the next START
        // performs the same clean teardown/new MediaProjection authorization as
        // the established transport flow instead of layering a new session on top.
        lockPlaceholderActive = H264FrameBus.lockPlaceholderActive()
        if (lockPlaceholderActive) {
            AppLog.add("LOCK PLACEHOLDER: stato riagganciato dopo ricreazione MainActivity")
        }

        val activeProfile = BikeProfileStore.load(this)
        setHeaderStatus("Pronto", activeProfile?.displayName ?: "", C_GREEN)
        setState(
            "Sistema pronto",
            activeProfile?.let { "Profilo moto salvato · START per connettere" } ?: "La prossimità è sempre attiva",
            C_GREEN,
            "LAN"
        )
        AppLog.add("MotoLink V1.2 GUI pronta; guida iniziale attiva; geometria display V15 validata invariata")
        AppLog.add("DISPLAY MANUALE: funzione nascosta 2x Volume Giù entro 5000ms; " +
            "BLACK OVERLAY + TOUCH BLOCK; Accessibility=OFF; polling=OFF")
        dashboard.post { startFirstRunExperience() }
    }

    private fun startFirstRunExperience() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val showGuideThisLaunch = prefs.getBoolean(PREF_GUIDE_NEXT_LAUNCH, true)
        if (showGuideThisLaunch) {
            // One-shot: consume the setting before the tour starts. Turning it ON later
            // schedules exactly one replay at the next app opening.
            prefs.edit().putBoolean(PREF_GUIDE_NEXT_LAUNCH, false).apply()
            dashboard.updateGuideEnabled(false)
            dashboard.startInitialGuide()
            AppLog.add("GUI V1: guida iniziale avviata; flag prossima apertura consumato -> OFF")
            return
        }

        if (!prefs.getBoolean(PREF_ONBOARDING_COMPLETE, false)) {
            prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE, true).apply()
        }
        showSafetyNoticeIfNeeded()
    }

    private fun showSafetyNoticeIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_SAFETY_NOTICE_SUPPRESSED, false)) return
        SafetyNoticeDialog.show(this) { suppressFuture ->
            if (suppressFuture) {
                prefs.edit().putBoolean(PREF_SAFETY_NOTICE_SUPPRESSED, true).apply()
                AppLog.add("SICUREZZA: avviso guida soppresso su scelta utente")
            } else {
                AppLog.add("SICUREZZA: avviso guida confermato; verrà mostrato ai prossimi avvii")
            }
        }
    }

    private fun startOneTouch(preferredComponent: String? = null) {
        // A real Android keyguard invalidates the old MediaProjection token. If the TFT is
        // currently showing MotoLink's synthetic lock notice, START performs one clean
        // teardown and then requests a brand-new user-authorized capture session.
        if (lockRestartPending) return
        if (lockPlaceholderActive) {
            lockRestartPending = true
            lockPlaceholderActive = false
            AppLog.add("LOCK PLACEHOLDER: nuovo START -> riavvio pulito con nuova autorizzazione MediaProjection")
            stopEverything()
            mainHandler.postDelayed({
                lockRestartPending = false
                startOneTouch(preferredComponent)
            }, HARD_STOP_SERVICE_VERIFY_MS + 250L)
            return
        }

        // After all automatic reconnect attempts are exhausted, START means "retry"
        // and reuses the still-live MediaProjection. No new Android capture dialog.
        if (recoveryFailedWaitingManual && runSelection == RunSelection.START) {
            recoveryFailedWaitingManual = false
            beginEasyConnRecovery("START manuale dopo recovery esaurita")
            return
        }
        if (startInProgress || runSelection == RunSelection.START) return
        pendingFavoriteLaunchComponent = preferredComponent
        startInProgress = true
        logCurrentUpstream("START")
        sessionGeneration++
        mirrorConnectedOnce = false
        recoveryFailedWaitingManual = false
        capturedAppVisible = true
        invalidateRecovery()
        waitingForOverlayPermission = false
        pendingBikeWifiPermission = false
        qrTransportFallbackAttempted = false
        firstStartProfileSetupPending = false
        setRunSelection(RunSelection.START)
        setHeaderStatus("Avvio", "", C_AMBER)
        setState("Avvio…", "Preparazione della sessione", C_AMBER, "…")

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_POCKET_MODE_CHOICE_SET, false)) {
            showPocketModeFirstUseDialog()
        } else {
            pocketModeForPendingStart = prefs.getBoolean(PREF_POCKET_MODE_ENABLED, false)
            continueStartAfterPocketModeChoice()
        }
    }

    private fun connectVoge() {
        easyConnServers.stop()
        if (!easyConnServers.start()) {
            if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
            wifiDirectLink = null
            setRunSelection(RunSelection.NONE)
            showEasyConnStartError()
            return
        }

        // Valico/DS900X path proven on real hardware: the TFT is the Wi-Fi Direct
        // Group Owner and accepts CmdBaseHead 0x70000010 on :10930 only after the
        // phone-side callback listeners are already open. Do this before mDNS.
        val p2p = wifiDirectLink
        if (p2p != null) {
            val sessionToken = sessionGeneration
            val direct = EasyConnDiscovery.ResolvedEasyConn(
                name = p2p.peerName,
                host = p2p.groupOwnerAddress,
                port = BikeNetworkConnector.DEFAULT_EASYCONN_INIT_PORT,
                attributes = mapOf(
                    "source" to "wifi_direct_p2p",
                    "init_mode" to "0x70000010",
                    "local_bind" to p2p.localAddress.hostAddress.orEmpty()
                )
            )
            lastResolved = direct
            setHeaderStatus("Connessione", activeBikeLabel(), C_AMBER)
            setState("WLAN Direct pronta", "Attivazione EasyConn sulla moto", C_AMBER, "P2P")
            AppLog.add(
                "P2P EASYCONN: callback 10920/10921/10922 aperti; " +
                    "attivo TFT ${p2p.groupOwnerAddress.hostAddress}:10930 con 0x70000010"
            )
            io.execute { runInit(direct, sessionToken) }
            return
        }

        val cached = lastResolved
        if (cached != null) {
            AppLog.add("Riutilizzo endpoint EasyConn già risolto in RAM: ${cached.host.hostAddress}:${cached.port}")
            setHeaderStatus("Connessione", activeBikeLabel(), C_AMBER)
            setState("Connessione…", "Riconnessione alla moto", C_AMBER, "LAN")
            val sessionToken = sessionGeneration
            io.execute { runInit(cached, sessionToken) }
            return
        }

        // If a QR supplied a concrete connection endpoint, try it
        // before mDNS. On any failure we transparently fall back to the frozen discovery path.
        val profile = BikeProfileStore.load(this)
        if (profile?.host != null && profile.port != null) {
            val sessionToken = sessionGeneration
            setHeaderStatus("Connessione", profile.displayName, C_AMBER)
            setState("Profilo QR…", "Provo l'endpoint salvato", C_AMBER, "QR")
            AppLog.add("QR PROFILE: endpoint EasyConn salvato disponibile; tentativo diretto prima di mDNS")
            io.execute {
                try {
                    val direct = EasyConnDiscovery.ResolvedEasyConn(
                        name = profile.serviceName ?: profile.displayName,
                        host = InetAddress.getByName(profile.host),
                        port = profile.port,
                        attributes = mapOf("source" to "qr_profile")
                    )
                    lastResolved = direct
                    val ok = runInit(direct, sessionToken, failureIsTerminal = false)
                    if (!ok && sessionToken == sessionGeneration && runSelection == RunSelection.START) {
                        lastResolved = null
                        runOnUiThread { startMdnsFallback("Endpoint QR non accettato") }
                    }
                } catch (t: Throwable) {
                    AppLog.add("QR PROFILE endpoint non utilizzabile: ${t.javaClass.simpleName}")
                    lastResolved = null
                    runOnUiThread {
                        if (sessionToken == sessionGeneration && runSelection == RunSelection.START) {
                            startMdnsFallback("Endpoint QR non raggiungibile")
                        }
                    }
                }
            }
            return
        }

        startMdnsFallback(null)
    }

    private fun showEasyConnStartError() {
        val detail = easyConnServers.lastStartErrorMessage.orEmpty()
        val occupied = detail.contains("EADDRINUSE", ignoreCase = true) ||
            detail.contains("Address already in use", ignoreCase = true)
        setHeaderStatus("Errore", "", C_DANGER)
        if (occupied) {
            setState("Connessione già in uso", "Chiudi altre app di mirroring e riprova", C_DANGER, "!")
            NeonDialogs.showInfo(
                activity = this,
                title = "Un’altra app di mirroring è attiva",
                message = "Le porte di collegamento alla moto risultano già occupate. Chiudi o arresta eventuali altre app di mirroring, poi premi START di nuovo."
            )
        } else {
            setState("Errore connessione", "Impossibile avviare il collegamento", C_DANGER, "!")
            NeonDialogs.showInfo(
                activity = this,
                title = "Errore connessione",
                message = "MotoLink non è riuscita ad avviare i listener EasyConn. Ferma eventuali altre app di mirroring e riprova."
            )
        }
    }

    private fun stopEverything() {
        pendingFavoriteLaunchComponent = null
        lockPlaceholderActive = false
        // STOP is a deterministic teardown command even if the UI thinks no mirror is active.
        // This gives the rider a reliable way to release
        // any stale EasyConn/TFT session without force-stopping or uninstalling the app.
        startInProgress = false
        sessionGeneration++ // invalidates late EC INIT/discovery work from the old session
        mirrorConnectedOnce = false
        recoveryFailedWaitingManual = false
        capturedAppVisible = true
        invalidateRecovery()
        waitingForOverlayPermission = false
        pocketModeForPendingStart = false
        firstStartProfileSetupPending = false
        setRunSelection(RunSelection.STOP)
        setHeaderStatus("Fermato", "", C_MUTED)
        setState("Fermato", "Chiusura completa della sessione", C_MUTED, "—")

        discovery.stop()
        bikeNetworkConnector.release()
        if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
        wifiDirectLink = null
        lastResolved = null // next START must rediscover the current HU endpoint
        EasyConnInitClient.cancelAll()
        easyConnServers.stop()
        AppLog.markMirrorSessionStopped("STOP utente / HARD STOP")
        try {
            startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_STOP })
        } catch (t: Throwable) {
            AppLog.add("HARD STOP service command fallito: ${t.javaClass.simpleName}: ${t.message ?: "-"}")
        }

        // ACTION_STOP already performs the clean shutdown. stopService is a bounded
        // second safety net so no foreground service instance can survive a STOP tap.
        mainHandler.postDelayed({
            // Repeat the network teardown after the bounded delay to catch a rare
            // EC INIT thread that was between its generation check and Socket creation.
            discovery.stop()
            lastResolved = null
            EasyConnInitClient.cancelAll()
            easyConnServers.stop()
            try {
                stopService(Intent(this, MirrorService::class.java))
            } catch (t: Throwable) {
                AppLog.add("HARD STOP stopService fallito: ${t.javaClass.simpleName}: ${t.message ?: "-"}")
            }
            if (runSelection == RunSelection.STOP) setRunSelection(RunSelection.NONE)
            // Once teardown is complete the UI is ready for a new START. Returning to PRONTO
            // also restores the untouched approved Home reference instead of leaving a patched
            // "Fermato" text block over the status card.
            setHeaderStatus("Pronto", activeBikeLabel(), C_GREEN)
            setState("Sistema pronto", "Premi START per connettere", C_GREEN, "LAN")
            AppLog.add("HARD STOP COMPLETATO: discovery/cache/socket EasyConn chiusi; MirrorService arrestato; resta solo il Log locale")
        }, HARD_STOP_SERVICE_VERIFY_MS)
    }

    private fun reactToLog(line: String) {
        when {
            line.contains("H264 FIRST FRAME") && runSelection == RunSelection.START -> {
                lockPlaceholderActive = false
                val recovered = recoveryActive
                val forcedAttempt = recoveryAttemptIndex
                mirrorConnectedOnce = true
                recoveryFailedWaitingManual = false
                if (recovered) {
                    invalidateRecovery()
                    if (forcedAttempt < 0) {
                        AppLog.add("RECOVERY OK: reconnect naturale moto completato; nessun teardown EasyConn")
                    } else {
                        AppLog.add("RECOVERY OK: H264 ripristinato automaticamente al tentativo ${forcedAttempt + 1}")
                    }
                }
                startInProgress = false
                setRunSelection(RunSelection.START)
                setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
                setState("Sistema pronto", "La prossimità è sempre attiva", C_GREEN, "LIVE")
            }
            recoveryActive && recoveryAttemptIndex < 0 && line.contains("IN 10920 H264 stream", ignoreCase = true) -> {
                // The Voge often performs its own reconnect when the rider exits/re-enters
                // the mirroring page. Never tear that freshly reopened socket down.
                recoveryNaturalH264Observed = true
                if (!recoveryNaturalWaitArmed) {
                    recoveryNaturalWaitArmed = true
                    val generation = recoveryGeneration
                    AppLog.add("RECOVERY: 10920 riaperto naturalmente dalla moto; attendo FIRST FRAME senza teardown")
                    armNaturalReconnectFirstFrameTimeout(generation)
                }
            }
            line.contains("EC INIT OK") && runSelection == RunSelection.START -> {
                if (recoveryActive) {
                    if (recoveryPersistentMode && isBikeTransportAlive()) {
                        setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
                        setState("Video in attesa", "Moto presente • attendo il flusso video", C_AMBER, "WIFI")
                    } else {
                        setHeaderStatus("Riconnessione", activeBikeLabel(), C_AMBER)
                        setState("Moto ritrovata", "Riavvio del video…", C_AMBER, "…")
                    }
                } else {
                    setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
                    setState("Moto collegata", "Avvio video in corso…", C_GREEN, "LAN")
                }
            }
            line.contains("APP CAPTURE NON VISIBILE", ignoreCase = true) -> {
                capturedAppVisible = false
                // Keep EasyConn/H264 transport untouched. MirrorService holds back Android's
                // hidden/black samples so the HU can retain the last valid app picture.
            }
            line.contains("APP CAPTURE VISIBILE", ignoreCase = true) -> {
                capturedAppVisible = true
                if (recoveryActive && recoveryDeferredForHiddenContent) {
                    recoveryDeferredForHiddenContent = false
                    val generation = recoveryGeneration
                    AppLog.add("RECOVERY RIPRESO: app selezionata di nuovo visibile")
                    armInitialRecoveryGrace(generation)
                }
            }
            line.contains("BLOCCO SCHERMO REALE RILEVATO", ignoreCase = true) -> {
                // Security boundary: Android has invalidated MediaProjection. Keep the
                // already-established EasyConn/P2P transport alive so the TFT can show
                // the synthetic lock notice, but allow the rider to press START after
                // unlocking to request a completely new capture token.
                lockPlaceholderActive = true
                startInProgress = false
                mirrorConnectedOnce = false
                recoveryFailedWaitingManual = false
                capturedAppVisible = false
                invalidateRecovery()
                setHeaderStatus("Bloccato", activeBikeLabel(), C_AMBER)
                setState("Telefono bloccato", "Sblocca il telefono e premi START", C_AMBER, "LOCK")
                setRunSelection(RunSelection.NONE)
            }
            // Service-origin event only. Never log from inside this listener (prevents recursion).
            line.contains("MediaProjection terminata dal sistema/utente") -> {
                lockPlaceholderActive = false
                startInProgress = false
                mirrorConnectedOnce = false
                recoveryFailedWaitingManual = false
                capturedAppVisible = true
                invalidateRecovery()
                if (runSelection != RunSelection.STOP) {
                    bikeNetworkConnector.release()
                    if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
                    wifiDirectLink = null
                    setHeaderStatus("Fermato", "", C_MUTED)
                    setState("Mirror terminato", "Premi START per ripartire", C_MUTED, "—")
                    setRunSelection(RunSelection.NONE)
                }
            }
            line.contains("Mirror/encoder fermato; frame RAM eliminati") -> {
                // User STOP stays green only while shutdown is actually executing.
                if (runSelection == RunSelection.STOP) setRunSelection(RunSelection.NONE)
            }
            isEasyConnConnectionLoss(line) && runSelection == RunSelection.START && mirrorConnectedOnce -> {
                if (recoveryActive) {
                    // If a natural reconnect candidate closes again before FIRST FRAME,
                    // it is no longer safe to wait on it; the forced recovery may proceed.
                    if (line.contains("10920", ignoreCase = true)) {
                        recoveryNaturalH264Observed = false
                        recoveryNaturalWaitArmed = false
                    }
                } else {
                    beginEasyConnRecovery(connectionLossReason(line))
                }
            }
            line.contains("errore", ignoreCase = true) || line.contains("fallito", ignoreCase = true) -> {
                // Expected socket failures during an active recovery are part of the old
                // EasyConn session being torn down and must not overwrite recovery UI.
                if (!recoveryActive && !line.contains("SocketTimeoutException")) {
                    setHeaderStatus("Controlla Log", "", C_DANGER)
                    setState("Controlla il Log", "È stato rilevato un errore", C_DANGER, "!")
                }
            }
        }
    }

    private fun runInit(
        resolved: EasyConnDiscovery.ResolvedEasyConn,
        sessionGenerationToken: Long,
        recoveryGenerationToken: Long? = null,
        recoveryAttempt: Int? = null,
        failureIsTerminal: Boolean = true
    ): Boolean {
        if (sessionGenerationToken != sessionGeneration || runSelection != RunSelection.START) return false
        return try {
            // The inbound EasyConn listener ports accept only the TFT resolved for this
            // session. Set it before EC INIT, which is what triggers the HU callbacks.
            easyConnServers.setExpectedPeer(resolved.host)
            val p2pMode = resolved.attributes["init_mode"] == "0x70000010"
            val result = if (p2pMode) {
                val local = resolved.attributes["local_bind"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(InetAddress::getByName)
                    ?: wifiDirectLink?.localAddress
                    ?: throw IllegalStateException("IP locale P2P assente")
                AppLog.add("EC init P2P -> ${resolved.host.hostAddress}:${resolved.port} cmd=0x70000010")
                // Prova prima l’identità di compatibilità primaria; in caso di rifiuto usa una sola volta il fallback neutro.
                val first = EasyConnInitClient.performP2pMdnsRespond(
                    resolved.host, resolved.port, "net.easyconn.easyride.wws", local
                )
                if (first.ok) {
                    AppLog.add("EC init P2P identità primaria accettata")
                    first
                } else {
                    AppLog.add("EC init P2P identità primaria non accettata; provo fallback neutro")
                    EasyConnInitClient.performP2pMdnsRespond(
                        resolved.host, resolved.port, "net.easyconn.carman.neutral", local
                    )
                }
            } else {
                val packageForHandshake = "net.easyconn.carman.neutral"
                AppLog.add("EC init -> ${resolved.host.hostAddress}:${resolved.port}")
                EasyConnInitClient.perform(resolved.host, resolved.port, packageForHandshake)
            }
            if (sessionGenerationToken != sessionGeneration || runSelection != RunSelection.START) return false
            if (result.ok) {
                AppLog.add(
                    if (p2pMode)
                        "EC INIT OK (0x70000011 P2P): la moto ha attivato EasyConn su WLAN Direct"
                    else
                        "EC INIT OK (0x11): la moto parla EasyConn compatibile"
                )
                runOnUiThread {
                    if (sessionGenerationToken == sessionGeneration && runSelection == RunSelection.START &&
                        (recoveryGenerationToken == null || isRecoveryCurrent(recoveryGenerationToken))
                    ) {
                        if (recoveryGenerationToken != null) {
                            setHeaderStatus("Riconnessione", activeBikeLabel(), C_AMBER)
                            setState("Moto ritrovata", "Attendo il nuovo flusso video…", C_AMBER, "…")
                        } else {
                            setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
                            setState("Moto collegata", "Attendo il video…", C_GREEN, "LAN")
                        }
                    }
                }
                true
            } else {
                AppLog.add("EC init risposta=${result.responseCode} body=${result.responseBody ?: "-"}")
                if (recoveryGenerationToken == null && failureIsTerminal) {
                    runOnUiThread {
                        if (sessionGenerationToken != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                        startInProgress = false
                        bikeNetworkConnector.release()
                        if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
                        wifiDirectLink = null
                        setRunSelection(RunSelection.NONE)
                        setHeaderStatus("Non pronta", "", C_DANGER)
                        setState("Moto non pronta", "Risposta ${result.responseCode}", C_DANGER, "!")
                    }
                } else if (recoveryGenerationToken != null) {
                    AppLog.add("RECOVERY tentativo ${recoveryAttempt ?: "?"}: EC INIT non accettato")
                } else {
                    AppLog.add("EC INIT non accettato durante probe/fallback; sessione resta attiva")
                }
                false
            }
        } catch (t: Throwable) {
            AppLog.add(if (failureIsTerminal || recoveryGenerationToken != null) "EC init fallito: ${t.message ?: t.javaClass.simpleName}" else "EC init probe non riuscito: ${t.javaClass.simpleName}")
            if (recoveryGenerationToken == null && failureIsTerminal) {
                runOnUiThread {
                    if (sessionGenerationToken != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    startInProgress = false
                    bikeNetworkConnector.release()
                    if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
                    wifiDirectLink = null
                    setRunSelection(RunSelection.NONE)
                    setHeaderStatus("Errore", "", C_DANGER)
                    setState("Connessione fallita", "Apri Log per i dettagli", C_DANGER, "!")
                }
            } else if (recoveryGenerationToken != null) {
                AppLog.add("RECOVERY tentativo ${recoveryAttempt ?: "?"}: EC INIT fallito")
            } else {
                AppLog.add("EC INIT probe/fallback fallito; sessione resta attiva")
            }
            false
        }
    }

    private fun beginEasyConnRecovery(reason: String) {
        if (runSelection != RunSelection.START || !mirrorConnectedOnce || recoveryActive) return
        recoveryActive = true
        recoveryFailedWaitingManual = false
        recoveryAttemptIndex = -1
        recoveryNaturalH264Observed = false
        recoveryNaturalWaitArmed = false
        recoveryPersistentMode = false
        val generation = ++recoveryGeneration
        AppLog.add("RECOVERY ARMATO: perdita EasyConn rilevata [$reason]; MediaProjection/encoder restano attivi")
        setHeaderStatus("Riconnessione", "", C_AMBER)
        armInitialRecoveryGrace(generation)
    }

    private fun armInitialRecoveryGrace(generation: Long) {
        if (!isRecoveryCurrent(generation)) return
        setState("Riconnessione…", "Attendo il reconnect naturale della moto", C_AMBER, "…")
        AppLog.add("RECOVERY grace naturale ${RECOVERY_NATURAL_GRACE_MS / 1000}s prima di qualsiasi teardown")
        mainHandler.postDelayed({
            if (!isRecoveryCurrent(generation)) return@postDelayed
            if (recoveryNaturalH264Observed) {
                if (!recoveryNaturalWaitArmed) {
                    recoveryNaturalWaitArmed = true
                    armNaturalReconnectFirstFrameTimeout(generation)
                }
                AppLog.add("RECOVERY: reconnect naturale in corso; teardown annullato, attendo FIRST FRAME")
            } else {
                performRecoveryAttempt(generation, 0)
            }
        }, RECOVERY_NATURAL_GRACE_MS)
    }

    private fun scheduleRecoveryAttempt(generation: Long, attemptIndex: Int) {
        if (!isRecoveryCurrent(generation)) return
        if (attemptIndex >= RECOVERY_BACKOFF_MS.size) {
            if (isBikeTransportAlive()) enterPersistentRecovery(generation)
            else finishRecoveryFailure(generation, "rete moto non più disponibile")
            return
        }
        val delay = RECOVERY_BACKOFF_MS[attemptIndex]
        AppLog.add("RECOVERY attesa ${delay / 1000}s prima del tentativo ${attemptIndex + 1}/${RECOVERY_BACKOFF_MS.size}")
        mainHandler.postDelayed({
            if (isRecoveryCurrent(generation)) performRecoveryAttempt(generation, attemptIndex)
        }, delay)
    }

    private fun performRecoveryAttempt(generation: Long, attemptIndex: Int) {
        if (!isRecoveryCurrent(generation)) return
        // Race guard: a HU-side reconnect can happen milliseconds before our retry timer.
        // If :10920 has already reopened, never stop the listeners underneath it.
        if (recoveryNaturalH264Observed) {
            if (!recoveryNaturalWaitArmed) {
                recoveryNaturalWaitArmed = true
                armNaturalReconnectFirstFrameTimeout(generation)
            }
            AppLog.add("RECOVERY RACE GUARD: 10920 già riaperto dalla moto; skip teardown e attendo FIRST FRAME")
            return
        }
        recoveryAttemptIndex = attemptIndex
        val attempt = attemptIndex + 1
        AppLog.add("RECOVERY tentativo $attempt/${RECOVERY_BACKOFF_MS.size}: riavvio sola sessione EasyConn")
        if (recoveryPersistentMode && isBikeTransportAlive()) {
            // Wi-Fi/P2P is still alive: preserve the connected identity while only the video/EasyConn
            // layer is being rebuilt. The rider can still press STOP at any time.
            setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
            setState("Video in attesa", "Wi-Fi moto attivo • ripristino automatico", C_AMBER, "WIFI")
        } else {
            setHeaderStatus("Riconnessione", "", C_AMBER)
            setState("Riconnessione $attempt/${RECOVERY_BACKOFF_MS.size}", "MediaProjection resta attiva", C_AMBER, "…")
        }

        // The encoder and MediaProjection live in MirrorService and are deliberately untouched.
        discovery.stop()
        EasyConnInitClient.cancelAll()
        easyConnServers.stop()
        if (!easyConnServers.start()) {
            AppLog.add("RECOVERY tentativo $attempt: listener EasyConn non avviati")
            scheduleRecoveryAttempt(generation, attemptIndex + 1)
            return
        }

        val resolved = lastResolved
        if (resolved == null) {
            AppLog.add("RECOVERY tentativo $attempt: endpoint non in cache, riavvio mDNS")
            discovery.start()
            // If mDNS resolves, the normal callback will run EC INIT. Give it the same
            // bounded first-frame window before moving to the next retry.
            armRecoveryFirstFrameTimeout(generation, attemptIndex)
            return
        }

        io.execute {
            val ok = runInit(resolved, sessionGeneration, generation, attempt)
            runOnUiThread {
                if (!isRecoveryCurrent(generation)) return@runOnUiThread
                if (ok) {
                    armRecoveryFirstFrameTimeout(generation, attemptIndex)
                } else {
                    scheduleRecoveryAttempt(generation, attemptIndex + 1)
                }
            }
        }
    }

    private fun armNaturalReconnectFirstFrameTimeout(generation: Long) {
        mainHandler.postDelayed({
            if (!isRecoveryCurrent(generation)) return@postDelayed
            if (!recoveryNaturalH264Observed) return@postDelayed
            AppLog.add("RECOVERY naturale: 10920 aperto ma nessun H264 FIRST FRAME entro ${RECOVERY_FIRST_FRAME_TIMEOUT_MS / 1000}s; passo al recovery forzato")
            recoveryNaturalH264Observed = false
            recoveryNaturalWaitArmed = false
            performRecoveryAttempt(generation, 0)
        }, RECOVERY_FIRST_FRAME_TIMEOUT_MS)
    }

    private fun armRecoveryFirstFrameTimeout(generation: Long, attemptIndex: Int) {
        mainHandler.postDelayed({
            if (!isRecoveryCurrent(generation)) return@postDelayed
            AppLog.add("RECOVERY tentativo ${attemptIndex + 1}: nessun H264 FIRST FRAME entro ${RECOVERY_FIRST_FRAME_TIMEOUT_MS / 1000}s")
            scheduleRecoveryAttempt(generation, attemptIndex + 1)
        }, RECOVERY_FIRST_FRAME_TIMEOUT_MS)
    }

    private fun enterPersistentRecovery(generation: Long) {
        if (!isRecoveryCurrent(generation)) return
        if (!isBikeTransportAlive()) {
            finishRecoveryFailure(generation, "rete moto non più disponibile")
            return
        }
        recoveryPersistentMode = true
        recoveryFailedWaitingManual = false
        recoveryAttemptIndex = -1
        recoveryNaturalH264Observed = false
        recoveryNaturalWaitArmed = false
        AppLog.add(
            "RECOVERY PERSISTENTE: 3 tentativi rapidi esauriti ma Wi-Fi/P2P moto ancora attivo; " +
                "sessione mantenuta e retry EasyConn ogni ${RECOVERY_PERSISTENT_RETRY_MS / 1000}s"
        )
        setHeaderStatus("Connesso", activeBikeLabel(), C_GREEN)
        setState("Video in attesa", "Wi-Fi moto attivo • riconnessione automatica continua", C_AMBER, "WIFI")
        schedulePersistentRecovery(generation)
    }

    private fun schedulePersistentRecovery(generation: Long) {
        if (!isRecoveryCurrent(generation) || !recoveryPersistentMode) return
        mainHandler.postDelayed({
            if (!isRecoveryCurrent(generation) || !recoveryPersistentMode) return@postDelayed
            if (!isBikeTransportAlive()) {
                finishRecoveryFailure(generation, "rete moto persa durante attesa video")
                return@postDelayed
            }
            AppLog.add("RECOVERY PERSISTENTE: Wi-Fi/P2P ancora attivo; nuovo ciclo EasyConn")
            performRecoveryAttempt(generation, 0)
        }, RECOVERY_PERSISTENT_RETRY_MS)
    }

    private fun isBikeTransportAlive(): Boolean {
        val classicWifiAlive = ::bikeNetworkConnector.isInitialized && bikeNetworkConnector.isLinkAlive()
        val p2pAlive = ::wifiDirectBikeConnector.isInitialized && wifiDirectBikeConnector.isLinkAlive()
        return classicWifiAlive || p2pAlive
    }

    private fun finishRecoveryFailure(generation: Long, reason: String = "trasporto moto non disponibile") {
        if (!isRecoveryCurrent(generation)) return
        recoveryActive = false
        recoveryPersistentMode = false
        recoveryFailedWaitingManual = true
        recoveryAttemptIndex = -1
        recoveryGeneration++
        AppLog.add("RECOVERY TERMINATA: $reason; il collegamento Wi-Fi/P2P non risulta più attivo")
        setHeaderStatus("Connessione persa", "", C_DANGER)
        setState("Connessione persa", "Rete moto non disponibile • premi START per riprovare", C_DANGER, "—")
    }

    private fun invalidateRecovery() {
        recoveryActive = false
        recoveryAttemptIndex = -1
        recoveryNaturalH264Observed = false
        recoveryNaturalWaitArmed = false
        recoveryPersistentMode = false
        recoveryDeferredForHiddenContent = false
        recoveryGeneration++
    }

    private fun isRecoveryCurrent(generation: Long): Boolean =
        recoveryActive && recoveryGeneration == generation && runSelection == RunSelection.START

    private fun showPocketModeFirstUseDialog() {
        NeonDialogs.showConfirm(
            activity = this,
            title = "Modalità tasca",
            message = """
                Attivare la Modalità tasca con il sensore di prossimità?

                IMPORTANTE: durante il mirroring NON usare il blocco schermo o il tasto di accensione per bloccare il telefono. Il vero blocco schermo termina la cattura Android. MotoLink proverà a lasciare sul TFT una schermata con lucchetto; dopo lo sblocco premi START per autorizzare di nuovo la cattura.

                Per tenere il telefono in tasca usa la Modalità tasca: il sensore di prossimità può oscurare il display senza bloccare il dispositivo. Su alcuni telefoni Android può essere necessario abilitare ‘Mostra sopra altre app’ per mantenere questa funzione quando MotoLink è in background.

                MotoLink non crea overlay visibili sopra le altre app.

                Se scegli NO, il mirroring parte normalmente e resta attivo il comportamento nativo del sensore di prossimità.
            """.trimIndent(),
            positiveText = "SÌ",
            negativeText = "NO",
            onPositive = {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_POCKET_MODE_CHOICE_SET, true)
                    .putBoolean(PREF_POCKET_MODE_ENABLED, true)
                    .apply()
                pocketModeForPendingStart = true
                AppLog.add("MODALITÀ TASCA primo utilizzo: SÌ; Gate background abilitata quando autorizzata")
                refreshPocketModeUi()
                continueStartAfterPocketModeChoice()
            },
            onNegative = {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_POCKET_MODE_CHOICE_SET, true)
                    .putBoolean(PREF_POCKET_MODE_ENABLED, false)
                    .apply()
                pocketModeForPendingStart = false
                AppLog.add("MODALITÀ TASCA primo utilizzo: NO; nessuna richiesta Mostra sopra altre app; solo proximity nativo")
                refreshPocketModeUi()
                continueStartAfterPocketModeChoice()
            }
        )
    }

    private fun continueStartAfterPocketModeChoice() {
        if (BikeProfileStore.load(this) == null) {
            showFirstStartConnectionChoice()
            return
        }
        continueStartAfterProfileReady()
    }

    private fun continueStartAfterProfileReady() {
        if (pocketModeForPendingStart) {
            ensureBackgroundGatePermissionThenProjection()
        } else {
            prepareBikeNetworkThenProjection()
        }
    }

    private fun showFirstStartConnectionChoice() {
        if (runSelection != RunSelection.START) return
        if (BikeProfileStore.load(this) != null) {
            firstStartProfileSetupPending = false
            continueStartAfterProfileReady()
            return
        }

        firstStartProfileSetupPending = true
        var handled = false
        val dialog = NeonDialogs.showCustom(
            activity = this,
            title = "Prima connessione",
            message = "Non hai ancora un profilo moto salvato.\n\nScegli come collegare la moto. In entrambi i casi completerai il normale profilo del Garage prima che START continui.",
            contentView = null,
            positiveText = "QR CODE",
            negativeText = "HOTSPOT",
            onPositive = {
                handled = true
                AppLog.add("PRIMO START V1.1: scelta QR CODE; apro scanner, poi profilo completo Garage")
                startQrCameraScan()
            },
            onNegative = {
                handled = true
                AppLog.add("PRIMO START V1.1: scelta HOTSPOT; apro profilo completo Garage")
                val base = BikeProfile(
                    displayName = "",
                    format = "HOTSPOT",
                    rawPayload = "HOTSPOT:${System.currentTimeMillis()}"
                )
                showBikeProfileCreationDialog(
                    baseProfile = base,
                    title = "Nuovo profilo moto",
                    message = "Completa il profilo della moto. Dopo il salvataggio MotoLink continuerà automaticamente con il normale collegamento Hotspot / EasyConn.",
                    onSaved = { profile ->
                        firstStartProfileSetupPending = false
                        lastResolved = null
                        refreshBikeProfiles()
                        setHeaderStatus("Avvio", profile.displayName, C_AMBER)
                        setState("Profilo salvato", "Continuo con il collegamento", C_AMBER, "LAN")
                        AppLog.add("PRIMO START V1.1: profilo HOTSPOT completo salvato; continuo automaticamente")
                        continueStartAfterProfileReady()
                    },
                    onCancel = { showFirstStartConnectionChoice() }
                )
            }
        )
        dialog.setOnDismissListener {
            mainHandler.post {
                if (!handled && firstStartProfileSetupPending &&
                    BikeProfileStore.load(this) == null && runSelection == RunSelection.START
                ) {
                    abortFirstStartProfileSetup("scelta Hotspot/QR chiusa")
                }
            }
        }
    }

    private fun abortFirstStartProfileSetup(reason: String) {
        firstStartProfileSetupPending = false
        pendingFavoriteLaunchComponent = null
        startInProgress = false
        setRunSelection(RunSelection.NONE)
        setHeaderStatus("Pronto", "", C_GREEN)
        setState("Avvio annullato", "Premi START per riprovare", C_MUTED, "LAN")
        AppLog.add("PRIMO START V1.1 annullato: $reason")
    }

    private fun ensureBackgroundGatePermissionThenProjection() {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            AppLog.add(
                "MODALITÀ TASCA: SYSTEM_ALERT_WINDOW disponibile; nessuna finestra overlay viene creata; " +
                    "permesso usato solo per consentire ProximityGateActivity dal background"
            )
            prepareBikeNetworkThenProjection()
            return
        }

        waitingForOverlayPermission = true
        setHeaderStatus("Modalità tasca", "", C_AMBER)
        setState("Abilita MotoLink", "In Mostra sopra altre app, seleziona MotoLink e abilita l’accesso", C_AMBER, "…")
        AppLog.add(
            "MODALITÀ TASCA: richiesta 'Mostra sopra altre app'; Android 11+ può mostrare l’elenco app; " +
                "seleziona MotoLink. Nessun overlay visibile verrà creato"
        )
        try {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                settingsIntent.data = Uri.parse("package:$packageName")
            }
            startActivity(settingsIntent)
        } catch (t: Throwable) {
            waitingForOverlayPermission = false
            pocketModeForPendingStart = false
            AppLog.add(
                "MODALITÀ TASCA: impossibile aprire impostazioni (${t.javaClass.simpleName}: ${t.message ?: "-"}); " +
                    "proseguo con proximity nativo"
            )
            prepareBikeNetworkThenProjection()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFavoriteApps()
        MirrorAdaptationConfig.load(this).also { dashboard.updateAdaptation(it.enabled, MirrorAdaptationConfig.dashboardLabel(this)) }

        if (pocketSettingsOpenedFromDashboard) {
            pocketSettingsOpenedFromDashboard = false
            val enabled = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_POCKET_MODE_CHOICE_SET, true)
                .putBoolean(PREF_POCKET_MODE_ENABLED, enabled)
                .apply()
            AppLog.add(
                "MODALITÀ TASCA accesso rapido: stato aggiornato=${if (enabled) "SI" else "NO"}; " +
                    "nessun mirroring avviato"
            )
        }
        refreshPocketModeUi()

        if (waitingForAdaptationOverlayPermission) {
            waitingForAdaptationOverlayPermission = false
            val granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
            MirrorAdaptationConfig.setEnabled(this, granted)
            val cfg = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(cfg.enabled, MirrorAdaptationConfig.dashboardLabel(this))
            AppLog.add(
                "ADATTAMENTO V15: ritorno permesso overlay -> ${if (granted) "ON" else "OFF"}"
            )
            if (granted && runSelection == RunSelection.START) {
                startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
            }
            if (granted) {
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Adattamento attivo",
                    message = MirrorAdaptationConfig.USER_HELP_TEXT
                )
            }
        }

        if (!waitingForOverlayPermission) return
        mainHandler.postDelayed({
            if (!waitingForOverlayPermission) return@postDelayed
            waitingForOverlayPermission = false
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                pocketModeForPendingStart = true
                AppLog.add(
                    "MODALITÀ TASCA: autorizzazione concessa; Gate background disponibile; proseguo con MediaProjection"
                )
            } else {
                pocketModeForPendingStart = false
                AppLog.add(
                    "MODALITÀ TASCA: autorizzazione non concessa; proseguo comunque con il solo proximity nativo"
                )
            }
            prepareBikeNetworkThenProjection()
        }, 220L)
    }

    private fun refreshPocketModeUi() {
        if (!::dashboard.isInitialized) return
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val chosen = prefs.getBoolean(PREF_POCKET_MODE_ENABLED, false)
        val overlayAvailable = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        dashboard.updatePocketMode(chosen && overlayAvailable)
    }

    private fun openPocketModeSettingsFromDashboard() {
        if (Build.VERSION.SDK_INT < 23) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_POCKET_MODE_CHOICE_SET, true)
                .putBoolean(PREF_POCKET_MODE_ENABLED, true)
                .apply()
            refreshPocketModeUi()
            NeonDialogs.showInfo(
                activity = this,
                title = "Modalità tasca",
                message = "Su questa versione Android la Modalità tasca non richiede la schermata ‘Mostra sopra altre app’."
            )
            return
        }

        pocketSettingsOpenedFromDashboard = true
        AppLog.add("MODALITÀ TASCA accesso rapido: apro 'Mostra sopra altre app' senza avviare il mirroring")
        try {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                settingsIntent.data = Uri.parse("package:$packageName")
            }
            startActivity(settingsIntent)
        } catch (t: Throwable) {
            pocketSettingsOpenedFromDashboard = false
            AppLog.add(
                "MODALITÀ TASCA accesso rapido fallito: ${t.javaClass.simpleName}: ${t.message ?: "-"}"
            )
            NeonDialogs.showInfo(
                activity = this,
                title = "Modalità tasca",
                message = "Non riesco ad aprire automaticamente ‘Mostra sopra altre app’. Apri Impostazioni Android > App > Accesso speciale > Mostra sopra altre app e seleziona MotoLink."
            )
        }
    }

    private fun prepareBikeNetworkThenProjection() {
        if (runSelection != RunSelection.START) return
        val profile = BikeProfileStore.load(this)
        if (profile == null) {
            requestProjectionChoice()
            return
        }
        if (!profile.hasWifiIdentity()) {
            // Generic HOTSPOT profiles rely on the rider already being on the motorcycle Wi-Fi.
            // Never continue on cellular: that produces a valid encoder session with no possible
            // EasyConn peer, as seen on the CFMOTO compatibility report.
            if (profile.format.equals("HOTSPOT", ignoreCase = true) && !isDefaultNetworkWifi()) {
                startInProgress = false
                setRunSelection(RunSelection.NONE)
                setHeaderStatus("Rete moto", profile.displayName, C_DANGER)
                setState("Collega il Wi-Fi della moto", "Il telefono è su rete mobile", C_DANGER, "!")
                AppLog.add("HOTSPOT GUARD V1.2: profilo senza SSID e rete corrente non Wi-Fi; START interrotto prima di MediaProjection")
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Collega prima la moto",
                    message = "Questo profilo Hotspot non contiene SSID/password. Collega prima il telefono alla rete Wi-Fi della moto, poi torna in MotoLink e premi START. Per CFMOTO usa preferibilmente QR CODE: MotoLink proverà automaticamente WLAN Direct/P2P e il fallback Wi-Fi/EasyConn."
                )
                return
            }
            requestProjectionChoice()
            return
        }

        if (!hasBikeWifiPermission()) {
            pendingBikeWifiPermission = true
            val permission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            AppLog.add(
                if (wifiDirectBikeConnector.shouldUse(profile))
                    "WLAN DIRECT: richiesta permesso Android per collegamento P2P alla moto"
                else
                    "QR WIFI: richiesta permesso Android per collegamento alla rete moto"
            )
            requestPermissions(arrayOf(permission), REQ_BIKE_WIFI_PERMISSION)
            return
        }

        if (wifiDirectBikeConnector.shouldUse(profile)) {
            connectBikeWifiDirectThenProjection(profile)
        } else {
            connectBikeWifiThenProjection(profile)
        }
    }

    private fun connectBikeWifiDirectThenProjection(profile: BikeProfile) {
        if (runSelection != RunSelection.START) return
        val token = sessionGeneration
        wifiDirectLink = null
        setHeaderStatus("WLAN Direct", profile.displayName, C_AMBER)
        setState("Cerco la moto…", "Connessione WLAN Direct/P2P", C_AMBER, "P2P")
        val explicitP2p = wifiDirectBikeConnector.isExplicitP2p(profile)
        AppLog.add(
            "WLAN DIRECT AUTO: percorso P2P selezionato per ${profile.displayName}; " +
                "target QR esatto=${profile.ssid?.trim()?.takeIf { it.isNotEmpty() } ?: "-"}; " +
                "classificazione=${if (explicitP2p) "EXPLICIT" else "AUTO_PROBE"}"
        )
        wifiDirectBikeConnector.connect(
            profile = profile,
            timeoutMs = if (explicitP2p) 22_000L else 14_000L,
            onReady = { link ->
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    wifiDirectLink = link
                    // Persist only the transport classification. No P2P MAC/password/IP is saved.
                    if (!profile.topology.equals("P2P", ignoreCase = true)) {
                        if (!BikeProfileStore.save(this, profile.copy(topology = "P2P"))) {
                            AppLog.add("QR PROFILE: aggiornamento classificazione P2P non salvato")
                        }
                    }
                    AppLog.add(
                        "WLAN DIRECT READY: phone=${link.localAddress.hostAddress}; " +
                            "bikeGO=${link.groupOwnerAddress.hostAddress}; interface=${link.interfaceName ?: "-"}"
                    )
                    setHeaderStatus("Rete pronta", profile.displayName, C_GREEN)
                    setState("WLAN Direct pronta", "Ora autorizza la condivisione dello schermo", C_GREEN, "P2P")
                    requestProjectionChoice()
                }
            },
            onUnavailable = { reason ->
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    wifiDirectLink = null
                    if (!explicitP2p) {
                        AppLog.add("WLAN DIRECT AUTO-PROBE: target QR esatto non agganciato ($reason); fallback al percorso Wi-Fi/EasyConn classico")
                        wifiDirectBikeConnector.release(removeGroup = false)
                        connectBikeWifiThenProjection(profile)
                        return@runOnUiThread
                    }
                    startInProgress = false
                    setRunSelection(RunSelection.NONE)
                    setHeaderStatus("WLAN Direct", profile.displayName, C_DANGER)
                    setState("Moto non collegata", "Apri la schermata telefono/QR sul TFT e riprova", C_DANGER, "!")
                    AppLog.add("WLAN DIRECT FAIL: $reason; START interrotto prima della cattura schermo")
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "WLAN Direct non collegata",
                        message = "Apri sul TFT la schermata di connessione telefono/QR e riprova. MotoLink cerca solo il dispositivo indicato dal QR. Se un’altra app di mirroring è attiva, chiudila completamente o forza l’arresto prima di riprovare."
                    )
                }
            }
        )
    }

    private fun isDefaultNetworkWifi(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasBikeWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun connectBikeWifiThenProjection(profile: BikeProfile) {
        if (runSelection != RunSelection.START) return
        val token = sessionGeneration
        setHeaderStatus("Rete moto", profile.displayName, C_AMBER)
        setState("Connessione rete…", "Associazione al TFT tramite profilo QR", C_AMBER, "QR")
        bikeNetworkConnector.connect(
            profile = profile,
            onReady = {
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    setHeaderStatus("Rete pronta", profile.displayName, C_GREEN)
                    setState("Rete moto pronta", "Ora autorizza la condivisione dello schermo", C_GREEN, "Wi-Fi")
                    requestProjectionChoice()
                }
            },
            onUnavailable = { reason ->
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    AppLog.add("QR WIFI: $reason; continuo sul percorso EasyConn esistente")
                    setHeaderStatus("Avvio", profile.displayName, C_AMBER)
                    setState("Rete QR non agganciata", "Proseguo con EasyConn sulla rete corrente", C_AMBER, "LAN")
                    requestProjectionChoice()
                }
            }
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_QR_CAMERA_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                AppLog.add("QR PAIRING V1.2: permesso CAMERA fallback concesso")
                launchInternalQrScanner()
            } else {
                AppLog.add("QR PAIRING V1.2: permesso CAMERA fallback non concesso; non verrà richiesto di nuovo automaticamente")
                showQrErrorForCurrentFlow(
                    "Accesso alla fotocamera non concesso. MotoLink non lo richiederà di nuovo automaticamente: puoi usare QR da immagine oppure abilitarlo dalle impostazioni Android."
                )
            }
            return
        }
        if (requestCode != REQ_BIKE_WIFI_PERMISSION) return
        if (!pendingBikeWifiPermission) return
        pendingBikeWifiPermission = false
        if (runSelection != RunSelection.START) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val profile = BikeProfileStore.load(this)
        if (granted && profile != null) {
            if (wifiDirectBikeConnector.shouldUse(profile)) {
                AppLog.add("WLAN DIRECT: permesso Android concesso")
                connectBikeWifiDirectThenProjection(profile)
            } else {
                AppLog.add("QR WIFI: permesso Android concesso")
                connectBikeWifiThenProjection(profile)
            }
        } else if (profile != null && wifiDirectBikeConnector.shouldUse(profile)) {
            startInProgress = false
            setRunSelection(RunSelection.NONE)
            AppLog.add("WLAN DIRECT: permesso Android non concesso; START interrotto")
            setHeaderStatus("Permesso richiesto", profile.displayName, C_DANGER)
            setState("WLAN Direct non disponibile", "Concedi Dispositivi Wi-Fi nelle vicinanze e riprova", C_DANGER, "!")
            NeonDialogs.showInfo(
                activity = this,
                title = "Permesso WLAN Direct necessario",
                message = "Per collegarsi direttamente alla moto via WLAN Direct, MotoLink deve poter usare Dispositivi Wi-Fi nelle vicinanze. Premi START di nuovo e concedi il permesso Android."
            )
        } else {
            AppLog.add("QR WIFI: permesso Android non concesso; attendo scelta utente prima di proseguire")
            NeonDialogs.showConfirm(
                activity = this,
                title = "Connessione alla moto necessaria",
                message = "Il profilo QR è salvato, ma MotoLink non può collegarsi automaticamente alla rete Wi-Fi della moto senza il permesso Dispositivi Wi-Fi nelle vicinanze.\n\nPremi RIPROVA per concedere il permesso. Usa CONTINUA SENZA QR solo per proseguire sulla rete attuale.",
                positiveText = "RIPROVA",
                negativeText = "CONTINUA SENZA QR",
                onPositive = { prepareBikeNetworkThenProjection() },
                onNegative = {
                    AppLog.add("QR WIFI: utente sceglie CONTINUA SENZA QR")
                    requestProjectionChoice()
                }
            )
        }
    }

    private fun requestProjectionChoice() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        val favoriteShortcut = pendingFavoriteLaunchComponent != null

        val captureIntent = if (favoriteShortcut && Build.VERSION.SDK_INT >= 34) {
            // A favorite is explicitly a one-tap shortcut. Android does not allow us to
            // preselect a third-party app in the single-app picker, so for favorites we
            // request the public Android 14+ full-display-only MediaProjection config.
            // Reflection keeps this source compatible with vendor SDK stubs that have
            // previously failed to expose MediaProjectionConfig at compile time.
            createFavoriteFullDisplayCaptureIntent(mgr)
        } else {
            if (Build.VERSION.SDK_INT >= 34) {
                AppLog.add("MediaProjection: START normale -> scelta sorgente Android (schermo intero o singola app)")
            } else {
                AppLog.add("MediaProjection: dispositivo pre-Android 14, cattura schermo intero")
            }
            mgr.createScreenCaptureIntent()
        }

        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_CAPTURE)
    }

    private fun createFavoriteFullDisplayCaptureIntent(mgr: MediaProjectionManager): Intent {
        return try {
            val configClass = Class.forName("android.media.projection.MediaProjectionConfig")
            val config = configClass.getMethod("createConfigForDefaultDisplay").invoke(null)
            val method = MediaProjectionManager::class.java.getMethod(
                "createScreenCaptureIntent",
                configClass
            )
            val intent = method.invoke(mgr, config) as Intent
            AppLog.add("MediaProjection: app preferita -> cattura schermo intero richiesta")
            intent
        } catch (t: Throwable) {
            // Safe fallback for unusual vendor implementations. The standard dialog still
            // works; on such devices the rider may need to choose Schermo intero manually.
            AppLog.add(
                "MediaProjection: full-display preferita non disponibile (${t.javaClass.simpleName}); uso chooser standard"
            )
            mgr.createScreenCaptureIntent()
        }
    }

    @Deprecated("Legacy result API kept to support minSdk 29 without ActivityX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROFILE_PHOTO) {
            val index = pendingProfileEditIndex
            pendingProfileEditIndex = -1
            if (resultCode == RESULT_OK && index >= 0 && data?.data != null) {
                val uri = data.data!!
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                BikeProfileStore.updateMetadata(this, index, photoUri = uri.toString())
                refreshBikeProfiles()
                AppLog.add("GARAGE: immagine profilo scelta dall'utente; contenuto non inserito nel Log")
            }
            return
        }
        if (requestCode == REQ_PROFILE_CAMERA) {
            val index = pendingProfileEditIndex
            pendingProfileEditIndex = -1
            if (resultCode == RESULT_OK && index >= 0) {
                @Suppress("DEPRECATION") val bmp = data?.extras?.get("data") as? Bitmap
                if (bmp != null) {
                    val dir = File(filesDir, "profile_photos").apply { mkdirs() }
                    val out = File(dir, "bike_${index}_${System.currentTimeMillis()}.jpg")
                    runCatching { FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                    BikeProfileStore.updateMetadata(this, index, photoUri = out.toURI().toString())
                    refreshBikeProfiles()
                    AppLog.add("GARAGE: foto profilo acquisita localmente; immagine non inserita nel Log")
                }
            }
            return
        }
        if (requestCode == REQ_QR_INTERNAL) {
            if (resultCode == RESULT_OK) {
                val raw = data?.getStringExtra(InternalQrScannerActivity.EXTRA_QR_RAW)
                if (raw.isNullOrBlank()) {
                    showQrErrorForCurrentFlow("La fotocamera non ha restituito un QR leggibile.")
                } else {
                    AppLog.add("QR PAIRING V1.2: QR letto dallo scanner interno; payload non scritto nel Log")
                    handleQrPayload(raw)
                }
            } else {
                AppLog.add("QR PAIRING V1.2: scanner interno chiuso senza QR")
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                }
            }
            return
        }
        if (requestCode == REQ_QR_IMAGE) {
            if (resultCode == RESULT_OK && data?.data != null) decodeQrFromImage(data.data!!)
            return
        }
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            AppLog.add("Condivisione schermo annullata")
            pendingFavoriteLaunchComponent = null
            bikeNetworkConnector.release()
            if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = true)
            wifiDirectLink = null
            startInProgress = false
            setRunSelection(RunSelection.NONE)
            setHeaderStatus("Pronto", "", C_GREEN)
            setState("Avvio annullato", "Premi START per riprovare", C_MUTED, "LAN")
            return
        }

        val activeProfileForVideo = BikeProfileStore.load(this)
        val useValicoSoftH264 = wifiDirectLink != null ||
            activeProfileForVideo?.topology?.equals("P2P", ignoreCase = true) == true
        AppLog.add(
            if (useValicoSoftH264)
                "VALICO VIDEO AUTO: WLAN Direct/P2P riconosciuta -> codifica H264 software compatibile"
            else
                "VIDEO AUTO: percorso standard -> encoder H264 hardware MotoLink"
        )

        val serviceIntent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            putExtra(MirrorService.EXTRA_VALICO_SOFT_H264, useValicoSoftH264)
        }
        startForegroundService(serviceIntent)
        AppLog.markMirrorSessionStarted()

        // The native proximity wake-lock is always armed. The transparent Gate workaround
        // is enabled only when the rider chose Pocket Mode and Android granted SAW.
        val gateAllowed = pocketModeForPendingStart &&
            (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))
        AppLog.add(
            "MODALITÀ TASCA SESSIONE: scelta=${if (pocketModeForPendingStart) "SI" else "NO"} " +
                "gateAllowed=$gateAllowed; proximity nativo sempre armato"
        )
        startService(Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_PROX_ARM
            putExtra(MirrorService.EXTRA_PROX_GATE_ALLOWED, gateAllowed)
        })

        setRunSelection(RunSelection.START)
        setHeaderStatus("Avvio", "", C_AMBER)
        setState("Avvio mirror…", "Connessione automatica alla moto", C_AMBER, "LAN")
        connectVoge()
        launchPendingFavoriteApp()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AppLog.add(
            "MOTOLINK TOP RESUMED: $isTopResumedActivity | run=$runSelection"
        )
        if (runSelection == RunSelection.START) {
            try {
                startService(Intent(this, MirrorService::class.java).apply {
                    action = MirrorService.ACTION_PROX_SNAPSHOT
                    putExtra(
                        MirrorService.EXTRA_PROX_STAGE,
                        "TOP_RESUMED_${if (isTopResumedActivity) "TRUE" else "FALSE"}"
                    )
                })
            } catch (t: Throwable) {
                AppLog.add("PROX TRANSITION SNAPSHOT fallito: ${t.javaClass.simpleName}: ${t.message ?: "-"}")
            }
        }
    }

    private fun isEasyConnConnectionLoss(line: String): Boolean {
        return line.contains("H264 stream chiuso dalla Voge", ignoreCase = true) ||
            (line.contains("10920 errore", ignoreCase = true) &&
                (line.contains("Broken pipe", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true))) ||
            ((line.contains("PXC#1", ignoreCase = true) || line.contains("PXC#2", ignoreCase = true)) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true))) ||
            (line.contains("10921", ignoreCase = true) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true)))
    }

    private fun connectionLossReason(line: String): String = when {
        line.contains("10920", ignoreCase = true) -> "H264/10920"
        line.contains("PXC#1", ignoreCase = true) -> "PXC#1/10922"
        line.contains("PXC#2", ignoreCase = true) -> "PXC#2/10922"
        line.contains("10921", ignoreCase = true) -> "MEDIA/10921"
        else -> "EasyConn"
    }

    private fun registerNetworkDiagnostics() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logCurrentUpstream("CAMBIO")
            }

            override fun onLost(network: Network) {
                mainHandler.postDelayed({ logCurrentUpstream("CAMBIO") }, 250L)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val label = describeUpstream(caps)
                if (label != lastUpstreamLabel) {
                    lastUpstreamLabel = label
                    AppLog.add("RETE UPSTREAM CAMBIO: $label")
                }
            }
        }
        networkCallback = cb
        try {
            cm.registerDefaultNetworkCallback(cb)
            logCurrentUpstream("APP")
        } catch (t: Throwable) {
            AppLog.add("RETE UPSTREAM diagnostica non disponibile: ${t.javaClass.simpleName}")
        }
    }

    private fun logCurrentUpstream(reason: String) {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val label = describeUpstream(caps)
            if (label != lastUpstreamLabel || reason == "START") {
                lastUpstreamLabel = label
                AppLog.add("RETE UPSTREAM $reason: $label")
            }
        } catch (t: Throwable) {
            AppLog.add("RETE UPSTREAM $reason: non disponibile (${t.javaClass.simpleName})")
        }
    }

    private fun describeUpstream(caps: NetworkCapabilities?): String {
        if (caps == null) return "NESSUNA/TRANSIZIONE"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "rete mobile"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) parts += "Ethernet"
        if (parts.isEmpty()) parts += "altro"
        return parts.joinToString("+")
    }

    private fun startMdnsFallback(reason: String?) {
        if (runSelection != RunSelection.START) return
        reason?.let { AppLog.add("QR PROFILE fallback mDNS: $it") }
        AppLog.add("Cerco _EasyConn._tcp sulla rete moto…")
        discovery.stop()
        discovery.start()
        setHeaderStatus("Ricerca", activeBikeLabel(), C_AMBER)
        setState("Ricerca moto…", "Attendo EasyConn sulla rete locale", C_AMBER, "LAN")
        scheduleQrTransportFallback()
    }

    private fun scheduleQrTransportFallback() {
        if (qrTransportFallbackAttempted) return
        val profile = BikeProfileStore.load(this) ?: return
        if (!profile.hasWifiIdentity() && profile.topology?.contains("P2P", true) != true) return
        val token = sessionGeneration
        mainHandler.postDelayed({
            if (token != sessionGeneration || runSelection != RunSelection.START) return@postDelayed
            if (lastResolved != null || qrTransportFallbackAttempted) return@postDelayed
            tryQrKnownEndpointFallback(profile, token)
        }, QR_MDNS_FALLBACK_MS)
    }

    private fun tryQrKnownEndpointFallback(profile: BikeProfile, token: Long) {
        if (qrTransportFallbackAttempted || token != sessionGeneration || runSelection != RunSelection.START) return
        val candidates = bikeNetworkConnector.candidateGatewayHosts(profile)
            .filterNot { profile.host != null && profile.port != null && it == profile.host }
            .distinct()
        if (candidates.isEmpty()) return
        qrTransportFallbackAttempted = true
        discovery.stop()
        setHeaderStatus("Ricerca", profile.displayName, C_AMBER)
        setState("Probe EasyConn…", "Verifico il gateway della rete moto", C_AMBER, "QR")
        AppLog.add("QR TRANSPORT: mDNS silenzioso; probe gateway EasyConn :${BikeNetworkConnector.DEFAULT_EASYCONN_INIT_PORT}")
        io.execute {
            for (host in candidates) {
                if (token != sessionGeneration || runSelection != RunSelection.START) return@execute
                val resolved = try {
                    EasyConnDiscovery.ResolvedEasyConn(
                        name = profile.serviceName ?: profile.displayName,
                        host = InetAddress.getByName(host),
                        port = BikeNetworkConnector.DEFAULT_EASYCONN_INIT_PORT,
                        attributes = mapOf("source" to "qr_gateway_probe")
                    )
                } catch (_: Throwable) {
                    continue
                }
                if (runInit(resolved, token, failureIsTerminal = false)) {
                    lastResolved = resolved
                    AppLog.add("QR TRANSPORT: gateway EasyConn compatibile trovato")
                    return@execute
                }
            }
            runOnUiThread {
                if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                AppLog.add("QR TRANSPORT: nessun gateway :10930 compatibile; torno a mDNS")
                discovery.stop()
                discovery.start()
                setHeaderStatus("Ricerca", activeBikeLabel(), C_AMBER)
                setState("Ricerca moto…", "EasyConn resta in ascolto sulla rete locale", C_AMBER, "LAN")
            }
        }
    }

    private fun activeBikeLabel(): String =
        BikeProfileStore.load(this)?.displayName?.take(30) ?: "VOGE-TROFEO-500"

    private fun refreshFavoriteApps() {
        if (!::dashboard.isInitialized) return
        val pm = packageManager
        val visuals = FavoriteAppsStore.load(this).map { entry ->
            val component = entry.component()
            val icon: Drawable? = try {
                component?.let { pm.getActivityIcon(it) }
            } catch (_: Throwable) {
                null
            }
            TrofeoDashboardView.FavoriteVisual(entry.label, icon)
        }
        dashboard.updateFavoriteApps(visuals)
    }

    private fun startFavoriteApp(index: Int) {
        val favorites = FavoriteAppsStore.load(this)
        val entry = favorites.getOrNull(index) ?: return
        val component = entry.component()
        if (component == null) {
            FavoriteAppsStore.remove(this, index)
            refreshFavoriteApps()
            return
        }

        // If mirroring already exists, this is just a launcher shortcut and must not
        // disturb the active EasyConn/MediaProjection session.
        if (runSelection == RunSelection.START && !startInProgress) {
            launchFavoriteComponent(component, entry.label)
            return
        }

        AppLog.add("APP PREFERITA: richiesta avvio '${entry.label}' con mirror")
        startOneTouch(entry.componentName)
    }

    private fun showFavoriteAppsMenu() {
        val favorites = FavoriteAppsStore.load(this)

        // First use remains one tap: + APP opens the custom grid immediately.
        if (favorites.isEmpty()) {
            pendingFavoriteReplaceIndex = 0
            launchFavoriteAppPicker()
            return
        }

        FavoriteAppsManageDialog.showManage(
            activity = this,
            favorites = favorites,
            onAdd = {
                addFavoriteFromGrid()
            },
            onReplace = { chooseFavoriteSlotForReplacement(favorites) },
            onRemove = { chooseFavoriteToRemove(favorites) }
        )
    }

    /** Direct controls used by the code-native 2x2 favorites grid. */
    private fun addFavoriteFromGrid() {
        val favorites = FavoriteAppsStore.load(this)
        if (favorites.size >= FavoriteAppsStore.MAX_FAVORITES) {
            NeonDialogs.showInfo(
                activity = this,
                title = "App preferite",
                message = "Puoi scegliere fino a ${FavoriteAppsStore.MAX_FAVORITES} app preferite. Rimuovine o sostituiscine una per continuare."
            )
            return
        }
        pendingFavoriteReplaceIndex = favorites.size
        launchFavoriteAppPicker()
    }

    private fun replaceFavoriteAt(index: Int) {
        val favorites = FavoriteAppsStore.load(this)
        if (index !in favorites.indices) return
        pendingFavoriteReplaceIndex = index
        launchFavoriteAppPicker()
    }

    private fun removeFavoriteAt(index: Int) {
        val favorites = FavoriteAppsStore.load(this)
        val label = favorites.getOrNull(index)?.label ?: return
        FavoriteAppsStore.remove(this, index)
        refreshFavoriteApps()
        AppLog.add("APP PREFERITA: rimossa '${label}' dalla posizione ${index + 1}")
    }

    private fun chooseFavoriteSlotForReplacement(favorites: List<FavoriteAppEntry>) {
        FavoriteAppsManageDialog.showFavoriteChoice(
            activity = this,
            title = "SOSTITUISCI PREFERITA",
            subtitle = "Scegli quale app vuoi cambiare",
            favorites = favorites,
            destructive = false
        ) { index ->
            replaceFavoriteAt(index)
        }
    }

    private fun chooseFavoriteToRemove(favorites: List<FavoriteAppEntry>) {
        FavoriteAppsManageDialog.showFavoriteChoice(
            activity = this,
            title = "RIMUOVI PREFERITA",
            subtitle = "Scegli quale app vuoi rimuovere",
            favorites = favorites,
            destructive = true
        ) { index ->
            removeFavoriteAt(index)
        }
    }

    private fun launchFavoriteAppPicker() {
        FavoriteAppPickerDialog.show(this) { component, label ->
            saveFavoriteCandidate(component, label)
        }
    }

    private fun saveFavoriteCandidate(component: ComponentName, label: String) {
        val saved = FavoriteAppsStore.put(
            this,
            pendingFavoriteReplaceIndex,
            FavoriteAppEntry(component.flattenToString(), label)
        )
        if (!saved) {
            AppLog.add("APP PREFERITA: duplicato rifiutato '${label}'")
            NeonDialogs.showInfo(
                activity = this,
                title = "App già presente",
                message = "${label} è già tra le tue app preferite. Scegli un'app diversa."
            )
            refreshFavoriteApps()
            return
        }
        AppLog.add(
            "APP PREFERITA: salvata '${label}' in posizione ${pendingFavoriteReplaceIndex + 1}"
        )
        refreshFavoriteApps()
    }

    private fun launchPendingFavoriteApp() {
        val flattened = pendingFavoriteLaunchComponent ?: return
        pendingFavoriteLaunchComponent = null
        val component = ComponentName.unflattenFromString(flattened) ?: return
        val label = FavoriteAppsStore.load(this)
            .firstOrNull { it.componentName == flattened }
            ?.label ?: "App"
        mainHandler.postDelayed({ launchFavoriteComponent(component, label) }, 220L)
    }

    private fun launchFavoriteComponent(component: ComponentName, label: String) {
        try {
            val launch = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(launch)
            AppLog.add("APP PREFERITA: aperta '${label}'")
        } catch (t: Throwable) {
            try {
                val fallback = packageManager.getLaunchIntentForPackage(component.packageName)
                    ?: throw t
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(fallback)
                AppLog.add("APP PREFERITA: aperta '${label}' tramite launcher fallback")
            } catch (_: Throwable) {
                AppLog.add("APP PREFERITA: apertura '${label}' fallita: ${t.javaClass.simpleName}")
                runOnUiThread {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "App non disponibile",
                        message = "Non riesco ad aprire ${label}. Puoi sostituirla dal pulsante +."
                    )
                }
            }
        }
    }

    private fun showQrPairingMenu() {
        if (runSelection == RunSelection.START || startInProgress) {
            NeonDialogs.showInfo(
                activity = this,
                title = "Pairing QR",
                message = "Ferma prima il mirroring con STOP, poi configura una nuova moto."
            )
            return
        }
        val profile = BikeProfileStore.load(this)
        QrPairingMenuDialog.show(
            activity = this,
            hasProfile = profile != null,
            onScan = { startQrCameraScan() },
            onImport = { startQrImagePicker() },
            onManual = { showManualWifiProfileDialog() },
            onLocalProfile = { showLocalBikeProfileDialog() },
            onOpenWifi = { openWifiSettings() },
            onShowProfile = profile?.let { saved -> ({ showActiveBikeProfile(saved) }) },
            onRemoveProfile = profile?.let { ({ confirmRemoveBikeProfile() }) }
        )
    }

    private fun startQrCameraScan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        AppLog.add("QR PAIRING: avvio scanner Google Code Scanner")
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) {
                    showQrErrorForCurrentFlow("Il QR non contiene dati leggibili.")
                } else {
                    handleQrPayload(raw)
                }
            }
            .addOnCanceledListener {
                AppLog.add("QR PAIRING: scansione annullata dall'utente")
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                }
            }
            .addOnFailureListener { e ->
                AppLog.add("QR PAIRING scanner Google fallito: ${e.javaClass.simpleName}")
                startInternalQrFallback(e.javaClass.simpleName)
            }
    }

    private fun startInternalQrFallback(reason: String) {
        AppLog.add("QR PAIRING V1.2: Google Code Scanner non disponibile ($reason); preparo fallback fotocamera interna")
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchInternalQrScanner()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val alreadyRequested = prefs.getBoolean(PREF_QR_CAMERA_PERMISSION_REQUESTED, false)
        if (!alreadyRequested) {
            // Persist before calling Android: even a denial/cancel must not cause a permission loop.
            prefs.edit().putBoolean(PREF_QR_CAMERA_PERMISSION_REQUESTED, true).apply()
            AppLog.add("QR PAIRING V1.2: richiesta permesso CAMERA fallback una tantum")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_QR_CAMERA_PERMISSION)
            return
        }

        AppLog.add("QR PAIRING V1.2: CAMERA già richiesta in precedenza; nessuna nuova richiesta automatica")
        showQrErrorForCurrentFlow(
            "Google Scanner non è disponibile su questo telefono e l'accesso alla fotocamera di MotoLink non è autorizzato. Puoi usare QR da immagine oppure abilitare Fotocamera dalle impostazioni Android."
        )
    }

    private fun launchInternalQrScanner() {
        AppLog.add("QR PAIRING V1.2: apro scanner interno CameraX + ML Kit")
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, InternalQrScannerActivity::class.java), REQ_QR_INTERNAL)
    }

    private fun showQrErrorForCurrentFlow(message: String) {
        if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
            NeonDialogs.showInfo(
                activity = this,
                title = "Pairing QR",
                message = message,
                onPositive = { showFirstStartConnectionChoice() }
            )
        } else {
            showQrError(message)
        }
    }

    private fun startQrImagePicker() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_QR_IMAGE)
    }

    private fun decodeQrFromImage(uri: Uri) {
        AppLog.add("QR PAIRING: analisi immagine locale selezionata")
        try {
            val image = InputImage.fromFilePath(this, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    val raw = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (raw.isNullOrBlank()) showQrError("Nell'immagine non è stato trovato un QR code leggibile.")
                    else handleQrPayload(raw)
                }
                .addOnFailureListener { e ->
                    AppLog.add("QR PAIRING immagine fallita: ${e.javaClass.simpleName}")
                    showQrError("Non riesco a leggere il QR dall'immagine selezionata.")
                }
                .addOnCompleteListener { scanner.close() }
        } catch (t: Throwable) {
            AppLog.add("QR PAIRING apertura immagine fallita: ${t.javaClass.simpleName}")
            showQrError("Immagine non accessibile.")
        }
    }

    private fun handleQrPayload(raw: String) {
        val parsed = try {
            QrPairing.parse(raw)
        } catch (_: Throwable) {
            showQrErrorForCurrentFlow("Il QR è vuoto o non valido.")
            return
        }
        // Never log the payload, SSID password or proprietary token.
        AppLog.add("QR PAIRING: formato=${parsed.format}; brand=${parsed.brand ?: "-"}; endpoint=${parsed.endpointLabel() != null}; ssid=${parsed.ssid != null}; topology=${parsed.topology ?: "-"}")

        val details = buildString {
            append("QR riconosciuto: ${parsed.format}.\n")
            parsed.brand?.let { append("Marca/ecosistema: $it\n") }
            parsed.ssid?.let { append("Rete Wi-Fi rilevata.\n") }
            parsed.topology?.let { append("Topologia: $it\n") }
            parsed.endpointLabel()?.let { append("Endpoint EasyConn rilevato.\n") }
            append("\nCompleta ora il normale profilo del Garage. Password, token e payload QR non vengono scritti nei Log.")
        }

        showBikeProfileCreationDialog(
            baseProfile = parsed.copy(displayName = ""),
            title = "Nuovo profilo moto",
            message = details,
            onSaved = { profile ->
                lastResolved = null
                refreshBikeProfiles()
                AppLog.add("QR PAIRING V1.1: profilo completo salvato localmente; payload non scritto nel Log")
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    firstStartProfileSetupPending = false
                    setHeaderStatus("Avvio", profile.displayName, C_AMBER)
                    setState("Profilo QR salvato", "Continuo automaticamente con la connessione", C_AMBER, "QR")
                    AppLog.add("PRIMO START V1.1: profilo QR completo salvato; continuo automaticamente")
                    continueStartAfterProfileReady()
                } else {
                    setHeaderStatus("Pronto", profile.displayName, C_GREEN)
                    setState("Moto configurata", "Da ora basta premere START", C_GREEN, "QR")
                }
            },
            onCancel = {
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                }
            }
        )
    }

    private fun bikeCatalogOptions(): List<String> = listOf(
        "Voge Trofeo 500",
        "Voge Valico 525DSX",
        "Voge Valico 625DSX",
        "Voge Valico 900DSX",
        "CFMoto 450MT",
        "CFMoto 700MT",
        "CFMoto 800MT",
        "CFMoto 800MT Explore",
        "CFMoto 800MT-X",
        "Altro modello"
    )

    private fun showBikeProfileCreationDialog(
        baseProfile: BikeProfile,
        title: String,
        message: String,
        onSaved: (BikeProfile) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        if (BikeProfileStore.loadAll(this).size >= BikeProfileStore.MAX_PROFILES) {
            NeonDialogs.showInfo(
                this,
                "Garage pieno",
                "Puoi salvare al massimo 3 profili moto. Elimina o modifica un profilo esistente.",
                onPositive = { onCancel?.invoke() }
            )
            return
        }

        val name = EditText(this).apply {
            hint = "Nome moto (es. La mia Trofeo)"
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            isSingleLine = true
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        }
        val description = EditText(this).apply {
            hint = "Descrizione (facoltativa)"
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            isSingleLine = true
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        }
        val catalogOptions = bikeCatalogOptions()
        val catalog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalogOptions)
            val currentLabel = baseProfile.catalogLabel?.trim()
            if (!currentLabel.isNullOrEmpty()) {
                val idx = catalogOptions.indexOfFirst { it.equals(currentLabel, true) }
                if (idx >= 0) setSelection(idx)
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
            addView(description, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
            addView(catalog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()))
        }

        var handled = false
        val dialog = NeonDialogs.showCustom(
            activity = this,
            title = title,
            message = message,
            contentView = box,
            positiveText = "SALVA",
            negativeText = "ANNULLA",
            onPositive = {
                handled = true
                val display = name.text.toString().trim()
                if (display.isEmpty()) {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Nome moto richiesto",
                        message = "Inserisci un nome per la moto prima di salvare.",
                        onPositive = {
                            showBikeProfileCreationDialog(baseProfile, title, message, onSaved, onCancel)
                        }
                    )
                    return@showCustom
                }
                val chosen = catalogOptions[catalog.selectedItemPosition]
                val completed = baseProfile.copy(
                    displayName = display,
                    description = description.text.toString().trim().takeIf { it.isNotBlank() },
                    catalogLabel = chosen
                )
                if (BikeProfileStore.save(this, completed)) {
                    handled = true
                    onSaved(completed)
                } else {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Profilo non salvato",
                        message = "MotoLink non è riuscita a salvare il profilo sul dispositivo.",
                        onPositive = { onCancel?.invoke() }
                    )
                }
            },
            onNegative = {
                handled = true
                onCancel?.invoke()
            }
        )
        dialog.setOnDismissListener {
            mainHandler.post {
                if (!handled) onCancel?.invoke()
            }
        }
    }

    private fun showLocalBikeProfileDialog() {
        val base = BikeProfile(
            displayName = "",
            format = "LOCAL",
            rawPayload = "LOCAL:${System.currentTimeMillis()}"
        )
        showBikeProfileCreationDialog(
            baseProfile = base,
            title = "Nuovo profilo moto",
            message = "Crea un profilo locale per la moto. Il nome è obbligatorio; descrizione e modello servono a riconoscerla nel Garage. START continuerà a usare la discovery EasyConn standard.",
            onSaved = { profile ->
                lastResolved = null
                refreshBikeProfiles()
                setHeaderStatus("Pronto", profile.displayName, C_GREEN)
                setState("Profilo selezionato", "Premi START per connettere", C_GREEN, "GARAGE")
                AppLog.add("GARAGE V1.1: profilo locale completo creato; nessun dato personale inserito nel Log")
            }
        )
    }

    private fun showActiveBikeProfile(profile: BikeProfile) {
        val message = buildString {
            append("Nome: ${profile.displayName}\n")
            append("Formato QR: ${profile.format}\n")
            profile.brand?.let { append("Marca/ecosistema: $it\n") }
            profile.model?.let { append("Modello: $it\n") }
            profile.ssid?.let { append("Rete Wi-Fi: $it\n") }
            profile.topology?.let { append("Topologia: $it\n") }
            profile.endpointLabel()?.let { append("EasyConn: $it\n") }
            profile.serviceName?.let { append("Servizio: $it\n") }
            append("\nPassword/token e payload QR sono conservati nell'area privata dell'app e cifrati con Android Keystore quando disponibile. Non vengono inclusi nei Log.")
        }
        NeonDialogs.showInfo(
            activity = this,
            title = "Profilo moto",
            message = message
        )
    }

    private fun showManualWifiProfileDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fieldLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
            bottomMargin = (10 * resources.displayMetrics.density).toInt()
        }
        val name = EditText(this).apply {
            hint = "Nome moto obbligatorio"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
        }
        val ssid = EditText(this).apply {
            hint = "Nome rete / SSID (es. CFMOTO-XXXXXX)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
        }
        val password = EditText(this).apply {
            hint = "Password Wi‑Fi (se presente)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
        }
        box.addView(name, fieldLp)
        box.addView(ssid, fieldLp)
        box.addView(password, fieldLp)
        NeonDialogs.showCustom(
            activity = this,
            title = "Wi‑Fi moto manuale",
            message = "Usa questa opzione quando il TFT mostra SSID/password ma il QR non li contiene.",
            contentView = box,
            positiveText = "SALVA",
            negativeText = "ANNULLA",
            onPositive = {
                val displayName = name.text.toString().trim()
                if (displayName.isEmpty()) {
                    showQrError("Inserisci un nome per la moto. Il nome è obbligatorio per salvare il profilo.")
                    return@showCustom
                }
                val profile = runCatching { QrPairing.manualWifiProfile(ssid.text.toString(), password.text.toString(), displayName) }.getOrNull()
                if (profile == null) {
                    showQrError("Inserisci almeno il nome della rete Wi‑Fi della moto.")
                } else {
                    if (BikeProfileStore.save(this, profile)) {
                        lastResolved = null
                        setHeaderStatus("Pronto", profile.displayName, C_GREEN)
                        setState("Moto configurata", "Da ora basta premere START", C_GREEN, "Wi‑Fi")
                        AppLog.add("QR PAIRING: profilo Wi‑Fi moto inserito manualmente; password non loggata")
                        refreshBikeProfiles()
                    } else {
                        showQrError("Impossibile salvare la rete in modo cifrato su questo dispositivo.")
                    }
                }
            }
        )
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun confirmRemoveBikeProfile() {
        NeonDialogs.showConfirm(
            activity = this,
            title = "Rimuovere la moto?",
            message = "Elimina il profilo QR salvato. Il mirroring EasyConn automatico continuerà a funzionare come nelle versioni precedenti.",
            positiveText = "RIMUOVI",
            negativeText = "ANNULLA",
            danger = true,
            onPositive = {
                BikeProfileStore.clear(this)
                refreshBikeProfiles()
                lastResolved = null
                setHeaderStatus("Pronto", "", C_GREEN)
                setState("Sistema pronto", "La prossimità è sempre attiva", C_GREEN, "LAN")
                AppLog.add("QR PAIRING: profilo moto rimosso")
            }
        )
    }

    private fun showQrError(message: String) {
        NeonDialogs.showInfo(
            activity = this,
            title = "Pairing QR",
            message = message
        )
    }

    private fun refreshBikeProfiles() {
        if (!::dashboard.isInitialized) return
        val profiles = BikeProfileStore.loadAll(this)
        val active = BikeProfileStore.activeIndex(this)
        val visuals = profiles.mapIndexed { index, profile ->
            val personalDrawable: Drawable? = profile.photoUri?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching {
                    val uri = Uri.parse(raw)
                    when (uri.scheme) {
                        "file" -> uri.path?.let { Drawable.createFromPath(it) }
                        else -> contentResolver.openInputStream(uri)?.use { Drawable.createFromStream(it, "bike_profile") }
                    }
                }.getOrNull()
            }
            // Artwork selection must depend on the selected catalog/model identity only.
            // The user-facing displayName is intentionally excluded: renaming a profile
            // (for example to "Trofeo 500") must never change the motorcycle artwork.
            val modelArtworkKey = profile.catalogLabel?.trim()?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(profile.brand, profile.model).joinToString(" ")

            val normalizedArtworkKey = when {
                modelArtworkKey.equals("Voge Trofeo 525DSX", true) -> "Voge Valico 525DSX"
                modelArtworkKey.equals("Immagine MotoLink generica", true) -> "Altro modello"
                else -> modelArtworkKey
            }

            val catalogDrawable: Drawable? = when {
                normalizedArtworkKey.contains("Trofeo 500", true) ||
                    normalizedArtworkKey.contains("500 AC", true) ||
                    normalizedArtworkKey.contains("500AC", true) -> getDrawable(R.drawable.catalog_voge_trofeo_500)

                normalizedArtworkKey.contains("Valico 525", true) ||
                    normalizedArtworkKey.contains("525 DSX", true) ||
                    normalizedArtworkKey.contains("525DSX", true) -> getDrawable(R.drawable.catalog_voge_valico_525_dsx)

                normalizedArtworkKey.contains("Valico 625", true) ||
                    normalizedArtworkKey.contains("625 DSX", true) ||
                    normalizedArtworkKey.contains("625DSX", true) -> getDrawable(R.drawable.catalog_voge_valico_625_dsx)

                normalizedArtworkKey.contains("Valico 900", true) ||
                    normalizedArtworkKey.contains("900 DSX", true) ||
                    normalizedArtworkKey.contains("900DSX", true) -> getDrawable(R.drawable.catalog_voge_valico_900_dsx)

                normalizedArtworkKey.contains("CFMoto 450MT", true) ||
                    normalizedArtworkKey.contains("450MT", true) -> getDrawable(R.drawable.catalog_cfmoto_450mt)

                normalizedArtworkKey.contains("800MT Explore", true) ||
                    normalizedArtworkKey.contains("800MT EXPLORE", true) -> getDrawable(R.drawable.catalog_cfmoto_800mt_explore)

                normalizedArtworkKey.contains("800MT-X", true) ||
                    normalizedArtworkKey.contains("800MT X", true) -> getDrawable(R.drawable.catalog_cfmoto_800mt_x)

                normalizedArtworkKey.contains("CFMoto 800MT", true) ||
                    normalizedArtworkKey.contains("800MT", true) -> getDrawable(R.drawable.catalog_cfmoto_800mt)

                normalizedArtworkKey.contains("CFMoto 700MT", true) ||
                    normalizedArtworkKey.contains("700MT", true) -> getDrawable(R.drawable.catalog_cfmoto_700mt)

                normalizedArtworkKey.contains("Altro modello", true) ||
                    normalizedArtworkKey.contains("altro modello", true) -> getDrawable(R.drawable.catalog_bike_adventure)

                !profile.catalogLabel.isNullOrBlank() -> getDrawable(R.drawable.catalog_bike_adventure)
                else -> null
            }
            val drawable = personalDrawable ?: catalogDrawable
            val descriptionLine = profile.description?.trim()?.takeIf { it.isNotBlank() }
            val modelLine = profile.catalogLabel?.trim()?.takeIf { it.isNotBlank() }?.let { label ->
                when (label) {
                    "Voge Trofeo 525DSX" -> "Voge Valico 525DSX"
                    "Immagine MotoLink generica" -> "Altro modello"
                    else -> label
                }
            } ?: listOfNotNull(profile.brand, profile.model)
                .joinToString(" ")
                .ifBlank { profile.format }
            TrofeoDashboardView.BikeVisual(profile.displayName, descriptionLine, modelLine, index == active, drawable)
        }
        dashboard.updateBikeProfiles(visuals)
    }

    private fun selectBikeProfile(index: Int) {
        val profiles = BikeProfileStore.loadAll(this)
        val profile = profiles.getOrNull(index) ?: return
        if (BikeProfileStore.setActive(this, index)) {
            lastResolved = null
            refreshBikeProfiles()
            setHeaderStatus("Pronto", profile.displayName, C_GREEN)
            setState("Profilo selezionato", "Premi START per connettere", C_GREEN, "GARAGE")
            val adaptation = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(adaptation.enabled, MirrorAdaptationConfig.dashboardLabel(this))
            if (runSelection == RunSelection.START) {
                startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
            }
            AppLog.add("GARAGE: profilo attivo selezionato index=$index; Adattamento ricaricato per il profilo selezionato")
        }
    }

    private fun showBikeProfileMenu(index: Int) {
        val profile = BikeProfileStore.loadAll(this).getOrNull(index) ?: return
        val name = EditText(this).apply {
            setText(profile.displayName)
            hint = "Nome moto"
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
            isSingleLine = true
        }
        val description = EditText(this).apply {
            setText(profile.description.orEmpty())
            hint = "Descrizione (facoltativa)"
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
            isSingleLine = true
        }
        val catalog = Spinner(this)
        val catalogOptions = listOf(
            "Voge Trofeo 500",
            "Voge Valico 525DSX",
            "Voge Valico 625DSX",
            "Voge Valico 900DSX",
            "CFMoto 450MT",
            "CFMoto 700MT",
            "CFMoto 800MT",
            "CFMoto 800MT Explore",
            "CFMoto 800MT-X",
            "Altro modello"
        )
        catalog.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, catalogOptions)
        val normalizedCatalogSelection = when (profile.catalogLabel) {
            "Voge Trofeo 525DSX" -> "Voge Valico 525DSX"
            "Immagine MotoLink generica" -> "Altro modello"
            else -> profile.catalogLabel
        }
        val selected = catalogOptions.indexOf(normalizedCatalogSelection).takeIf { it >= 0 } ?: 0
        catalog.setSelection(selected)
        var editProfileDialog: android.app.Dialog? = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            addView(name, lp)
            addView(description, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() })
            addView(catalog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (14 * resources.displayMetrics.density).toInt() })
            addView(TextView(this@MainActivity).apply {
                text = "ELIMINA PROFILO"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF6A6A"))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                background = NeonDialogs.rounded("#07120B", "#7A2A2A", 1, 16, this@MainActivity)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    NeonDialogs.showConfirm(
                        activity = this@MainActivity,
                        title = "Eliminare profilo?",
                        message = "Il profilo moto verrà rimosso dal Garage.",
                        positiveText = "ELIMINA",
                        negativeText = "ANNULLA",
                        danger = true,
                        onPositive = {
                            BikeProfileStore.delete(this@MainActivity, index)
                            editProfileDialog?.dismiss()
                            refreshBikeProfiles()
                            val active = BikeProfileStore.load(this@MainActivity)
                            setHeaderStatus("Pronto", active?.displayName ?: "", C_GREEN)
                            setState("Profilo eliminato", "Garage aggiornato", C_GREEN, "GARAGE")
                            AppLog.add("GARAGE: profilo eliminato da Modifica profilo index=$index")
                        }
                    )
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (50 * resources.displayMetrics.density).toInt()))
        }
        editProfileDialog = NeonDialogs.showCustom(
            activity = this,
            title = "Modifica profilo",
            message = "Il nome della moto è obbligatorio. Descrizione e modello sono modificabili; per usare la foto della tua moto scegli FOTO / ALTRO. Le regolazioni di Adattamento restano associate a questo profilo anche se lo rinomini.",
            contentView = box,
            positiveText = "SALVA",
            negativeText = "FOTO / ALTRO",
            onPositive = {
                val editedName = name.text.toString().trim()
                if (editedName.isEmpty()) {
                    showQrError("Il nome della moto è obbligatorio. Inserisci un nome prima di salvare il profilo.")
                    return@showCustom
                }
                if (!BikeProfileStore.updateMetadata(
                        this, index,
                        displayName = editedName,
                        description = description.text.toString(),
                        catalogLabel = catalogOptions[catalog.selectedItemPosition]
                    )) {
                    showQrError("Impossibile salvare il profilo.")
                    return@showCustom
                }
                refreshBikeProfiles()
                dashboard.updateAdaptation(MirrorAdaptationConfig.load(this).enabled, MirrorAdaptationConfig.dashboardLabel(this))
            },
            onNegative = {
                val editedName = name.text.toString().trim()
                if (editedName.isEmpty()) {
                    showQrError("Il nome della moto è obbligatorio. Inserisci un nome prima di continuare.")
                    return@showCustom
                }
                if (!BikeProfileStore.updateMetadata(
                        this, index,
                        displayName = editedName,
                        description = description.text.toString(),
                        catalogLabel = catalogOptions[catalog.selectedItemPosition]
                    )) {
                    showQrError("Impossibile salvare il profilo.")
                    return@showCustom
                }
                refreshBikeProfiles()
                dashboard.updateAdaptation(MirrorAdaptationConfig.load(this).enabled, MirrorAdaptationConfig.dashboardLabel(this))
                showBikeProfileExtraMenu(index)
            }
        )
    }

    private fun showBikeProfileExtraMenu(index: Int) {
        val profile = BikeProfileStore.loadAll(this).getOrNull(index) ?: return
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null

        fun choice(label: String, danger: Boolean = false, onClick: () -> Unit) {
            choices.addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(if (danger) Color.parseColor(C_DANGER) else Color.WHITE)
                textSize = 17f
                setPadding((14 * resources.displayMetrics.density).toInt(), (15 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt(), (15 * resources.displayMetrics.density).toInt())
                background = NeonDialogs.rounded(
                    "#07120B",
                    if (danger) "#7A2A2A" else "#2A7A28",
                    1,
                    16,
                    this@MainActivity
                )
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            })
        }

        choice("Scegli foto dalla galleria") { startProfilePhotoPicker(index) }
        choice("Scatta una foto") { startProfileCamera(index) }
        if (!profile.photoUri.isNullOrBlank()) {
            choice("Rimuovi foto personale", danger = true) {
                if (clearProfilePersonalPhoto(index)) {
                    dialog?.dismiss()
                }
            }
        }

        dialog = NeonDialogs.showCustom(
            this,
            "Profilo moto",
            "Personalizzazione locale del Garage.",
            choices,
            "CHIUDI",
            "",
            onPositive = {}
        )
    }

    private fun clearProfilePersonalPhoto(index: Int): Boolean {
        val profile = BikeProfileStore.loadAll(this).getOrNull(index) ?: return false
        val rawPhotoUri = profile.photoUri?.takeIf { it.isNotBlank() } ?: return false
        val uri = runCatching { Uri.parse(rawPhotoUri) }.getOrNull()

        // Delete only MotoLink-owned camera files. Gallery photos are never modified.
        if (uri?.scheme.equals("file", ignoreCase = true)) {
            val path = uri?.path
            if (!path.isNullOrBlank()) {
                runCatching {
                    val file = File(path)
                    val photoDir = File(filesDir, "profile_photos").canonicalFile
                    val candidate = file.canonicalFile
                    if (candidate.parentFile == photoDir) candidate.delete()
                }
            }
        } else if (uri?.scheme.equals("content", ignoreCase = true)) {
            // Release the persisted read grant when possible; the source image itself remains untouched.
            runCatching {
                contentResolver.releasePersistableUriPermission(uri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        if (!BikeProfileStore.clearPhoto(this, index)) return false
        refreshBikeProfiles()
        AppLog.add("GARAGE: foto personale rimossa; ripristinata immagine modello")
        return true
    }

    private fun startProfilePhotoPicker(index: Int) {
        pendingProfileEditIndex = index
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, REQ_PROFILE_PHOTO)
    }

    private fun startProfileCamera(index: Int) {
        pendingProfileEditIndex = index
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PROFILE_CAMERA)
    }

    private fun clearLocalLog() {
        AppLog.clearLogs()
        dashboard.replaceSupportLogs(AppLog.recentLines(AppLog.UI_VISIBLE_LINE_LIMIT))
    }

    private fun toggleIntroSetting() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val next = !prefs.getBoolean(PREF_INTRO_ENABLED, true)
        prefs.edit().putBoolean(PREF_INTRO_ENABLED, next).apply()
        dashboard.updateIntroEnabled(next)
        AppLog.add("GUI V1: animazione iniziale ${if (next) "ON" else "OFF"}")
    }

    private fun toggleGuideSetting() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val next = !prefs.getBoolean(PREF_GUIDE_NEXT_LAUNCH, false)
        prefs.edit().putBoolean(PREF_GUIDE_NEXT_LAUNCH, next).apply()
        dashboard.updateGuideEnabled(next)
        AppLog.add("GUI V1: guida iniziale alla prossima apertura ${if (next) "ON" else "OFF"}")
    }

    private fun toggleDynamicBackgroundSetting() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val next = !prefs.getBoolean(PREF_DYNAMIC_BACKGROUND_ENABLED, true)
        prefs.edit().putBoolean(PREF_DYNAMIC_BACKGROUND_ENABLED, next).apply()
        dashboard.updateDynamicBackgroundEnabled(next)
        AppLog.add("GUI V1: sfondo dinamico moto collegata ${if (next) "ON" else "OFF"}")
    }


    private fun toggleAdaptationSetting() {
        val current = MirrorAdaptationConfig.load(this)
        if (current.enabled) {
            MirrorAdaptationConfig.setEnabled(this, false)
            val cfg = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(false, MirrorAdaptationConfig.dashboardLabel(this))
            val port = MirrorAdaptationConfig.load(this, MirrorAdaptationConfig.Profile.PORTRAIT)
            AppLog.add(
                "ADATTAMENTO V15: OFF; profili conservati LAND=L${cfg.leftPx}/T${cfg.topPx}/R${cfg.rightPx}/B${cfg.bottomPx}; " +
                    "PORT=L${port.leftPx}/T${port.topPx}/R${port.rightPx}/B${port.bottomPx}"
            )
            if (runSelection == RunSelection.START) {
                startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            waitingForAdaptationOverlayPermission = true
            AppLog.add("ADATTAMENTO V15: richiedo permesso 'Mostra sopra altre app' per il pannello flottante")
            try {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        data = Uri.parse("package:$packageName")
                    }
                }
                startActivity(settingsIntent)
            } catch (t: Throwable) {
                waitingForAdaptationOverlayPermission = false
                AppLog.add("ADATTAMENTO V15: apertura permesso fallita ${t.javaClass.simpleName}: ${t.message ?: "-"}")
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Adattamento",
                    message = "Abilita MotoLink in Impostazioni Android > Accesso speciale > Mostra sopra altre app."
                )
            }
            return
        }

        MirrorAdaptationConfig.setEnabled(this, true)
        val cfg = MirrorAdaptationConfig.load(this)
        dashboard.updateAdaptation(true, MirrorAdaptationConfig.dashboardLabel(this))
        val port = MirrorAdaptationConfig.load(this, MirrorAdaptationConfig.Profile.PORTRAIT)
        AppLog.add(
            "ADATTAMENTO V15: ON; profili separati per orientamento; base landscape auto-scalata dalla geometria TFT; source crop/zoom automatico OFF; step=${MirrorAdaptationConfig.STEP_PX}px; range=${MirrorAdaptationConfig.MIN_EDGE_PX}..${MirrorAdaptationConfig.MAX_EDGE_PX}px; " +
                "LAND=L${cfg.leftPx}/T${cfg.topPx}/R${cfg.rightPx}/B${cfg.bottomPx}; " +
                "PORT=L${port.leftPx}/T${port.topPx}/R${port.rightPx}/B${port.bottomPx}"
        )
        if (runSelection == RunSelection.START) {
            startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
        }
        NeonDialogs.showInfo(
            activity = this,
            title = "Adattamento attivo",
            message = MirrorAdaptationConfig.USER_HELP_TEXT
        )
    }

    private fun showAssistantInfo() {
        NeonDialogs.showInfo(
            this,
            "Assistente MotoLink",
            "Come usare la chat\n" +
                "Scrivi direttamente nel campo in basso e premi Invia. L'Assistente è dedicato al supporto MotoLink e non ha accesso al codice sorgente, alle chiavi API o ai secret dell'app.\n\n" +
                "Come allegare il Log\n" +
                "Vai in Supporto > Log > Condividi e scegli Assistente. MotoLink prepara sul telefono un estratto tecnico, rimuove identificativi e secret e lo invia solo per quella richiesta.\n\n" +
                "Privacy\n" +
                "Durante una chat normale il Log non viene letto né inviato. MotoLink non salva chat o Log nel proprio database. Le richieste dell'Assistente vengono elaborate online.\n\n" +
                "Se l'Assistente non sa rispondere\n" +
                "Non deve inventare: può proporti il gruppo ufficiale MotoLink Mirroring e mostrarti il pulsante Apri gruppo WhatsApp."
        )
    }

    private fun sendAssistantQuestion(question: String, diagnostics: String?) {
        val q = question.trim()
        if (q.isBlank()) return
        dashboard.updateAssistantConversation(q, "Sto preparando la risposta…")
        AppLog.add("ASSISTENTE IA: richiesta utente avviata; diagnostica=${if (diagnostics.isNullOrBlank()) "NO" else "OPT_IN"}")

        val client = MotoLinkAiClient(this)
        if (!client.isConfigured()) {
            dashboard.updateAssistantConversation(
                q,
                "Il server Assistente non è configurato. Le funzioni MotoLink restano operative."
            )
            AppLog.add("ASSISTENTE IA: backend non configurato")
            return
        }

        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "sconosciuta"
        }.getOrDefault("sconosciuta")

        aiIo.execute {
            val result = client.ask(q, diagnostics, version)
            runOnUiThread {
                result.onSuccess { reply ->
                    dashboard.updateAssistantConversation(q, reply.answer, reply.supportWhatsapp)
                    AppLog.add("ASSISTENTE IA: risposta ricevuta source=${reply.source}; whatsapp=${reply.supportWhatsapp}")
                }.onFailure { error ->
                    dashboard.updateAssistantConversation(
                        q,
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Assistente temporaneamente non disponibile. Riprova più tardi."
                    )
                    AppLog.add("ASSISTENTE IA: richiesta fallita ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun openWhatsAppGroup() {
        val inviteUrl = "https://chat.whatsapp.com/BNTmFxXQuOkGdYWHrX2rV0?s=cl&p=a&mlu=4"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(inviteUrl)))
            AppLog.add("CREDITI: apertura gruppo WhatsApp MotoLink Mirroring")
        } catch (t: Throwable) {
            AppLog.add("CREDITI: apertura gruppo WhatsApp fallita: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            NeonDialogs.showInfo(this, "MotoLink Mirroring", "Impossibile aprire il collegamento WhatsApp su questo dispositivo.")
        }
    }

    private fun showLogShareChoice() {
        NeonDialogs.showCustom(
            this,
            "Condividi Log",
            "Scegli dove inviare il Log. Con Assistente, MotoLink filtra localmente identificativi e secret prima dell'invio. La scelta vale come consenso per questa singola richiesta.",
            null,
            "ASSISTENTE",
            "ESTERNO",
            onPositive = { sendLogToAssistant() },
            onNegative = { shareLogExternal() }
        )
    }

    private fun sendLogToAssistant() {
        val diagnostics = AiPrivacyRedactor.buildDiagnostics(this)
        AppLog.add("ASSISTENTE IA: Log condiviso volontariamente dall'utente; estratto privacy preparato localmente")
        sendAssistantQuestion(
            "Analizza questo Log MotoLink e spiegami in modo semplice qual è il problema e cosa devo fare.",
            diagnostics
        )
    }

    private fun shareLogExternal() {
        try {
            AppLog.add("Condivisione Log richiesta dall'utente")
            val file = AppLog.createShareFile()
            if (file == null) {
                setState("Log non disponibile", "Riprova tra qualche secondo", C_DANGER, "!")
                return
            }
            val uri = Uri.Builder()
                .scheme("content")
                .authority("$packageName.logs")
                .appendPath(file.name)
                .build()

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MotoLink Log")
                clipData = ClipData.newRawUri("MotoLink Log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Invia Log"))
        } catch (t: Throwable) {
            AppLog.add("Condivisione Log fallita: ${t.message ?: t.javaClass.simpleName}")
            setState("Impossibile condividere il Log", "Riprova", C_DANGER, "!")
        }
    }

    private fun setRunSelection(selection: RunSelection) {
        runSelection = selection
        if (!::dashboard.isInitialized) return
        dashboard.setSelection(
            when (selection) {
                RunSelection.NONE -> TrofeoDashboardView.Selection.READY
                RunSelection.START -> TrofeoDashboardView.Selection.START
                RunSelection.STOP -> TrofeoDashboardView.Selection.STOP
            }
        )
    }

    private fun setHeaderStatus(title: String, subtitle: String, colorHex: String) {
        if (!::dashboard.isInitialized) return
        dashboard.updateHeader(title, subtitle, color(colorHex))
    }

    private fun setState(title: String, subtitle: String, dotColor: String, right: String) {
        if (!::dashboard.isInitialized) return
        dashboard.updateState(title, subtitle, color(dotColor), right)
    }

    private fun color(hex: String): Int = Color.parseColor(hex)


    override fun onDestroy() {
        invalidateRecovery()
        AppLog.unsubscribe(logListener)
        try {
            networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (_: Throwable) {
        }
        networkCallback = null
        if (::bikeNetworkConnector.isInitialized) bikeNetworkConnector.release()
        if (::wifiDirectBikeConnector.isInitialized) wifiDirectBikeConnector.release(removeGroup = false)
        wifiDirectLink = null
        io.shutdownNow()
        aiIo.shutdownNow()
        super.onDestroy()
    }
}
