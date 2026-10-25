# MotoLink V1.8.1 — BLE Navigation Resync

> **Stato su GitHub al 25/10/2026:** questo documento descrive l'APK V1.8.1 già compilato e firmato con il certificato ufficiale, ma l'asset non è ancora presente nella sezione Release pubblica. Il download da GitHub resta quello della V1.8 finché il file firmato non viene effettivamente caricato. La correzione BLE non ha ancora un collaudo fisico di riaccensione TFT.


Versione Android ufficiale incrementale: `it.motolink.app`, `versionCode 35`, `versionName 1.8.1`.

## Novità
- Quando il collegamento di navigazione BLE torna **READY** dopo spegnimento/riaccensione del quadro, MotoLink prova a risincronizzare l'indicazione corrente di Google Maps senza richiedere STOP/START.
- La lettura avviene dalla **notifica Google Maps di navigazione attualmente attiva**, non da una manovra memorizzata prima dello spegnimento.
- Confermati i controlli esistenti: manovra e distanza reali, distanza non zero, categoria navigazione e Bluetooth GATT verificato. Notifiche vecchie oltre 120 secondi non vengono reinviate, per evitare indicazioni obsolete.
- Restano invariati protocollo BLE e sequenza dei cinque pacchetti, heartbeat, recupero Bluetooth, mirroring Hotspot/QR/EasyConn e profili.
- Aggiunta diagnostica `BLE NAV RESYNC V1` / `MAPS NAV BLE RESYNC` nel Log.

## Compatibilità e aggiornamento
- **Stesso package ufficiale** `it.motolink.app`. Un APK firmato con **il certificato ufficiale esistente** e `versionCode 35` aggiorna la V1.8 `versionCode 34` senza disinstallarla.
- Non usare APK con suffissi `preplaytest`, `preplaytest2` o `bletest` se desideri aggiornare l'app ufficiale.
- Aggiornare conservando dati e profili; mantenere un backup del Log per assistenza.
- Testare la funzione a veicolo fermo: avvia una rotta Maps, aspetta che compaia l'indicazione sul TFT, spegni il quadro per circa dieci minuti senza STOP, riaccendi e verifica il ritorno automatico del BLE e dell'indicazione.

## Limiti
La riconnessione BLE **non garantisce** che Google Maps fornisca subito una nuova distanza interpretabile. Se una notifica è assente, vecchia o senza distanza reale, MotoLink non inventa valori. La conferma dell'indicazione realmente visualizzata sul TFT richiede prova fisica. I gruppi completati GATT non sono una misura diretta del rendering TFT.

## Controlli
GitHub Actions deve verificare compilazione, test sintetici, package/versionCode e certificato di firma ufficiale (SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`). In assenza del keystore originale il rilascio deve interrompersi.
