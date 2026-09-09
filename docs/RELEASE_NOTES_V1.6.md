# MotoLink V1.6

MotoLink V1.6 estende la linea ufficiale V1.x introducendo i profili multi-connessione e il canale di navigazione VOGE BLE, preservando il core EasyConn/H.264 già validato della V1.5.1 per i profili non-BLE.

## Novità

- Il primo START senza profili apre direttamente **Nuovo profilo moto**.
- Il profilo moto usa **Nome**, **Modello** e **Tipo di connessione**: Automatico, Hotspot, Qrcode o BLE.
- Il campo Descrizione viene rimosso dal flusso di creazione/modifica profilo.
- In modalità **Automatico**, MotoLink prova il collegamento VOGE BLE e può ricadere su un Hotspot moto già collegato.
- La modifica del tipo di connessione dal Garage richiede conferma e sostituisce la configurazione precedente solo dopo una verifica riuscita.
- Aggiunto il trasporto navigazione VOGE sul secondo collegamento BLE con heartbeat `0x5A` e pacchetti `0x6A`–`0x6E`.
- Per i profili BLE, MotoLink può leggere le indicazioni di Google Maps tramite l'accesso notifiche autorizzato dall'utente e trasformarle nel formato VOGE. I dati mancanti non vengono inventati.
- Il doppio Volume Giù resta disponibile durante il mirroring come nella V1.5.1.
- EasyConn, H.264, clock, adattamento e geometrie display della V1.5.1 restano invariati per i profili non-BLE.

## Versione

- applicationId: `it.motolink.app`
- versionCode: `11`
- versionName: `1.6`

## Firma ufficiale

La release finale deve usare la stessa identità di firma permanente MotoLink delle release ufficiali precedenti. Certificato SHA-256 atteso:

`9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

La chiave privata di firma non viene conservata né pubblicata nel repository.

## Sicurezza Assistente MotoLink

La V1.6 non modifica il boundary client/server dell'Assistente MotoLink e non pubblica chiavi private, service-role key o secret del provider IA.
