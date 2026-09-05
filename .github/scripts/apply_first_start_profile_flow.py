from pathlib import Path

MAIN = Path("app/src/main/java/it/motolink/app/MainActivity.kt")
DASH = Path("app/src/main/java/it/motolink/app/TrofeoDashboardView.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        print(f"[skip] {label}: already applied")
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    print(f"[apply] {label}")
    return text.replace(old, new, 1)


main = MAIN.read_text(encoding="utf-8")

main = replace_once(
    main,
    '''    private var lockPlaceholderActive = false
    private var lockRestartPending = false
    private var pendingProfileEditIndex = -1
''',
    '''    private var lockPlaceholderActive = false
    private var lockRestartPending = false
    private var pendingProfileEditIndex = -1
    private var firstStartProfileSetupPending = false
''',
    "first-start state field",
)

main = replace_once(
    main,
    '''        pendingBikeWifiPermission = false
        qrTransportFallbackAttempted = false
        setRunSelection(RunSelection.START)
''',
    '''        pendingBikeWifiPermission = false
        qrTransportFallbackAttempted = false
        firstStartProfileSetupPending = false
        setRunSelection(RunSelection.START)
''',
    "reset first-start state on START",
)

main = replace_once(
    main,
    '''        waitingForOverlayPermission = false
        pocketModeForPendingStart = false
        setRunSelection(RunSelection.STOP)
''',
    '''        waitingForOverlayPermission = false
        pocketModeForPendingStart = false
        firstStartProfileSetupPending = false
        setRunSelection(RunSelection.STOP)
''',
    "reset first-start state on STOP",
)

main = replace_once(
    main,
    '''    private fun continueStartAfterPocketModeChoice() {
        if (pocketModeForPendingStart) {
            ensureBackgroundGatePermissionThenProjection()
        } else {
            prepareBikeNetworkThenProjection()
        }
    }

    private fun ensureBackgroundGatePermissionThenProjection() {
''',
    '''    private fun continueStartAfterPocketModeChoice() {
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
            message = "Non hai ancora un profilo moto salvato.\\n\\nScegli come colleghi questa moto. In entrambi i casi MotoLink salva il profilo nel Garage, così ai prossimi START non te lo chiederà più.",
            contentView = null,
            positiveText = "QR CODE",
            negativeText = "HOTSPOT",
            onPositive = {
                handled = true
                AppLog.add("PRIMO START: scelta QR CODE; apro lo scanner e attendo il salvataggio del profilo")
                startQrCameraScan()
            },
            onNegative = {
                handled = true
                AppLog.add("PRIMO START: scelta HOTSPOT; richiedo il nome moto prima di continuare")
                showFirstStartHotspotProfileDialog()
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

    private fun showFirstStartHotspotProfileDialog() {
        if (runSelection != RunSelection.START) return
        firstStartProfileSetupPending = true
        val requiredName = EditText(this).apply {
            hint = "Nome moto obbligatorio"
            setTextColor(Color.WHITE)
            setHintTextColor(color(C_MUTED))
            isSingleLine = true
            background = NeonDialogs.rounded("#07120B", "#2A7A28", 1, 16, this@MainActivity)
            setPadding((14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt(), 0)
        }
        var handled = false
        val dialog = NeonDialogs.showCustom(
            activity = this,
            title = "Profilo Hotspot",
            message = "Dai un nome alla moto. MotoLink salverà un profilo locale nel Garage e continuerà automaticamente con il normale collegamento Hotspot / EasyConn.",
            contentView = requiredName,
            positiveText = "SALVA E CONTINUA",
            negativeText = "INDIETRO",
            onPositive = {
                handled = true
                val chosenName = requiredName.text.toString().trim()
                if (chosenName.isEmpty()) {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Nome moto richiesto",
                        message = "Inserisci un nome per la moto prima di continuare.",
                        onPositive = { showFirstStartHotspotProfileDialog() }
                    )
                    return@showCustom
                }
                val profile = BikeProfile(
                    displayName = chosenName,
                    format = "HOTSPOT",
                    rawPayload = "HOTSPOT:${System.currentTimeMillis()}"
                )
                if (BikeProfileStore.save(this, profile)) {
                    firstStartProfileSetupPending = false
                    lastResolved = null
                    refreshBikeProfiles()
                    setHeaderStatus("Avvio", profile.displayName, C_AMBER)
                    setState("Profilo salvato", "Continuo con il collegamento Hotspot", C_AMBER, "LAN")
                    AppLog.add("PRIMO START: profilo HOTSPOT salvato localmente; continuo automaticamente")
                    continueStartAfterProfileReady()
                } else {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Profilo non salvato",
                        message = "MotoLink non è riuscita a salvare il profilo. Riprova oppure scegli QR CODE.",
                        onPositive = { showFirstStartConnectionChoice() }
                    )
                }
            },
            onNegative = {
                handled = true
                showFirstStartConnectionChoice()
            }
        )
        dialog.setOnDismissListener {
            mainHandler.post {
                if (!handled && firstStartProfileSetupPending &&
                    BikeProfileStore.load(this) == null && runSelection == RunSelection.START
                ) {
                    abortFirstStartProfileSetup("profilo Hotspot chiuso")
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
        AppLog.add("PRIMO START annullato: $reason")
    }

    private fun ensureBackgroundGatePermissionThenProjection() {
''',
    "first-start Hotspot/QR gate",
)

main = replace_once(
    main,
    '''    private fun startQrCameraScan() {
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
                    showQrError("Il QR non contiene dati leggibili.")
                } else {
                    handleQrPayload(raw)
                }
            }
            .addOnCanceledListener { AppLog.add("QR PAIRING: scansione annullata dall'utente") }
            .addOnFailureListener { e ->
                AppLog.add("QR PAIRING scanner fallito: ${e.javaClass.simpleName}")
                showQrError("Scanner QR non disponibile. Puoi usare “Importa QR da immagine”.")
            }
    }
''',
    '''    private fun startQrCameraScan() {
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
                AppLog.add("QR PAIRING scanner fallito: ${e.javaClass.simpleName}")
                showQrErrorForCurrentFlow("Scanner QR non disponibile. Puoi riprovare oppure scegliere HOTSPOT.")
            }
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
''',
    "QR scanner first-start continuation",
)

main = replace_once(
    main,
    '''        val profile = try {
            QrPairing.parse(raw)
        } catch (_: Throwable) {
            showQrError("Il QR è vuoto o non valido.")
            return
        }
''',
    '''        val profile = try {
            QrPairing.parse(raw)
        } catch (_: Throwable) {
            showQrErrorForCurrentFlow("Il QR è vuoto o non valido.")
            return
        }
''',
    "QR parse error first-start routing",
)

old_qr_dialog = '''        NeonDialogs.showCustom(
            activity = this,
            title = "QR moto rilevato",
            message = details + "\\n\\nDai un nome a questa moto: è obbligatorio per salvarla nel Garage e per mantenere associate le sue regolazioni di Adattamento.",
            contentView = requiredName,
            positiveText = "SALVA MOTO",
            negativeText = "ANNULLA",
            onPositive = {
                val chosenName = requiredName.text.toString().trim()
                if (chosenName.isEmpty()) {
                    showQrError("Inserisci un nome per la moto. Il nome è obbligatorio per salvare il profilo.")
                    return@showCustom
                }
                val namedProfile = profile.copy(displayName = chosenName)
                if (BikeProfileStore.save(this, namedProfile)) {
                    lastResolved = null
                    setHeaderStatus("Pronto", namedProfile.displayName, C_GREEN)
                    setState("Moto configurata", "Da ora basta premere START", C_GREEN, "QR")
                    AppLog.add("QR PAIRING: profilo moto salvato localmente con nome utente; payload non scritto nel Log")
                    refreshBikeProfiles()
                } else {
                    showQrError("Garage pieno (massimo 3 profili) oppure profilo non salvabile. Elimina una moto e riprova.")
                }
            }
        )
'''
new_qr_dialog = '''        var qrProfileDialogHandled = false
        val qrProfileDialog = NeonDialogs.showCustom(
            activity = this,
            title = "QR moto rilevato",
            message = details + "\\n\\nDai un nome a questa moto: è obbligatorio per salvarla nel Garage e per mantenere associate le sue regolazioni di Adattamento.",
            contentView = requiredName,
            positiveText = "SALVA MOTO",
            negativeText = "ANNULLA",
            onPositive = {
                qrProfileDialogHandled = true
                val chosenName = requiredName.text.toString().trim()
                if (chosenName.isEmpty()) {
                    if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                        NeonDialogs.showInfo(
                            activity = this,
                            title = "Nome moto richiesto",
                            message = "Inserisci un nome per la moto prima di continuare.",
                            onPositive = { handleQrPayload(raw) }
                        )
                    } else {
                        showQrError("Inserisci un nome per la moto. Il nome è obbligatorio per salvare il profilo.")
                    }
                    return@showCustom
                }
                val namedProfile = profile.copy(displayName = chosenName)
                if (BikeProfileStore.save(this, namedProfile)) {
                    lastResolved = null
                    AppLog.add("QR PAIRING: profilo moto salvato localmente con nome utente; payload non scritto nel Log")
                    refreshBikeProfiles()
                    if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                        firstStartProfileSetupPending = false
                        setHeaderStatus("Avvio", namedProfile.displayName, C_AMBER)
                        setState("Profilo QR salvato", "Continuo automaticamente con la connessione", C_AMBER, "QR")
                        AppLog.add("PRIMO START: profilo QR salvato; continuo automaticamente")
                        continueStartAfterProfileReady()
                    } else {
                        setHeaderStatus("Pronto", namedProfile.displayName, C_GREEN)
                        setState("Moto configurata", "Da ora basta premere START", C_GREEN, "QR")
                    }
                } else {
                    if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                        NeonDialogs.showInfo(
                            activity = this,
                            title = "Profilo non salvato",
                            message = "Garage pieno oppure profilo non salvabile. Libera un profilo o scegli HOTSPOT.",
                            onPositive = { showFirstStartConnectionChoice() }
                        )
                    } else {
                        showQrError("Garage pieno (massimo 3 profili) oppure profilo non salvabile. Elimina una moto e riprova.")
                    }
                }
            },
            onNegative = {
                qrProfileDialogHandled = true
                if (firstStartProfileSetupPending && runSelection == RunSelection.START) {
                    showFirstStartConnectionChoice()
                }
            }
        )
        qrProfileDialog.setOnDismissListener {
            mainHandler.post {
                if (!qrProfileDialogHandled && firstStartProfileSetupPending &&
                    BikeProfileStore.load(this) == null && runSelection == RunSelection.START
                ) {
                    showFirstStartConnectionChoice()
                }
            }
        }
'''
main = replace_once(main, old_qr_dialog, new_qr_dialog, "QR profile save continues START")

MAIN.write_text(main, encoding="utf-8")


dash = DASH.read_text(encoding="utf-8")

dash = replace_once(
    dash,
    '''            body = "START avvia la ricerca e il mirroring verso la moto.\\n\\nIl colore del pulsante aiuta a riconoscere la fase corrente: pronto, ricerca o riconnessione, collegato.\\n\\nSTOP termina la sessione e interrompe il collegamento gestito da MotoLink.",
''',
    '''            body = "START avvia la ricerca e il mirroring verso la moto.\\n\\nPRIMO COLLEGAMENTO\\nSe non esiste ancora un profilo, dopo la scelta della Modalità tasca MotoLink chiede HOTSPOT oppure QR CODE. HOTSPOT richiede il nome della moto, salva il profilo nel Garage e continua con il collegamento normale. QR CODE apre lo scanner, salva il profilo dopo che hai assegnato un nome alla moto e continua automaticamente.\\n\\nDagli START successivi, finché il profilo resta salvato, questa scelta non viene più richiesta.\\n\\nIl colore del pulsante aiuta a riconoscere la fase corrente: pronto, ricerca o riconnessione, collegato.\\n\\nSTOP termina la sessione e interrompe il collegamento gestito da MotoLink.",
''',
    "guide HOME first connection",
)

dash = replace_once(
    dash,
    '''            body = "Usa Aggiungi con QR quando la moto mostra un codice QR compatibile.\\n\\nSe il modello non utilizza il QR, puoi creare un profilo locale con la procedura prevista da MotoLink.\\n\\nPrima del salvataggio assegna sempre un nome alla moto. L’immagine del profilo dipende dal modello selezionato, non dal nome personalizzato.",
''',
    '''            body = "Usa Aggiungi con QR quando la moto mostra un codice QR compatibile.\\n\\nSe non hai ancora nessun profilo, anche il primo START può crearne uno: scegli QR CODE per scansionare il codice oppure HOTSPOT per salvare un profilo locale e usare il collegamento normale. In entrambi i casi devi assegnare un nome alla moto e, dopo il salvataggio, MotoLink continua automaticamente.\\n\\nPuoi sempre gestire, rinominare o eliminare i profili dal GARAGE. L’immagine del profilo dipende dal modello selezionato, non dal nome personalizzato.",
''',
    "guide GARAGE first-start profile",
)

dash = replace_once(
    dash,
    '''            body = "ATTIVAZIONE\\nADATTAMENTO è disattivato al primo utilizzo. Quando lo attivi, rimane attivo finché non lo disattivi.\\n\\nREGOLAZIONE DEI BORDI\\n↑ regola il bordo superiore.\\n↓ regola il bordo inferiore.\\n← regola il bordo sinistro.\\n→ regola il bordo destro.\\nOgni pressione modifica il bordo di 5 px.\\n\\nDIMENSIONE\\n+ allarga l’area visibile.\\n− restringe l’area visibile.\\n\\nPANNELLO ADATTAMENTO\\nTrascina il titolo per spostare il pannello.\\nⓘ apre le istruzioni.\\n× chiude il pannello e disattiva ADATTAMENTO.\\n\\nPROFILI MOTO\\nOgni moto conserva le proprie regolazioni. Un profilo mai regolato utilizza la base automatica predefinita.",
''',
    '''            body = "ATTIVAZIONE\\nADATTAMENTO è disattivato al primo utilizzo. Quando lo attivi, rimane attivo finché non lo disattivi dalle IMPOSTAZIONI.\\n\\nREGOLAZIONE DEI BORDI\\n↑ regola il bordo superiore.\\n↓ regola il bordo inferiore.\\n← regola il bordo sinistro.\\n→ regola il bordo destro.\\nOgni pressione modifica il bordo di 5 px.\\n\\nDIMENSIONE\\n+ allarga l’area visibile.\\n− restringe l’area visibile.\\n\\nPANNELLO ADATTAMENTO\\nTrascina il titolo per spostare il pannello.\\nⓘ apre le istruzioni.\\n× chiude soltanto il pannello: la regolazione personalizzata resta salvata e continua a essere applicata.\\n↺ Ripristina torna ai valori iniziali dell’app solo per l’orientamento che stai modificando e richiede due conferme prima di eseguire il ripristino.\\n\\nPROFILI MOTO\\nOgni moto conserva le proprie regolazioni. Anche quando ADATTAMENTO è OFF i valori personali restano salvati; riattivandolo tornano disponibili. Un profilo mai regolato utilizza la base automatica predefinita.",
''',
    "guide adaptation persistence and reset",
)

DASH.write_text(dash, encoding="utf-8")
print("Patch completed successfully")
