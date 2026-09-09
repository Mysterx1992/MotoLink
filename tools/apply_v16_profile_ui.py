from pathlib import Path

MAIN = Path("app/src/main/java/it/motolink/app/MainActivity.kt")
DIALOG = Path("app/src/main/java/it/motolink/app/V16ConnectionModeDialog.kt")
text = MAIN.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"PROFILE UI PATCH FAIL {label}: expected 1 occurrence, found {count}")
    text = text.replace(old, new, 1)
    print(f"PROFILE UI PATCH OK {label}")


def replace_region(start: str, end: str, replacement: str, label: str) -> None:
    global text
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"PROFILE UI PATCH FAIL {label}: start marker missing")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"PROFILE UI PATCH FAIL {label}: end marker missing")
    text = text[:i] + replacement + text[j:]
    print(f"PROFILE UI PATCH OK {label}")


replace_once(
    "    private var firstStartProfileSetupPending = false\n",
    "    private var firstStartProfileSetupPending = false\n"
    "    private var pendingV16QrProfileName: String? = null\n"
    "    private var pendingV16QrCatalogLabel: String? = null\n"
    "    private var pendingV16QrProfileFromStart = false\n",
    "pending_qr_fields",
)

new_first_start = r'''    private fun showFirstStartConnectionChoice() {
        if (runSelection != RunSelection.START) return
        if (BikeProfileStore.load(this) != null) {
            continueStartAfterConnectionProfile()
            return
        }
        firstStartProfileSetupPending = true
        showV16FirstStartProfileDialog()
    }

    private fun showV16FirstStartProfileDialog() {
        if (runSelection != RunSelection.START) return

        val name = EditText(this).apply {
            hint = "Nome moto (es. La mia Trofeo)"
            setText(pendingV16QrProfileName.orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            isSingleLine = true
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        }

        val connectionOptions = listOf("Automatico", "Hotspot", "QrCode", "BLE")
        val connectionLabel = TextView(this).apply {
            text = "Connessione:"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((4 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }
        val connection = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, connectionOptions)
            setSelection(if (pendingV16QrProfileFromStart) 2 else 0)
        }

        val catalogOptions = bikeCatalogOptions()
        val catalog = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, catalogOptions)
            pendingV16QrCatalogLabel?.let { remembered ->
                val idx = catalogOptions.indexOfFirst { it.equals(remembered, true) }
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
            title = "Nuovo profilo moto",
            message = "Crea il profilo della moto e scegli il tipo di connessione.",
            contentView = box,
            positiveText = "SALVA",
            negativeText = "ANNULLA",
            onPositive = {
                handled = true
                val display = name.text.toString().trim()
                val chosenCatalog = catalogOptions[catalog.selectedItemPosition]
                if (display.isEmpty()) {
                    pendingV16QrProfileName = null
                    pendingV16QrCatalogLabel = chosenCatalog
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Nome moto richiesto",
                        message = "Inserisci un nome per la moto prima di salvare.",
                        onPositive = { showV16FirstStartProfileDialog() }
                    )
                    return@showCustom
                }

                when (connectionOptions[connection.selectedItemPosition]) {
                    "QrCode" -> {
                        pendingV16QrProfileName = display
                        pendingV16QrCatalogLabel = chosenCatalog
                        pendingV16QrProfileFromStart = true
                        firstStartProfileSetupPending = true
                        AppLog.add("PROFILO V1.6: connessione QRCODE selezionata; apro scanner senza secondo dialog profilo")
                        startQrCameraScan()
                    }
                    else -> {
                        val selected = connectionOptions[connection.selectedItemPosition]
                        val connectionType = when (selected) {
                            "Automatico" -> "AUTOMATIC"
                            "Hotspot" -> "HOTSPOT_EASYCONN"
                            "BLE" -> "BLE_NAV"
                            else -> error("Connessione V1.6 non riconosciuta")
                        }
                        val format = when (selected) {
                            "Hotspot" -> "HOTSPOT"
                            "BLE" -> "BLE_NAV"
                            else -> "PROFILE"
                        }
                        val completed = BikeProfile(
                            displayName = display,
                            description = null,
                            catalogLabel = chosenCatalog,
                            format = format,
                            connectionType = connectionType,
                            rawPayload = "PROFILE:${System.currentTimeMillis()}"
                        )
                        if (BikeProfileStore.save(this, completed)) {
                            pendingV16QrProfileName = null
                            pendingV16QrCatalogLabel = null
                            pendingV16QrProfileFromStart = false
                            firstStartProfileSetupPending = false
                            refreshBikeProfiles()
                            setHeaderStatus("Profilo", completed.displayName, C_GREEN)
                            AppLog.add("PROFILO V1.6: connessione $selected salvata nel profilo")
                            continueStartAfterConnectionProfile()
                        } else {
                            NeonDialogs.showInfo(
                                activity = this,
                                title = "Profilo non salvato",
                                message = "MotoLink non è riuscita a salvare il profilo sul dispositivo.",
                                onPositive = { abortFirstStartProfileSetup("salvataggio profilo fallito") }
                            )
                        }
                    }
                }
            },
            onNegative = {
                handled = true
                pendingV16QrProfileName = null
                pendingV16QrCatalogLabel = null
                pendingV16QrProfileFromStart = false
                abortFirstStartProfileSetup("creazione profilo annullata")
            }
        )
        dialog.setOnDismissListener {
            mainHandler.post {
                if (!handled) {
                    pendingV16QrProfileName = null
                    pendingV16QrCatalogLabel = null
                    pendingV16QrProfileFromStart = false
                    abortFirstStartProfileSetup("creazione profilo chiusa")
                }
            }
        }
    }

'''
replace_region(
    "    private fun showFirstStartConnectionChoice() {",
    "    private fun showV16ConnectionTypeChoice(profile: BikeProfile) {",
    new_first_start,
    "first_start_profile_with_connection",
)

replace_region(
    "    private fun showV16ConnectionTypeChoice(profile: BikeProfile) {",
    "    private fun continueStartAfterConnectionProfile() {",
    "",
    "remove_separate_connection_dialogs",
)

replace_once(
    "            BikeConnectionType.UNSET -> showV16ConnectionTypeChoice(profile)\n",
    "            BikeConnectionType.UNSET -> {\n"
    "                val index = BikeProfileStore.activeIndex(this)\n"
    "                BikeProfileStore.updateConnection(this, index, \"HOTSPOT_EASYCONN\", clearBle = true)\n"
    "                AppLog.add(\"PROFILO V1.6: profilo legacy senza connessione -> HOTSPOT/EasyConn\")\n"
    "                continueV16HotspotStart()\n"
    "            }\n",
    "legacy_unset_fallback",
)

qr_marker = "        // Never log the payload, SSID password or proprietary token.\n"
qr_branch = r'''        if (pendingV16QrProfileFromStart && firstStartProfileSetupPending && runSelection == RunSelection.START) {
            val display = pendingV16QrProfileName?.trim().orEmpty()
            val chosenCatalog = pendingV16QrCatalogLabel?.trim().orEmpty()
            if (display.isEmpty() || chosenCatalog.isEmpty()) {
                AppLog.add("PROFILO V1.6 QRCODE: dati profilo temporanei mancanti; ritorno alla creazione profilo")
                pendingV16QrProfileFromStart = false
                showFirstStartConnectionChoice()
                return
            }
            val completed = parsed.copy(
                displayName = display,
                description = null,
                catalogLabel = chosenCatalog,
                connectionType = "HOTSPOT_EASYCONN"
            )
            if (BikeProfileStore.save(this, completed)) {
                pendingV16QrProfileName = null
                pendingV16QrCatalogLabel = null
                pendingV16QrProfileFromStart = false
                firstStartProfileSetupPending = false
                lastResolved = null
                refreshBikeProfiles()
                setHeaderStatus("Avvio", completed.displayName, C_AMBER)
                setState("Profilo QR salvato", "Continuo automaticamente con la connessione", C_AMBER, "QR")
                AppLog.add("PROFILO V1.6 QRCODE: QR associato al profilo senza secondo dialog")
                continueStartAfterConnectionProfile()
            } else {
                AppLog.add("PROFILO V1.6 QRCODE: salvataggio profilo fallito")
                NeonDialogs.showInfo(
                    activity = this,
                    title = "Profilo non salvato",
                    message = "MotoLink non è riuscita a salvare il profilo QR sul dispositivo.",
                    onPositive = { showFirstStartConnectionChoice() }
                )
            }
            return
        }

'''
replace_once(qr_marker, qr_branch + qr_marker, "qr_merge_without_second_profile_dialog")

MAIN.write_text(text, encoding="utf-8")
if DIALOG.exists():
    DIALOG.unlink()
    print("PROFILE UI PATCH OK remove_V16ConnectionModeDialog_source")

final = MAIN.read_text(encoding="utf-8")
checks_absent = [
    "V16ConnectionModeDialog.show",
    "private fun showV16ConnectionTypeChoice",
    "private fun showV16HotspotQrChoice",
    "Subito dopo sceglierai Automatica, Hotspot / QR oppure BLE.",
]
for needle in checks_absent:
    if needle in final:
        raise SystemExit(f"PROFILE UI VERIFY FAIL still present: {needle}")

checks_present = [
    'text = "Connessione:"',
    'listOf("Automatico", "Hotspot", "QrCode", "BLE")',
    'connectionType = "HOTSPOT_EASYCONN"',
    'connectionType = "BLE_NAV"',
    'connectionType = "AUTOMATIC"',
    'PROFILO V1.6 QRCODE: QR associato al profilo senza secondo dialog',
]
for needle in checks_present:
    if needle not in final:
        raise SystemExit(f"PROFILE UI VERIFY FAIL missing: {needle}")

print("V1.6 PROFILE CONNECTION UI PATCH COMPLETE")
