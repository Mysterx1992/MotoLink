# MotoLink V1.5

La V1.5 consolida le correzioni emerse dai test fisici successivi alla V1.4.

## Novità e correzioni

- **Trofeo:** ripristinata la geometria/adattamento predefinito validato già dalla prima installazione, anche con l'editor Adattamento chiuso.
- **Adattamento display:** le regolazioni restano salvate, la X chiude l'editor senza perdere la personalizzazione e il pulsante **OK** delle istruzioni è stato corretto.
- **Recovery TFT:** quando il display cambia temporaneamente modalità, MotoLink mantiene la sessione e continua ad attendere il ritorno del mirroring senza un limite fisso di tentativi.
- **Recovery H264:** eliminato il falso successo che poteva essere generato dal testo del Log; il ripristino viene confermato dallo stato reale della sessione/video.
- **CFMOTO:** corretto il lifecycle del consumer H264 che poteva lasciare P2P/EasyConn collegati ma senza flusso video. Il collegamento MotoLink V1.5 → CFMOTO è stato verificato fisicamente dopo la correzione.
- **Assistente MotoLink:** ripristinata la configurazione pubblica del backend. Nessun secret privato è incluso nell'APK.
- **VERSIONE nelle Impostazioni:** toccando VERSIONE vengono mostrate localmente le novità e i bugfix della release installata.
- **Guida aggiornata** per il nuovo comportamento di Adattamento, VERSIONE e rientro automatico nel mirroring.

## Aggiornamento

La release usa la stessa identità di firma permanente MotoLink delle release ufficiali precedenti. Chi ha già una release ufficiale compatibile può installare `MotoLink_V1.5.apk` come aggiornamento.

## Integrità APK

SHA-256 `MotoLink_V1.5.apk`:

`42a0d83ee4a2892839bff90b4ec4314b0074c0e06251c2a1c00c0728b044d64e`

Certificato MotoLink SHA-256:

`9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Licenza

Il codice sorgente è pubblico e consultabile, ma MotoLink **non è open source**. Tutti i diritti sono riservati; vedere `LICENSE`.
