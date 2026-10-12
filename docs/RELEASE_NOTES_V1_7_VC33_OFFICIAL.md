# MotoLink V1.7 — versione ufficiale

**Scarica l'APK firmata ufficiale:** [MotoLink1.7.apk](https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink1.7.apk)

- **Applicazione:** `it.motolink.app`
- **Versione:** `1.7` · `versionCode 33`
- **SHA-256:** `c4d68f35e5c96b348d003d36e80b4648eff9a6e947a18e326c0411f1104e954e`
- **Certificato di firma SHA-256:** `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Miglioramenti della navigazione VOGE

- **Aggiornamento rapido della distanza:** la coda BLE mantiene l'ultimo aggiornamento valido di Google Maps invece di accumulare distanze precedenti, completando comunque i cinque pacchetti di una trasmissione già iniziata.
- **Riconnessione BLE:** dopo lo spegnimento e la successiva riaccensione del quadro, MotoLink mantiene la richiesta di collegamento e ripete la scansione con intervalli progressivi; la pressione di STOP arresta la ricerca.
- **Ripresa dopo il collegamento:** il ritorno allo stato READY richiede la verifica GATT e heartbeat; non viene riaperto Google Maps soltanto a causa del riaggancio BLE.
- **Telemetria della latenza:** registra i tempi tra ricezione delle istruzioni, coda e callback GATT per aiutare a diagnosticare eventuali ritardi.
- **Navigazione conservata:** parser russo Google Maps, manovre, rotonde, framing TFT, heartbeat, ritmo scritture BLE, mirroring EasyConn/H264 e Modalità tasca invariati.

## Evidenze e limiti

Il tester russo su VOGE Valico 625 DSX ha confermato il funzionamento della navigazione e delle rotonde con la versione precedente, oltre al funzionamento a schermo del telefono spento. Il log del 12 ottobre 2026 mostrava l'avanzamento 90→80→70→60→50 metri accettato da MotoLink, ma riferiva un ritardo sul TFT.

La nuova gestione della coda BLE e la riconnessione hanno superato compilazione Android CI, test sintetici e verifica crittografica dell'APK firmata. **La misura del ritardo effettivo sul TFT e la conferma fisica della riconnessione dopo OFF/ON restano da effettuare:** i test software non equivalgono a una verifica su strada. Non usare lo smartphone durante la guida.

La precedente vc32 firmata resta disponibile come archivio nella release. I dettagli e la storia integrale sono nel [master unico](https://github.com/Mysterx1992/MotoLink/blob/main/docs/MOTOLINK_MASTER_UNICO_CURRENT.md).
