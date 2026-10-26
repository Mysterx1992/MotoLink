# MotoLink — Google Play Data Safety preparazione (DRAFT, non da pubblicare)

_24/10/2026 — solo revisione delle funzioni esistenti. Non contiene dati privati, credenziali o il Master interno._

## Evidenze nel codice

| Funzione | Dati / accessi riscontrati | Trattamento | Stato scheda Play |
| --- | --- | --- | --- |
| Garage, App preferite e impostazioni | nomi e configurazioni dei profili, dati locali QR/connessione, preferenze | conservati localmente; app backup disabilitato | confermare categorie finali |
| Mirroring Android | contenuto dello schermo con MediaProjection, inviato al TFT sulla rete locale | richiesta consenso Android, non spedito al backend IA | dichiarare scopo mediaProjection / FGS |
| Navigazione BLE | dati estratti dalle notifiche del package Google Maps | accesso Notification Listener con consenso Android; navigazione inoltrata al TFT | verificare specifici requisiti di informativa/notifiche |
| Scanner QR | Google Code Scanner / fallback CameraX | servizi Google coinvolti; SDK third-party da valutare | consultare SDK Data Safety |
| Log tecnico | eventi di diagnostica tecnica, possibili metadati connessione | file sul dispositivo, pulizia manuale; condivisione esterna volontaria | formulazione informativa verificata |
| Assistente | testo domande fino a 1200 caratteri, versione app, diagnostica opt-in fino a 6000 | invio HTTPS a Supabase Edge Function; possibile inoltro a Groq | dichiarare contenuti e fornitori; non dichiarare “nessun dato raccolto” |
| Autenticazione IA | sessione Supabase Auth anonima e token | identità/sessione tecnica lato Supabase; token nel telefono | chiarire trattamento identificatori, cancellazione remota, account policy |
| Database applicativo | contatore tecnico aggregato giornaliero | non persistono chat/risposte/log applicativi; non esclude log infrastrutturali e fornitori | distinguere da dati su Supabase Auth |
| Donazioni | apertura del sito PayPal; immagine e pixel PayPal nella schermata Crediti | richieste web a PayPal possono partire alla sola apertura della pagina | valutare rimozione pixel prima della release |

## Decisioni / controlli ancora aperti

- [x] **Email pubblica ufficiale per privacy e assistenza:** motolinkofficial.app@gmail.com (confermata dall'autore; coincide con quella usata per le donazioni PayPal).
- [ ] **Identità legale/pubblica del titolare o publisher** da completare in Play Console in base alla tipologia di account; non dedurre dall'indirizzo email.
- [ ] Decidere se il semplice account tecnico Supabase Auth anonimo rientra nel requisito di cancellazione account di Play; in ogni caso definire risposta alle richieste di eliminazione di identità lato server.
- [ ] Confermare tempi di conservazione / trattamento dati personali presso Supabase (incluse sessioni) e Groq (Customer Data soggetti a Services Agreement / DPA, non solo privacy generica).
- [ ] Confermare le risposte esatte alla schermata **Sicurezza dei dati** nella Play Console sulla base del comportamento dell'AAB definitivo e degli SDK.
- [ ] Controllare Google Code Scanner e ML Kit per telemetria e dichiarazioni SDK.
- [ ] Semplificare PayPal WebView / pixel se possibile in modifica distinta (nessuna variazione al flusso di pagamento senza review).
- [ ] Verificare che la URL pubblica policy scelta sia ammessa dalla console, sempre accessibile senza login e corrisponda a quanto mostra l'app.
- [ ] Le dichiarazioni relative a MediaProjection e foreground service richiedono le informazioni e dimostrazioni specifiche della Play Console.

Questo documento è una checklist di compliance, **non** una dichiarazione di approvazione Google Play.
