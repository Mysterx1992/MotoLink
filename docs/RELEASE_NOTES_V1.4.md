# MotoLink V1.4

Aggiornamento di compatibilità, stabilità e gestione del mirroring.

## Novità e correzioni

- Aggiunto un percorso dedicato per i profili CFMOTO: il nome WLAN Direct ricavato dal QR viene normalizzato sul peer reale della moto e viene mantenuto un fallback sulla rete indicata dal QR.
- Corretto il collegamento HOTSPOT su Trofeo/Valico quando Android mantiene la rete mobile come rete predefinita: MotoLink può usare la rete Wi-Fi locale della moto senza cambiare la connettività delle altre app.
- Migliorata la gestione CFMOTO del display e della rotazione durante il mirroring.
- Migliorata la riconnessione del flusso video quando si esce e si rientra nella modalità mirroring del TFT.
- Migliorato il riaggancio dell'interfaccia MotoLink a una sessione di mirroring già attiva, evitando START duplicati dopo la ricreazione dell'Activity.
- Corretto Adattamento: dopo la regolazione, la X chiude l'editor e porta l'interruttore su OFF, ma la personalizzazione salvata continua a essere applicata nelle sessioni successive.
- Le regolazioni Adattamento restano separate per profilo moto e orientamento e vengono azzerate solo con un ripristino esplicito dell'utente.
- EasyConn wire-level, H264FrameBus e la geometria V15 di riferimento restano invariati.

## Aggiornamento dalla V1.2

Chi utilizza la release ufficiale MotoLink V1.2 può installare `MotoLink_V1.4.apk` direttamente sopra la V1.2. Profili e impostazioni locali vengono mantenuti dal normale aggiornamento Android.

La V1.3 non viene pubblicata come release GitHub: il canale pubblico passa direttamente da V1.2 a V1.4.

## Licenza

Il codice sorgente è pubblico e consultabile, ma MotoLink **non è open source**. Tutti i diritti sono riservati; vedere `LICENSE`.
