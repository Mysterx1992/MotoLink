from pathlib import Path


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"missing start anchor: {label}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"missing end anchor: {label}")
    return text[:a] + replacement.rstrip() + "\n\n" + text[b:]


# -----------------------------------------------------------------------------
# Version
# -----------------------------------------------------------------------------
p = Path("app/build.gradle.kts")
s = p.read_text()
s = must_replace(s, 'versionCode = 10', 'versionCode = 11', 'versionCode')
s = must_replace(s, 'versionName = "1.5.1"', 'versionName = "1.6"', 'versionName')
p.write_text(s)

# -----------------------------------------------------------------------------
# Manifest: BLE + connected-device FGS + Maps notification listener
# -----------------------------------------------------------------------------
p = Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
s = must_replace(
    s,
    '        <intent>\n            <action android:name="android.intent.action.MAIN" />\n            <category android:name="android.intent.category.LAUNCHER" />\n        </intent>\n',
    '        <intent>\n            <action android:name="android.intent.action.MAIN" />\n            <category android:name="android.intent.category.LAUNCHER" />\n        </intent>\n        <package android:name="com.google.android.apps.maps" />\n',
    'maps query'
)
s = must_replace(
    s,
    '    <uses-permission android:name="android.permission.CAMERA" />\n',
    '    <uses-permission android:name="android.permission.CAMERA" />\n'
    '    <!-- V1.6: dedicated VOGE navigation transport over the second BLE link. -->\n'
    '    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />\n'
    '    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />\n'
    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />\n',
    'BLE permissions'
)
s = must_replace(
    s,
    '        <service\n            android:name=".MirrorService"\n            android:exported="false"\n            android:foregroundServiceType="mediaProjection" />\n',
    '        <service\n            android:name=".MirrorService"\n            android:exported="false"\n            android:foregroundServiceType="mediaProjection" />\n\n'
    '        <service\n            android:name=".VogeNavService"\n            android:exported="false"\n            android:foregroundServiceType="connectedDevice" />\n\n'
    '        <service\n            android:name=".MapsNavigationListenerService"\n            android:label="MotoLink Maps Navigation"\n            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"\n            android:exported="true">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.service.notification.NotificationListenerService" />\n'
    '            </intent-filter>\n'
    '        </service>\n',
    'services'
)
p.write_text(s)

# -----------------------------------------------------------------------------
# BikeProfileStore: persist resolved transport + BLE identity privately.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/it/motolink/app/BikeProfileStore.kt")
s = p.read_text()
s = must_replace(
    s,
    '    val catalogLabel: String? = null,\n    val savedAtMs: Long = System.currentTimeMillis(),\n',
    '    val catalogLabel: String? = null,\n'
    '    val connectionType: String = "",\n'
    '    val bleAddress: String? = null,\n'
    '    val bleName: String? = null,\n'
    '    val bleServiceUuid: String? = null,\n'
    '    val bleWriteUuid: String? = null,\n'
    '    val bleNotifyUuid: String? = null,\n'
    '    val savedAtMs: Long = System.currentTimeMillis(),\n',
    'profile connection fields'
)
s = must_replace(
    s,
    '        return !wifiPassword.isNullOrBlank() || sec in setOf("", "NOPASS", "OPEN", "NONE")\n    }\n}\n\nobject BikeProfileStore',
    '        return !wifiPassword.isNullOrBlank() || sec in setOf("", "NOPASS", "OPEN", "NONE")\n'
    '    }\n\n'
    '    fun resolvedConnectionType(): String {\n'
    '        val explicit = connectionType.trim().uppercase()\n'
    '        if (explicit in setOf("BLE", "HOTSPOT", "QR", "AUTOMATIC")) return explicit\n'
    '        return when {\n'
    '            format.equals("BLE_VOGE", true) || !bleAddress.isNullOrBlank() -> "BLE"\n'
    '            format.equals("HOTSPOT", true) -> "HOTSPOT"\n'
    '            format.contains("QR", true) || hasWifiIdentity() -> "QR"\n'
    '            else -> "AUTOMATIC"\n'
    '        }\n'
    '    }\n'
    '}\n\nobject BikeProfileStore',
    'resolved connection type'
)
s = must_replace(
    s,
    '    fun clearPhoto(context: Context, index: Int): Boolean {\n',
    '    fun replaceAt(context: Context, index: Int, profile: BikeProfile): Boolean {\n'
    '        val list = loadAll(context).toMutableList()\n'
    '        val old = list.getOrNull(index) ?: return false\n'
    '        list[index] = profile.copy(profileId = old.profileId, savedAtMs = old.savedAtMs)\n'
    '        return saveAll(context, list)\n'
    '    }\n\n'
    '    fun clearPhoto(context: Context, index: Int): Boolean {\n',
    'replace profile'
)
s = must_replace(
    s,
    '        if (a.ssid != null && b.ssid != null && a.ssid == b.ssid) return true\n',
    '        if (a.bleAddress != null && b.bleAddress != null && a.bleAddress == b.bleAddress) return true\n'
    '        if (a.ssid != null && b.ssid != null && a.ssid == b.ssid) return true\n',
    'same identity BLE'
)
s = must_replace(
    s,
    '        .put("catalogLabel", profile.catalogLabel)\n        .put("savedAtMs", profile.savedAtMs)\n',
    '        .put("catalogLabel", profile.catalogLabel)\n'
    '        .put("connectionType", profile.connectionType)\n'
    '        .put("bleAddress", profile.bleAddress)\n'
    '        .put("bleName", profile.bleName)\n'
    '        .put("bleServiceUuid", profile.bleServiceUuid)\n'
    '        .put("bleWriteUuid", profile.bleWriteUuid)\n'
    '        .put("bleNotifyUuid", profile.bleNotifyUuid)\n'
    '        .put("savedAtMs", profile.savedAtMs)\n',
    'profile json write'
)
s = must_replace(
    s,
    '        catalogLabel = obj.optNullableString("catalogLabel"),\n        savedAtMs = obj.optLong("savedAtMs", 0L),\n',
    '        catalogLabel = obj.optNullableString("catalogLabel"),\n'
    '        connectionType = obj.optNullableString("connectionType") ?: "",\n'
    '        bleAddress = obj.optNullableString("bleAddress"),\n'
    '        bleName = obj.optNullableString("bleName"),\n'
    '        bleServiceUuid = obj.optNullableString("bleServiceUuid"),\n'
    '        bleWriteUuid = obj.optNullableString("bleWriteUuid"),\n'
    '        bleNotifyUuid = obj.optNullableString("bleNotifyUuid"),\n'
    '        savedAtMs = obj.optLong("savedAtMs", 0L),\n',
    'profile json read'
)
p.write_text(s)

# -----------------------------------------------------------------------------
# MainActivity V1.6 UX and routing
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/it/motolink/app/MainActivity.kt")
s = p.read_text()
s = must_replace(s, '        private const val REQ_QR_CAMERA_PERMISSION = 7007\n', '        private const val REQ_QR_CAMERA_PERMISSION = 7007\n        private const val REQ_V16_BLE_PERMISSION = 7008\n', 'BLE request code')
s = must_replace(
    s,
    '        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)\n        if (!prefs.getBoolean(PREF_POCKET_MODE_CHOICE_SET, false)) {\n',
    '        if (BikeProfileStore.load(this) == null) {\n'
    '            showFirstStartConnectionChoice()\n'
    '            return\n'
    '        }\n\n'
    '        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)\n'
    '        if (!prefs.getBoolean(PREF_POCKET_MODE_CHOICE_SET, false)) {\n',
    'START profile first'
)
s = must_replace(
    s,
    '    private fun continueStartAfterProfileReady() {\n        if (pocketModeForPendingStart) {\n            ensureBackgroundGatePermissionThenProjection()\n        } else {\n            prepareBikeNetworkThenProjection()\n        }\n    }\n',
    '    private fun continueStartAfterProfileReady() {\n'
    '        val profile = BikeProfileStore.load(this)\n'
    '        if (profile?.resolvedConnectionType() == "BLE") {\n'
    '            prepareBikeNetworkThenProjection()\n'
    '            return\n'
    '        }\n'
    '        if (pocketModeForPendingStart) {\n'
    '            ensureBackgroundGatePermissionThenProjection()\n'
    '        } else {\n'
    '            prepareBikeNetworkThenProjection()\n'
    '        }\n'
    '    }\n',
    'BLE bypass pocket gate'
)

first_start = r'''    private fun showFirstStartConnectionChoice() {
        if (runSelection != RunSelection.START) return
        if (BikeProfileStore.load(this) != null) {
            firstStartProfileSetupPending = false
            continueStartAfterProfileReady()
            return
        }

        firstStartProfileSetupPending = true
        val base = BikeProfile(
            displayName = "",
            format = "LOCAL",
            connectionType = "AUTOMATIC",
            rawPayload = "V16_NEW:${System.currentTimeMillis()}"
        )
        showBikeProfileCreationDialog(
            baseProfile = base,
            title = "Nuovo profilo moto",
            message = "Crea il profilo. MotoLink può rilevare automaticamente il collegamento oppure provare direttamente Hotspot, Qrcode o BLE.",
            onSaved = { profile ->
                firstStartProfileSetupPending = false
                lastResolved = null
                refreshBikeProfiles()
                setHeaderStatus("Avvio", profile.displayName, C_AMBER)
                setState("Profilo verificato", "Continuo con ${v16ConnectionLabel(profile)}", C_AMBER, "V1.6")
                AppLog.add("PRIMO START V1.6: profilo verificato e salvato; tipo=${profile.resolvedConnectionType()}")
                continueV16AfterProfileCreated(profile)
            },
            onCancel = { abortFirstStartProfileSetup("creazione profilo chiusa") }
        )
    }'''
s = replace_between(s, '    private fun showFirstStartConnectionChoice() {', '    private fun abortFirstStartProfileSetup(', first_start, 'first start dialog')

creation = r'''    private fun showBikeProfileCreationDialog(
        baseProfile: BikeProfile,
        title: String,
        message: String,
        onSaved: (BikeProfile) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        if (BikeProfileStore.loadAll(this).size >= BikeProfileStore.MAX_PROFILES) {
            NeonDialogs.showInfo(this, "Garage pieno", "Puoi salvare al massimo 3 profili moto.", onPositive = { onCancel?.invoke() })
            return
        }

        val name = EditText(this).apply {
            hint = "Nome moto (es. La mia Voge)"
            setText(baseProfile.displayName)
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            isSingleLine = true
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        }
        val catalogOptions = bikeCatalogOptions()
        val catalog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalogOptions)
            val idx = catalogOptions.indexOfFirst { it.equals(baseProfile.catalogLabel, true) }
            if (idx >= 0) setSelection(idx)
        }
        val connectionOptions = v16ConnectionLabels()
        val connection = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, connectionOptions)
            val idx = connectionOptions.indexOf(v16ConnectionLabel(baseProfile))
            setSelection(if (idx >= 0) idx else 0)
        }
        fun label(textValue: String) = TextView(this).apply {
            text = textValue
            setTextColor(color(C_MUTED))
            textSize = 13f
            setPadding(2, (5 * resources.displayMetrics.density).toInt(), 2, (4 * resources.displayMetrics.density).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
            addView(label("Modello"))
            addView(catalog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
            addView(label("Tipo di connessione"))
            addView(connection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()))
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
                    NeonDialogs.showInfo(this, "Nome moto richiesto", "Inserisci un nome per la moto prima di salvare.", onPositive = {
                        showBikeProfileCreationDialog(baseProfile, title, message, onSaved, onCancel)
                    })
                    return@showCustom
                }
                val candidate = baseProfile.copy(
                    displayName = display,
                    description = null,
                    catalogLabel = catalogOptions[catalog.selectedItemPosition]
                )
                val requested = v16ConnectionStorage(connectionOptions[connection.selectedItemPosition])
                verifyV16Connection(
                    requested = requested,
                    base = candidate,
                    onSuccess = { verified ->
                        if (BikeProfileStore.save(this, verified)) {
                            onSaved(verified)
                        } else {
                            NeonDialogs.showInfo(this, "Profilo non salvato", "MotoLink non è riuscita a salvare il profilo sul dispositivo.")
                        }
                    },
                    onFailure = { reason ->
                        NeonDialogs.showInfo(this, "Connessione non verificata", reason, onPositive = {
                            showBikeProfileCreationDialog(candidate, title, message, onSaved, onCancel)
                        })
                    }
                )
            },
            onNegative = {
                handled = true
                onCancel?.invoke()
            }
        )
        dialog.setOnDismissListener { mainHandler.post { if (!handled) onCancel?.invoke() } }
    }'''
s = replace_between(s, '    private fun showBikeProfileCreationDialog(', '    private fun showLocalBikeProfileDialog()', creation, 'profile creation')

edit = r'''    private fun showBikeProfileMenu(index: Int) {
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
        val catalogOptions = bikeCatalogOptions()
        val catalog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalogOptions)
            val normalized = when (profile.catalogLabel) {
                "Voge Trofeo 525DSX" -> "Voge Valico 525DSX"
                "Immagine MotoLink generica" -> "Altro modello"
                else -> profile.catalogLabel
            }
            val idx = catalogOptions.indexOf(normalized)
            setSelection(if (idx >= 0) idx else 0)
        }
        val connectionOptions = v16ConnectionLabels()
        val connection = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, connectionOptions)
            val idx = connectionOptions.indexOf(v16ConnectionLabel(profile))
            setSelection(if (idx >= 0) idx else 0)
        }
        var allowConnectionOpen = false
        connection.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP && !allowConnectionOpen) {
                NeonDialogs.showConfirm(
                    activity = this,
                    title = "Modifica tipo di connessione",
                    message = "Cambiare il tipo di connessione potrebbe impedire al profilo di collegarsi correttamente alla moto. La nuova configurazione verrà verificata prima di essere salvata; se la verifica non riesce, la connessione attuale resterà invariata.",
                    positiveText = "CONTINUA",
                    negativeText = "ANNULLA",
                    onPositive = {
                        allowConnectionOpen = true
                        connection.performClick()
                        mainHandler.postDelayed({ allowConnectionOpen = false }, 600L)
                    }
                )
                true
            } else false
        }

        var editProfileDialog: android.app.Dialog? = null
        fun smallLabel(value: String) = TextView(this).apply {
            text = value
            setTextColor(color(C_MUTED))
            textSize = 13f
            setPadding(2, (5 * resources.displayMetrics.density).toInt(), 2, (4 * resources.displayMetrics.density).toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
            addView(smallLabel("Modello"))
            addView(catalog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
            addView(smallLabel("Tipo di connessione"))
            addView(connection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { bottomMargin = (14 * resources.displayMetrics.density).toInt() })
            addView(TextView(this@MainActivity).apply {
                text = "ELIMINA PROFILO"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF6A6A"))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                background = NeonDialogs.rounded("#07120B", "#7A2A2A", 1, 16, this@MainActivity)
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
                        }
                    )
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (50 * resources.displayMetrics.density).toInt()))
        }

        editProfileDialog = NeonDialogs.showCustom(
            activity = this,
            title = "Modifica profilo",
            message = "Nome, modello e tipo di connessione sono modificabili. Il tipo di connessione viene sostituito solo dopo una verifica riuscita.",
            contentView = box,
            positiveText = "SALVA",
            negativeText = "FOTO / ALTRO",
            onPositive = {
                val editedName = name.text.toString().trim()
                if (editedName.isEmpty()) {
                    showQrError("Il nome della moto è obbligatorio.")
                    return@showCustom
                }
                val base = profile.copy(
                    displayName = editedName,
                    description = null,
                    catalogLabel = catalogOptions[catalog.selectedItemPosition]
                )
                val requested = v16ConnectionStorage(connectionOptions[connection.selectedItemPosition])
                if (requested == profile.resolvedConnectionType()) {
                    if (BikeProfileStore.replaceAt(this, index, base)) {
                        refreshBikeProfiles()
                        dashboard.updateAdaptation(MirrorAdaptationConfig.load(this).enabled, MirrorAdaptationConfig.dashboardLabel(this))
                    }
                } else {
                    verifyV16Connection(
                        requested = requested,
                        base = base,
                        onSuccess = { verified ->
                            if (BikeProfileStore.replaceAt(this, index, verified)) {
                                lastResolved = null
                                refreshBikeProfiles()
                                dashboard.updateAdaptation(MirrorAdaptationConfig.load(this).enabled, MirrorAdaptationConfig.dashboardLabel(this))
                                NeonDialogs.showInfo(this, "Connessione aggiornata", "Nuovo tipo verificato: ${v16ConnectionLabel(verified)}.")
                            }
                        },
                        onFailure = { reason ->
                            NeonDialogs.showInfo(this, "Connessione invariata", "$reason\n\nIl profilo continua a usare ${v16ConnectionLabel(profile)}.")
                        }
                    )
                }
            },
            onNegative = {
                val editedName = name.text.toString().trim()
                if (editedName.isEmpty()) {
                    showQrError("Il nome della moto è obbligatorio.")
                    return@showCustom
                }
                BikeProfileStore.replaceAt(this, index, profile.copy(
                    displayName = editedName,
                    description = null,
                    catalogLabel = catalogOptions[catalog.selectedItemPosition]
                ))
                refreshBikeProfiles()
                showBikeProfileExtraMenu(index)
            }
        )
    }'''
s = replace_between(s, '    private fun showBikeProfileMenu(index: Int) {', '    private fun showBikeProfileExtraMenu(index: Int)', edit, 'profile edit')

helpers = r'''    private fun v16ConnectionLabels(): List<String> = listOf("Automatico", "Hotspot", "Qrcode", "BLE")

    private fun v16ConnectionStorage(label: String): String = when (label.lowercase()) {
        "hotspot" -> "HOTSPOT"
        "qrcode" -> "QR"
        "ble" -> "BLE"
        else -> "AUTOMATIC"
    }

    private fun v16ConnectionLabel(profile: BikeProfile): String = when (profile.resolvedConnectionType()) {
        "BLE" -> "BLE"
        "HOTSPOT" -> "Hotspot"
        "QR" -> "Qrcode"
        else -> "Automatico"
    }

    private fun continueV16AfterProfileCreated(profile: BikeProfile) {
        if (profile.resolvedConnectionType() == "BLE") {
            pocketModeForPendingStart = false
            continueStartAfterProfileReady()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_POCKET_MODE_CHOICE_SET, false)) {
            showPocketModeFirstUseDialog()
        } else {
            pocketModeForPendingStart = prefs.getBoolean(PREF_POCKET_MODE_ENABLED, false)
            continueStartAfterProfileReady()
        }
    }

    private fun verifyV16Connection(
        requested: String,
        base: BikeProfile,
        onSuccess: (BikeProfile) -> Unit,
        onFailure: (String) -> Unit
    ) {
        fun verifiedBle(result: VogeBleProbe.Result): BikeProfile = base.copy(
            format = "BLE_VOGE",
            connectionType = "BLE",
            bleAddress = result.address,
            bleName = result.name,
            bleServiceUuid = result.serviceUuid,
            bleWriteUuid = result.writeUuid,
            bleNotifyUuid = result.notifyUuid,
            rawPayload = "BLE_VOGE:${result.address}"
        )

        fun runBle(fallbackToHotspot: Boolean) {
            if (!VogeBleProbe.hasPermissions(this)) {
                VogeBleProbe.requestPermissions(this, REQ_V16_BLE_PERMISSION)
                onFailure("Concedi i permessi Bluetooth richiesti da Android, poi premi di nuovo SALVA.")
                return
            }
            setState("Ricerca BLE…", "Verifico il secondo Bluetooth della moto", C_AMBER, "BLE")
            VogeBleProbe.probe(
                activity = this,
                timeoutMs = if (fallbackToHotspot) 14_000L else 12_000L,
                onSuccess = { result -> onSuccess(verifiedBle(result)) },
                onFailure = { bleReason ->
                    if (fallbackToHotspot && bikeNetworkConnector.bindExistingWifiForHotspot()) {
                        AppLog.add("V1.6 AUTO: BLE non verificato; rete Wi-Fi locale disponibile -> HOTSPOT")
                        onSuccess(base.copy(format = "HOTSPOT", connectionType = "HOTSPOT", rawPayload = "HOTSPOT:${System.currentTimeMillis()}"))
                    } else {
                        onFailure(if (fallbackToHotspot) "$bleReason\nNessun Hotspot moto già collegato è stato rilevato. Se la moto mostra un QR, scegli Qrcode." else bleReason)
                    }
                }
            )
        }

        when (requested.uppercase()) {
            "BLE" -> runBle(false)
            "HOTSPOT" -> {
                if (bikeNetworkConnector.bindExistingWifiForHotspot()) {
                    onSuccess(base.copy(format = "HOTSPOT", connectionType = "HOTSPOT", rawPayload = "HOTSPOT:${System.currentTimeMillis()}"))
                } else {
                    onFailure("Collega prima il telefono alla rete Wi-Fi/Hotspot della moto, poi riprova.")
                }
            }
            "QR" -> {
                if (base.resolvedConnectionType() == "QR" && base.rawPayload.isNotBlank() && !base.format.equals("LOCAL", true)) {
                    onSuccess(base.copy(connectionType = "QR"))
                    return
                }
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build()
                AppLog.add("V1.6 PROFILE: Qrcode selezionato -> apro scanner fotocamera")
                GmsBarcodeScanning.getClient(this, options).startScan()
                    .addOnSuccessListener { barcode ->
                        val raw = barcode.rawValue
                        if (raw.isNullOrBlank()) {
                            onFailure("Il Qrcode non contiene dati leggibili.")
                        } else {
                            val parsed = runCatching { QrPairing.parse(raw) }.getOrNull()
                            if (parsed == null) {
                                onFailure("Il Qrcode non è riconosciuto come configurazione moto compatibile.")
                            } else {
                                onSuccess(parsed.copy(
                                    displayName = base.displayName,
                                    description = null,
                                    catalogLabel = base.catalogLabel,
                                    connectionType = "QR",
                                    profileId = base.profileId,
                                    savedAtMs = base.savedAtMs
                                ))
                            }
                        }
                    }
                    .addOnCanceledListener { onFailure("Scansione Qrcode annullata.") }
                    .addOnFailureListener { onFailure("Scanner Qrcode non disponibile: ${it.javaClass.simpleName}.") }
            }
            else -> runBle(true)
        }
    }

    private fun startV16BleNavigation(profile: BikeProfile) {
        if (!VogeBleProbe.hasPermissions(this)) {
            VogeBleProbe.requestPermissions(this, REQ_V16_BLE_PERMISSION)
            startInProgress = false
            setRunSelection(RunSelection.NONE)
            setState("Permesso Bluetooth", "Concedi l'accesso e premi START di nuovo", C_AMBER, "BLE")
            return
        }
        val listeners = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this)
        if (!listeners.contains(packageName)) {
            startInProgress = false
            setRunSelection(RunSelection.NONE)
            NeonDialogs.showInfo(
                activity = this,
                title = "Accesso notifiche Maps",
                message = "Per leggere le indicazioni di Google Maps in background, abilita MotoLink in Accesso alle notifiche. MotoLink usa questo accesso solo per trasformare le notifiche di navigazione compatibili in indicazioni per il TFT.",
                onPositive = {
                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            )
            return
        }
        VogeNavService.start(this, profile)
        startInProgress = false
        setRunSelection(RunSelection.START)
        setHeaderStatus("BLE VOGE", profile.displayName, C_GREEN)
        setState("Navigazione BLE attiva", "Apro Google Maps · avvia una rotta", C_GREEN, "BLE")
        AppLog.add("V1.6 BLE PROFILE START: servizio VOGE avviato; Maps può restare in background dopo l'avvio della rotta")
        mainHandler.postDelayed({
            val launch = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
            if (launch != null) {
                runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    .onFailure { NeonDialogs.showInfo(this, "Google Maps", "Non riesco ad aprire Google Maps automaticamente.") }
            } else {
                NeonDialogs.showInfo(this, "Google Maps", "Google Maps non risulta installato sul telefono.")
            }
        }, 1800L)
    }

'''
s = must_replace(s, '    private fun prepareBikeNetworkThenProjection() {\n', helpers + '    private fun prepareBikeNetworkThenProjection() {\n', 'V1.6 helper insertion')
s = must_replace(
    s,
    '        if (profile == null) {\n            requestProjectionChoice()\n            return\n        }\n        if (!profile.hasWifiIdentity()) {\n',
    '        if (profile == null) {\n'
    '            requestProjectionChoice()\n'
    '            return\n'
    '        }\n'
    '        if (profile.resolvedConnectionType() == "BLE") {\n'
    '            startV16BleNavigation(profile)\n'
    '            return\n'
    '        }\n'
    '        if (!profile.hasWifiIdentity()) {\n',
    'BLE start route'
)
s = must_replace(
    s,
    '    private fun stopEverything() {\n        pendingFavoriteLaunchComponent = null\n',
    '    private fun stopEverything() {\n        VogeNavService.stop(this)\n        pendingFavoriteLaunchComponent = null\n',
    'BLE stop'
)
s = must_replace(
    s,
    '            val descriptionLine = profile.description?.trim()?.takeIf { it.isNotBlank() }\n',
    '            val descriptionLine: String? = null\n',
    'hide profile description'
)
s = must_replace(
    s,
    '            append("Formato QR: ${profile.format}\\n")\n',
    '            append("Formato: ${profile.format}\\n")\n            append("Tipo connessione: ${v16ConnectionLabel(profile)}\\n")\n',
    'profile detail connection'
)
s = s.replace(
    'Crea un profilo locale per la moto. Il nome è obbligatorio; descrizione e modello servono a riconoscerla nel Garage. START continuerà a usare la discovery EasyConn standard.',
    'Crea un profilo per la moto scegliendo nome, modello e tipo di connessione. Automatico prova prima BLE VOGE e poi un Hotspot già collegato.'
)
s = s.replace('MotoLink V1.5.1 GUI pronta; guida iniziale attiva; geometria display V15 validata invariata', 'MotoLink V1.6 TEST GUI pronta; profili multi-connessione e VOGE BLE navigation abilitati')

release_notes = r'''    private fun showInstalledReleaseNotes() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.6" }.getOrDefault("1.6")
        val notes = if (version == "1.6") {
            "• START senza profili apre direttamente Nuovo profilo moto.\n" +
                "• Il profilo usa Nome, Modello e Tipo di connessione: Automatico, Hotspot, Qrcode o BLE; il campo Descrizione è stato rimosso.\n" +
                "• Automatico prova il BLE VOGE e, se non disponibile, un Hotspot moto già collegato.\n" +
                "• Nel Garage il Tipo di connessione è modificabile con avviso preventivo; la configurazione precedente resta salvata se la nuova verifica fallisce.\n" +
                "• Aggiunto il trasporto navigazione VOGE sul secondo BLE: heartbeat 0x5A e pacchetti 0x6A–0x6E con coda TX serializzata.\n" +
                "• Google Maps viene aperto per i profili BLE; dopo l'avvio della rotta può restare in background. MotoLink invia al TFT solo manovre e distanze realmente presenti nelle notifiche Maps, senza inventare dati mancanti.\n" +
                "• Il doppio Volume Giù resta disponibile durante il mirroring indipendentemente dalla Modalità tasca.\n" +
                "• EasyConn, H264, clock, Adattamento e geometrie display della V1.5.1 restano invariati per i profili non-BLE."
        } else {
            "Nessuna nota locale disponibile per questa versione."
        }
        NeonDialogs.showInfo(this, "Release v$version", notes)
    }'''
s = replace_between(s, '    private fun showInstalledReleaseNotes() {', '    private fun showAssistantInfo()', release_notes, 'release notes')
p.write_text(s)

# -----------------------------------------------------------------------------
# Guide text: remove the obsolete Hotspot/QR-only first-start instructions.
# -----------------------------------------------------------------------------
p = Path("app/src/main/java/it/motolink/app/TrofeoDashboardView.kt")
s = p.read_text()
s = s.replace(
    'Se non esiste ancora un profilo, dopo la scelta della Modalità tasca MotoLink chiede HOTSPOT oppure QR CODE. In entrambi i casi si apre il normale pannello Nuovo profilo moto: assegna un nome, una descrizione facoltativa e scegli il modello. Con QR CODE lo scanner viene eseguito prima e i dati di collegamento rilevati restano associati al profilo. Dopo SALVA, START continua automaticamente.',
    'Se non esiste ancora un profilo, START apre direttamente Nuovo profilo moto. Inserisci Nome, scegli Modello e Tipo di connessione: Automatico, Hotspot, Qrcode o BLE. Automatico prova a individuare da solo un collegamento compatibile; dopo una verifica riuscita il profilo viene salvato e START continua.'
)
s = s.replace(
    'Usa Aggiungi con QR quando la moto mostra un codice QR compatibile. Dopo la scansione completi lo stesso normale profilo del Garage con nome, descrizione facoltativa e modello.',
    'Nel Garage i profili usano Nome, Modello e Tipo di connessione. Qrcode apre direttamente lo scanner; BLE verifica il secondo collegamento Bluetooth VOGE quando compatibile.'
)
s = s.replace(
    'Al primo START senza profili, MotoLink propone HOTSPOT oppure QR CODE e poi apre questo stesso pannello; dopo il salvataggio la connessione continua automaticamente.',
    'Al primo START senza profili, MotoLink apre direttamente questo stesso pannello; dopo una verifica riuscita la connessione continua automaticamente.'
)
p.write_text(s)

print("MotoLink V1.6 CI patch applied")
