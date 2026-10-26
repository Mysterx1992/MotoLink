# MotoLink V1.8 — Dichiarazione Google Play del servizio in primo piano

Data della verifica: **24 ottobre 2026**. Lineage: **V1.8 pubblica, branch main, versionCode 34, targetSdk 36**.

**Scopo:** preparare contenuti fedeli al comportamento dell'app per la pagina **Google Play Console → Contenuti app → Dichiarazioni servizi in primo piano**. Non è una compilazione automatica della Play Console e non certifica l'approvazione da parte di Google.

## 1. Tipo di servizio da dichiarare

| Campo | Valore verificato |
| --- | --- |
| Tipo foreground service | `mediaProjection` / `TYPE_MEDIA_PROJECTION` |
| Permesso generale | `android.permission.FOREGROUND_SERVICE` |
| Permesso specifico | `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` |
| Componente | `it.motolink.app.MirrorService` (non esportato) |
| Motivo | Proiezione dello schermo di un telefono Android sul TFT compatibile della moto tramite MediaProjection + codifica H.264, inviati sul collegamento locale EasyConn/P2P |
| Avvio visibile | Utente sceglie un profilo, preme START o una scorciatoia preferita e accetta la finestra di acquisizione schermo di Android |
| Notifica di sistema | `MotoLink — Mirroring attivo verso la moto`, notifica permanente durante il servizio |
| Arresto | Pulsante STOP o interruzione/revoca MediaProjection; la cattura viene chiusa e le risorse rilasciate |
| Video di prova | **Non ancora registrato**: URL da aggiungere manualmente in Play Console |

Il Manifest della V1.8 contiene già entrambi i permessi, e `MirrorService` dichiara `android:foregroundServiceType="mediaProjection"`. Il codice Android richiama `startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`.

### Testo proposto per Google Play Console — descrizione della funzione

> MotoLink consente all'utente di proiettare lo schermo del proprio smartphone Android sul display TFT di una moto compatibile. Dopo aver selezionato il profilo della moto e premuto START, l'utente autorizza esplicitamente la condivisione dello schermo mediante la schermata di consenso Android. MotoLink usa il servizio in primo piano di tipo mediaProjection per mantenere attiva la cattura dello schermo durante l'utilizzo di Google Maps o altre applicazioni, codificare i frame in H.264 e inviarli al display della moto attraverso la connessione locale. Una notifica permanente informa l'utente che il mirroring è in corso. La sessione può essere fermata con STOP oppure dal sistema revocando la condivisione.

### Impatto se il servizio viene avviato in ritardo

> Se il servizio non viene avviato subito dopo il consenso dell'utente, la proiezione non inizia e il TFT non riceve i frame video attesi. La funzione principale di mirroring richiesta dall'utente rimane quindi indisponibile fino al nuovo avvio. Le altre funzioni MotoLink indipendenti dal mirroring, come la gestione locale dei profili moto, non richiedono la cattura dello schermo.

### Impatto se il servizio viene interrotto durante l'utilizzo

> Se Android interrompe o revoca MediaProjection, MotoLink smette di produrre nuovi frame video; il TFT può mantenere temporaneamente l'ultimo contenuto o mostrare uno stato sostitutivo, secondo il firmware e la situazione del collegamento. Per riprendere la proiezione dopo la revoca del consenso, l'utente deve avviare una nuova sessione e autorizzare nuovamente la cattura schermo. MotoLink non può proseguire la cattura senza autorizzazione Android valida.

### Caso d'uso da scegliere

Nella lista di Google Play, scegliere **TYPE_MEDIA_PROJECTION → Media or Content Projection and streaming or recording with MediaProjection API** (o equivalente italiano: **Proiezione/streaming di contenuti verso dispositivo esterno**). Non scegliere `mediaPlayback`, `location` o `connectedDevice` per questo **servizio**: il Manifest non dichiara tali FGS e l'utilizzo del servizio è la cattura dello schermo.

## 2. Video dimostrativo richiesto da Google Play

Fornire **un video reale**, non simulato. È necessario un telefono Android e, per mostrare la proiezione funzionante, un TFT compatibile. Il filmato deve permettere al revisore di verificare:

1. MotoLink apre Home: mostrare il profilo moto compatibile connesso, senza esporre QR o password.
2. Toccare **START** per avviare il mirroring.
3. Mostrare il **consenso Android MediaProjection**, con scelta e conferma dell'utente.
4. Mostrare la **notifica permanente** MotoLink, `Mirroring attivo verso la moto`.
5. Mostrare che lo schermo appare sul **TFT** durante una schermata ordinaria del telefono (senza dati sensibili).
6. Toccare **STOP** e mostrare che la sessione termina e la notifica scompare.

Nel video evitare strada in movimento, dati personali, contenuti sensibili di altre app o rivelazione di reti e token. Se il TFT non è disponibile, **non** presentare una simulazione come prova completa della proiezione: registrare quando il dispositivo sarà disponibile.

**URL video verificato:** DA FORNIRE / NON INVENTARE.

## 3. Accesso alle notifiche per navigazione BLE

`MapsNavigationListenerService` è dichiarato con `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` e richiede che l'utente abiliti manualmente l'accesso alle notifiche. Il codice riceve la notifica di Android con capacità ampia ma **filtra il package Google Maps** (`com.google.android.apps.maps`), legge dati relativi alla navigazione e ne invia le indicazioni supportate via BLE al TFT.

**Testo di trasparenza proposto:**

> Per visualizzare sul TFT le indicazioni di navigazione di Google Maps, Android deve concedere a MotoLink l'accesso alle notifiche. Android descrive questo come un permesso di accesso alle notifiche; MotoLink utilizza il listener per elaborare quelle provenienti da Google Maps. Da queste ricava le indicazioni di svolta e distanza da trasmettere alla moto. Puoi revocare l'autorizzazione dalle impostazioni Android in qualsiasi momento; senza il permesso la navigazione BLE collegata alle notifiche Maps non funziona.

**Importante:** il TFT supportato visualizza **frecce/manovre secondo il protocollo compatibile**; non dichiarare che proietta simboli grafici specifici di rotonde sul TFT.

## 4. Altri permessi: non introdurre cambi non verificati

- `SYSTEM_ALERT_WINDOW`: attualmente è effettivamente usato sia per le superfici `TYPE_APPLICATION_OVERLAY` relative a schermo nero/manuale e pannello Adattamento sia per il comportamento della Gate Activity. **Non eliminarlo** con la sola motivazione della privacy: richiede una verifica di proporzionalità e delle regole di Play separata.
- `ACCESS_FINE_LOCATION` (maxSdkVersion 32): compatibilità Android precedenti nelle funzioni Wi-Fi legacy; non è `ACCESS_BACKGROUND_LOCATION`. Non dichiarare raccolta di GPS in background non presente nel codice.
- `CAMERA`: scanner QR interno di fallback, non funzione di MediaProjection.
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`: collegamento a moto/TFT e QR; gestione distinta dalla proiezione schermo.
- `android:usesCleartextTraffic="true"`: superficie di sicurezza da controllare separatamente in base ai collegamenti TCP EasyConn su LAN; **non cambiarla alla cieca**.
- `MapsNavigationListenerService` non è un foreground service; non assegnargli artificialmente un tipo FGS.

## 5. Evidenze puntuali nel sorgente

- `app/src/main/AndroidManifest.xml`: permessi, `MirrorService`, `MapsNavigationListenerService`.
- `app/src/main/java/it/motolink/app/MainActivity.kt`: `requestProjectionChoice()`, `onActivityResult(REQ_CAPTURE)`, `startForegroundService(serviceIntent)`, autorizzazione Notifications.
- `app/src/main/java/it/motolink/app/MirrorService.kt`: `startProjection()`, `startForeground()`, `getMediaProjection()`, callback `onStop()`, `shutdown()`.
- `app/src/main/java/it/motolink/app/V16Navigation.kt`: `MapsNavigationListenerService`, filtro del package Google Maps e invio `VogeBleNavigationManager.sendNavigation`.

## 6. Riferimenti Google

- [Dichiarazione servizi in primo piano — Play Console](https://support.google.com/googleplay/android-developer/answer/13392821?hl=it)
- [Android — Foreground service type mediaProjection](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android — MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Google Play — Device and Network Abuse / FGS](https://support.google.com/googleplay/android-developer/answer/16559646?hl=it)

**Stato:** Manifest e percorso Android coerenti nell'analisi sorgente; Play Console, prova video e controllo su un AAB combinato con tutte le PR non ancora effettuati. Nessuna autorizzazione alla pubblicazione o al merge.
