# MotoLink V1.8.2 — traduzioni complete HOME e flussi di connessione

**Package ufficiale:** `it.motolink.app`  
**VersionCode:** `36`  
**VersionName:** `1.8.2`  
**Lingue:** italiano, English, Русский, 简体中文; tutti gli altri sistemi utilizzano l'inglese.

## Cosa cambia

- Corrette le scritte della HOME **Pronto / Stato / Collegato a / Nessun collegamento**. Ogni parola ora viene tradotta separatamente prima di essere visualizzata.
- Corretto il bug latente per cui lo stato **Connesso** veniva tradotto prima della logica interna, rischiando di impedire il riconoscimento della connessione e di nascondere lo sfondo moto nei telefoni con locale non italiano. Stato logico mantenuto in forma canonica; traduzione solo sul rendering.
- Tradotti i titoli delle sezioni, le istruzioni e i pulsanti del **Ripristino Wi-Fi Trofeo 500**, gli esiti diagnostici più comuni, i messaggi di permessi, rete e Bluetooth.
- Completati numerosi testi di **Garage, QR, CFMOTO, WLAN Direct, Preferite, impostazioni, profili ed errori** nelle tre lingue extra.
- Preservata la funzionalità di **BLE Navigation Resync V1.8.1**: al ritorno della connessione GATT, MotoLink legge la notifica Google Maps attiva, verificando età, manovra e distanza reale senza inventare dati.
- Preservati gli stessi protocolli BLE, EasyConn, H264, Hotspot, QR, gestione profili e log tecnici.

## Compatibilità aggiornamento

Il pacchetto resta `it.motolink.app` e deve essere firmato con lo **stesso certificato** di MotoLink V1.8.1, SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`. L'aggiornamento **non richiede la disinstallazione della V1.8.1**, quindi conserva dati e Garage. Per lingua sistema non supportata viene mostrato l'inglese, non l'italiano.

## Verifiche

- `scripts/check_locale_v182.py` controlla presenza di traduzioni IT/EN/RU/ZH, fallback e separazione tra stato interno e label.
- `scripts/check_ble_navigation_resync.py` conferma il percorso reattach e i gate di sicurezza.
- `tools/v18_ble_regression.py` controlla il vecchio trasporto VC33.
- Compilazione Android e firma ufficiale devono avere esito PASS prima della release pubblica.
- La pubblicazione del codice non equivale a validazione fisica: confermare le nuove traduzioni e il display TFT sul dispositivo, mantenendo la sicurezza di guida.

## Edizione TEST per Play

La distinta build di prova adotta `it.motolink.app.preplaytest2` e contiene le medesime stringhe V1.8.2 e il BLE Resync. Rimane test separato, debug-signed, non un AAB approvato da Google Play e non sostituisce il package ufficiale.

## Procedura di pubblicazione

Quando il nuovo APK firmato sarà stato verificato, pubblicare Release `v1.8.2` con `MotoLink1.8.2.apk` e SHA256. Aggiornare contemporaneamente i due link di download diretto e la spiegazione sulla homepage README. Non esporre né committare keystore o password in GitHub.
