from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, got {count}\nOLD={old[:160]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


def insert_before(path, marker, insertion):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f'{path}: marker expected once, got {count}: {marker[:120]!r}')
    p.write_text(text.replace(marker, insertion + marker, 1), encoding='utf-8')

# Version
replace_once(
    'app/build.gradle.kts',
    '        versionCode = 3\n        versionName = "1.2"',
    '        versionCode = 7\n        versionName = "1.4"'
)

# CFMOTO QR group SSID -> peer deviceName normalization for P2P matching.
replace_once(
    'app/src/main/java/it/motolink/app/WifiDirectBikeConnector.kt',
    '        targetName = profile.ssid?.trim().orEmpty()',
    '        val rawTargetName = profile.ssid?.trim().orEmpty()\n'
    '        targetName = if (profile.brand.equals("CFMOTO", ignoreCase = true)) {\n'
    '            Regex("CFMOTO-[A-Z0-9_-]+", RegexOption.IGNORE_CASE).find(rawTargetName)?.value ?: rawTargetName\n'
    '        } else {\n'
    '            rawTargetName\n'
    '        }'
)

# HOTSPOT V1.4: bind MotoLink to any active local Wi-Fi even if cellular is default.
network_path = 'app/src/main/java/it/motolink/app/BikeNetworkConnector.kt'
replace_once(network_path, 'import android.os.Build\n', 'import android.os.Build\nimport java.net.Inet4Address\n')
insert_before(
    network_path,
    '    fun candidateGatewayHosts(profile: BikeProfile): List<String> {',
    '''    /**
     * V1.4 HOTSPOT: bind MotoLink to an already-connected local Wi-Fi even when
     * Android keeps cellular as the default Internet network. Only this process is bound.
     */
    fun bindExistingWifiForHotspot(): Boolean {
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
            val hasIpv4 = lp.linkAddresses.any { link ->
                val address = link.address
                address is Inet4Address && !address.isLoopbackAddress
            }
            if (!hasIpv4) return@mapNotNull null
            val localOnlyScore = if (
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) 0 else 1
            Triple(localOnlyScore, network, lp)
        }.sortedBy { it.first }

        val chosen = candidates.firstOrNull() ?: return false
        release()
        bind(chosen.second)
        linkProperties = chosen.third
        AppLog.add("HOTSPOT V1.4: rete Wi-Fi locale rilevata; processo MotoLink associato alla rete moto")
        return true
    }

'''
)

# AppLog: inspect stale marker only once per process; expose in-process logical session state.
log_path = 'app/src/main/java/it/motolink/app/AppLog.kt'
replace_once(
    log_path,
    '''    @Synchronized
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
''',
    '''    @Synchronized
    fun install(context: Context) {
        val firstInstallInProcess = appContext == null
        if (firstInstallInProcess) appContext = context.applicationContext
        cleanupOldLogs()
        installCrashHandlerIfNeeded()

        // Only a fresh app process may interpret the marker as a previous abnormal exit.
        // MainActivity can be recreated while MirrorService/EasyConn are still legitimately
        // alive; treating that as a crash used to erase the marker and allow duplicate START.
        if (firstInstallInProcess) {
            val marker = markerFile()
            if (marker?.exists() == true) {
                add("AVVIO: la sessione precedente non risulta chiusa normalmente")
                marker.delete()
            }
        }
    }

    @Synchronized
    fun isMirrorSessionOpen(): Boolean = mirrorSessionOpen.get()
'''
)

main_path = 'app/src/main/java/it/motolink/app/MainActivity.kt'
replace_once(
    main_path,
    '        AppLog.add("MotoLink V1.2 GUI pronta; guida iniziale attiva; geometria display V15 validata invariata")',
    '        AppLog.add("MotoLink V1.4 GUI pronta; guida iniziale attiva; geometria display V15 validata invariata")'
)
replace_once(
    main_path,
    '            if (profile.format.equals("HOTSPOT", ignoreCase = true) && !isDefaultNetworkWifi()) {',
    '            if (profile.format.equals("HOTSPOT", ignoreCase = true) && !bikeNetworkConnector.bindExistingWifiForHotspot()) {'
)
replace_once(
    main_path,
    '                AppLog.add("HOTSPOT GUARD V1.2: profilo senza SSID e rete corrente non Wi-Fi; START interrotto prima di MediaProjection")',
    '                AppLog.add("HOTSPOT GUARD V1.4: nessuna rete Wi-Fi locale utilizzabile; START interrotto prima di MediaProjection")'
)

# CFMOTO primary P2P + original QR SSID direct-network fallback.
insert_before(
    main_path,
    '    private fun connectBikeWifiDirectThenProjection(profile: BikeProfile) {',
    '''    private fun isCfmotoProfile(profile: BikeProfile?): Boolean {
        if (profile == null) return false
        return profile.brand.equals("CFMOTO", ignoreCase = true) ||
            profile.format.startsWith("CFMOTO_", ignoreCase = true)
    }

    /**
     * Physical CFMOTO test proved that the QR/group SSID and P2P peer name are distinct:
     * DIRECT-go-CFMOTO-XXXX vs CFMOTO-XXXX. This fallback deliberately keeps the original
     * QR SSID; the primary CFMOTO path is WLAN Direct/P2P.
     */
    private fun connectCfmotoDirectNetworkFallback(profile: BikeProfile) {
        if (runSelection != RunSelection.START) return
        val originalSsid = profile.ssid?.trim().orEmpty()
        if (originalSsid.isBlank()) {
            startInProgress = false
            setRunSelection(RunSelection.NONE)
            setHeaderStatus("CFMOTO", profile.displayName, C_DANGER)
            setState("Moto non collegata", "SSID CFMOTO assente", C_DANGER, "!")
            AppLog.add("CFMOTO NETWORK V1.4 FALLBACK: SSID QR assente; START interrotto")
            return
        }
        val token = sessionGeneration
        wifiDirectLink = null
        setHeaderStatus("CFMOTO", profile.displayName, C_AMBER)
        setState("Connessione rete…", "Fallback rete T-Box CFMOTO", C_AMBER, "Wi-Fi")
        AppLog.add("CFMOTO NETWORK V1.4 FALLBACK: WifiNetworkSpecifier sul SSID QR originale; EasyConn/H264 invariati")
        bikeNetworkConnector.connect(
            profile = profile.copy(ssid = originalSsid),
            timeoutMs = 12_000,
            onReady = {
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    AppLog.add("CFMOTO NETWORK V1.4 FALLBACK READY: rete T-Box associata")
                    setHeaderStatus("Rete pronta", profile.displayName, C_GREEN)
                    setState("CFMOTO collegata", "Ora autorizza la condivisione dello schermo", C_GREEN, "Wi-Fi")
                    requestProjectionChoice()
                }
            },
            onUnavailable = { reason ->
                runOnUiThread {
                    if (token != sessionGeneration || runSelection != RunSelection.START) return@runOnUiThread
                    bikeNetworkConnector.release()
                    startInProgress = false
                    setRunSelection(RunSelection.NONE)
                    setHeaderStatus("CFMOTO", profile.displayName, C_DANGER)
                    setState("Moto non collegata", "P2P e rete T-Box non disponibili", C_DANGER, "!")
                    AppLog.add("CFMOTO NETWORK V1.4 FAIL: P2P e fallback rete diretta falliti ($reason)")
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "CFMOTO non collegata",
                        message = "MotoLink non è riuscita ad agganciare la CFMOTO. Lascia aperta sul TFT la schermata Connessione telefono/QR e riprova."
                    )
                }
            }
        )
    }

'''
)
replace_once(
    main_path,
    '''                    wifiDirectLink = null
                    if (!explicitP2p) {
                        AppLog.add("WLAN DIRECT AUTO-PROBE: target QR esatto non agganciato ($reason); fallback al percorso Wi-Fi/EasyConn classico")
                        wifiDirectBikeConnector.release(removeGroup = false)
                        connectBikeWifiThenProjection(profile)
                        return@runOnUiThread
                    }
''',
    '''                    wifiDirectLink = null
                    if (isCfmotoProfile(profile)) {
                        AppLog.add("CFMOTO P2P V1.4: WLAN Direct non agganciata ($reason); provo fallback SSID QR originale")
                        wifiDirectBikeConnector.release(removeGroup = false)
                        connectCfmotoDirectNetworkFallback(profile)
                        return@runOnUiThread
                    }
                    if (!explicitP2p) {
                        AppLog.add("WLAN DIRECT AUTO-PROBE: target QR esatto non agganciato ($reason); fallback al percorso Wi-Fi/EasyConn classico")
                        wifiDirectBikeConnector.release(removeGroup = false)
                        connectBikeWifiThenProjection(profile)
                        return@runOnUiThread
                    }
'''
)
replace_once(
    main_path,
    '''            AppLog.add(
                if (wifiDirectBikeConnector.shouldUse(profile))
                    "WLAN DIRECT: richiesta permesso Android per collegamento P2P alla moto"
                else
                    "QR WIFI: richiesta permesso Android per collegamento alla rete moto"
            )''',
    '''            AppLog.add(
                when {
                    isCfmotoProfile(profile) -> "CFMOTO P2P V1.4: richiesta permesso Android per WLAN Direct"
                    wifiDirectBikeConnector.shouldUse(profile) -> "WLAN DIRECT: richiesta permesso Android per collegamento P2P alla moto"
                    else -> "QR WIFI: richiesta permesso Android per collegamento alla rete moto"
                }
            )'''
)

# Recovery sees the socket-abort vocabulary observed physically on CFMOTO.
replace_once(
    main_path,
    '''    private fun isEasyConnConnectionLoss(line: String): Boolean {
        return line.contains("H264 stream chiuso dalla Voge", ignoreCase = true) ||
            (line.contains("10920 errore", ignoreCase = true) &&
                (line.contains("Broken pipe", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true))) ||
            ((line.contains("PXC#1", ignoreCase = true) || line.contains("PXC#2", ignoreCase = true)) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true))) ||
            (line.contains("10921", ignoreCase = true) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || line.contains("Connection reset", ignoreCase = true)))
    }
''',
    '''    private fun isEasyConnConnectionLoss(line: String): Boolean {
        val socketAbort = line.contains("Broken pipe", ignoreCase = true) ||
            line.contains("Connection reset", ignoreCase = true) ||
            line.contains("Software caused connection abort", ignoreCase = true) ||
            line.contains("Socket closed", ignoreCase = true)
        return line.contains("H264 stream chiuso dalla Voge", ignoreCase = true) ||
            (line.contains("10920 errore", ignoreCase = true) && socketAbort) ||
            ((line.contains("PXC#1", ignoreCase = true) || line.contains("PXC#2", ignoreCase = true)) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || socketAbort)) ||
            (line.contains("10921", ignoreCase = true) &&
                (line.contains("chiuso dalla Voge", ignoreCase = true) || socketAbort))
    }
'''
)

# Reattach MainActivity to a live same-process mirror instead of exposing duplicate START.
replace_once(
    main_path,
    '''        AppLog.subscribe(logListener)
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
''',
    '''        AppLog.subscribe(logListener)
        registerNetworkDiagnostics()

        // A lock-placeholder or a normal live MirrorService session can outlive/recreate
        // MainActivity. Reattach the controls instead of offering a duplicate START.
        lockPlaceholderActive = H264FrameBus.lockPlaceholderActive()
        val activeProfile = BikeProfileStore.load(this)
        val liveSessionReattach = AppLog.isMirrorSessionOpen() && !lockPlaceholderActive
        if (lockPlaceholderActive) {
            setRunSelection(RunSelection.NONE)
            AppLog.add("LOCK PLACEHOLDER: stato riagganciato dopo ricreazione MainActivity")
            setHeaderStatus("Bloccato", activeProfile?.displayName ?: "", C_AMBER)
            setState("Telefono bloccato", "Sblocca il telefono e premi START", C_AMBER, "LOCK")
        } else if (liveSessionReattach) {
            startInProgress = false
            mirrorConnectedOnce = true
            recoveryFailedWaitingManual = false
            setRunSelection(RunSelection.START)
            setHeaderStatus("Connesso", activeProfile?.displayName ?: "", C_GREEN)
            setState("Sessione attiva", "Mirroring già in corso", C_GREEN, "LIVE")
            AppLog.add("SESSION REATTACH V1.4: MainActivity ricreata; sessione MirrorService/EasyConn già attiva, START duplicato bloccato")
        } else {
            setRunSelection(RunSelection.NONE)
            setHeaderStatus("Pronto", activeProfile?.displayName ?: "", C_GREEN)
            setState(
                "Sistema pronto",
                activeProfile?.let { "Profilo moto salvato · START per connettere" } ?: "La prossimità è sempre attiva",
                C_GREEN,
                "LAN"
            )
        }
'''
)

# Explicit CFMOTO video compatibility flag.
replace_once(
    main_path,
    '''        val serviceIntent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            putExtra(MirrorService.EXTRA_VALICO_SOFT_H264, useValicoSoftH264)
        }''',
    '''        val serviceIntent = Intent(this, MirrorService::class.java).apply {
            action = MirrorService.ACTION_START
            putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorService.EXTRA_RESULT_DATA, data)
            putExtra(MirrorService.EXTRA_VALICO_SOFT_H264, useValicoSoftH264)
            putExtra(MirrorService.EXTRA_CFMOTO_COMPAT, isCfmotoProfile(activeProfileForVideo))
        }'''
)

# MirrorService CFMOTO-only pre-Android14 source geometry watcher.
mirror_path = 'app/src/main/java/it/motolink/app/MirrorService.kt'
replace_once(
    mirror_path,
    'import android.content.IntentFilter\n',
    'import android.content.IntentFilter\nimport android.content.res.Configuration\nimport android.util.DisplayMetrics\n'
)
replace_once(
    mirror_path,
    '        const val EXTRA_VALICO_SOFT_H264 = "valicoSoftH264"\n',
    '        const val EXTRA_VALICO_SOFT_H264 = "valicoSoftH264"\n        const val EXTRA_CFMOTO_COMPAT = "cfmotoCompat"\n'
)
replace_once(
    mirror_path,
    '    @Volatile private var valicoSoftH264 = false\n',
    '    @Volatile private var valicoSoftH264 = false\n    @Volatile private var cfmotoCompat = false\n    private var cfmotoGeometryWatchRunnable: Runnable? = null\n'
)
replace_once(
    mirror_path,
    '''        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        lockPlaceholderActive = false
        valicoSoftH264 = intent.getBooleanExtra(EXTRA_VALICO_SOFT_H264, false)
''',
    '''        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        lockPlaceholderActive = false
        valicoSoftH264 = intent.getBooleanExtra(EXTRA_VALICO_SOFT_H264, false)
        cfmotoCompat = intent.getBooleanExtra(EXTRA_CFMOTO_COMPAT, false)
        if (cfmotoCompat) {
            AppLog.add("CFMOTO VIDEO V1.4: compatibilità source dinamica attiva; pre-Android14 orientation watcher abilitato")
        }
'''
)
replace_once(
    mirror_path,
    '''            applyAdaptationRuntime("projection ready")
            startDrain()
''',
    '''            applyAdaptationRuntime("projection ready")
            startCfmotoPre34GeometryWatcher()
            startDrain()
'''
)
insert_before(
    mirror_path,
    '    private fun isLandscapeSource(): Boolean = sourceWidth >= sourceHeight\n',
    '''    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (cfmotoCompat && Build.VERSION.SDK_INT < 34 && projection != null) {
            val (w, h) = currentPhysicalSourceGeometry()
            queueCfmotoSourceResize(w, h, "configurationChanged")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentPhysicalSourceGeometry(): Pair<Int, Int> {
        return try {
            val wm = getSystemService(WindowManager::class.java)
            val physical = wm.defaultDisplay
            val metrics = DisplayMetrics()
            physical.getRealMetrics(metrics)
            Pair(metrics.widthPixels.coerceAtLeast(16), metrics.heightPixels.coerceAtLeast(16))
        } catch (_: Throwable) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels.coerceAtLeast(16), dm.heightPixels.coerceAtLeast(16))
        }
    }

    /** Android 13 and lower do not expose MediaProjection content-resize callbacks. */
    private fun startCfmotoPre34GeometryWatcher() {
        if (!cfmotoCompat || Build.VERSION.SDK_INT >= 34 || projection == null) return
        cfmotoGeometryWatchRunnable?.let { geometryHandler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                if (!cfmotoCompat || Build.VERSION.SDK_INT >= 34 || projection == null || shuttingDown.get()) return
                val (w, h) = currentPhysicalSourceGeometry()
                if (w != sourceWidth || h != sourceHeight) queueCfmotoSourceResize(w, h, "displayPoll")
                geometryHandler.postDelayed(this, 500L)
            }
        }
        cfmotoGeometryWatchRunnable = tick
        geometryHandler.postDelayed(tick, 350L)
        AppLog.add("CFMOTO ROTATION V1.4: watcher source pre-Android14 armato")
    }

    private fun queueCfmotoSourceResize(width: Int, height: Int, reason: String) {
        if (!cfmotoCompat || Build.VERSION.SDK_INT >= 34 || projection == null) return
        if (width < 16 || height < 16) return
        if (width == sourceWidth && height == sourceHeight && pendingGeometryWidth == 0) return

        pendingGeometryWidth = width
        pendingGeometryHeight = height
        geometryGeneration += 1L
        val generation = geometryGeneration
        coverRenderer?.enterGeometryHold(width, height)
        geometrySettleRunnable?.let { geometryHandler.removeCallbacks(it) }
        val settle = Runnable {
            if (generation != geometryGeneration || projection == null) return@Runnable
            val settledW = pendingGeometryWidth
            val settledH = pendingGeometryHeight
            if (settledW < 16 || settledH < 16) return@Runnable
            if (settledW == sourceWidth && settledH == sourceHeight) {
                pendingGeometryWidth = 0
                pendingGeometryHeight = 0
                coverRenderer?.cancelGeometryTransition("CFMOTO geometria invariata")
                return@Runnable
            }

            val renderer = coverRenderer
            if (renderer == null) {
                runCatching { display?.resize(settledW, settledH, captureDpi) }
                    .onSuccess {
                        sourceWidth = settledW
                        sourceHeight = settledH
                        pendingGeometryWidth = 0
                        pendingGeometryHeight = 0
                        requestImmediateSyncFrame("CFMOTO rotation direct-surface")
                        AppLog.add("CFMOTO ROTATION V1.4: COMMIT SOURCE ${settledW}x${settledH} [$reason]")
                    }
                    .onFailure { AppLog.add("CFMOTO ROTATION V1.4: resize direct fallito: ${it.javaClass.simpleName}") }
                return@Runnable
            }

            renderer.prepareSourceResize(settledW, settledH) {
                geometryHandler.post {
                    if (generation != geometryGeneration || projection == null) return@post
                    runCatching { display?.resize(settledW, settledH, captureDpi) }
                        .onSuccess {
                            sourceWidth = settledW
                            sourceHeight = settledH
                            pendingGeometryWidth = 0
                            pendingGeometryHeight = 0
                            applyAdaptationRuntime("CFMOTO orientation ${settledW}x${settledH}")
                            renderer.notifyProducerResized(settledW, settledH)
                            AppLog.add(
                                "CFMOTO ROTATION V1.4: VirtualDisplay SOURCE=${settledW}x${settledH}; " +
                                    "TFT=${targetWidth}x${targetHeight}; reason=$reason"
                            )
                        }
                        .onFailure {
                            renderer.cancelGeometryTransition("CFMOTO VirtualDisplay.resize fallito")
                            AppLog.add("CFMOTO ROTATION V1.4: VirtualDisplay resize fallito: ${it.javaClass.simpleName}")
                        }
                }
            }
        }
        geometrySettleRunnable = settle
        geometryHandler.postDelayed(settle, 180L)
        AppLog.add(
            "CFMOTO ROTATION V1.4: candidato SOURCE=${width}x${height}; " +
                "ultimo=${sourceWidth}x${sourceHeight}; TFT=${targetWidth}x${targetHeight}; reason=$reason"
        )
    }

'''
)
replace_once(
    mirror_path,
    '''        projectionStartPending = false
        geometrySettleRunnable?.let { geometryHandler.removeCallbacks(it) }
''',
    '''        projectionStartPending = false
        cfmotoGeometryWatchRunnable?.let { geometryHandler.removeCallbacks(it) }
        cfmotoGeometryWatchRunnable = null
        geometrySettleRunnable?.let { geometryHandler.removeCallbacks(it) }
'''
)
replace_once(
    mirror_path,
    '''            lockPlaceholderActive = false
            capturedContentVisible = true
''',
    '''            lockPlaceholderActive = false
            cfmotoCompat = false
            capturedContentVisible = true
'''
)

print('MotoLink V1.4 r7 physical fixes applied successfully')
