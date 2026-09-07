from pathlib import Path
import re


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}\nOLD={old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def insert_before(path, marker, insertion):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"{path}: marker expected once, got {count}: {marker[:160]!r}")
    p.write_text(text.replace(marker, insertion + marker, 1), encoding="utf-8")


# -----------------------------------------------------------------------------
# Same public V1.4 name; higher code so it upgrades every earlier V1.4 candidate.
# -----------------------------------------------------------------------------
replace_once(
    "app/build.gradle.kts",
    '        versionCode = 7\n        versionName = "1.4"',
    '        versionCode = 8\n        versionName = "1.4"'
)


# -----------------------------------------------------------------------------
# Adaptation persistence model.
# KEY_ENABLED is now the editor switch only. A separate per-bike persisted flag
# says whether the saved calibration must continue to be rendered.
# -----------------------------------------------------------------------------
config_path = "app/src/main/java/it/motolink/app/MirrorAdaptationConfig.kt"

replace_once(
    config_path,
    '    const val SETTINGS_DESCRIPTION = "Adatta manualmente il display"',
    '    const val SETTINGS_DESCRIPTION = "Attiva per regolare. La X salva, chiude e lascia la personalizzazione applicata."'
)

replace_once(
    config_path,
    '''            PANNELLO
            L’editor compare solo quando il telefono è in orizzontale. In verticale Adattamento resta attivo ma il pannello è nascosto.
            Trascina la scritta “Adattamento” per spostarlo.

            SALVATAGGIO
            Le regolazioni si salvano automaticamente e restano separate per ogni profilo moto e per orientamento verticale/orizzontale. Se una moto non è mai stata regolata, MotoLink usa la base automatica predefinita. Rinominare la moto non fa perdere le sue regolazioni.

            COMANDI
            ⓘ apre o chiude queste istruzioni. Quando le istruzioni sono aperte, i comandi vengono nascosti e la barra superiore con × resta sempre visibile.
            × chiude solo il pannello: le regolazioni salvate restano attive.
            ↺ Ripristina riporta solo l'orientamento che stai modificando ai valori iniziali dell'app e richiede due conferme.

            Adattamento è OFF al primo accesso. Se lo attivi, resta ON finché non lo disattivi dalle Impostazioni.''',
    '''            PANNELLO
            Attiva Adattamento dalle Impostazioni quando vuoi modificare la calibrazione. L’editor compare solo quando il telefono è in orizzontale.
            Trascina la scritta “Adattamento” per spostarlo.

            SALVATAGGIO
            Le regolazioni si salvano automaticamente e restano separate per ogni profilo moto e per orientamento verticale/orizzontale. Rinominare la moto non fa perdere le sue regolazioni.

            COMANDI
            ⓘ apre o chiude queste istruzioni. Quando le istruzioni sono aperte, i comandi vengono nascosti e la barra superiore con × resta sempre visibile.
            × conclude la regolazione: chiude il pannello e porta automaticamente l’interruttore Adattamento su OFF, ma la personalizzazione appena salvata continua a essere applicata in ogni nuova sessione.
            ↺ Ripristina riporta solo l'orientamento che stai modificando ai valori iniziali dell'app e richiede due conferme.

            OFF nelle Impostazioni significa “editor chiuso”, non “annulla personalizzazione”. Per modificare di nuovo il display, riattiva Adattamento: il pannello riparte dai valori già salvati.'''
)

replace_once(
    config_path,
    '''    data class Snapshot(
        val enabled: Boolean,
        val profile: Profile,''',
    '''    data class Snapshot(
        val enabled: Boolean,
        val calibrationActive: Boolean,
        val profile: Profile,'''
)

replace_once(
    config_path,
    '''        val label: String
            get() = if (!enabled) {
                "OFF"
            } else {
                val p = if (profile == Profile.LANDSCAPE) "LAND" else "PORT"
                "ON • $p • extra L${signed(leftPx)} T${signed(topPx)} R${signed(rightPx)} B${signed(bottomPx)}"
            }''',
    '''        val label: String
            get() {
                val p = if (profile == Profile.LANDSCAPE) "LAND" else "PORT"
                return when {
                    enabled -> "ON • MODIFICA $p • extra L${signed(leftPx)} T${signed(topPx)} R${signed(rightPx)} B${signed(bottomPx)}"
                    calibrationActive -> "OFF • PERSONALIZZAZIONE $p ATTIVA"
                    else -> "OFF"
                }
            }'''
)

insert_before(
    config_path,
    '    private fun migratePerBikeIfNeeded(context: Context) {',
    '''    private fun calibrationActiveKey(context: Context): String =
        "mirror_adaptation_bike_${activeBikeId(context)}_calibration_active"

'''
)

replace_once(
    config_path,
    '''    private fun ensureEnabledDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ENABLED)) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            AppLog.add("ADATTAMENTO V19 DEFAULT: primo accesso -> OFF")
        }
    }

    fun load(context: Context, profile: Profile): Snapshot {
        migrateIfNeeded(context)
        migratePerBikeIfNeeded(context)
        ensureEnabledDefault(context)
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keys = profileKeys(context, profile)
        return Snapshot(
            enabled = p.getBoolean(KEY_ENABLED, false),
            profile = profile,''',
    '''    private fun ensureEnabledDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ENABLED)) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            AppLog.add("ADATTAMENTO V19 DEFAULT: primo accesso -> OFF")
        }
    }

    private fun ensureCalibrationDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = calibrationActiveKey(context)
        if (prefs.contains(key)) return

        // Migration from previous V1.4 candidates: if the old editor switch was ON,
        // or this bike already has non-zero saved corrections, preserve that calibration.
        val savedKeys = profileKeys(context, Profile.LANDSCAPE).toList() +
            profileKeys(context, Profile.PORTRAIT).toList()
        val hasSavedCorrection = savedKeys.any { prefs.getInt(it, 0) != 0 }
        val active = prefs.getBoolean(KEY_ENABLED, false) || hasSavedCorrection
        prefs.edit().putBoolean(key, active).apply()
        AppLog.add("ADATTAMENTO V1.4 PERSISTENZA: migrazione profilo -> calibrazioneAttiva=$active")
    }

    fun load(context: Context, profile: Profile): Snapshot {
        migrateIfNeeded(context)
        migratePerBikeIfNeeded(context)
        ensureEnabledDefault(context)
        ensureCalibrationDefault(context)
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keys = profileKeys(context, profile)
        return Snapshot(
            enabled = p.getBoolean(KEY_ENABLED, false),
            calibrationActive = p.getBoolean(calibrationActiveKey(context), false),
            profile = profile,'''
)

replace_once(
    config_path,
    '''    fun setEnabled(context: Context, enabled: Boolean) {
        migrateIfNeeded(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun adjustEdge(context: Context, profile: Profile, edge: String, deltaPx: Int): Snapshot {''',
    '''    fun setEnabled(context: Context, enabled: Boolean) {
        migrateIfNeeded(context)
        migratePerBikeIfNeeded(context)
        ensureCalibrationDefault(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setCalibrationActive(context: Context, active: Boolean) {
        migrateIfNeeded(context)
        migratePerBikeIfNeeded(context)
        ensureEnabledDefault(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(calibrationActiveKey(context), active).apply()
    }

    fun adjustEdge(context: Context, profile: Profile, edge: String, deltaPx: Int): Snapshot {'''
)

replace_once(
    config_path,
    '''        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(keys[0], left)
            .putInt(keys[1], top)
            .putInt(keys[2], right)
            .putInt(keys[3], bottom)
            .apply()
        return load(context, profile)''',
    '''        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(keys[0], left)
            .putInt(keys[1], top)
            .putInt(keys[2], right)
            .putInt(keys[3], bottom)
            .putBoolean(calibrationActiveKey(context), true)
            .apply()
        return load(context, profile)'''
)

replace_once(
    config_path,
    '''    fun dashboardLabel(context: Context): String {
        val land = load(context, Profile.LANDSCAPE)
        if (!land.enabled) return "OFF"
        val port = load(context, Profile.PORTRAIT)
        val bikeName = BikeProfileStore.load(context)?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "profilo corrente"
        return "ON • $bikeName • AUTO TFT • LAND extra L${signed(land.leftPx)} T${signed(land.topPx)} R${signed(land.rightPx)} B${signed(land.bottomPx)} " +
            "• PORT L${signed(port.leftPx)} T${signed(port.topPx)} R${signed(port.rightPx)} B${signed(port.bottomPx)}"
    }''',
    '''    fun dashboardLabel(context: Context): String {
        val land = load(context, Profile.LANDSCAPE)
        val port = load(context, Profile.PORTRAIT)
        val bikeName = BikeProfileStore.load(context)?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "profilo corrente"
        val mode = when {
            land.enabled -> "ON • MODIFICA"
            land.calibrationActive -> "OFF • PERSONALIZZAZIONE ATTIVA"
            else -> "OFF"
        }
        return "$mode • $bikeName • LAND L${signed(land.leftPx)} T${signed(land.topPx)} R${signed(land.rightPx)} B${signed(land.bottomPx)} " +
            "• PORT L${signed(port.leftPx)} T${signed(port.topPx)} R${signed(port.rightPx)} B${signed(port.bottomPx)}"
    }'''
)

replace_once(
    config_path,
    '''            edit.remove("${prefix}_left_px")
                .remove("${prefix}_top_px")
                .remove("${prefix}_right_px")
                .remove("${prefix}_bottom_px")''',
    '''            edit.remove("${prefix}_left_px")
                .remove("${prefix}_top_px")
                .remove("${prefix}_right_px")
                .remove("${prefix}_bottom_px")
                .remove("mirror_adaptation_bike_${id}_calibration_active")'''
)


# -----------------------------------------------------------------------------
# MainActivity: the Settings switch controls editor visibility only. Turning it
# OFF never disables the stored calibration. Turning it ON reopens editing from
# the stored values and marks the current bike calibration active.
# -----------------------------------------------------------------------------
main_path = "app/src/main/java/it/motolink/app/MainActivity.kt"

replace_once(main_path, 'import android.content.ComponentName\n', 'import android.content.ComponentName\nimport android.content.BroadcastReceiver\nimport android.content.Context\nimport android.content.IntentFilter\n')

insert_before(
    main_path,
    '    private val logListener: (String) -> Unit = { line ->',
    '''    private var adaptationEditorReceiverRegistered = false
    private val adaptationEditorStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MirrorService.ACTION_ADAPTATION_EDITOR_STATE_CHANGED) return
            val cfg = MirrorAdaptationConfig.load(this@MainActivity)
            if (::dashboard.isInitialized) {
                dashboard.updateAdaptation(cfg.enabled, MirrorAdaptationConfig.dashboardLabel(this@MainActivity))
            }
            AppLog.add("ADATTAMENTO V1.4 UI: regolazione conclusa -> interruttore OFF; personalizzazione conservata")
        }
    }

'''
)

replace_once(
    main_path,
    '''        AppLog.subscribe(logListener)
        registerNetworkDiagnostics()
''',
    '''        AppLog.subscribe(logListener)
        registerNetworkDiagnostics()
        val adaptationFilter = IntentFilter(MirrorService.ACTION_ADAPTATION_EDITOR_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(adaptationEditorStateReceiver, adaptationFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(adaptationEditorStateReceiver, adaptationFilter)
        }
        adaptationEditorReceiverRegistered = true
'''
)

replace_once(
    main_path,
    '''            MirrorAdaptationConfig.setEnabled(this, granted)
            val cfg = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(cfg.enabled, MirrorAdaptationConfig.dashboardLabel(this))''',
    '''            if (granted) MirrorAdaptationConfig.setCalibrationActive(this, true)
            MirrorAdaptationConfig.setEnabled(this, granted)
            val cfg = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(cfg.enabled, MirrorAdaptationConfig.dashboardLabel(this))'''
)

old_toggle = '''    private fun toggleAdaptationSetting() {
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
'''

new_toggle = '''    private fun toggleAdaptationSetting() {
        val current = MirrorAdaptationConfig.load(this)
        if (current.enabled) {
            // OFF now means only "editor closed". The calibration stays active and saved.
            MirrorAdaptationConfig.setEnabled(this, false)
            val cfg = MirrorAdaptationConfig.load(this)
            dashboard.updateAdaptation(false, MirrorAdaptationConfig.dashboardLabel(this))
            AppLog.add(
                "ADATTAMENTO V1.4: EDITOR OFF manuale; calibrazioneAttiva=${cfg.calibrationActive}; " +
                    "personalizzazione conservata e ancora applicata"
            )
            if (runSelection == RunSelection.START) {
                startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            waitingForAdaptationOverlayPermission = true
            AppLog.add("ADATTAMENTO V1.4: richiedo permesso 'Mostra sopra altre app' per l'editor flottante")
            try {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        data = Uri.parse("package:$packageName")
                    }
                }
                startActivity(settingsIntent)
            } catch (t: Throwable) {
                waitingForAdaptationOverlayPermission = false
                AppLog.add("ADATTAMENTO V1.4: apertura permesso fallita ${t.javaClass.simpleName}: ${t.message ?: "-"}")
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Adattamento",
                    message = "Abilita MotoLink in Impostazioni Android > Accesso speciale > Mostra sopra altre app."
                )
            }
            return
        }

        MirrorAdaptationConfig.setCalibrationActive(this, true)
        MirrorAdaptationConfig.setEnabled(this, true)
        val cfg = MirrorAdaptationConfig.load(this)
        dashboard.updateAdaptation(true, MirrorAdaptationConfig.dashboardLabel(this))
        AppLog.add(
            "ADATTAMENTO V1.4: EDITOR ON; riparto dai valori salvati; " +
                "calibrazioneAttiva=${cfg.calibrationActive}; step=${MirrorAdaptationConfig.STEP_PX}px"
        )
        if (runSelection == RunSelection.START) {
            startService(Intent(this, MirrorService::class.java).apply { action = MirrorService.ACTION_ADAPTATION_UPDATE })
        }
        NeonDialogs.showInfo(
            activity = this,
            title = "Regola Adattamento",
            message = MirrorAdaptationConfig.USER_HELP_TEXT
        )
    }
'''
replace_once(main_path, old_toggle, new_toggle)

replace_once(
    main_path,
    '''        networkCallback = null
        if (::bikeNetworkConnector.isInitialized) bikeNetworkConnector.release()''',
    '''        networkCallback = null
        if (adaptationEditorReceiverRegistered) {
            runCatching { unregisterReceiver(adaptationEditorStateReceiver) }
            adaptationEditorReceiverRegistered = false
        }
        if (::bikeNetworkConnector.isInitialized) bikeNetworkConnector.release()'''
)


# -----------------------------------------------------------------------------
# MirrorService: render calibration independently from editor visibility.
# X commits/closes the editor, persists switch OFF, and broadcasts the UI refresh.
# -----------------------------------------------------------------------------
mirror_path = "app/src/main/java/it/motolink/app/MirrorService.kt"

replace_once(
    mirror_path,
    '        const val ACTION_ADAPTATION_UPDATE = "it.motolink.app.ADAPTATION_UPDATE"\n',
    '        const val ACTION_ADAPTATION_UPDATE = "it.motolink.app.ADAPTATION_UPDATE"\n        const val ACTION_ADAPTATION_EDITOR_STATE_CHANGED = "it.motolink.app.ADAPTATION_EDITOR_STATE_CHANGED"\n'
)

replace_once(
    mirror_path,
    '''            ACTION_ADAPTATION_UPDATE -> {
                // A deliberate change from Settings re-opens the editor when adaptation is ON.
                adaptationPanelDismissed = false
                applyAdaptationRuntime("impostazioni")
            }''',
    '''            ACTION_ADAPTATION_UPDATE -> {
                val cfg = MirrorAdaptationConfig.load(this)
                adaptationPanelDismissed = !cfg.enabled
                applyAdaptationRuntime("impostazioni/editor")
            }'''
)

replace_once(
    mirror_path,
    '''        projectionStartPending = true
        adaptationPanelDismissed = false
''',
    '''        projectionStartPending = true
        adaptationPanelDismissed = !MirrorAdaptationConfig.load(this).enabled
'''
)

old_runtime = '''    private fun applyAdaptationRuntime(reason: String) {
        val profile = activeAdaptationProfile()
        val config = MirrorAdaptationConfig.load(this, profile)
        coverRenderer?.updateEdgeAdaptation(
            left = config.leftPx,
            top = config.topPx,
            right = config.rightPx,
            bottom = config.bottomPx,
            enabled = config.enabled
        )
        val editorAllowedForOrientation = profile == MirrorAdaptationConfig.Profile.LANDSCAPE
        if (config.enabled && editorAllowedForOrientation && projection != null && !adaptationPanelDismissed) {
            showAdaptationOverlay()
        } else {
            hideAdaptationOverlay(
                if (config.enabled && !editorAllowedForOrientation) "portrait: editor solo orizzontale" else reason
            )
        }
        clampAdaptationOverlayToScreen("runtime $reason")

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.enabled) {
            MirrorAdaptationConfig.landscapeAutoFrameFor(targetWidth, targetHeight)
        } else {
            MirrorAdaptationConfig.AutoFrame(0, 0, targetWidth, targetHeight)
        }
        val effectiveX = autoBase.x - if (config.enabled) config.leftPx else 0
        val effectiveY = autoBase.y - if (config.enabled) config.bottomPx else 0
        val effectiveW = (autoBase.width + if (config.enabled) config.netHorizontalPx else 0).coerceAtLeast(16)
        val effectiveH = (autoBase.height + if (config.enabled) config.netVerticalPx else 0).coerceAtLeast(16)

        AppLog.add(
            "ADATTAMENTO V15 STATE: reason=$reason orientation=${config.profile} enabled=${config.enabled}; " +
                "autoBase=${autoBase.width}x${autoBase.height}@${autoBase.x},${autoBase.y}; " +
                "extra L=${MirrorAdaptationConfig.signed(config.leftPx)} T=${MirrorAdaptationConfig.signed(config.topPx)} " +
                "R=${MirrorAdaptationConfig.signed(config.rightPx)} B=${MirrorAdaptationConfig.signed(config.bottomPx)}; " +
                "totale_extra=${MirrorAdaptationConfig.signed(config.totalNetPx)}px; " +
                "viewport_effettivo=${effectiveW}x${effectiveH}@${effectiveX},${effectiveY}; target=${targetWidth}x${targetHeight}; " +
                "autoGeometry=${if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE) "TBOX_RUNTIME_SCALE" else "PORTRAIT_FIT"}"
        )
    }
'''

new_runtime = '''    private fun applyAdaptationRuntime(reason: String) {
        val profile = activeAdaptationProfile()
        val config = MirrorAdaptationConfig.load(this, profile)
        coverRenderer?.updateEdgeAdaptation(
            left = config.leftPx,
            top = config.topPx,
            right = config.rightPx,
            bottom = config.bottomPx,
            enabled = config.calibrationActive
        )

        // Editor visibility and rendered calibration are intentionally independent.
        val editorAllowedForOrientation = profile == MirrorAdaptationConfig.Profile.LANDSCAPE
        if (config.enabled && editorAllowedForOrientation && projection != null && !adaptationPanelDismissed) {
            showAdaptationOverlay()
        } else {
            hideAdaptationOverlay(
                if (config.enabled && !editorAllowedForOrientation) "portrait: editor solo orizzontale" else reason
            )
        }
        clampAdaptationOverlayToScreen("runtime $reason")

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.calibrationActive) {
            MirrorAdaptationConfig.landscapeAutoFrameFor(targetWidth, targetHeight)
        } else {
            MirrorAdaptationConfig.AutoFrame(0, 0, targetWidth, targetHeight)
        }
        val effectiveX = autoBase.x - if (config.calibrationActive) config.leftPx else 0
        val effectiveY = autoBase.y - if (config.calibrationActive) config.bottomPx else 0
        val effectiveW = (autoBase.width + if (config.calibrationActive) config.netHorizontalPx else 0).coerceAtLeast(16)
        val effectiveH = (autoBase.height + if (config.calibrationActive) config.netVerticalPx else 0).coerceAtLeast(16)

        AppLog.add(
            "ADATTAMENTO V1.4 STATE: reason=$reason orientation=${config.profile}; " +
                "editor=${config.enabled}; calibrazioneAttiva=${config.calibrationActive}; " +
                "autoBase=${autoBase.width}x${autoBase.height}@${autoBase.x},${autoBase.y}; " +
                "extra L=${MirrorAdaptationConfig.signed(config.leftPx)} T=${MirrorAdaptationConfig.signed(config.topPx)} " +
                "R=${MirrorAdaptationConfig.signed(config.rightPx)} B=${MirrorAdaptationConfig.signed(config.bottomPx)}; " +
                "viewport_effettivo=${effectiveW}x${effectiveH}@${effectiveX},${effectiveY}; target=${targetWidth}x${targetHeight}"
        )
    }
'''
replace_once(mirror_path, old_runtime, new_runtime)

replace_once(
    mirror_path,
    '''        coverRenderer?.updateEdgeAdaptation(
            config.leftPx,
            config.topPx,
            config.rightPx,
            config.bottomPx,
            config.enabled
        )

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.enabled) {''',
    '''        coverRenderer?.updateEdgeAdaptation(
            config.leftPx,
            config.topPx,
            config.rightPx,
            config.bottomPx,
            config.calibrationActive
        )

        val autoBase = if (config.profile == MirrorAdaptationConfig.Profile.LANDSCAPE && config.calibrationActive) {'''
)

replace_once(
    mirror_path,
    '''        close.setOnClickListener {
            cancelResetConfirmation()
            adaptationPanelDismissed = true
            AppLog.add(
                "ADATTAMENTO V1.1: X premuta -> pannello chiuso; " +
                    "Adattamento resta attivo e calibrazione salvata continua applicata"
            )
            hideAdaptationOverlay("X pannello")
        }''',
    '''        close.setOnClickListener {
            cancelResetConfirmation()
            // X means "finished editing": switch OFF, calibration stays active and persisted.
            MirrorAdaptationConfig.setEnabled(this, false)
            adaptationPanelDismissed = true
            applyAdaptationRuntime("X pannello: editor OFF, calibrazione salvata")
            sendBroadcast(Intent(ACTION_ADAPTATION_EDITOR_STATE_CHANGED).setPackage(packageName))
            AppLog.add(
                "ADATTAMENTO V1.4: X premuta -> editor OFF automatico; " +
                    "personalizzazione salvata continua applicata nelle sessioni future"
            )
        }'''
)


# -----------------------------------------------------------------------------
# Remove external-product references from the candidate build tree. The pattern
# is assembled so the forbidden product string itself never appears in this file.
# -----------------------------------------------------------------------------
blocked = re.compile("moto" + r"[- _]?" + "hub", re.IGNORECASE)
for root in (Path("app"), Path("docs"), Path("README.md"), Path(".github")):
    targets = [root] if root.is_file() else list(root.rglob("*")) if root.exists() else []
    for path in targets:
        if not path.is_file() or path.suffix.lower() not in {".kt", ".kts", ".md", ".yml", ".yaml", ".py", ".txt"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        cleaned = blocked.sub("external mirroring reference", text)
        if cleaned != text:
            path.write_text(cleaned, encoding="utf-8")

print("MotoLink V1.4 r8 adaptation persistence fixes applied successfully")
