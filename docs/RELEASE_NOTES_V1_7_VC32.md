# MotoLink V1.7 — vc32

**Ultima APK ufficiale:** `MotoLink_V1.7.apk` — `versionCode 32`, `versionName 1.7`, package `it.motolink.app`.

**Download diretto:** https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink_V1.7.apk

**SHA-256 verificato dell'APK:** `ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625`.

## Aggiornamenti V1.7

- Interfaccia multilingua con italiano, inglese, russo e cinese.
- Google Maps: lettura estesa delle notifiche (testo, viste RemoteViews e accessibility text).
- Navigazione BLE turn-by-turn con riconoscimento delle distanze e delle manovre in russo.
- Gestione e codifica VOGE delle rotonde implementata; verifica delle uscite fisiche ancora in corso.
- Classificatore icone indipendente dal tema/colore Android.
- Diagnostica privacy-safe per notifiche Google Maps.
- **vc32:** diagnostica estesa dei campi di notifica per verificare i casi intermittenti, senza alterare la logica del parser, i pacchetti di navigazione o il protocollo BLE rispetto alla precedente V1.7.
- Recovery EasyConn, MediaProjection/H264 e Modalità tasca preservati.

## Verifiche sul campo

Navigazione Google Maps in russo e frecce sul TFT VOGE Valico 625 DSX confermate dal tester. Il tester ha inoltre confermato la navigazione con display del telefono spento.

**Limiti ancora aperti:** riconnessione automatica BLE quando il quadro moto viene spento e riacceso (prevista per una futura vc33), conferma fisica delle indicazioni di rotonda. Queste correzioni non sono incluse nella vc32.

## Versione precedente

La vc31 rimane disponibile come archivio: [MotoLink V1.7 vc31](https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink_V1.7_VC31_archive.apk).

La vc32 è stata pubblicata a seguito di una verifica byte-per-byte contro l'APK firmata utilizzata durante i test fisici. Il procedimento di pubblicazione non ha richiesto né esposto chiavi private di firma.
