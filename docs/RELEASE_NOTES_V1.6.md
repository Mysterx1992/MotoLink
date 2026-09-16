# MotoLink V1.6

MotoLink V1.6 introduce la modalità **BLE turn-by-turn** per TFT VOGE compatibili, mantenendo il mirroring EasyConn tramite i profili Hotspot/QR.

## Novità principali
- profilo **BLE** dedicato alla navigazione turn-by-turn, separato dal mirroring video;
- trasporto BLE VOGE con heartbeat, coda serializzata e fallback adattivo `WRITE_TYPE_NO_RESPONSE`;
- interpretazione locale delle indicazioni Google Maps, comprese le uscite di rotonda, tramite modello TensorFlow Lite sul dispositivo;
- encoding VOGE aggiornato per direzione, `roadFlag`, settore anulare e campi di percorso;
- **Zero Transition Guard** per evitare che una transizione a 0 m sovrascriva la manovra successiva;
- compilazione dei campi VOGE di **tempo e chilometraggio restanti** quando Google Maps li espone nella notifica;
- recovery BLE migliorato, mantenendo il core EasyConn/H264 già validato;
- Garage e scelta profilo aggiornati per Hotspot, QR Code e BLE.

## Compatibilità e limiti
La modalità BLE invia indicazioni di navigazione e **non effettua mirroring video**. La resa grafica delle manovre dipende dal firmware TFT: alcuni display possono mostrare la freccia dell'uscita invece di un'icona circolare dedicata per le rotonde.

I campi di tempo e chilometraggio restanti sono implementati in V1.6; la loro resa finale dipende anche dai dati esposti dalla versione di Google Maps e dal firmware TFT.

## APK ufficiale
Scaricare `MotoLink_V1.6.apk` dagli asset della release GitHub V1.6. L'APK mantiene la firma permanente MotoLink.
