# MotoLink V1.2

Aggiornamento di compatibilità QR e collegamento moto.

## Novità

- Google Code Scanner resta la prima scelta per la scansione QR.
- Se Google Code Scanner non è disponibile sul telefono, MotoLink passa automaticamente allo scanner interno CameraX + ML Kit.
- Il permesso Fotocamera viene richiesto solo al primo fallback; se viene negato, MotoLink non continua a riproporlo automaticamente.
- Restano disponibili QR da immagine e configurazione manuale.
- Aggiunto un controllo di sicurezza al profilo HOTSPOT: se non contiene SSID e il telefono è su rete mobile, START viene interrotto prima della cattura schermo.
- Il percorso CFMOTO QR resta invariato: selezione per evidenza di protocollo, prova WLAN Direct/P2P sul target QR esatto e fallback Wi-Fi/EasyConn classico.
- Protocollo EasyConn, H264, clock, geometria V15 e adattamento non modificati.

## Aggiornamento

Installare `MotoLink_V1.2.apk` sopra la versione precedente. Un normale aggiornamento Android mantiene profili e impostazioni locali.
