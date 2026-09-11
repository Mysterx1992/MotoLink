# Changelog MotoLink

## v1.6 vc16 candidate — 2026-09-11

### Stato

- **Candidata tecnica, non release pubblica.** `main` e la release V1.5.1 restano invariati.
- Deriva dalla V1.6 vc15 (`versionCode 15`) e porta la candidata a `versionCode 16`, mantenendo `versionName 1.6`.

### Rotonde VOGE / Google Maps

- Reverse engineering statico di VOGE Global 1.1.7: `roadFlag=2` identifica il pedaggio/casello; la rotonda OEM usa `roadFlag=4`.
- Confermato che l'encoder MotoLink vc15 possiede già `annularDegrees` e lo scrive nel byte 16 del frame navigazione `0x6A`; vc16 alimenta finalmente quel campo.
- Aggiunto classificatore locale TensorFlow Lite per le icone reali di Google Maps, limitato alla sola determinazione del settore di uscita della rotonda.
- Le classi rotonda 16-settori del modello OpenDash/KTM-Nav-GEN3 vengono convertite nei 12 settori `annularDegrees` VOGE.
- Se il classificatore non raggiunge la confidenza minima, MotoLink non inventa l'uscita: invia `roadFlag=4`, `annularDegrees=0` e registra il fallback nel Log.
- Parser vc15 delle manovre non-rotonda, UUID BLE, heartbeat `0x5A`, frame `0x6A–0x6E`, pacing e recovery BLE restano invariati.

### Provenienza modello

- Modello OpenDash / KTM-Nav-GEN3 MIT, commit pinned `ceafaf1fa899cdbb646051f1f517372eb21759fa`, Git blob `e0104c6dcd104f05ee3fe08eb9922c036d60c9da`.
- Attribuzione completa in `THIRD_PARTY_NOTICES.md`.

## v1.5.1 — 2026-09-08

### Novità e correzioni

- Corretto l’armamento della prossimità quando la richiesta arriva prima che MediaProjection/video siano pronti: la richiesta resta pendente e viene applicata automaticamente appena la sessione è pronta.
- Listener `TYPE_PROXIMITY` e wake-lock di prossimità mantenuti armati fino a STOP/teardown.
- Aggiunto anti auto-lock durante il mirroring per evitare il blocco dovuto al timer di inattività, mantenendo disponibile il tasto Power manuale.
- Migliorata la diagnostica del blocco schermo per distinguere prossimità, anti-auto-lock e possibili blocchi manuali/policy OEM.
- Doppio Volume Giù invariato come comando manuale di blackout.
- EasyConn, H264, clock, percorsi rete e geometrie display già validate restano invariati.

## v1.5 — 2026-09-08

### Novità e correzioni

- Ripristinata la geometria/adattamento predefinito Trofeo già dalla prima installazione, anche con editor Adattamento chiuso.
- Corretto il comportamento dell'Adattamento: regolazioni persistenti, X senza reset e pulsante **OK** delle istruzioni funzionante.
- Recovery TFT resa persistente quando il display cambia temporaneamente modalità, senza un numero fisso di tentativi.
- Eliminato il falso `RECOVERY OK` basato sul testo del Log: il ripristino viene confermato dallo stato reale della sessione/video.
- Corretto il lifecycle del consumer H264 su CFMOTO; collegamento MotoLink V1.5 → CFMOTO verificato fisicamente.
- Ripristinata la configurazione pubblica dell'Assistente MotoLink senza includere secret privati nell'APK.
- Aggiunta la voce **VERSIONE** interattiva nelle Impostazioni con novità e bugfix locali della release installata.
- Guida utente aggiornata per Adattamento, VERSIONE e rientro automatico nel mirroring.
- Release ufficiale `v1.5` pubblicata con `MotoLink_V1.5.apk` firmato con l'identità permanente MotoLink.

## v1.4 — 2026-09-07

### Novità e correzioni

- Percorso di connessione dedicato ai profili CFMOTO con normalizzazione del peer WLAN Direct e fallback rete QR.
- Correzione HOTSPOT per reti moto locali quando Android mantiene la rete mobile come predefinita.
- Miglioramenti CFMOTO a rotazione/display e recovery del video.
- Riaggancio dell'interfaccia a sessioni di mirroring già attive.
- Adattamento persistente: chiusura editor con X e personalizzazione mantenuta fino a ripristino esplicito.
- Core EasyConn/H264 e geometria V15 di riferimento mantenuti invariati.

## v1.0 — 2026-09-04

Prima release pubblica MotoLink con identità Android `it.motolink.app`.

### Funzioni principali

- Mirroring verso display moto compatibili con il flusso MotoLink.
- Garage e profili moto.
- Configurazione tramite QR o profilo locale quando previsto.
- App preferite.
- MediaProjection Android.
- Adattamento manuale del display per profilo.
- Modalità tasca e schermo nero con blocco tocchi.
- Guida iniziale.
- Log tecnico locale persistente fino a **Pulisci**.
- Assistente MotoLink con backend Supabase/Groq.
- Invio volontario del Log filtrato all'Assistente.
- Supporto community WhatsApp.
- Voce intro MotoLink Connect.

### Repository

- Codice sorgente Android pubblico.
- Backend Supabase dell'Assistente incluso senza secret.
- Nessuna chiave privata o keystore incluso nel repository.
