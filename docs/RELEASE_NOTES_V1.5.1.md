# MotoLink V1.5.1

MotoLink V1.5.1 è un aggiornamento di stabilità della V1.5, con particolare attenzione alla Modalità tasca/prossimità e alla prevenzione del blocco automatico del telefono durante il mirroring.

## Novità e correzioni

- **Prossimità più affidabile all’avvio:** se l’armamento arriva prima che MediaProjection e il video siano realmente pronti, MotoLink conserva la richiesta e la applica automaticamente appena la sessione è pronta.
- **Modalità tasca mantenuta attiva:** listener di prossimità e wake-lock dedicato restano armati fino a STOP/teardown della sessione.
- **Anti auto-lock durante il mirroring:** MotoLink mantiene il telefono sveglio rispetto al normale timeout di inattività; il tasto Power manuale resta utilizzabile.
- **Diagnostica blocco schermo migliorata:** il Log distingue il percorso di prossimità, l’anti-auto-lock attivo e i possibili blocchi manuali/policy OEM.
- **Doppio Volume Giù invariato** come comando manuale di blackout/riattivazione.
- **Core validato preservato:** EasyConn, H264, clock, percorsi rete e geometrie display già validate restano invariati da questo bugfix.

## Aggiornamento

La release usa la stessa identità di firma permanente MotoLink delle release ufficiali precedenti. Chi utilizza già una release ufficiale compatibile può installare `MotoLink_V1.5.1.apk` come aggiornamento.

## Integrità APK

SHA-256 `MotoLink_V1.5.1.apk`:

`18f413e93fe0a76055765bcd64f4654ebeb3641b1fe0a27c7bfd90505c55d21f`

Certificato MotoLink SHA-256:

`9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Sicurezza Assistente MotoLink

La V1.5.1 non modifica la configurazione client dell’Assistente MotoLink e non pubblica chiavi private, service-role key o secret del provider IA. I secret dell’Assistente restano esclusivamente lato server.

## Licenza

Il codice sorgente è pubblico e consultabile, ma MotoLink **non è open source**. Tutti i diritti sono riservati; vedere `LICENSE`.
