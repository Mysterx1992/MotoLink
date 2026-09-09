from pathlib import Path

MAIN = Path("app/src/main/java/it/motolink/app/MainActivity.kt")
STORE = Path("app/src/main/java/it/motolink/app/BikeProfileStore.kt")
text = MAIN.read_text(encoding="utf-8")
store = STORE.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"GARAGE UI PATCH FAIL {label}: expected 1 occurrence, found {count}")
    text = text.replace(old, new, 1)
    print(f"GARAGE UI PATCH OK {label}")


def replace_region(start: str, end: str, replacement: str, label: str) -> None:
    global text
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"GARAGE UI PATCH FAIL {label}: start marker missing")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"GARAGE UI PATCH FAIL {label}: end marker missing")
    text = text[:i] + replacement + text[j:]
    print(f"GARAGE UI PATCH OK {label}")


def store_replace_once(old: str, new: str, label: str) -> None:
    global store
    count = store.count(old)
    if count != 1:
        raise SystemExit(f"GARAGE STORE PATCH FAIL {label}: expected 1 occurrence, found {count}")
    store = store.replace(old, new, 1)
    print(f"GARAGE STORE PATCH OK {label}")


# Garage QR selection needs a small temporary state so the user does not have to
# re-enter name/model after choosing QrCode from the profile form.
replace_once(
    "    private var pendingV16QrProfileFromStart = false\n",
    "    private var pendingV16QrProfileFromStart = false\n"
    "    private var pendingV16GarageQrProfileName: String? = null\n"
    "    private var pendingV16GarageQrCatalogLabel: String? = null\n"
    "    private var pendingV16GarageQrEditIndex = -1\n",
    "garage_qr_pending_fields",
)

# Stable in-place profile replacement for an edited Garage entry. This preserves
# profileId/savedAtMs, therefore per-bike adaptation lineage remains attached.
store_replace_once(
    "    fun clearPhoto(context: Context, index: Int): Boolean {\n",
    r'''    fun replaceAt(context: Context, index: Int, profile: BikeProfile): Boolean {
        if (profile.displayName.trim().isEmpty()) return false
        val list = loadAll(context).toMutableList()
        val old = list.getOrNull(index) ?: return false
        list[index] = profile.copy(profileId = old.profileId, savedAtMs = old.savedAtMs)
        if (!saveAll(context, list)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACTIVE_INDEX, index)
            .remove(KEY_ACTIVE)
            .apply()
        return true
    }

    fun clearPhoto(context: Context, index: Int): Boolean {
''',
    "replace_profile_at_index",
)

# When QR scanning is cancelled from a Garage form, return to that same Garage
# operation rather than falling through to the first-start flow.
replace_once(
    '''            .addOnCanceledListener {
                AppLog.add("QR PAIRING: scansione annullata dall'utente")
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                }
            }
''',
    '''            .addOnCanceledListener {
                AppLog.add("QR PAIRING: scansione annullata dall'utente")
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                } else if (pendingV16GarageQrProfileName != null) {
                    val editIndex = pendingV16GarageQrEditIndex
                    pendingV16GarageQrProfileName = null
                    pendingV16GarageQrCatalogLabel = null
                    pendingV16GarageQrEditIndex = -1
                    if (editIndex >= 0) showBikeProfileMenu(editIndex) else showLocalBikeProfileDialog()
                }
            }
''',
    "garage_qr_cancel_return",
)

replace_once(
    '''    private fun showQrErrorForCurrentFlow(message: String) {
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
''',
    '''    private fun showQrErrorForCurrentFlow(message: String) {
        if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
            NeonDialogs.showInfo(
                activity = this,
                title = "Pairing QR",
                message = message,
                onPositive = { showFirstStartConnectionChoice() }
            )
        } else if (pendingV16GarageQrProfileName != null) {
            val editIndex = pendingV16GarageQrEditIndex
            pendingV16GarageQrProfileName = null
            pendingV16GarageQrCatalogLabel = null
            pendingV16GarageQrEditIndex = -1
            NeonDialogs.showInfo(
                activity = this,
                title = "Pairing QR",
                message = message,
                onPositive = {
                    if (editIndex >= 0) showBikeProfileMenu(editIndex) else showLocalBikeProfileDialog()
                }
            )
        } else {
            showQrError(message)
        }
    }
''',
    "garage_qr_error_return",
)

# The first-start patch already injected its own QR branch immediately before this
# marker. Add the Garage branch after it and before normal QR handling.
qr_marker = "        // Never log the payload, SSID password or proprietary token.\n"
garage_qr_branch = r'''        if (pendingV16GarageQrProfileName != null) {
            val display = pendingV16GarageQrProfileName?.trim().orEmpty()
            val chosenCatalog = pendingV16GarageQrCatalogLabel?.trim().orEmpty()
            val editIndex = pendingV16GarageQrEditIndex
            pendingV16GarageQrProfileName = null
            pendingV16GarageQrCatalogLabel = null
            pendingV16GarageQrEditIndex = -1

            if (display.isEmpty() || chosenCatalog.isEmpty()) {
                AppLog.add("GARAGE V1.6 QRCODE: stato temporaneo incompleto")
                if (editIndex >= 0) showBikeProfileMenu(editIndex) else showLocalBikeProfileDialog()
                return
            }

            val old = if (editIndex >= 0) BikeProfileStore.loadAll(this).getOrNull(editIndex) else null
            val completed = parsed.copy(
                displayName = display,
                description = null,
                catalogLabel = chosenCatalog,
                photoUri = old?.photoUri,
                connectionType = "HOTSPOT_EASYCONN"
            )
            val saved = if (editIndex >= 0) {
                BikeProfileStore.replaceAt(this, editIndex, completed)
            } else {
                BikeProfileStore.save(this, completed)
            }
            if (saved) {
                lastResolved = null
                refreshBikeProfiles()
                setHeaderStatus("Pronto", completed.displayName, C_GREEN)
                setState("Moto configurata", "Profilo QrCode aggiornato", C_GREEN, "QR")
                AppLog.add("GARAGE V1.6 QRCODE: profilo salvato senza secondo modulo")
            } else {
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Profilo non salvato",
                    message = "MotoLink non è riuscita a salvare il profilo QR sul dispositivo."
                )
            }
            return
        }

'''
replace_once(qr_marker, garage_qr_branch + qr_marker, "garage_qr_merge_without_second_form")

new_creation = r'''    private fun v16GarageConnectionOptions(): List<String> =
        listOf("Automatico", "Hotspot", "QrCode", "BLE")

    private fun v16GarageHasQrIdentity(profile: BikeProfile): Boolean {
        val format = profile.format.trim().uppercase()
        val raw = profile.rawPayload.trim().uppercase()
        if (format == "HOTSPOT" || format.contains("MANUAL")) return false
        if (raw.startsWith("HOTSPOT:") || raw.startsWith("LOCAL:") || raw.startsWith("PROFILE:") || raw.startsWith("BLE:")) return false
        return profile.endpointLabel() != null ||
            !profile.machineId.isNullOrBlank() ||
            !profile.pairingToken.isNullOrBlank() ||
            format.contains("QR") ||
            format.contains("CARBIT") ||
            format.contains("EASYCONN") ||
            format.startsWith("CFMOTO_") ||
            raw.isNotBlank()
    }

    private fun v16GarageConnectionIndex(profile: BikeProfile): Int {
        val type = profile.connectionType.orEmpty().uppercase()
        val format = profile.format.trim().uppercase()
        return when {
            type == "BLE_NAV" -> 3
            type == "AUTOMATIC" -> 0
            format == "HOTSPOT" || format.contains("MANUAL") -> 1
            v16GarageHasQrIdentity(profile) -> 2
            type == "HOTSPOT_EASYCONN" -> 1
            else -> 0
        }
    }

    private fun v16GarageConnectionType(label: String): String = when (label) {
        "Automatico" -> "AUTOMATIC"
        "Hotspot", "QrCode" -> "HOTSPOT_EASYCONN"
        "BLE" -> "BLE_NAV"
        else -> error("Connessione Garage V1.6 non riconosciuta")
    }

    private fun v16GarageFormat(profile: BikeProfile, label: String): String = when (label) {
        "Hotspot" -> "HOTSPOT"
        "BLE" -> "BLE_NAV"
        else -> profile.format
    }

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
        val connectionOptions = v16GarageConnectionOptions()
        val connectionLabel = TextView(this).apply {
            text = "Connessione:"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((4 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        val connection = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, connectionOptions)
            setSelection(v16GarageConnectionIndex(baseProfile))
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
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            })
            addView(connectionLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(connection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            })
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
                        onPositive = { showBikeProfileCreationDialog(baseProfile, title, message, onSaved, onCancel) }
                    )
                    return@showCustom
                }
                val chosenCatalog = catalogOptions[catalog.selectedItemPosition]
                val selectedConnection = connectionOptions[connection.selectedItemPosition]

                if (selectedConnection == "QrCode" && !v16GarageHasQrIdentity(baseProfile)) {
                    pendingV16GarageQrProfileName = display
                    pendingV16GarageQrCatalogLabel = chosenCatalog
                    pendingV16GarageQrEditIndex = -1
                    AppLog.add("GARAGE V1.6: QrCode selezionato nel nuovo profilo; apro scanner")
                    startQrCameraScan()
                    return@showCustom
                }

                val completed = baseProfile.copy(
                    displayName = display,
                    description = null,
                    catalogLabel = chosenCatalog,
                    format = v16GarageFormat(baseProfile, selectedConnection),
                    connectionType = v16GarageConnectionType(selectedConnection)
                )
                if (BikeProfileStore.save(this, completed)) {
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

'''
replace_region(
    "    private fun showBikeProfileCreationDialog(\n",
    "    private fun showLocalBikeProfileDialog() {",
    new_creation,
    "garage_new_profile_form",
)

replace_once(
    '            message = "Crea un profilo locale per la moto. Il nome è obbligatorio; descrizione e modello servono a riconoscerla nel Garage. START continuerà a usare la discovery EasyConn standard.",\n',
    '            message = "Crea un profilo locale per la moto. Il nome è obbligatorio; scegli connessione e modello. START userà la connessione salvata nel profilo.",\n',
    "garage_local_profile_message",
)

# Existing descriptions are no longer part of the V1.6 Garage UI. Keep legacy data
# readable internally but stop rendering it on the Garage card.
replace_once(
    "            val descriptionLine = profile.description?.trim()?.takeIf { it.isNotBlank() }\n",
    "            val descriptionLine: String? = null\n",
    "garage_hide_legacy_description_line",
)

new_edit = r'''    private fun showBikeProfileMenu(index: Int) {
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
        val connectionOptions = v16GarageConnectionOptions()
        val connectionLabel = TextView(this).apply {
            text = "Connessione:"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((4 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        val connection = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, connectionOptions)
            setSelection(v16GarageConnectionIndex(profile))
        }
        val catalog = Spinner(this)
        val catalogOptions = bikeCatalogOptions()
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
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
            addView(name, lp)
            addView(connectionLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(connection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            })
            addView(catalog, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply {
                bottomMargin = (14 * resources.displayMetrics.density).toInt()
            })
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

        fun persistGarageEdit(openExtraMenu: Boolean) {
            val editedName = name.text.toString().trim()
            if (editedName.isEmpty()) {
                showQrError("Il nome della moto è obbligatorio. Inserisci un nome prima di salvare il profilo.")
                return
            }
            val chosenCatalog = catalogOptions[catalog.selectedItemPosition]
            val selectedConnection = connectionOptions[connection.selectedItemPosition]

            if (selectedConnection == "QrCode" && !v16GarageHasQrIdentity(profile)) {
                pendingV16GarageQrProfileName = editedName
                pendingV16GarageQrCatalogLabel = chosenCatalog
                pendingV16GarageQrEditIndex = index
                editProfileDialog?.dismiss()
                AppLog.add("GARAGE V1.6: QrCode selezionato in Modifica profilo; apro scanner")
                startQrCameraScan()
                return
            }

            val updated = profile.copy(
                displayName = editedName,
                description = null,
                catalogLabel = chosenCatalog,
                format = v16GarageFormat(profile, selectedConnection),
                connectionType = v16GarageConnectionType(selectedConnection)
            )
            if (!BikeProfileStore.replaceAt(this, index, updated)) {
                showQrError("Impossibile salvare il profilo.")
                return
            }
            lastResolved = null
            refreshBikeProfiles()
            dashboard.updateAdaptation(MirrorAdaptationConfig.load(this).enabled, MirrorAdaptationConfig.dashboardLabel(this))
            AppLog.add("GARAGE V1.6: profilo aggiornato; connessione=$selectedConnection")
            if (openExtraMenu) showBikeProfileExtraMenu(index)
        }

        editProfileDialog = NeonDialogs.showCustom(
            activity = this,
            title = "Modifica profilo",
            message = "Il nome della moto è obbligatorio. Connessione e modello sono modificabili; per usare la foto della tua moto scegli FOTO / ALTRO. Le regolazioni di Adattamento restano associate a questo profilo anche se lo rinomini.",
            contentView = box,
            positiveText = "SALVA",
            negativeText = "FOTO / ALTRO",
            onPositive = { persistGarageEdit(false) },
            onNegative = { persistGarageEdit(true) }
        )
    }

'''
replace_region(
    "    private fun showBikeProfileMenu(index: Int) {",
    "    private fun showBikeProfileExtraMenu(index: Int) {",
    new_edit,
    "garage_edit_profile_form",
)

MAIN.write_text(text, encoding="utf-8")
STORE.write_text(store, encoding="utf-8")

final = MAIN.read_text(encoding="utf-8")
store_final = STORE.read_text(encoding="utf-8")

required = [
    'private fun v16GarageConnectionOptions()',
    'listOf("Automatico", "Hotspot", "QrCode", "BLE")',
    'text = "Connessione:"',
    'GARAGE V1.6 QRCODE: profilo salvato senza secondo modulo',
    'message = "Il nome della moto è obbligatorio. Connessione e modello sono modificabili;',
    'val descriptionLine: String? = null',
]
for needle in required:
    if needle not in final:
        raise SystemExit(f"GARAGE UI VERIFY FAIL missing: {needle}")

for forbidden in [
    'hint = "Descrizione (facoltativa)"',
    'Descrizione e modello sono modificabili',
    'descrizione e modello servono a riconoscerla nel Garage',
]:
    if forbidden in final:
        raise SystemExit(f"GARAGE UI VERIFY FAIL still present: {forbidden}")

if 'fun replaceAt(context: Context, index: Int, profile: BikeProfile): Boolean' not in store_final:
    raise SystemExit("GARAGE STORE VERIFY FAIL replaceAt missing")

print("V1.6 GARAGE PROFILE UI PATCH COMPLETE")
