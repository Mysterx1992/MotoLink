# Changelog MotoLink

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
