# Changelog MotoLink

## v1.5 — 2026-09-07

### Novità e correzioni

- Ripristinata sulla Trofeo la calibrazione/adattamento predefinito validato già dalla prima installazione, indipendentemente dall'apertura dell'editor.
- Corretto il comportamento dell'editor Adattamento: personalizzazione persistente, chiusura con X senza reset e pulsante OK delle istruzioni funzionante.
- Ripristinata la permanenza della sessione quando il TFT cambia modalità: MotoLink continua ad attendere il display e riprende il mirroring quando torna disponibile.
- Eliminato il falso successo del recovery basato sulla stringa `H264 FIRST FRAME`; il ripristino viene confermato dallo stato reale della sessione/video.
- Corretto su CFMOTO il lifecycle del consumer H264 che poteva lasciare P2P/EasyConn collegati ma senza flusso video.
- Collegamento CFMOTO con MotoLink V1.5 verificato fisicamente dopo la correzione.
- Ripristinata la configurazione pubblica dell'Assistente MotoLink, mantenendo i secret privati esclusivamente lato server.
- Aggiunta nelle Impostazioni la voce VERSIONE interattiva con novità e bugfix locali della release installata.
- Guida aggiornata per la nuova funzione VERSIONE.
- Mantenuti separati e invariati i componenti/protocolli già validati che non richiedevano correzioni.

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

- Codice sorgente Android pubblico e consultabile.
- MotoLink non è open source; tutti i diritti sono riservati e valgono i termini in `LICENSE`.
- Backend Supabase dell'Assistente incluso senza secret.
- Nessuna chiave privata o keystore incluso nel repository.
