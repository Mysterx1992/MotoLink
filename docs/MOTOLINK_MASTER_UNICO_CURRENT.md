# MotoLink V1.x — MASTER UNICO CORRENTE R70

**Aggiornato:** 2026-10-12  
**Stato:** documento unico autorevole della linea ufficiale MotoLink V1.x.  
**Revisione:** R70. **R69 preservato integralmente qui sotto**, non riscritto retroattivamente.

> **REGOLA DI PREVALENZA — R70**
>
> Lo stato R70 prevale sulle descrizioni precedenti. R69 e gli archivi inferiori sono conservati come storia tecnica e possono citare candidate o release superate. Non confondere pubblicazione software con validazione fisica.

---

# STATO AUTOREVOLE CORRENTE — 2026-10-12 (R70)

## 1. Release pubblica: MotoLink V1.7

- Repository: `Mysterx1992/MotoLink`, branch `main`; Pull Request #15 della vc33 **unita** con merge SHA `3985001051241f6462023171c200d78c96307e08`.
- Application ID `it.motolink.app`, `versionName 1.7`, `versionCode 33`.
- **Nome pubblico APK unico per il download principale:** `MotoLink1.7.apk` — https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink1.7.apk
- Release ufficiale: https://github.com/Mysterx1992/MotoLink/releases/tag/v1.7 — titolo **MotoLink V1.7**; la prerelease precedente è stata ritirata.
- APK firmata con certificato ufficiale: SHA-256 `c4d68f35e5c96b348d003d36e80b4648eff9a6e947a18e326c0411f1104e954e`; certificato SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`; 73.479.750 byte, firma APK v3 verificata.
- Pubblicazione e verifica GitHub Actions `38238875788` **SUCCESS**: confronto dei file applicativi con sorgente congelata `c40257232307fb0b5b11f9d9a3403d1e4f187228`, test sintetici, verifica hash esatta, certificato, firma, `versionCode=33`, package e download post-caricamento.
- Precedente vc32 preservata nella stessa release come `MotoLink_V1.7_VC32_archive.apk` (SHA-256 `ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625`); vc31 archivio già preservata. L'alias pubblico obsoleto `MotoLink_V1.7.apk` è stato ritirato: il nuovo collegamento è **MotoLink1.7.apk**.
- Il badge grafico RGB opaco della homepage, `docs/images/download-motolink-v17-rgb.png`, resta visivamente invariato: **SCARICA MOTOLINK | V1.7**, con collegamento aggiornato al file corrente. Nessun nuovo badge esterno.
- File README, note release, CHANGELOG e presente master riflettono la distribuzione attuale.

## 2. Modifiche funzionali portate dalla vc33 in V1.7

- **Coda BLE latest-wins:** il vecchio comportamento accumulava 5 frame `0x6A..0x6E` per ogni distanza. Ora i gruppi non ancora iniziati vengono sostituiti dall'ultimo gruppo valido; il gruppo già in corso completa tutti e cinque i frame senza mescolare manovre. Singola scrittura GATT in volo, `NAV_PACE_MS=100`, heartbeat e formato pacchetti invariati.
- **Misure temporali interne:** `NAV_GROUP_START` e `NAV_GROUP_COMPLETE` registrano tempo di attesa in coda e intervallo fino all'ultima callback GATT. Non misurano il momento in cui il TFT renderizza l'informazione.
- **Riconnessione dopo quadro OFF/ON:** al termine di una scansione da 6,5s senza dispositivo, la sessione BLE esplicita resta in attesa e ripete le scansioni dopo 2,5/5/10/15 secondi; STOP invalida i retry. Dopo un READY iniziale la modalità automatica non devia verso Hotspot/QR a seguito del solo spegnimento del quadro; il fallback iniziale pre-READY è preservato.
- Stato connesso solo dopo verifica GATT/heartbeat; riaggancio senza rilanciare Google Maps; i frame obsoleti vengono rimossi durante il recovery.
- Non modificati: parser Google Maps russo, classificazione manovre, rotonde, codifica VOGE, servizio UUID, EasyConn, H264, MediaProjection, Modalità tasca, profili e grafica principale. Osmand/OsmAnd non è una sorgente di navigazione attiva nella versione corrente.

## 3. Evidenze del tester russo e rischi ancora aperti

- VOGE Valico 625 DSX: il tester ha riferito che la navigazione vc32 funziona anche con schermo telefono spento, che le rotonde funzionano e che il TFT rimaneva indietro rispetto alle distanze di Maps.
- Log `MotoLink_Log_20261012_093513.txt`: 1.541 righe; **194** istruzioni accettate, **166** aggiornamenti BLE accodati, **48** aggiornamenti di rotonda (41 BLE accodati), **23** `distanceMissing=true`, **28** istruzioni senza invio per canale TFT non verificato. Sono aggiornamenti, non manovre uniche.
- Esempio di latenza sorgente: RIGHT 90m alle 09:23:32, 80m alle 09:23:34, 70m alle 09:23:36, 60m alle 09:23:37, 50m alle 09:23:39. Il log mostra ricezione e accodamento, non l'orario di visualizzazione sul TFT.
- **Validazione fisica della nuova versione ancora PENDING:** controllare sul TFT se il ritardo Maps→TFT diminuisce, confermare rotonde e manovre, quadro OFF per alcuni minuti e ON senza secondo START, STOP senza ulteriori tentativi, assenza di dati vecchi dopo il reattach.
- I gate Android CI, regressione sintetica e firma sono **PASS**. La pubblicazione è stata eseguita su richiesta dell'utente prima di completare il nuovo test sulla moto; questa distinzione va sempre riportata e non deve essere trasformata in un PASS fisico inventato.

## 4. Regole e prossimo gate

- La versione distribuita è V1.7 vc33; non chiamarla TEST nel README, nel pulsante, nel titolo della release o nel nome della APK principale.
- Conservare in master la cronologia storica e gli SHA; non alterare le prove vc32 e non dichiarare ancora certificata la latenza o la riconnessione fisica.
- Non introdurre stime di distanza in assenza di dati reali Google Maps. Non interrompere un gruppo NAV avviato e non modificare il protocollo o il BLE write type senza una diagnosi isolata.
- Non esporre chiavi private, password o Secrets; mantenere il controllo server/repository sui file firmati.
- **Prossima prova su strada:** una sessione con Google Maps russo, tratto con distanze ravvicinate, rotonda reale e spegnimento quadro ≥2 minuti, riaccensione senza STOP/START; raccogliere log più video o tempi TFT sincronizzati. Prova solo a veicolo fermo o con passeggero che osserva il display; il conducente non deve usare il telefono.

---

# ARCHIVIO INTEGRALE — R69 (STATO STORICO; PREVALE R70)

# MotoLink V1.x — MASTER UNICO CORRENTE R69

**Aggiornato:** 2026-10-12
**Stato:** documento vivo autorevole per la linea MotoLink ufficiale V1.x.
**Revisione:** R69
**Baseline storica preservata integralmente:** R68, SHA-256 `887c43dd8d85be1b0eba5eb65359c2ecccd8961e20523f16570317a69ac3ef5b`.

> **REGOLA DI PREVALENZA — R69**
>
> Lo **STATO AUTOREVOLE CORRENTE — 2026-10-12** qui sotto prevale su R68 e su tutti gli snapshot storici. La storia R68 rimane integralmente conservata dopo il separatore «STORICO R68». Una build candidate, un test tecnico PASS e una release pubblicata NON sono la stessa cosa. Non promuovere vc33 senza firma ufficiale e validazione fisica.

---

# STATO AUTOREVOLE CORRENTE — 2026-10-12

## 1. Release pubblica verificata: MotoLink V1.7 vc32

- Repository: `Mysterx1992/MotoLink`, branch `main`
- Application ID: `it.motolink.app`
- `versionName = 1.7`, `versionCode = 32`
- Release pubblica: `https://github.com/Mysterx1992/MotoLink/releases/tag/v1.7`
- APK standard: `https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink_V1.7.apk`
- APK vc32 diagnostica: `MotoLink_V1.7_VC32Diagnosi.apk`
- SHA-256 ufficiale delle due APK vc32 byte-identiche: `ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625`
- Firma ufficiale SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`
- vc31 archivio: `MotoLink_V1.7_VC31_archive.apk`, SHA-256 `b0b1396176d4094d2042a52ba42c313fa0a527b0e2851e9d194f43a6ecd4a726`
- La PR #14 diagnostica è stata unita nel branch `main` (merge `5db274eed98d829a2eeb9effd1ae4ec20c401e6b`).
- `README.md` e note della release V1.7 sono state aggiornate alla vc32. Il pulsante download V1.7 nella homepage punta all'APK standard ora vc32.
- Il badge inizialmente usava shields.io e poi PNG corrotti/invisibili; ripristinato con `docs/images/download-motolink-v17-rgb.png` generato da Pillow come RGB opaco e validato dal workflow GitHub `Repair homepage MotoLink V1.7 download badge` (run `38170063781` PASS, commit di correzione `924f942992165bd1681519e641ff938be7c4d083`).

### Regola di pubblicazione
Non spostare la release stabile o il collegamento del pulsante di download verso vc33 finché l'APK vc33 non è firmata con **lo stesso certificato ufficiale**, controllata e sottoposta ai gate hardware. La firma privata non va pubblicata nei repository, nei log o nelle GitHub Actions.

## 2. Prove russo / rotonde — 2026-10-09..12

- Il tester VOGE Valico 625 DSX ha confermato che il mirroring/navigazione BLE funziona anche con **display smartphone spento**, a differenza del comportamento osservato da lui in Voge Global.
- L'avvio della prima freccia può richiedere che inizi il movimento; ci sono state notifiche Maps in russo senza distanza pubblicata, perciò il parser correttamente non ha emesso istruzioni inventate.
- Spegnimento quadro: perdita BLE, recovery parziale, finestra di errore e START manuale richiesto; causa software accertata nel ciclo di scansione, non nel protocollo VOGE.
- Spoofing GPS o rete instabile: il TFT può mantenere l'ultima istruzione lunga valida finché la sorgente Google Maps non torna coerente; non attribuire necessariamente tutto il fenomeno a MotoLink.
- **2026-10-12:** tester riferisce che le rotonde funzionano fisicamente; è un feedback reale positivo (non implica copertura di tutte le geometrie o firmware).
- Log locale `MotoLink_Log_20261012_093513.txt`: **1.541 righe**, nel tratto testato **194 istruzioni Maps accettate**, **166 gruppi BLE accodati**, **23 eventi diagnostici `distanceMissing=true`**, **28 istruzioni bloccate perché canale TFT non verificato**, **48 aggiornamenti `ROUNDABOUT` riconosciuti**, **41 accodati**. I conteggi misurano aggiornamenti, non intersezioni/rotonde uniche.
- Codifiche rotonda nel log: `(roadFlag, annular, dir)=(4,11,8)` e `(4,6,1)`; nessun `roundaboutEncodingMissing=true`.
- Il tester segnala ritardo **Maps 90 m → 50 m mentre TFT mostra ancora 90 m**. Esempio verificato nel log: `RIGHT 90 m` accettato+accodato **09:23:32**, poi 80m 09:23:34, 70m 09:23:36, 60m 09:23:37, 50m 09:23:39. Il log dimostra ricezione e accodamento, non il tempo di rendering effettivo del TFT.
- Il codice vc32 accodava tutti e cinque i pacchetti `0x6A..0x6E` a ogni aggiornamento Maps, mantenendo in attesa tutti i gruppi NAV vecchi; `NAV_PACE_MS=100` e una sola GATT write in-flight. Il backlog può causare ritardo significativo. Causa software strutturale confermata; quantificazione TFT end-to-end ancora **PENDING**.

## 3. VC33 — ottimizzazione coda BLE e riconnessione (CANDIDATE, NON PUBBLICATA)

- Branch: `feature/v17-vc33-ble-latest-wins-reconnect`
- Pull Request: `https://github.com/Mysterx1992/MotoLink/pull/15` — draft in review.
- `versionName = 1.7`; `versionCode = 33`; `applicationId` invariato.
- Solo moduli previsti: `V16Navigation.kt` (trasporto BLE), `MainActivity.kt` (sessione/UI riconnessione), `app/build.gradle.kts` e documentazione/test. **Nessuna modifica** a Google Maps parser, modello manovre, rotonde, TFT framing, Service UUID, 5 comandi di NAV, heartbeat, BLE write pace, EasyConn, MediaProjection, H264, Pocket Mode, profili.
- **Latest-wins pending:** ogni gruppo NAV è composto da 5 frame; un gruppo iniziato resta integro; gli altri gruppi NAV in coda vengono sostituiti dal solo ultimo valido. Heartbeat non si sovrappone alle sequenze. `inFlight` resta singolo; `onCharacteristicWrite` governa gli avanzamenti.
- Logging di attesa in coda e terminazione callback GATT: serve a separare latenza software misurabile da latenza reale del TFT, che non viene inventata né dichiarata misurata.
- **Riconnessione quadro OFF/ON:** al termine di scansione 6,5s senza dispositivo non invocare `unavailable()` in BLE esplicito; tenere START e mostrare «In attesa della moto»; ripetere scansioni con pause 2,5/5/10/15s. STOP invalida le generazioni e interrompe i retry. Stato READY soltanto dopo handshake/heartbeat; l'app non riapre Maps a ogni reattach.
- Semantica `AUTOMATIC`: prima connessione senza moto conserva l'originario fallback Hotspot/QR; dopo almeno un READY, un quadro spento non deve forzare il cambio di trasporto.
- `onScanFailed`: retry controllato per errori di scan mentre la sessione è attiva; permessi negati o Bluetooth dello smartphone spento restano condizioni di errore esplicite.
- **Non assumere** che la vc33 risolva da sola qualsiasi ritardo: possono contribuire periodicità notifica Maps, trasporto GATT, buffer interno/display TFT.

### Esiti tecnici verificati — 2026-10-12 (vc33 TEST)

- Branch vc33: `feature/v17-vc33-ble-latest-wins-reconnect` — Pull Request [#15](https://github.com/Mysterx1992/MotoLink/pull/15) **aperta e in bozza**, non unita a `main`.
- Android debug CI PASS; unsigned release build run `38226998331` SUCCESS; artifact `11736279958` (scade 19 ottobre) e SHA unsigned `1541459cd10a7c3a5f49aab29ef0c506f3a76c68e4062be67e5ab5862f481f2e`.
- APK vc33 firmata privatamente, certificate SHA-256 identico all'ufficiale vc32: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`. APK v3 **verificata**; anche la vc32 ufficiale verificava v3=true, v2=false.
- APK vc33 size **73.479.750 bytes**, SHA-256 `c4d68f35e5c96b348d003d36e80b4648eff9a6e947a18e326c0411f1104e954e`; `applicationId=it.motolink.app`, versionCode 33, versionName 1.7.
- Pubblicazione prerelease di test v1.7-vc33-test **PASS** via GitHub Actions `38228579834`, con controllo SHA-identico, firma v3, certificato e metadata. Download diretto APK firmata test: https://github.com/Mysterx1992/MotoLink/releases/download/v1.7-vc33-test/MotoLink_V1.7_VC33_TEST_OFFICIAL_SIGNED.apk
- Il test prerelease recupera dal CI l'unsigned esatto e i dati *pubblici* del contenitore/signature block già firmato, riproducendo esattamente l'APK verificata senza trasferire o pubblicare keystore e password. Questo non consente di firmare un APK modificato: SHA fail-closed.
- La **release stabile V1.7** resta vc32: `MotoLink_V1.7.apk` SHA `ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625`.
- **Rimane PENDING il test fisico vc33:** riconnessione quadro OFF/ON senza nuovo START, interruzione STOP, latenza reale TFT, rotonde e sicurezza/assenza di regressioni, prima di promuovere il badge e il download stabile.
- Homepage principale: badge grafico solo **V1.7** per la release stabile; la vc33 TEST può essere presentata separatamente con dicitura chiara, non automaticamente come download principale.

### Gate di avanzamento (non confondere PASS tecnici con validazione fisica)
1. Android CI debug build per ogni commit finale della PR (PASS tecnico nei run correnti; ogni modifica futura al codice richiede un nuovo run).
2. `tools/vc33_ble_regression.py`: static contract e modello sintetico; non simulazione del vero firmware né dimostrazione completa del comportamento Android.
3. Build `assembleRelease` non firmata su branch, `versionCode 33`; artifact temporaneo disponibile e verificato (PASS). Nessuna APK debug/unsigned deve diventare download ufficiale.
4. Firma vc33 con certificato ufficiale, firma v3, package/versione, SHA-256 e prerelease TEST tutti verificati (PASS). Il file standard della release stabile non è stato sostituito (gate fisico ancora PENDING).
5. Prova moto: GPS reale, TBT rotonda, progressione 90/80/70/60/50 e misurazione visiva del TFT; quadro OFF almeno qualche minuto e successivo ON senza nuovo START, STOP senza ulteriori tentativi; ripresa senza vecchia manovra e senza rilanciare Google Maps.
6. Solo dopo gate 1–5: aggiorna `main`, `v1.7`, README, badge (sempre grafica **V1.7**, senza vc33 nel pulsante), release notes e APK standard con hash firmato vc33. Conserva vc32 come archivio.

## 4. Decisioni e rischi congelati

- Mai presentare un log «TBT reale accodato» come ack GATT o render TFT.
- Non interpolare/mettere distanze inventate in assenza della distanza reale nella notifica.
- Non sopprimere a metà i pacchetti di un gruppo NAV iniziato; non cambiare ritmo o `WRITE_TYPE_DEFAULT` senza diagnostica indipendente.
- Non esporre keystore, PIN, password, segreti CI o chiavi private.
- Nessun rilascio pubblico vc33 senza firma ufficiale e verifica su moto.
- Aggiornare ogni successivo risultato / correzione in questa sezione R69 o in una successiva revisione che preservi integralmente R69.

---

# STORICO R68 — PRESERVATO INTEGRALMENTE

# MotoLink V1.x — MASTER UNICO CORRENTE R68

**Aggiornato:** 2026-10-09  
**Stato:** documento vivo autorevole per la linea MotoLink ufficiale V1.x.  
**Revisione:** R68  
**Base storica incorporata:** `MOTOLINK_V1_0_MASTER_20260925_R67.md`  

> **REGOLA DI PREVALENZA**
>
> Le sezioni **STATO AUTOREVOLE CORRENTE — 2026-10-09** e **NEXT GATE OBBLIGATORIO** qui sotto prevalgono su qualsiasi sezione storica successiva nel file.  
> Lo storico R67 è conservato integralmente per continuità tecnica, ma contiene stati, ipotesi e release ormai superati.  
> Non riaprire decisioni congelate né promuovere vecchie candidate se lo snapshot corrente le ha sostituite.

---

# STATO AUTOREVOLE CORRENTE — 2026-10-09

## 1. Release pubblica corrente — MotoLink V1.7 vc31

La release pubblica ufficiale corrente è **MotoLink V1.7**, aggiornata il 08/10/2026 mantenendo `versionName = 1.7` ma incrementando il codice installabile a `versionCode = 31`, così da aggiornare in-place la precedente V1.7 vc30.

```text
repository = Mysterx1992/MotoLink
applicationId = it.motolink.app
versionName = 1.7
versionCode = 31
GitHub release = v1.7
APK = MotoLink_V1.7.apk
APK size = 73,414,214 bytes
APK SHA-256 = b0b1396176d4094d2042a52ba42c313fa0a527b0e2851e9d194f43a6ecd4a726
official certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
main merge commit = 195b988fa9af14d649d4904359cf1b34a5ddb4e2
PR = #13 MotoLink V1.7 Maps + roundabout update
```

Download diretto ufficiale:

`https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink_V1.7.apk`

Il README principale punta allo stesso URL diretto e dichiara V1.7 / `versionCode 31`.

### Build / publication gates

```text
Build release gate run = 37837177372
Result = SUCCESS
Artifact = MotoLink_V1.7_VC31_MAPS_ROUNDABOUT_UNSIGNED
Artifact ID = 11575933154
Artifact digest = sha256:984ca9c8edd5de28e8455e02eb3ced48f3a8961ff08570ef6cbef3044ed7db6b

Official publish run = 37839120907
Result = SUCCESS
Signed APK verification = PASS
Package/version verification = PASS
Certificate verification = PASS
Release asset replacement = PASS

main Android CI run = 37839305902
Result = SUCCESS
```

La precedente V1.7 vc30 (`versionCode 30`, APK SHA-256 `4f30e09d63c75450b136b75665e5c876276417ff1363160aa16f50ac5ece1b86`) è **SUPERSEDED** dalla vc31.

---

## 2. Frozen baseline / componenti da non contaminare

La lineage V1.7 deriva dalla baseline V1.6 vc27/vc28. Restano protetti salvo bug fisicamente dimostrato e scope esplicito:

```text
EasyConn / PXC
network recovery vc28
MediaProjection core
H264 core
Pocket Mode / proximity gate vc27
anti-auto-lock / lock path
clock path già validato
BLE VOGE UUID / heartbeat / GATT queue
firma ufficiale MotoLink
AI / Supabase security boundary
```

Baseline vc27 ufficiale nota:

```text
APK = MotoLink_V1.6_VC27_POCKET_MODE_V151_GATE_RESTORE_EASYCONN_FIX_OFFICIAL_SIGNED.apk
SHA-256 = a757654f02961ae7ebadeb4a776b0b3d759f263ab483cad5995548da771e564d
certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
```

La vc28 recovery è incorporata nella base sorgente della V1.7.

---

## 3. Traduzioni V1.7

La V1.7 usa la lingua del telefono e ha pacchetti utente completi per:

```text
Italiano
English
Русский
中文
```

Per le altre lingue viene usato l'inglese come fallback.

Sono tradotti i principali contenuti user-facing:

```text
Home / Main UI
dialog principali
Garage / profili
app preferite
QR menu
Safety notice
Assistant info/help
Pocket Mode prompt/guidance
share chooser log
help permesso notifiche Google Maps
errori e prompt operativi principali
Getting Started guide
titoli/corpo/pulsanti guida
Versione App / release notes V1.7
```

I log tecnici e alcune stringhe diagnostiche restano volutamente stabili e non sono da considerare parte della traduzione UI completa.

---

## 4. BLE VOGE — stato corrente

Protocollo congelato:

```text
SERVICE UUID = 5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e
TX / WRITE UUID = 6052202a-2928-4131-a2d0-456d5673ed2f
RX / NOTIFY UUID = ab9938d5-c354-4e2b-94f4-364e16ebcd33
heartbeat = 0x5A circa 1 Hz
navigation = 0x6A -> 0x6B -> 0x6C -> 0x6D -> 0x6E
frame size = 20 byte
checksum = XOR byte 18
terminator = byte 19 = 0x04
```

Il test fisico Voge Valico 625 DSX del 09/10/2026 conferma che il trasporto BLE **si collega realmente**:

```text
connectGatt = PASS
GATT status = 0 / state = connected
TX properties = write true
CCCD notify = status 0
heartbeat 0x5A ACK = PASS
transport READY = PASS
```

Quindi il blocker corrente non è l'apertura della connessione BLE con la 625.

### VOGE Global

VOGE Global usa lo stesso lato BLE navigazione (`BLE-VOGE-...`) e può essere una variabile di contesa. Durante i test controllati va tenuta **arrestata** per eliminare l'interferenza, senza disinstallarla o modificare servizi globali.

Nel log 09/10/2026, tuttavia, MotoLink raggiunge READY, quindi VOGE Global non spiega il failure osservato di parsing Maps in quella sessione.

---

## 5. Google Maps V1.7 vc31 — parser corrente

La vc31 modifica soltanto la pipeline di lettura Google Maps / classificazione manovra, lasciando BLE e i componenti protetti fuori scope.

### Superfici notifica lette

```text
Notification.EXTRA_TITLE
Notification.EXTRA_TEXT
Notification.EXTRA_BIG_TEXT
Notification.EXTRA_SUB_TEXT
Notification.EXTRA_INFO_TEXT
Notification.EXTRA_SUMMARY_TEXT
Notification.EXTRA_TEXT_LINES
notification.tickerText
notification.contentView
notification.bigContentView
notification.headsUpContentView
recovered content/big/heads-up RemoteViews
TextView text
View contentDescription / accessibility text
largeIcon fallback
```

### Distanze / lingue

Il parser supporta almeno:

```text
m / km
м / км / метр... / километр...
米 / 公里 / 千米
mi / ft
NBSP / narrow-NBSP / Unicode spaces
```

Il percorso italiano preesistente viene preservato. Il russo è esteso senza sostituire le regole italiane.

### Route summary vs distance-to-turn

La vc31 mantiene separate:

```text
distanceMeters = distanza alla prossima manovra
routeRemainDistanceMeters = distanza residua intero percorso
routeRemainTimeSeconds = tempo residuo percorso
ETA = orario di arrivo
```

Una riga tipo `durata · distanza residua · ETA` è trattata come route summary e non deve essere usata come distanza alla prossima manovra.

### Fail-closed

Se la distanza reale alla manovra non viene esposta o la manovra non è affidabile, MotoLink non inventa il dato e non invia un pacchetto navigazione sbagliato al TFT.

---

## 6. Diagnostica Maps privacy-safe vc31

Quando mancano distanza/manovra/settore rotonda, la vc31 deve emettere una riga simile a:

```text
MAPS NAV V1.7 FIELD DIAG: ...
```

Il diagnostico registra soltanto:

```text
nome superficie notifica
lunghezza testo
presenza/assenza distanza
route-summary yes/no
classe manovra
presenza cirillico / cinese
numero icone candidate
```

Non deve registrare nomi strada, destinazioni o testo integrale della navigazione.

Questa diagnostica è il gate obbligatorio prima di ulteriori modifiche al parser russo.

---

## 7. Rotonde — stato autorevole corrente

### Correzione storica importante

Le vecchie sezioni del master che riportano `roadFlag=2 = roundabout` sono **SUPERSEDED**.

Dal reverse engineering di VOGE Global 1.1.7 e dai test successivi, il percorso rotonda corrente usa:

```text
roadFlag = 4
annularDegrees = settore uscita VOGE
direction = direzione ordinaria coerente con l'uscita
```

Anchor VOGE consolidati:

```text
annular 3  = uscita/destra
direction 5

annular 6  = dritto
direction 1

annular 9  = sinistra
direction 4

annular 12 = inversione / U-turn
direction 2
```

La classifier lineage usa settori 16-way e conversione a 12 settori. Mapping corrente:

```text
section16 2  -> direction 7  slight-right
section16 4  -> direction 5  right
section16 6  -> direction 9  sharp-right
section16 8  -> direction 1  straight
section16 10 -> direction 6  slight-left
section16 12 -> direction 4  left
section16 14 -> direction 8  sharp-left
section16 16 -> direction 2  U-turn
annular = round(section16 * 12 / 16)
```

### vc31 roundabout improvement

La vc31 rende il riconoscimento rotonda **language-agnostic**:

```text
text roundabout wording = supporto aggiuntivo
visual icon geometry = sorgente indipendente dalla lingua
ML classifier = recupero settore uscita
input ML = normalizzato theme-independent, white ink on black
```

Se il testo o la geometria confermano che è una rotonda, la classifier può usare una fallback conservativa tra le sole classi roundabout anche quando la confidence globale è sotto la soglia normale. Se il settore resta ambiguo, non viene inventato.

### Stato fisico

La logica vc31 è **IMPLEMENTATA / BUILD PASS / SIGNATURE PASS**, ma la resa reale delle rotonde sulla 625 resta **PHYSICAL VALIDATION PENDING**.

Il precedente test vc19 aveva dimostrato che `roadFlag=4 + annular + direction` non produceva necessariamente un simbolo grafico dedicato sulla 625; il TFT mostrava spesso una freccia ordinaria. Il reverse engineering OEM aveva però mostrato che la struttura del frame MotoLink era concettualmente equivalente al builder VOGE Global 1.1.7. Rimane quindi possibile che il firmware TFT della 625 renda le rotonde come freccia d'uscita invece che come icona rotonda dedicata.

Per distinguere definitivamente difetto MotoLink vs comportamento firmware, il gate ideale resta un A/B sulla stessa 625 con VOGE Global ufficiale oppure una cattura BLE OEM.

---

## 8. TEST FISICO 09/10/2026 — RISULTATO CRITICO

Log:

`MotoLink_Log_20261009_070350.txt`

Header:

```text
MotoLink 1.7 - Log locale leggibile
```

Connessione 625:

```text
07:03:12 BLE START
07:03:13 GATT status=0 state=2
07:03:13 CCCD status=0
07:03:14 READY dopo ACK heartbeat 0x5A
```

Maps:

```text
07:03:20 MAPS NAV V1.6 GATE: istruzione ricevuta ma distanza reale alla manovra non esposta
07:03:21 MAPS NAV V1.6 ML: nessuna classe affidabile confidence=0.04029575
07:03:21 MAPS NAV V1.6 GATE: istruzione ricevuta ma distanza reale alla manovra non esposta
```

### Interpretazione obbligatoria

Questo log **NON qualifica la vc31**.

Motivo:

- il sorgente vc31 emette marker `MAPS NAV V1.7 ...`;
- in failure deve poter emettere `MAPS NAV V1.7 FIELD DIAG ...`;
- il log fisico mostra invece esclusivamente il vecchio marker `MAPS NAV V1.6` e la vecchia riga classifier `confidence=0.04029575`.

Conclusione più probabile: sul telefono è stata eseguita una **V1.7 precedente (vc30 o altra build con versionName 1.7)**, non l'APK vc31 corrente. Il solo testo “MotoLink 1.7” non distingue vc30 da vc31 perché entrambe hanno `versionName = 1.7`.

Pertanto:

```text
Maps russo vc31 = NON ANCORA TESTATO FISICAMENTE
rotonde vc31 = NON ANCORA TESTATE FISICAMENTE
BLE 625 = PASS
```

Non fare altre modifiche a parser/rotonde sulla base di questo log finché non è provato che il telefono esegue `versionCode 31`.

---

# NEXT GATE OBBLIGATORIO — NUOVA CHAT

## A. Verificare l'APK installato prima di toccare il codice

APK autorevole da installare:

```text
MotoLink_V1.7.apk
SHA-256 = b0b1396176d4094d2042a52ba42c313fa0a527b0e2851e9d194f43a6ecd4a726
package = it.motolink.app
versionName = 1.7
versionCode = 31
certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
```

Non disinstallare l'app salvo diagnosi esplicita: la disinstallazione può cancellare configurazioni/dati. Installare l'APK vc31 **come aggiornamento** sopra quella esistente.

Se è disponibile ADB, verificare direttamente `versionCode=31`. In assenza di ADB, il primo nuovo log deve mostrare almeno un marker `MAPS NAV V1.7`; se compare ancora `MAPS NAV V1.6`, fermarsi: è ancora una build vecchia.

## B. Test controllato Maps russo + rotonda

1. arresto forzato di VOGE Global, senza disinstallarla;
2. quadro/TFT spento e riacceso;
3. aprire MotoLink vc31;
4. pulire il log locale;
5. START profilo BLE;
6. verificare `READY dopo ACK heartbeat 0x5A`;
7. aprire Google Maps con telefono in russo;
8. avviare una rotta reale con almeno una svolta ordinaria e una rotonda;
9. condividere il log completo.

### Marker attesi se vc31 è realmente in esecuzione

```text
MAPS NAV V1.7 ...
MAPS NAV V1.7 FIELD DIAG: ...     [se il parser non trova ancora un dato]
MAPS NAV V1.7 ML: ...             [classifier rotonda]
MAPS NAV V1.7: TBT reale accettato ... [quando il dato è completo]
```

## C. Decisione dopo il log vc31

```text
Caso 1: distanza/manovra accettata -> Maps russo PASS.
Caso 2: FIELD DIAG mostra campo concreto con distanza -> patch minima solo su quel campo/formato.
Caso 3: FIELD DIAG dimostra che Maps non espone la distanza -> valutare Navigation SDK / altra sorgente ufficiale, non regex casuali.
Caso 4: rotonda riconosciuta + settore/dir inviati ma TFT mostra solo freccia -> confrontare con VOGE Global sulla stessa 625 / HCI capture prima di cambiare protocollo.
Caso 5: ancora marker V1.6 -> installazione vc31 non riuscita; nessuna modifica codice autorizzata.
```

---

## 9. GitHub / distribuzione corrente

Repository pubblico:

`Mysterx1992/MotoLink`

Stato:

```text
main = V1.7 vc31 source
main commit = 195b988fa9af14d649d4904359cf1b34a5ddb4e2
Android CI = PASS
release = v1.7 public / non-prerelease
release APK digest = sha256:b0b1396176d4094d2042a52ba42c313fa0a527b0e2851e9d194f43a6ecd4a726
README main direct-download button = V1.7 direct APK
```

Non pubblicare nuove TEST release per questo issue. La linea corrente resta **V1.7 ufficiale** con incremento `versionCode` quando serve un aggiornamento installabile.

---

## 10. Regole permanenti di lavoro MotoLink

- Prima di modificare un componente già fisicamente validato, provare che il difetto appartiene a quel componente.
- BLE 625 READY non implica automaticamente parser Maps PASS: tenere distinti transport e source parsing.
- Non usare VOGE Global come spiegazione automatica se MotoLink raggiunge GATT READY; usarla come variabile controllata nei test.
- Nessuna modifica alla firma ufficiale.
- Nessun secret nel repository o nel master.
- Nessuna regressione EasyConn/H264/Pocket/clock per correggere Maps.
- Rotonde: non cambiare più byte VOGE a tentativi; richiedere evidenza fisica/OEM per ogni nuovo delta protocollo.
- Google Maps notification parsing: prima diagnosticare la superficie reale, poi applicare patch minima.
- Ogni APK ufficiale deve mantenere `applicationId = it.motolink.app` e certificato ufficiale.
- Per aggiornare una stessa versione pubblica, mantenere `versionName` se richiesto e incrementare `versionCode`.
- Il file master corrente è l'unico handoff da usare nella nuova chat.

---

# ARCHIVIO STORICO R67 E PRECEDENTI

> Tutto ciò che segue è mantenuto integralmente per tracciare esperimenti, build e decisioni precedenti.  
> Se una sezione storica contraddice lo **STATO AUTOREVOLE CORRENTE — 2026-10-09**, prevale sempre lo stato corrente sopra.

# MotoLink V1.0 / V1.x — MASTER R54

**Aggiornato:** 2026-09-12  
**Stato:** documento vivo autorevole per la linea MotoLink ufficiale V1.x.  
**Revisione:** R54  
**Master precedente:** `MOTOLINK_V1_0_MASTER_20260912_R53.md`

> **Regola di continuità**
>
> R44 aggiorna lo stato corrente dopo R43 e incorpora anche i test fisici, le diagnosi Wi‑Fi/VPN, le verifiche BLE, le ricerche di compatibilità e le nuove evidenze sulle rotonde raccolte fino al 11/09/2026.  
> Tutta la cronologia tecnica di R39 e dei master precedenti resta valida salvo esplicita sostituzione riportata qui.  
> In caso di conflitto sullo stato corrente, **R44 prevale**.

---

# SNAPSHOT CORRENTE — 2026-09-11

## A. Release pubblica corrente — MotoLink V1.5.1 — APPROVATA / PUBBLICATA / FROZEN

La release pubblica corrente resta:

```text
repository = Mysterx1992/MotoLink
applicationId = it.motolink.app
versionCode = 10
versionName = 1.5.1
GitHub release = v1.5.1
release target commit = 07c4767da2805aea8f2077437a4cdc5a99fad508
APK = MotoLink_V1.5.1.apk
APK SHA-256 = 18f413e93fe0a76055765bcd64f4654ebeb3641b1fe0a27c7bfd90505c55d21f
certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
```

La release GitHub più recente resta **v1.5.1**.

### Stato `main` al 10/09/2026

`main` non è più byte-identico al commit release V1.5.1 perché è stato aggiunto un aggiornamento documentale README:

```text
current main SHA = cf1519914610765336050310c1e57ac93a52cd53
commit = Add Voge Valico 625 dsx details to README
```

Il delta osservato è documentale sul `README.md`; **la sorgente applicativa V1.6 non è stata promossa su `main`** e non esiste una release pubblica V1.6.

Nota documentale aperta: la riga README aggiunta riporta l'anno `20025`; correggere il refuso in una futura revisione documentale se confermato.

---

# 1. LINEA V1.6 — STATO GENERALE

La V1.6 è ora una **candidate tecnica reale** e non più soltanto un concetto.

Regole correnti:

```text
versionName = 1.6
public release V1.6 = NON CREATA
public tag V1.6 = NON CREATO
V1.6 source promotion to main = NON ESEGUITA
technical branch = build/v1.6-official-candidate
official signing identity = PRESERVATA
```

Il core seguente resta da preservare salvo bug fisicamente dimostrato:

```text
EasyConn
H264
clock
network path validato
display geometry/adaptation
2x Volume Giù
signing identity
Supabase / AI security boundary
```

---

# 2. FIRMA UFFICIALE V1.6 — RECOVERY COMPLETATA

È stata recuperata e verificata la chiave ufficiale MotoLink già usata dalle release V1.2+.

Dati autorevoli:

```text
keystore SHA-256 = 0cd0da3b480c5b811e93c3c68d049c3df03f83f29febdc6c0ab4a0382324a96b
alias = motolink
key type = PrivateKeyEntry
RSA = 4096
official certificate SHA-256 =
9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
```

Profilo di firma verificato:

```text
v1 = false
v2 = false
v3 = true
v3.1 = false
v4 = false
```

Regola permanente: **non pubblicare né incorporare keystore, password, private key, `GITHUB_SECRETS.txt` o altri secret**.

---

# 3. PROFILI DI CONNESSIONE V1.6 — DECISIONE CORRENTE

Le scelte utente previste per i profili V1.6 sono soltanto:

```text
Hotspot
QrCode
BLE
```

`Automatico` è stato rimosso dalla UI corrente.

Comportamento dei profili legacy:

```text
profilo legacy AUTOMATIC
-> non viene reinterpretato silenziosamente come BLE
-> viene richiesta una scelta una tantum:
   Hotspot / QrCode / BLE
```

La migrazione dei vecchi profili V1.5.1 resta conservativa verso il percorso Hotspot/EasyConn quando necessario; il nuovo BLE non deve contaminare i profili EasyConn esistenti.

---

# 4. PROTOCOLLO BLE VOGE — FROZEN

Protocollo navigazione Voge corrente:

```text
SERVICE UUID
5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e

TX / WRITE UUID
6052202a-2928-4131-a2d0-456d5673ed2f

RX / NOTIFY UUID
ab9938d5-c354-4e2b-94f4-364e16ebcd33
```

Heartbeat:

```text
command = 0x5A
periodo = circa 1 Hz
```

Navigazione:

```text
0x6A
0x6B
0x6C
0x6D
0x6E
20 byte / frame
byte 18 = XOR checksum
byte 19 = 0x04
```

Direction mapping congelato:

```text
0 = unknown
1 = STRAIGHT
2 = UTURN_LEFT
3 = UTURN_RIGHT
4 = LEFT
5 = RIGHT
6 = SLIGHT_LEFT
7 = SLIGHT_RIGHT
8 = SHARP_LEFT
9 = SHARP_RIGHT
```

Road flags congelati:

```text
0 = normal
1 = tunnel
2 = roundabout
3 = service
4 = toll
```

---

# 5. GATT QUEUE DI PRODUZIONE — REGOLA FROZEN

Per V1.6 vale la regola:

```text
one write in flight = SÌ
heartbeat = solo quando idle
nav 0x6A..0x6E = callback-paced
busy/transient = retry serializzato
timeout callback = NON liberare artificialmente writeInFlight
callback mancante = reconnect
heartbeat = NON può interrompere sequenza nav
```

La caratteristica TX usa:

```text
BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
```

---

# 6. BUILD V1.6 CODE 12 — SUPERATA / NON USARE

Una build precedente code 12 ha mostrato un problema BLE reale:

```text
CCCD = status 0
READY dichiarato troppo presto
primo heartbeat immediato
write callback status = 3
reconnect loop
```

`status=3` corrispondeva a una scrittura non accettata in quel momento.  
Il write type era già `WRITE_TYPE_DEFAULT`; quindi il fix corretto non è stato cambiare arbitrariamente tipo di scrittura.

**Code 12 non deve essere usata come build finale.**

---

# 7. V1.6 CODE 13 — FIX BLE TIMING / READY VERIFICATO

La code 13 ha introdotto il fix BLE mirato:

```text
attesa 750 ms dopo CCCD
-> primo heartbeat 0x5A
-> READY solo dopo callback heartbeat status=0
```

Inoltre:

```text
status 3 deterministico -> stop/recovery senza reconnect storm
Maps -> aperta soltanto dopo BLE READY verificato
```

Dati build code 13:

```text
technical branch = build/v1.6-official-candidate
head SHA = d67918b393740bc3f1158a8ff1df2eb5a24c66ca
workflow run = 34441697300
artifact id = 10138219064
artifact digest =
sha256:191ed9ffc6f1e9e6381f887de783019d10b7399cba38291be074c3c6bd273a11

APK = MotoLink_V1.6_vc13.apk
versionCode = 13
versionName = 1.6
signed APK SHA-256 =
0bc290a9a01717e06333bbaf48a669e75527c9ae19cbd34649f88fbd2d9d359b
official cert = PRESERVATO
signature v3 = true
```

Code 13 ha chiuso il primo problema di handshake/READY, ma non il riconoscimento manovra Google Maps.

---

# 8. GOOGLE MAPS — DIAGNOSI SU CODE 13

Durante la prova reale è stato isolato il blocco successivo:

```text
BLE = connesso
heartbeat ACK = status 0
Notification Listener = operativo
distanza reale = disponibile
manovra = non classificabile
```

Messaggio diagnostico chiave osservato:

```text
MAPS NAV V1.6 GATE:
distanza reale presente ma manovra non classificabile;
TFT non aggiornato
```

Questa evidenza ha spostato il problema dal BLE al parser Maps.

---

# 9. V1.6 CODE 14 — MAPS MANEUVER FIX

La code 14 è la build corrente di verifica fisica Maps.

Dati build:

```text
versionCode = 14
versionName = 1.6
technical branch = build/v1.6-official-candidate
head SHA = 2c3eabf4a89a934e29085dff2a6c5a0115b10aaf
commit message = Build V1.6 code 14 with Maps maneuver fix

workflow run = 34498803348
workflow result = SUCCESS
artifact id = 10161057120
artifact digest =
sha256:f23b46f0b94f23eb21faef9f7e6853349a9570c4bdcc4bbecb7e103091fa415a

APK = MotoLink_V1.6_vc14_MapsFix.apk
APK size = 53,712,066 bytes
APK SHA-256 =
922e70ed04cabfdd63c68fedbb5054dee078f6dd7e3aa6db5be44b6a176bc192

certificate SHA-256 =
9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100

signature = APK Signature Scheme v3
```

---

# 10. CODE 14 — NUOVO PARSER MANOVRA MAPS

Il fix code 14 estende la sorgente Google Maps senza modificare il protocollo Voge.

Campi testuali letti:

```text
Notification.EXTRA_TITLE
Notification.EXTRA_TEXT
Notification.EXTRA_BIG_TEXT
Notification.EXTRA_SUB_TEXT
Notification.EXTRA_INFO_TEXT
Notification.EXTRA_SUMMARY_TEXT
notification.tickerText
```

In aggiunta, il reader prova a estrarre testo e grafica dalle `RemoteViews` della notifica Maps.

Fallback grafico:

```text
nav_notification_icon
right_icon
lockscreen_notification_icon
largeIcon
```

Strategia:

```text
1. tentativo classificazione TEXT
2. se manovra non classificabile -> tentativo ICON
3. se ancora ambigua -> NON inventare la manovra
```

Logging nuovo:

```text
source=GOOGLE_MAPS_NOTIFICATION_TEXT
oppure
source=GOOGLE_MAPS_NOTIFICATION_ICON
```

Il BLE, heartbeat, UUID e frame 0x6A..0x6E non sono stati cambiati dal fix Maps.

---

# 11. TEST FISICO CODE 14 — FRECCIA + DISTANZA — PASS

Il 10/09/2026 è stata ottenuta la prima evidenza fisica reale della pipeline completa:

```text
Google Maps
-> Notification Listener
-> maneuver icon parser
-> modello TBT MotoLink
-> coda BLE
-> frame navigazione Voge
-> TFT
```

Evidenza log:

```text
MAPS NAV V1.6:
TBT reale accettato
source=GOOGLE_MAPS_NOTIFICATION_ICON
maneuver=LEFT
distance=90m

BLE NAV V1.6:
TBT reale accodato
maneuver=LEFT
distance=90m
frames=5
```

Evidenza fisica TFT:

```text
freccia LEFT = visibile
distanza = 90 m
```

Verdetto:

```text
Maps notification reception = PASS
real maneuver extraction = PASS per LEFT osservato
real distance extraction = PASS per 90 m osservato
MotoLink normalized TBT = PASS
BLE enqueue = PASS
TFT render LEFT + 90 m = PASS FISICO
```

La code 14 ha quindi superato il primo gate reale **Maps -> TFT**.

---

# 12. CODE 14 — DUE PROBLEMI ANCORA APERTI

La build non è ancora promuovibile come release finale perché nel log code 14 sono emersi due punti da chiudere.

## 12.1 `STRAIGHT distance=0m` spurio

È stata osservata una sequenza incoerente:

```text
MAPS NAV V1.6 GATE:
istruzione ricevuta ma distanza reale alla manovra non esposta;
TFT non aggiornato

subito dopo:
TBT reale accettato
source=GOOGLE_MAPS_NOTIFICATION_ICON
maneuver=STRAIGHT
distance=0m
```

Questo `0m` **non deve essere accettato come distanza reale** se il gate ha appena stabilito che la distanza non è disponibile.

Fix futuro richiesto:

```text
NO real distance
-> NO TBT reale
-> NO 0x6A dichiarato reale
```

Eccezioni eventuali (es. arrivo/route completion) devono essere trattate esplicitamente e non dedotte automaticamente da `0m`.

## 12.2 Callback GATT mancante

Nel log è comparso:

```text
BLE NAV V1.6:
callback write assente;
riconnetto senza liberare artificialmente writeInFlight

Timeout callback GATT;
riconnessione programmata
```

Il comportamento di safety è coerente con la regola frozen della queue, ma resta da verificare in navigazione prolungata se:

```text
evento = raro/transitorio
oppure
evento = problema sistematico dopo una sequenza nav
```

Serve quindi un test reale più lungo prima della release.

---

# 13. GATE DI TEST SU STRADA CODE 14

Prima di promuovere V1.6 occorre una prova di almeno 10–15 minuti, preferibilmente con più manovre.

Controllare:

```text
LEFT
RIGHT
STRAIGHT
roundabout se disponibile
distanze che diminuiscono/cambiano
<100 m
>1000 m se disponibile
Maps in background
nessuna freccia spuriosa
nessun 0m spurio
nessun freeze TFT
nessuna perdita permanente della sessione BLE
reconnect pulito se necessario
STOP pulito
re-START pulito
```

Il primo PASS LEFT+90m è importante ma **non equivale ancora al PASS completo V1.6**.

---

# 14. GOOGLE MAPS IN BACKGROUND — COMPORTAMENTO CORRENTE

Per il funzionamento normale non è necessario lasciare Google Maps a tutto schermo.

Condizione utile:

```text
navigazione Maps = ATTIVA
Maps = può stare in background
notifica navigazione = presente
MotoLink NotificationListener = autorizzato
```

La Picture-in-Picture / finestrella flottante non è un requisito.

---

# 15. ACCESSO ALLE NOTIFICHE — CHIARIMENTO

Il permesso BLE `Dispositivi vicini` e l'`Accesso alle notifiche` sono due meccanismi Android distinti.

MotoLink usa un:

```text
NotificationListenerService
```

per leggere la notifica di navigazione di Google Maps.

L'utente ha chiarito che sul dispositivo testato l'accesso speciale alle notifiche **era già attivo** prima della prova; quindi quel permesso non era la causa del precedente problema di frecce.

`POST_NOTIFICATIONS` non è il permesso necessario per leggere le notifiche altrui e **non va aggiunto soltanto per far comparire MotoLink nella normale lista delle notifiche app**.

---

# 16. PROSSIMA VERSIONE — UX OBBLIGATORIA PER ACCESSO NOTIFICHE MAPS

Decisione utente del 10/09/2026: **salvare questa aggiunta per la prossima versione**.

Comportamento richiesto:

```text
START con profilo BLE
-> controlla se MotoLink Notification Listener è autorizzato
```

Se autorizzato:

```text
nessun popup inutile
-> continua con BLE
-> Maps
```

Se NON autorizzato:

```text
bloccare il normale avvio navigazione
mostrare spiegazione chiara
pulsante = ATTIVA ACCESSO NOTIFICHE
```

Testo UX consigliato, da finalizzare:

> Per inviare le indicazioni di Google Maps al display della moto, MotoLink necessita dell'Accesso alle notifiche.

### Apertura impostazioni indipendente dall'OEM

MotoLink non deve affidarsi a istruzioni manuali tipo:

```text
Impostazioni -> Notifiche -> ...
```

perché i nomi e i percorsi cambiano tra Motorola, Samsung, Xiaomi, Pixel, OnePlus e versioni Android.

Implementazione richiesta:

```text
1. tentare pagina dettaglio del Notification Listener MotoLink
   Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS

2. se non supportata:
   Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS

3. se ancora non supportata:
   fallback a Settings standard / impostazioni app compatibili
```

Android/OEM mostrerà la propria UI; MotoLink non deve conoscere in anticipo il percorso specifico del produttore.

Quando l'utente torna a MotoLink:

```text
ricontrollare realmente lo stato
ATTIVO -> procedere
NON ATTIVO -> restare nel gate e spiegare il requisito
```

Aggiunta UI consigliata e approvata come requisito futuro:

```text
Accesso notifiche Maps: ATTIVO
oppure
Accesso notifiche Maps: NON ATTIVO
```

con pulsante:

```text
ATTIVA
```

MotoLink **non può e non deve attivare autonomamente l'interruttore Android**: l'azione finale resta sotto controllo dell'utente.

Questa modifica è **NEXT VERSION / NON ANCORA APPLICATA ALLA CODE 14**.

---

# 17. DOPPIO VOLUME GIÙ — STATO CORRETTO

La linea corrente ha già dimostrato che il comportamento manuale 2x Volume Giù esiste nel `MirrorService` ed è indipendente dal toggle Modalità tasca.

Regola frozen:

```text
2x Volume Giù = comando manuale
Modalità tasca = proximity / comportamento automatico
```

Non introdurre modifiche al core del doppio Volume Giù durante i fix BLE/Maps salvo bug fisicamente dimostrato.

---

# 18. PLAY STORE ITALIA — VALUTAZIONE PRELIMINARE

È stata discussa la futura pubblicazione MotoLink su Google Play in Italia.

Verdetto di pianificazione:

```text
blocco legale strutturale dell'app = NON EVIDENZIATO
pubblicabilità = PLAUSIBILE
compliance review dedicata prima dello Store = NECESSARIA
```

Aree da preparare prima di Play Store:

```text
Privacy Policy
Google Play Data Safety
disclosure in-app per Notification Listener
dichiarazione/uso MediaProjection e foreground service
Android App Bundle (.aab)
Play App Signing / continuità della chiave ufficiale
descrizione corretta di compatibilità con marchi terzi
disclaimer di indipendenza da Voge / Google / altri produttori
linguaggio di sicurezza: configurazione da fermi / no invito all'uso manuale durante la guida
eventuali requisiti di closed testing dell'account sviluppatore
```

La base Android corrente è già orientata a:

```text
compileSdk = 36
targetSdk = 36
```

e non usa `QUERY_ALL_PACKAGES` per la lista app preferite.

### Regola di sequenza

La fase Play Store deve essere separata dai fix V1.6:

```text
1. chiudere tecnicamente V1.6
2. ottenere PASS fisico su strada
3. congelare candidate
4. solo dopo avviare PLAY STORE COMPLIANCE
```

Non introdurre cambi compliance mentre si sta ancora verificando la stabilità BLE/Maps, salvo necessità tecnica inevitabile.

---

# 19. RELEASE NOTES V1.6 — NON ANCORA CONGELATE

La formulazione finale delle release notes V1.6 non è ancora approvata.

In particolare, evitare formulazioni troppo generiche come:

```text
Migliorata la gestione della connessione e della riconnessione alla moto.
```

quando il delta riguarda specificamente BLE.

Formulazione tecnica più aderente proposta:

```text
Migliorata la stabilità della connessione Bluetooth BLE con la moto.
```

La prima voce delle release notes dovrà inoltre riflettere il fatto che `Automatico` è stato rimosso e che le modalità utente sono:

```text
Hotspot
QrCode
BLE
```

Nessuna release note definitiva va pubblicata prima del freeze finale.

---

# 20. GITHUB / PROMOZIONE V1.6 — REGOLA CORRENTE

Nonostante l'uso della branch tecnica per compilare:

```text
branch = build/v1.6-official-candidate
```

non è stata autorizzata/promossa una release pubblica V1.6.

Regola:

```text
NO tag pubblico V1.6
NO release pubblica V1.6
NO promozione sorgente V1.6 su main
```

finché:

```text
test strada = PASS
0m spurio = risolto o dimostrato innocuo e corretto
callback GATT missing = compreso/accettato
regressione Hotspot/QrCode/EasyConn = PASS
firma = verificata
release notes = congelate
utente = autorizza esplicitamente la pubblicazione
```

---

# 21. SNAPSHOT STORICO PRE-VC15 — SUPERATO DALLE SEZIONI 29+

```text
PUBLIC RELEASE
  V1.5.1 = PUBBLICATA
  latest release = v1.5.1
  release target = 07c4767da2805aea8f2077437a4cdc5a99fad508
  APK SHA-256 = 18f413e93fe0a76055765bcd64f4654ebeb3641b1fe0a27c7bfd90505c55d21f

CURRENT MAIN
  SHA = cf1519914610765336050310c1e57ac93a52cd53
  latest observed delta = README-only
  V1.6 app source = NON PROMOSSA

SIGNING
  official cert SHA-256 =
  9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
  v3 = true
  private signing material = NON PUBBLICARE

V1.6 CONNECTION MODES
  Hotspot = SÌ
  QrCode = SÌ
  BLE = SÌ
  Automatico = RIMOSSO
  legacy AUTOMATIC = scelta esplicita una tantum

BLE CODE 13
  CCCD -> wait 750ms -> heartbeat 0x5A -> ACK status0 -> READY
  handshake fix = PASS
  signed APK SHA-256 =
  0bc290a9a01717e06333bbaf48a669e75527c9ae19cbd34649f88fbd2d9d359b

MAPS CODE 14
  versionCode = 14
  versionName = 1.6
  signed APK SHA-256 =
  922e70ed04cabfdd63c68fedbb5054dee078f6dd7e3aa6db5be44b6a176bc192
  parser TEXT + RemoteViews + ICON fallback = IMPLEMENTATO
  LEFT + 90m real TFT = PASS FISICO

OPEN ISSUES
  STRAIGHT 0m spurio = APERTO
  missing GATT write callback/reconnect = APERTO
  long road test = PENDING
  multi-maneuver validation = PENDING
  Hotspot/QrCode/EasyConn regression = PENDING

NEXT VERSION REQUIREMENT
  Notification Listener state check = DA IMPLEMENTARE
  direct Android settings opening = DA IMPLEMENTARE
  OEM-specific manual menu paths = NON NECESSARI
  ATTIVO/NON ATTIVO status UI = DA IMPLEMENTARE
  user must enable Android switch manually = SÌ

PLAY STORE
  future compliance phase = PREVISTA
  pubblicazione immediata = NO
```

---

# 22. PRIORITÀ IMMEDIATA

```text
1. completare test strada code 14
2. verificare più manovre e distanze reali
3. correggere/scartare TBT spurio distance=0m
4. capire frequenza/root cause del missing GATT callback
5. regressione Hotspot/QrCode/EasyConn
6. implementare nella prossima versione il gate Accesso notifiche Maps
7. solo dopo freeze tecnico valutare Play Store Compliance
8. pubblicare V1.6 solo dopo autorizzazione esplicita utente
```

---

# 23. CONTINUITÀ CON R39

R40 non cancella il contenuto storico di:

```text
MOTOLINK_V1_0_MASTER_20260909_R39.md
```

R39 resta il riferimento storico per:

```text
reverse engineering Voge
NavBridge standalone
frame byte-exact
Google Maps diagnostic iniziale
V1.5.1 baseline
AI security
clean-room / interoperability
lineage precedente V1.x
```

R40 sostituisce invece lo stato corrente di R39 nei punti in cui R39 riportava:

```text
V1.6 coding/build = non iniziato
real maneuver distance Maps = blocco aperto assoluto
V1.6 = solo concetto
```

Stato nuovo:

```text
V1.6 candidate = costruita
BLE handshake = corretto e fisicamente verificato
Maps maneuver + real distance = PASS fisico almeno per LEFT 90m
release finale = ancora PENDING
```

---



# 24. TEST STRADA BLE VALICO 625DSX — PASS CON DUE ISSUE ISOLATE

Test fisico reale del 11/09/2026 su Voge Valico 625DSX con MotoLink V1.6 code 14:

```text
BLE service = 5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e
CCCD status = 0
post-CCCD wait = 750 ms
heartbeat 0x5A ACK = status 0
READY = PASS
road TBT LEFT/RIGHT + distanza = PASS fisico
```

Il log mostra numerose sequenze reali LEFT e RIGHT con distanza progressiva fino a 0 m. Il tester riferisce funzionamento molto buono su strada.

Due problemi separati sono stati osservati:

```text
A) rotonde / uscite non mostrate
   11:23:12..11:23:28 e 11:24:48..11:24:59
   Maps distance presente ma manovra non classificabile
   BLE ancora sano durante questi eventi

B) stabilità BLE lunga percorrenza
   11:26:50 callback write GATT assente
   recovery successiva entra in ripetuti status=147 / state=0
   connessione non recupera fino a fine log
```

Interpretazione congelata: A è un limite del parser/icona Maps, non del trasporto BLE; B è un problema distinto nel recovery GATT dopo callback mancante.

---

# 25. RICERCA ROTONDE GOOGLE — RISULTATO

La documentazione ufficiale Google Navigation SDK conferma che il modello di navigazione Google distingue nativamente molte manovre di rotatoria, incluse:

```text
ROUNDABOUT_CLOCKWISE / COUNTERCLOCKWISE
ROUNDABOUT_LEFT / RIGHT / SLIGHT / SHARP / STRAIGHT / U-TURN
ROUNDABOUT_EXIT_CLOCKWISE / COUNTERCLOCKWISE
roundaboutTurnNumber / exit number
```

MotoLink però NON usa il turn-by-turn feed del Navigation SDK: legge la notifica pubblica dell'app Google Maps tramite NotificationListenerService. Quindi questi enum/exit number non sono disponibili automaticamente nel percorso corrente.

Decisione vc15: aggiungere un riconoscitore conservativo dell'icona di rotatoria nel RemoteViews della notifica, basato su un loop centrale chiuso. Se l'evidenza non è sufficiente, la manovra resta UNKNOWN e non viene inviata al TFT. Per gli UNKNOWN viene scritto soltanto un fingerprint binario 8x8 privacy-safe dell'icona, senza salvare bitmap o testo della rotta.

Il protocollo Voge già implementato supporta:

```text
NavManeuver.ROUNDABOUT -> directionCode 1
roadFlag = 2
```

Quindi vc15 può provare a mostrare una rotonda generica senza cambiare packet format. NON è stata inventata la semantica di `annularDegrees` e NON viene ancora promesso il numero esatto dell'uscita: questo richiede evidenza fisica/protocollo aggiuntiva.

---

# 26. V1.6 CODE 15 — ROUNDABOUT + BLE ROAD STABILITY CANDIDATE

Branch tecnico soltanto:

```text
branch = build/v1.6-official-candidate
patch commit = c145a358cd30e31a0edd5799eee7817111ca87b1
build head = f3d2a2192205eb33e038d7a186211ac76abb41e9
workflow run = 34591343563
job = 103237103550
result = SUCCESS
artifact ID = 10195806359
artifact digest SHA-256 = 7a7172ba55920a0f9c73e41363dc5d95d960ac8c8dac60e3a5f84d451ade5f4e
unsigned APK SHA-256 = 5da982fbef346590d7795f5269caae4fb85225bf44ca63ab58af6444549af4a6
```

Build identity verificata in CI:

```text
applicationId = it.motolink.app
versionCode = 15
versionName = 1.6
compileSdk = 36
targetSdk = 36
BUILD SUCCESSFUL
```

Modifiche vc15 limitate a due aree:

```text
MAPS / ROTONDE
- riconoscimento conservativo di glyph con loop centrale chiuso
- roadFlag Voge 2 per ROUNDABOUT già esistente
- fingerprint privacy-safe 8x8 per icone UNKNOWN
- nessuna supposizione su exit number / annularDegrees

BLE RECOVERY
- isReady richiede transportVerified
- NAV bloccato fino al primo heartbeat ACK dopo reconnect
- callback da vecchi GATT ignorati
- callback write assente -> cooldown + fresh BLE scan
- backoff recovery 2.5 / 4 / 6 / 10 s
- dopo failure ripetute evita hammering immediato del cached GATT
- reconnectAttempt reset solo dopo heartbeat verificato o nuovo STOP
- UUID, WRITE_TYPE_DEFAULT, frame 20-byte e normale pacing invariati
```

APK firmato ufficialmente:

```text
APK = MotoLink_V1.6_vc15_Roundabout_BLEStability.apk
APK SHA-256 = bb4fc07fad81604147a483700d09c6195edb0080a3ed39d9be618b1ddb2d2d4d
certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
v1 = false
v2 = false
v3 = true
v3.1 = false
v4 = false
ZIP integrity = PASS
```

Stato fisico vc15:

```text
compile/static/package/signing = PASS
roundabout TFT physical test = PENDING
roundabout exit number = NON IMPLEMENTATO / NON AUTORIZZATO SENZA EVIDENZA
long-road BLE recovery = PENDING PHYSICAL TEST
```

---

# 27. STATO PUBBLICO DOPO VC15

Nessuna promozione pubblica è stata eseguita:

```text
main = cf1519914610765336050310c1e57ac93a52cd53 (immutato)
latest public release = v1.5.1
public V1.6 tag = NON CREATO
public V1.6 release = NON CREATA
vc15 = SOLO candidate tecnica di test
```

---

# 28. PIANO DI TEST VC15 — STORICO / ORA ESEGUITO

Usare preferibilmente la stessa Valico 625DSX che ha prodotto il PASS su strada code 14.

Controllare in un percorso con almeno 2-3 rotatorie e una sessione abbastanza lunga da attraversare il precedente punto di failure:

```text
1. CCCD -> 750 ms -> heartbeat ACK -> READY
2. LEFT/RIGHT continuano a funzionare
3. ingresso rotatoria: verificare se compare simbolo roundabout TFT
4. uscita rotatoria: annotare cosa appare sul TFT
5. condividere log; cercare ROUNDABOUT o ICON DIAG fp=...
6. proseguire oltre 10-15 minuti per verificare recovery BLE
7. se manca callback GATT, verificare che recovery usi fresh scan e torni READY
```

Se la rotonda resta UNKNOWN, il fingerprint del glyph permetterà una classificazione successiva fondata su evidenza reale. Se compare la rotonda ma non il numero/uscita esatta, NON mappare `annularDegrees` a tentativi: prima raccogliere frame/evidenza specifica.

---

# 29. VC15 — TEST FISICO ROTONDE SU VALICO 625DSX — ESITO REALE

Il test fisico vc15 è stato eseguito il 11/09/2026 sulla Valico 625DSX.

File evidenza principale:

```text
MotoLink_Log_20260911_141658.txt
```

Il percorso BLE iniziale è risultato corretto:

```text
14:07:33 connection state status=0 state=2
14:07:33 notify CCCD status=0
14:07:33 attesa 750 ms
14:07:34 heartbeat 0x5A ACK status=0
14:07:34 READY verificato
```

Quindi:

```text
BLE discovery/connection = PASS
CCCD = PASS
timing 750 ms = PASS
heartbeat = PASS
READY = PASS
```

Le manovre ordinarie LEFT / RIGHT e le distanze continuano ad essere generate e accodate correttamente.

---

# 30. ROTONDE — RISULTATO VC15: RICONOSCIMENTO MAPS PASS, CODIFICA TFT FAIL

La vc15 ha dimostrato una distinzione importante.

## 30.1 Google Maps -> MotoLink

MotoLink riconosce realmente una sequenza come:

```text
maneuver=ROUNDABOUT distance=1000m
...
maneuver=ROUNDABOUT distance=100m
...
maneuver=ROUNDABOUT distance=0m
```

Quindi il problema "Maps non riconosce la rotonda" NON è più valido in senso generale.

Stato:

```text
riconoscimento glyph ROUNDABOUT = PASS fisico/log
distanza rotonda = PASS
```

## 30.2 MotoLink -> TFT

La codifica sperimentale vc15:

```text
ROUNDABOUT -> directionCode = 1
roadFlag = 2
```

NON può essere considerata corretta.

Il tester ha riferito che in una rotonda in cui doveva prendere immediatamente la prima uscita a destra, il TFT ha mostrato una freccia a sinistra.

Verdetto:

```text
ROUNDABOUT Maps recognition = PASS
ROUNDABOUT TFT semantic rendering = FAIL
roundabout exit selection = NON IMPLEMENTATA
prima uscita/destra = NON CODIFICATA CORRETTAMENTE
```

Questa evidenza supersede la precedente ipotesi secondo cui `roadFlag=2` fosse sufficiente per ottenere una rotonda corretta sul TFT.

Regola nuova:

```text
NON promuovere la mappatura vc15 ROUNDABOUT
NON inventare nuovi byte/flag a tentativi
mantenere logging diagnostico
```

---

# 31. ROTONDE — FINGERPRINT ICONA MAPS RACCOLTI

Nel test vc15 sono stati osservati due fingerprint privacy-safe di icone Maps non classificate:

```text
006064f6dff66400
0006266ffb6f2600
```

Il primo compare prima di una sequenza ROUNDABOUT riconosciuta; il secondo compare dopo la sequenza.

Non è autorizzato dedurre automaticamente che rappresentino:

```text
ingresso rotonda
uscita rotonda
prima/seconda/terza uscita
```

senza ulteriore evidenza.

Sono però marker diagnostici utili per futuri confronti su percorsi noti.

---

# 32. ICONA "OMINO" SUL TFT — OSSERVATA, SIGNIFICATO NON DETERMINATO

È stata ricevuta una foto del TFT durante il test vc15 che mostra:

```text
freccia verticale / dritto
distanza 650 m
icona aggiuntiva simile a omino/persona dentro un riquadro
```

Punto fondamentale:

```text
NON è noto se la foto sia stata scattata in prossimità della rotonda.
```

Quindi NON si deve legare automaticamente l'icona "omino" a:

```text
roundabout
roadFlag=2
uscita
service
pedone
```

Il simbolo resta:

```text
UNEXPLAINED TFT STATUS/ROAD ICON
```

e va trattato separatamente dalla diagnosi rotonde finché non viene riprodotto in un momento noto.

---

# 33. VC15 — BLE RECOVERY DOPO CALLBACK MANCANTE — TEST FISICO PARZIALE

Nel test vc15 il problema di lunga percorrenza si è ripresentato:

```text
14:15:48 callback write assente
Timeout callback GATT
recovery attempt=1 delay=6000ms freshScan=true
14:15:54 recovery -> nuova scansione BLE prima di connectGatt
14:16:01 unavailable=Nessuna moto BLE compatibile trovata
```

Interpretazione:

```text
nuovo recovery vc15 = ESEGUITO come progettato
fresh scan dopo timeout = PASS come comportamento
ritorno a READY = NON AVVENUTO nel segmento osservato
```

Quindi la stabilità BLE lunga percorrenza NON è ancora PASS.

La vc15 ha migliorato il comportamento rispetto al vecchio loop status=147, ma il recovery non può essere dichiarato risolto.

Possibile pista tecnica da verificare:

```text
alcune Voge possono non advertising il service vendor BLE nel pacchetto di advertising
un fresh scan troppo filtrato può quindi non ritrovare subito la moto
```

Questa pista è coerente con evidenza open-source esterna su Voge, ma NON è ancora provata come root cause del recovery 625DSX.

---

# 34. VALICO 900DSX SENZA WI‑FI — STATO CORRENTE

È in test una Valico 900DSX a Latina, versione senza mirroring Wi‑Fi.

Classificazione più probabile:

```text
generazione = MY2024 / prima generazione
TFT family probabile = SS621X11
connettività = Bluetooth 1 + Bluetooth 2 / BLE-VOGE
Carbit/Wi-Fi mirroring = assente
```

L'identificazione `SS621X11` resta da confermare con foto reale della schermata versione:

```text
HW
OS
HMI
MCU
```

Comportamento osservato con MotoLink:

```text
BLE si collega
ma sul TFT non compaiono le frecce
```

Questo problema è distinto dalla 625DSX, dove LEFT/RIGHT + distanza sono stati fisicamente mostrati.

Ipotesi aperte:

```text
A) stesso GATT ma handshake/stato applicativo differente
B) stesso GATT ma formato TBT differente
C) stessa caratteristica ma pacchetti MotoLink non interpretati come NAV dal firmware SS621X11
D) pagina/stato TFT navigazione non correttamente attivato
```

NON è autorizzato assumere automaticamente che il protocollo applicativo della 625DSX sia identico a quello della 900DSX solo perché entrambe usano `BLE-VOGE`.

---

# 35. RICERCA ESTERNA / OPEN SOURCE SU VOGE BLE — RISULTATI

È stata trovata evidenza open-source nel progetto MOTO-HUB.

Su una Voge 800 Rally è stata trascritta una tabella GATT reale con:

```text
service
5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e

write
6052202a-2928-4131-a2d0-456d5673ed2f

notify
ab9938d5-c354-4e2b-94f4-364e16ebcd33
```

Questi UUID coincidono con il canale MotoLink già validato sulla 625DSX.

Nello stesso progetto esiste anche una field note su una Valico 900 in cui una scansione BLE filtrata sui service seriali conosciuti non vedeva il TFT, pur essendo il quadro acceso e operativo. Il diagnostico è stato successivamente reso unfiltered per scoprire il vero advertising/GATT.

Interpretazione autorizzata:

```text
stessa famiglia vendor BLE = plausibile
stesso service GATT su più modelli = supportato
stesso comportamento advertising = NON garantito
stesso protocollo NAV applicativo = NON dimostrato
```

Al momento NON è stata trovata una implementazione open-source affidabile che documenti byte per byte:

```text
rotonda Voge
numero uscita rotonda
Valico 900 SS621X11 TBT specifico
```

Quindi questi punti restano reverse-engineering pending.

---

# 36. VOGE GLOBAL / HCI — PIANO DI VERIFICA PROTOCOLLO

La prova più forte, se disponibile, resta:

```text
VOGE Global ufficiale
+ Bluetooth HCI snoop log Android
+ una manovra nota
```

Obiettivo:

```text
confrontare byte per byte ciò che VOGE Global scrive
contro ciò che MotoLink scrive
```

Per la Valico 900 servirebbe idealmente:

```text
RIGHT semplice
LEFT semplice
```

Per la 625DSX servirebbe idealmente:

```text
rotonda
prima uscita a destra
eventuale seconda/terza uscita
```

L'utente ha chiarito che il tester 625 non usa normalmente VOGE Global. Quindi questa prova non è un prerequisito obbligatorio, ma resta il metodo di riferimento se non si trova una sorgente protocollo alternativa.

---

# 37. NOTIFICATION LISTENER — REQUIREMENT ANCORA NON IMPLEMENTATO IN VC15

La vc15 NON contiene ancora il gate UX richiesto per l'Accesso speciale alle notifiche.

Requisito next build:

```text
START BLE
-> verifica accesso NotificationListenerService
```

Se già attivo:

```text
continua senza popup
```

Se disattivo:

```text
mostra spiegazione
pulsante ATTIVA ACCESSO NOTIFICHE
apre impostazioni Android del Notification Listener
al ritorno ricontrolla realmente lo stato
solo se ATTIVO continua
```

UI impostazioni prevista:

```text
Accesso notifiche Maps: ATTIVO
Accesso notifiche Maps: NON ATTIVO
```

Non confondere con:

```text
POST_NOTIFICATIONS
```

che è un permesso diverso.

---

# 38. VPN — ROOT CAUSE CONFERMATA PER IL FALLIMENTO EASYCONN OSSERVATO

È stato eseguito un A/B reale su MotoLink V1.5.1 / Trofeo 500.

Con VPN attiva:

```text
mDNS trova EasyConn TFT a 10.110.155.85:10930
EC init tenta connessione al TFT
source locale osservata = 10.2.0.2
connessione TCP fallisce
```

La `10.2.0.2` era coerente con il tunnel VPN.

Dopo disattivazione VPN, con lo stesso TFT:

```text
mDNS trova 10.110.155.85:10930
EC INIT OK (0x11)
PXC #1 = handshake OK CAR_CTRL
PXC #2 = SECONDARY HELLO ACK CAR_DATA
MEDIA 10921 = connesso
TFT richiede 800x480
H264 10920 = connesso
H264 FIRST FRAME = PASS
stream accepted > 0
consumer=true
```

Verdetto:

```text
per quella VPN/configurazione specifica
VPN = blocker diretto dell'outbound EasyConn locale
```

NON generalizzare a tutte le VPN: alcune consentono LAN bypass/split tunneling.

Requisito consigliato next build:

```text
profilo Hotspot/EasyConn
+ VPN attiva
-> avviso chiaro prima dello START
```

Possibile messaggio:

> È attiva una VPN. Alcune VPN possono impedire a MotoLink di raggiungere il display della moto sulla rete locale.

Opzioni UX da finalizzare:

```text
CONTINUA
ANNULLA
eventuale APRI IMPOSTAZIONI VPN
```

---

# 39. TROFEO 500 — TOPOLOGIA WI‑FI CORRETTA

Topologia corretta confermata:

```text
PHONE = hotspot / access point
TROFEO 500 TFT = client Wi‑Fi
SSID hotspot telefono = VOGE-36f
password = quella mostrata dal TFT / gestita dall'utente
```

Regola privacy/sicurezza:

```text
NON loggare
NON hardcodare
NON riportare nei pacchetti
la password hotspot
```

La precedente interpretazione "telefono client della rete moto" era errata per questa Trofeo.

---

# 40. TROFEO 500 — WI‑FI BLOCCATO E RECOVERY DOPO DIAGNOSTICA

Sintomo storico:

```text
TFT non entrava nell'hotspot
più telefoni avevano lo stesso problema
disconnessione batteria non risolveva
Bluetooth continuava a funzionare
```

Successivamente, dopo l'uso dell'APK diagnostico, il Wi‑Fi ha ripreso a collegarsi.

Il tester ha poi chiarito:

```text
prima del diagnostico non funzionava neanche con dati mobili + hotspot
dopo il diagnostico ha ricominciato a collegarsi normalmente
```

Quindi NON è corretto attribuire il recovery al passaggio:

```text
Wi-Fi casa OFF
dati mobili ON
```

La diagnostica resta il principale evento precedente al recovery.

Il pulsante 1 del diagnostico NON era puramente passivo: eseguiva una combinazione di:

```text
Bluetooth Classic discovery
SDP UUID request
BLE scan
possibile BLE GATT connect
discoverServices()
```

senza scritture applicative intenzionali al TFT.

Il tester riferisce che il Wi‑Fi può tornare dopo 1-3 controlli/premute.

Ipotesi operativa:

```text
una delle attività Bluetooth può risvegliare/reinizializzare
uno stato bloccato del modulo combo BT/Wi‑Fi/TFT
```

Questa è una ipotesi plausibile ma NON ancora causalmente isolata.

Se il problema ricompare, il test corretto è separare:

```text
SDP only
BLE scan only
GATT connect only
GATT discoverServices only
Wi-Fi observation only
```

e misurare esattamente quale passaggio precede il ritorno del client TFT nell'hotspot.

---

# 41. DIAGNOSTICA TROFEO 500 — STATO BRANCH

Branch isolato:

```text
diagnostic/trofeo500-wifi-v1
```

Le prime build V1 / V1.0.1 erano state progettate con la topologia sbagliata telefono-as-client; quindi:

```text
Wi-Fi scan
WifiNetworkSpecifier
```

NON sono diagnostiche decisive per la topologia reale Trofeo 500.

Restano invece utili le osservazioni Bluetooth:

```text
paired candidate VOGE-36f
Classic discovery
SDP
BLE scan
```

Durante la preparazione di un diagnostico corretto hotspot/TFT-client è stato fatto un commit branch-only parziale:

```text
commit = cdcacd71a2bb8eeb058f412a9866259818bc36c1
file = diag-trofeo500/src/main/java/it/motolink/diag/trofeo500/MainActivity.java
blob SHA = 9172d7a0727ad697ef94f11549e2c35252f3483e
```

Dopo lo STOP utente:

```text
nessun APK nuovo
nessun workflow build
nessuna modifica main
nessuna release pubblica
```

Non continuare/revertire questo lavoro senza nuova autorizzazione.

---

# 42. DIAGNOSTICA HOTSPOT TROFEO — DESIGN CORRETTO SE SERVIRÀ

Se il problema Wi‑Fi ricompare, il diagnostico corretto deve osservare il telefono come AP/hotspot, NON tentare di collegarlo alla rete moto.

Segnali utili:

```text
interfacce/IP locali accessibili
VPN TRANSPORT_VPN
mDNS _EasyConn._tcp.
inbound TCP canary accept-only su:
10922 PXC
10921 Media
10920 H264
```

Regola canary:

```text
accept remote IP/port
chiudere subito
nessun payload
nessun comando TFT
MotoLink chiuso durante il test
```

Interpretazione:

```text
mDNS o inbound 10920-10922
-> TFT ha raggiunto il telefono a livello IP

hotspot stabile ma nessun mDNS/inbound su più tentativi
-> forte sospetto associazione TFT/AP, non prova assoluta

un altro telefono funziona
-> configurazione hotspot telefono specifico più probabile
```

Android pubblico può non esporre tutti i client tethered; non promettere telemetria che l'OS non concede.

---

# 43. COMPATIBILITÀ MODELLI VOGE — SNAPSHOT DI RICERCA

Questa sezione NON equivale a compatibilità MotoLink fisicamente verificata. Distingue documentazione Voge / ricerca / test reali.

## Valico 900DSX

```text
MY2024 / prima generazione:
- doppio Bluetooth
- VOGE- + BLE-VOGE-
- Turn-by-Turn Voge
- mirroring Wi-Fi/Carbit non documentato
- famiglia TFT probabile SS621X11

MY2025+:
- doppio Bluetooth
- Wi-Fi / Carbit / mirroring
- famiglia TFT SS621X15 riportata da dati proprietari/community
```

Nota:

```text
anno immatricolazione != sempre anno modello
```

Usare la schermata HW/OS/HMI/MCU come identificatore più affidabile quando disponibile.

## Valico 800DS Rally

```text
MY2025+
doppio Bluetooth + Carbit/Wi-Fi
stesso vendor GATT BLE osservato in evidenza open-source 800 Rally
```

## Valico 625DSX

```text
MY2025+
doppio Bluetooth
MotoLink LEFT/RIGHT + distanza = PASS fisico
roundabout = NON ANCORA CORRETTA
BLE long-road recovery = ANCORA APERTO
```

## Valico 525DSX

```text
dal 2023 circa
doppio Bluetooth documentato
MotoLink physical compatibility = NON ANCORA certificata
```

## Trofeo 500AC

```text
Wi-Fi/EasyConn esiste su configurazioni reali
manuali italiani storici mostrano differenze di mercato/disponibilità
MotoLink EasyConn su TFT reale = fisicamente dimostrato
```

## Trofeo 500ACX

```text
modello esistente in alcuni mercati
tipo protocollo / compatibilità MotoLink = DA VERIFICARE
```

## Trofeo 525ACX

```text
Bluetooth documentato
secondo canale BLE/doppio Bluetooth per mercato italiano = NON CONFERMATO
```

## Brivido 625R

```text
MY2025+
doppio Bluetooth documentato
MotoLink physical compatibility = NON ANCORA certificata
```

## Brivido 125R / 125S

```text
documentazione italiana corrente = LCD
doppio Bluetooth non confermato
non classificare come compatibili BLE MotoLink senza nuova evidenza
```

## Sfida SR3

```text
differenze di mercato/manuale
alcune documentazioni estere mostrano VOGE- + BLE-VOGE-
versione italiana = da verificare fisicamente
```

## Sfida SR4 Max

```text
Bluetooth documentato
Wi-Fi osservato/documentato principalmente per dashcam/registratore
NON classificare automaticamente come EasyConn/Carbit MotoLink
```

## Sfida SR450X

```text
MY2026
Carbit/mirroring documentato
trasporto/protocollo esatto MotoLink = ancora da confermare
```

Regola database futura:

```text
colonna 1 = connettività Voge accertata
colonna 2 = compatibilità MotoLink fisicamente verificata
```

Non confondere le due.

---

# 44. CODA NAV / COALESCENZA — CANDIDATA, NON ANCORA APPLICATA

Durante le prove reali Maps aggiorna la distanza frequentemente:

```text
300
290
280
...
20
10
0
```

Ogni nuovo aggiornamento genera attualmente una sequenza di 5 frame.

Possibile miglioramento:

```text
frame già in flight = non interrompere
vecchi frame NAV ancora pending = eliminare
inserire soltanto lo stato NAV più recente
heartbeat sempre subordinato a NAV
```

Obiettivo:

```text
ridurre traffico inutile
ridurre backlog
ridurre probabilità di stress GATT
```

Stato:

```text
CANDIDATE FIX
NON IMPLEMENTATO IN VC15
NON ancora fisicamente validato
```

---

# 45. NEXT BUILD — CONTENUTO RACCOMANDATO

Per la prossima candidate tecnica, senza promozione pubblica:

```text
OBBLIGATORIO / BASSO RISCHIO
- gate Accesso speciale notifiche Maps
- stato ATTIVO / NON ATTIVO
- apertura impostazioni Notification Listener
- warning VPN per Hotspot/EasyConn

ROTONDE
- NON considerare valida la codifica vc15
- mantenere diagnostics/fingerprint
- non inviare semantica rotonda errata come se fosse corretta
- cercare evidenza reale del protocollo Voge per exit/roundabout

BLE
- continuare analisi recovery dopo callback missing
- verificare discovery meno dipendente da advertised service se necessario
- non cambiare UUID/write type/frame senza evidenza

NAV QUEUE
- valutare coalescenza latest-state
- test separato per assicurare nessuna regressione
```

La Valico 900 resta una linea di verifica distinta: non adattare il protocollo 625 alla 900 senza evidenza.

---

# 46. STATO AUTOREVOLE FINALE R42

```text
PUBLIC RELEASE
  V1.5.1 = PUBBLICATA / FROZEN
  latest release = v1.5.1
  V1.6 public release = NO
  V1.6 public tag = NO
  V1.6 source on main = NO

CURRENT TECHNICAL CANDIDATE
  V1.6 vc15
  versionCode = 15
  versionName = 1.6
  APK SHA-256 =
  bb4fc07fad81604147a483700d09c6195edb0080a3ed39d9be618b1ddb2d2d4d
  official cert = PRESERVATO

625DSX
  BLE connect/READY = PASS
  LEFT/RIGHT + distance = PASS FISICO
  Maps roundabout recognition = PASS
  TFT roundabout semantics = FAIL
  exit direction/number = NOT IMPLEMENTED
  long-road BLE recovery = NOT PASS YET

ROUNDABOUT
  vc15 direction=1 + roadFlag=2 = NON VALIDATA / FISICAMENTE INADEGUATA
  tester first-exit-right showed LEFT = FAIL
  unknown icon fingerprints collected = 2
  unexplained "omino" TFT icon = OPEN / not tied to roundabout

900DSX NO-WIFI
  double-Bluetooth architecture = likely/expected
  BLE MotoLink connects = OBSERVED
  arrows not rendered = OPEN
  probable SS621X11 = TO CONFIRM FROM VERSION SCREEN
  model-specific protocol possibility = OPEN

TROFEO 500 WIFI
  phone = hotspot/AP
  TFT = client
  SSID = VOGE-36f
  old Wi-Fi lock = recovered after diagnostic activity
  exact wake trigger = NOT YET ISOLATED

VPN
  specific tested VPN/config = CONFIRMED BLOCKER FOR EASYCONN
  all VPNs blocked = NO
  pre-START warning = NEXT BUILD

NOTIFICATION ACCESS
  listener itself = used and works
  automatic gate/prompt = NOT IN VC15
  next build = REQUIRED

GITHUB
  main untouched by V1.6 app source
  release v1.5.1 remains latest
  technical work stays on candidate/diagnostic branches
```

---

# 47. PRIORITÀ DOPO R42

```text
1. non promuovere vc15 come release finale
2. correggere UX Notification Listener
3. aggiungere warning VPN per Hotspot/EasyConn
4. non inviare più la rotonda vc15 come semantica "corretta"
5. cercare protocollo reale roundabout/exit Voge
6. continuare test/recovery BLE dopo callback missing
7. verificare Valico 900 con identificazione firmware HW/OS/HMI/MCU
8. se possibile confrontare traffico VOGE Global / HCI o trovare fonte open-source equivalente
9. valutare NAV queue coalescing in test separato
10. solo dopo PASS fisico multi-moto -> freeze candidate -> Play Store compliance -> eventuale pubblicazione
```

---

# 48. REGOLE DI EVIDENZA

Per evitare errori di interpretazione, ogni nuova conclusione deve essere classificata come una delle seguenti:

```text
IMPLEMENTATO
VERIFICATO FISICAMENTE
OSSERVATO DA LOG
RIPORTATO DAL TESTER
INFERENZA / IPOTESI
PENDING
REGRESSION / FAIL
```

Non promuovere una inferenza a fatto senza evidenza.

Esempi correnti:

```text
ROUNDABOUT riconosciuto da Maps = OSSERVATO DA LOG
prima uscita a destra mostrata come LEFT = RIPORTATO DAL TESTER / FAIL
icona "omino" causata da roadFlag=2 = NON PROVATO
VPN specifica blocca EasyConn = VERIFICATO A/B
diagnostico Bluetooth risveglia sicuramente Wi-Fi = IPOTESI FORTE, trigger esatto PENDING
900DSX usa esattamente SS621X11 = PROBABILE, foto firmware PENDING
```

---

# FINE R42


---

# 49. R43 — VOGE GLOBAL 1.1.7 REVERSE ENGINEERING / VC16 ROUNDABOUT FIX

Data: 2026-09-11

## 49.1 Evidenza OEM VOGE

Classificazione evidenza:

```text
VOGE Global 1.1.7 XAPK = ANALIZZATO STATICAMENTE
package = com.loncin.vogeglobal
XAPK SHA-256 = d269788b4d9034aadefe4413591461db2b20cd01393ab4859aced16ed241aa2a
```

Nel codice OEM VOGE sono stati individuati `NaviRoadInfoEntity`, `nextRoadDirection`,
`roadFlag` e `annularDegrees`, oltre al ramo che costruisce i dati di navigazione per
AMap/GaoDe e Mapbox.

Semantica OEM recuperata:

```text
roadFlag = 0  normale
roadFlag = 1  tunnel
roadFlag = 2  tollgate / pedaggio
roadFlag = 3  area di servizio
roadFlag = 4  rotonda
```

Quindi il comportamento vc15 è spiegato causalmente:

```text
vc15 ROUNDABOUT -> direction=1 + roadFlag=2
roadFlag=2 OEM = PEDAGGIO
=> icona TFT "omino" / pedaggio coerente con il frame inviato
```

Verdetto: la semantica vc15 della rotonda è definitivamente RIGETTATA.

## 49.2 Annular degrees

L'APK vc15 fisicamente testato è stato ispezionato a livello DEX.
`TurnByTurnInstruction` possiede già `annularDegrees` e `VogeNavPacketEncoder.navFrame()`
lo scrive nel byte 16 del frame opcode `0x6A`.

Il framing BLE vc15 quindi non va riscritto.

Conferme DEX MotoLink vc15:

```text
byte 10 = directionCode
byte 14 = roadFlag
byte 15 = naviOnOff
byte 16 = annularDegrees
```

Il fix vc16 deve alimentare correttamente questi campi, mantenendo protocollo e recovery vc15.

## 49.3 Google Maps maneuver classifier

È stato verificato `Pavanayi1/KTM-Nav-GEN3` / OpenDash, licenza MIT.
Il progetto pubblica un modello TensorFlow Lite addestrato sulle icone reali Google Maps.

Pin vc16:

```text
repository = Pavanayi1/KTM-Nav-GEN3
commit = ceafaf1fa899cdbb646051f1f517372eb21759fa
model git blob = e0104c6dcd104f05ee3fe08eb9922c036d60c9da
model size = 3391124 bytes
```

Il modello distingue le varianti di rotonda in settori 16-way RH/LH.
MotoLink vc16 converte l'indice 16-settori nel campo VOGE 12-settori `annularDegrees`.
Il modello è usato solo per le rotonde; tutte le altre manovre restano sul parser vc15.

Fallback sicuro:

```text
se ROUNDABOUT riconosciuto ma settore ML non affidabile:
roadFlag = 4
annularDegrees = 0
log diagnostico
nessuna uscita inventata
```

## 49.4 GitHub vc16

Branch tecnica invariata:

```text
build/v1.6-official-candidate
main = NON TOCCATA
public release = V1.5.1
```

Implementazioni vc16 già committate:

```text
V16ManeuverClassifier.kt
apply_v16_vc16_voge_roundabout.py
THIRD_PARTY_NOTICES.md
TensorFlow Lite dependency
workflow vc16 versionCode 16
CHANGELOG vc16 candidate
```

Commit principali:

```text
eb28b0bcb24fcc5b559e5f6fb30bd0abddd3bd33  classifier
711b98e0977ba33d6b76a057ba4c50c1844c39bb  VOGE roundabout patch
ddc217791a8ee554974db2424d20b0a876050baf  attribution
178da2a31ebe44ee93772ae945175f49e927adeb  TensorFlow dependency
bc7b442f66606071bf00ea649d7682f8bcb00545  vc16 build workflow
bcf42c9a9ddbd68d7e9741830b34a627d2693577  changelog/state
```

## 49.5 Stato gate

```text
IMPLEMENTATO
  roadFlag ROUNDABOUT 2 -> 4
  annularDegrees alimentato dal classificatore locale
  modello ML pinned
  BLE vc15 preservato
  versionCode 16

VERIFICATO STATICAMENTE
  workflow source materialization PASS
  vc16 source assertions PASS
  OEM VOGE semantics from VOGE Global 1.1.7 PASS
  vc15 DEX annular byte 16 PASS

VERIFICATO TECNICAMENTE
  GitHub Actions run 34632684632 = PASS completo
  compile release APK = PASS
  identity check package/version = PASS
  source/static vc16 gates = PASS
  artifact upload = PASS
  APK signed con certificato ufficiale = PASS
  APK Signature Scheme v3 = PASS
  certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
  signed APK SHA-256 = 77d74eecf1b8b76e5d83b9412cda72ad142a7b4bf39d643ef58a4274b376b13d
  signed APK package = it.motolink.app
  signed APK versionCode = 16
  signed APK versionName = 1.6
  signed DEX contains V16ManeuverClassifier + ML_ICON + OEM roundabout fallback markers = PASS

PENDING
  test fisico rotonda su TFT VOGE
  long-road BLE recovery physical gate

NON PROMOSSO
  V1.6 public release
  main merge
```

# 50. V1.6 vc17 — VALICO 900 ADAPTIVE BLE WRITE

## 50.1 Evidenza fisica Valico 900 — 09/09/2026

Log fisico analizzato: `MotoLink_Log_20260909_202810(1).txt`.

Osservato sulla Valico 900 con profilo BLE:

```text
service VOGE trovato = 5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e
connectGatt status=0 state=2
notify CCCD status=0
TX/RX esatti trovati
prima write heartbeat 0x5A -> callback status=3
vecchia build -> reconnect loop -> successivi status=147
```

Interpretazione tecnica congelata:

```text
status=3 = GATT_WRITE_NOT_PERMITTED
UUID/discovery BLE = NON root cause
prima scrittura WRITE_TYPE_DEFAULT = rifiutata dal firmware 900 osservato
```

L'evidenza esterna MOTO-HUB sulla stessa famiglia VOGE conferma che la characteristic
`6052202a-2928-4131-a2d0-456d5673ed2f` può esporre sia PROPERTY_WRITE sia
PROPERTY_WRITE_NO_RESPONSE e il loro ramo EC-BTP utilizza WRITE_TYPE_NO_RESPONSE.
Questa evidenza supporta il fallback, ma non sostituisce il test fisico vc17 sulla 900.

## 50.2 Design vc17

Regola implementata:

```text
START sessione:
  write mode = WRITE_TYPE_DEFAULT

primo heartbeat di verifica:
  se status=0 -> percorso vc16/625 invariato
  se status=3
     AND heartbeat iniziale
     AND transportVerified=false
     AND TX dichiara PROPERTY_WRITE_NO_RESPONSE
     AND fallback non già tentato
  -> passa una sola volta a WRITE_TYPE_NO_RESPONSE
  -> riaccoda lo stesso heartbeat
  -> nessun reconnect immediato
```

Per WRITE_TYPE_NO_RESPONSE:

```text
nessun ACK ATT richiesto per definizione
one-write-in-flight preservato
settle locale breve prima di liberare inFlight
heartbeat e NAV restano serializzati
nessun watchdog da 5 s basato su callback obbligatoria
```

Il fallback NON è globale e NON modifica la 625/625DSX se DEFAULT continua a funzionare.

## 50.3 Preservazioni vc17

```text
VOGE roundabout vc16 = PRESERVATO
roadFlag roundabout = 4
annularDegrees = PRESERVATO
ML classifier = PRESERVATO
UUID VOGE = PRESERVATI
heartbeat 0x5A = PRESERVATO
opcode 0x6A-0x6E = PRESERVATI
main V1.5.1 = NON TOCCATA
public release = ANCORA V1.5.1
```

## 50.4 GitHub vc17

```text
branch = build/v1.6-official-candidate
versionName = 1.6
versionCode = 17
patch = tools/apply_v16_vc17_valico900_adaptive_write.py
patch commit = 6155e62c1faac9d3ef451dd932225fa7b7970c67
workflow commit = 65b655764ca2ec01bc1203c3458d5746825bc14b
```

## 50.5 Stato gate vc17

```text
IMPLEMENTATO
  fallback DEFAULT -> NO_RESPONSE solo su status=3 iniziale
  controllo PROPERTY_WRITE_NO_RESPONSE
  massimo un fallback per sessione
  coda seriale preservata
  no-response settle path
  logging TX properties e modalità selezionata
  versionCode 17

VERIFICATO STATICAMENTE LOCALE
  patch applicata con anchor exact sulla sorgente vc16 generata = PASS
  marker vc16 roundabout ancora presenti = PASS

VERIFICATO TECNICAMENTE
  GitHub Actions run 34634024767 = PASS completo
  source materialization = PASS
  vc17 static/source assertions = PASS
  compile release APK = PASS
  package/version identity in CI = PASS (it.motolink.app / 17 / 1.6)
  artifact upload = PASS
  artifact ID = 10277348431
  artifact digest = sha256:9cd95510c6f4bd003f92c83842264a9a08ea34fb8dc516269469ccc2509a6029
  official APK signature v3 = PASS
  official certificate SHA-256 = 9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100
  signed APK SHA-256 = 528f6c6e9f6200e004246a12c43bf795700da8fe5e182a1f8b4784f73a6774bc
  ZIP integrity = PASS
  stored entries 4-byte aligned = PASS
  native .so offsets 16 KiB aligned = PASS
  signed APK DEX markers adaptive fallback + roundabout = PASS

PENDING FISICO
  test Valico 900: status=3 DEFAULT deve attivare NO_RESPONSE una volta e arrivare READY
  test Valico 900: TBT Google Maps deve comparire sul TFT
  test regressione Valico 625/625DSX: deve restare su DEFAULT e funzionare come vc16
  test fisico rotonda vc16/vc17
  long-road BLE recovery

NON PROMOSSO
  V1.6 public release
  main merge
```

# FINE R44

## PLANNED — V1.6 OFFICIAL: PAYPAL DONATIONS BUTTON (2026-09-11)

Status: **PLANNED / NOT YET IMPLEMENTED**.

Decision frozen for the future MotoLink V1.6 official release:
- Add the donation/support entry in **CREDITI**.
- User-facing label may be **DONAZIONI** / **Sostieni MotoLink**; final wording to be frozen at implementation.
- Use the **official PayPal-generated Donate button/branding**, not a MotoLink-created imitation of the PayPal button.
- The button must open the official PayPal donation/payment flow associated with a **dedicated PayPal Business account for MotoLink**, not the developer's private personal account.
- MotoLink must not collect, process, log, or store card/bank/payment credentials.
- Contribution must remain fully optional and must **not unlock features, priority support, badges, or different app functionality**.
- The exact PayPal Business donation URL/button must be inserted only after the dedicated account is created and the externally visible recipient identity has been checked.
- If distribution through Google Play is planned, re-check the then-current Google Play payments policy before release.

Current source/transport/navigation core remains unchanged by this planning decision.

## CLARIFICATION — V1.6 OFFICIAL: PAYPAL SUPPORT FLOW (2026-09-11)

Status: **PLANNED / NOT YET IMPLEMENTED**.

The dedicated PayPal Business account for MotoLink has been created as a **persona fisica** account, not as a registered charity/non-profit. During the live PayPal Italy setup, the current **Link e pulsanti di pagamento** builder exposed standard hosted payment links/buttons but did **not** expose a dedicated charity-style `Donazione` template in the visible menu for this account.

Implementation decision for V1.6 official:
- Do **not** misclassify MotoLink as a registered charity/non-profit just to obtain a charity Donate button.
- Use an **official PayPal-hosted payment link** generated by the dedicated MotoLink Business account.
- Configure the hosted link as voluntary support with **customer-selected amount** (PayPal's current business payment-link product supports customer-decided pricing).
- Preferred user-facing wording inside MotoLink: **DONAZIONI** or **Sostieni MotoLink**; final label to be frozen at implementation.
- Suggested PayPal hosted item name: `Sostieni MotoLink`.
- Suggested description: `Contributo volontario per sostenere lo sviluppo e i test di MotoLink. Non sblocca funzionalità e non dà vantaggi aggiuntivi.`
- Currency: EUR.
- No shipping/tax semantics for this support link.
- Native Android app should open the official PayPal-hosted URL externally; do not embed/imitate a web checkout or collect payment credentials inside MotoLink.
- The exact hosted URL must only be committed after the user confirms the final PayPal page/recipient identity is correct.
- If PayPal later exposes an official donation-specific flow compatible with this non-charity account, it may be reconsidered, but no false charity classification is allowed.

This clarification supersedes any earlier wording in R45 that assumed an official charity-style PayPal Donate button would necessarily be available to this account type.

## FROZEN — V1.6 OFFICIAL: PAYPAL HOSTED SUPPORT LINK (2026-09-11)

Status: **CONFIGURED EXTERNALLY / NOT YET IMPLEMENTED IN APP**.

The dedicated MotoLink PayPal Business account has generated the final hosted payment link intended for the future V1.6 Credits support/donation entry:

`https://www.paypal.com/ncp/payment/Q75R67LUDDWV6`

Validation evidence:
- The PayPal hosted-button builder generated `hostedButtonId = Q75R67LUDDWV6`.
- The shareable URL uses PayPal's official Payment Links format `/ncp/payment/<ID>`.
- The hosted ID in the URL exactly matches the generated hosted button ID.

Frozen implementation rule:
- In **CREDITI**, MotoLink will open this official PayPal-hosted URL externally.
- MotoLink will not embed checkout logic, collect payment credentials, or imitate PayPal processing.
- User-facing support remains optional and provides no feature unlocks, badges, priority, or other consideration.
- Before final V1.6 release, perform one logged-out/incognito physical check of the public page to confirm the recipient branding/text and customer-selected amount behavior are correct.
- If the PayPal link is ever regenerated, the master and app constant must be updated together; do not silently substitute a different hosted ID.

This section supersedes the earlier placeholder stating that the exact hosted URL was still pending.

## VERIFIED — PAYPAL HOSTED SUPPORT PAGE VISUAL CHECK (2026-09-11)

Status: **PHYSICALLY/VISUALLY VERIFIED VIA LOGGED-OUT HOSTED PAGE SCREENSHOT / APP INTEGRATION STILL PENDING**.

The final hosted URL `https://www.paypal.com/ncp/payment/Q75R67LUDDWV6` was opened outside the PayPal account and the resulting PayPal-hosted page was visually checked.

Observed on the public hosted page:
- MotoLink branding/logo is shown.
- Title: `Sostieni MotoLink`.
- Description clearly states that the contribution is voluntary and supports development, compatibility tests, maintenance, bug fixing and stability improvements.
- The text explicitly says that the contribution does not purchase services/features, does not unlock premium content and does not provide additional advantages.
- Amount field label: `Offerta Libera`.
- Currency: EUR.
- Amount is customer-selected; initial total is 0,00 EUR until the user enters a value.
- PayPal-hosted payment methods are presented, including PayPal and card payment; additional methods may be shown by PayPal depending on device/account eligibility.
- The page is visibly hosted and powered by PayPal; MotoLink does not collect payment credentials.

Important note:
- PayPal's generic hosted-page footer uses standard merchant wording such as `prodotto ... offerto e venduto dal venditore`. This is PayPal platform boilerplate and does not override the MotoLink-specific voluntary-support description above.

Frozen V1.6 implementation consequence:
- The Credits `DONAZIONI` / `Sostieni MotoLink` action may now use the hosted URL above as the validated target.
- Open externally (browser / secure external intent); do not implement an in-app payment processor or custom card form.
- Keep the in-app explanatory text consistent with the hosted page: voluntary support, no premium unlock, no user advantage.


## DISCOVERY — NATIVE PAYPAL DONATIONS WORKFLOW FOUND (2026-09-11)

Status: **DISCOVERED / IDENTITY CONFIRMATION PENDING / NO APP CHANGE YET**.

A PayPal Business dashboard page dedicated specifically to donations was found. The page visibly states `Inizia ad accettare donazioni con PayPal`, includes a `Conferma la tua identità` action, a `Riepilogo delle donazioni` area, counters for `Raccolti`, `Donazioni` and `Donazione media`, and a `Le tue pagine delle donazioni` section.

This is materially better aligned with the original V1.6 product requirement than the generic Payment Links / hosted-button workaround because it is an actual PayPal donations workflow rather than a normal merchant payment link.

Decision / frozen next step:
- Do **not** remove or modify the already validated generic hosted support link `https://www.paypal.com/ncp/payment/Q75R67LUDDWV6` yet; keep it only as a working fallback.
- Complete PayPal's required identity confirmation for the native donations feature.
- If PayPal then allows creation of an official MotoLink donation page/link/button, prefer that native donation artifact for the V1.6 Credits integration.
- Before replacing the fallback, verify the native donation page logged-out/incognito and record its exact URL/identifier and visible wording.
- Only after that verification should the master and future app constant be changed from the generic payment-link target to the native donation target.
- MotoLink must still open PayPal externally and must not collect or process payment credentials itself.

This discovery supersedes the earlier assumption that the account did not expose a dedicated PayPal donation flow.


## R50 — 2026-09-12 — Valico 625 physical roundabout regression: encoder roadFlag hardcoded to tollgate

**Status:** PHYSICALLY OBSERVED / ROOT CAUSE IDENTIFIED IN GENERATED VC17 SOURCE / FIX NOT YET APPLIED.

Physical log `MotoLink_Log_20260912_010433.txt` from VOGE Valico 625 shows that Google Maps roundabouts are now recognized correctly by the ML icon classifier and the model-level TBT object carries OEM `roadFlag=4` with nonzero `annular` sectors. Across the session there are 146 accepted ROUNDABOUT TBT updates, grouped into multiple real maneuver episodes with annular values 2, 3, 6, 8 and 9. The user nevertheless reports that the TFT still renders the same wrong "omino" / tollgate-like icon instead of the roundabout.

Source inspection of the exact generated vc17 artifact (`GENERATED_V16Navigation_vc17.kt`) identified the root cause in `VogeNavPacketEncoder.navFrame()`: byte 14 is still encoded as `if (m.maneuver == NavManeuver.ROUNDABOUT) 2 else m.roadFlag`. Therefore, despite the upstream model/log showing `roadFlag=4`, the actual BLE opcode `0x6A` frame sent to the TFT contains `roadFlag=2`. OEM reverse engineering had already established `2 = tollgate` and `4 = roundabout`, so the physical "omino" observation is fully consistent with the transmitted frame. `annularDegrees` is correctly written to byte 16 and the direction code for ROUNDABOUT remains 1.

Required minimal fix for next candidate: replace the byte-14 encoder assignment with direct use of the model value, e.g. `p[14] = m.roadFlag.coerceIn(0, 4).toByte()`. Add a CI/static assertion that no roundabout hardcode to `2` exists in the generated source, and add a packet-level regression test proving a ROUNDABOUT model with `roadFlag=4` produces byte 14 = `0x04` while preserving byte 16 annular sector and checksum. Do not change classifier, direction mapping, BLE UUIDs, write-type logic or vc17 Valico-900 fallback as part of this fix.


## R51 — 2026-09-12 — V1.6 vc18 ROUNDABOUT_ENCODER_FIX test APK built and signed

**Status:** IMPLEMENTED / CI BUILD PASS / OFFICIAL SIGNATURE PASS / PHYSICAL 625 TEST PENDING.

A dedicated test branch was created from the frozen vc17 candidate lineage:
- branch: `build/v1.6-vc18-roundabout-fix`
- base vc17 commit: `65b655764ca2ec01bc1203c3458d5746825bc14b`
- vc18 patch commit: `bdb671d5f8df611ca0e3b792fe283ddac5d37cd4`
- vc18 workflow/head commit: `8c04bb559180de581cbb9e917369ed3aaa2a17a7`

The vc18 economic/protocol delta is deliberately one generated-source line only inside `VogeNavPacketEncoder.navFrame()`:
- vc17: `p[14] = (if (m.maneuver == NavManeuver.ROUNDABOUT) 2 else m.roadFlag).coerceIn(0, 4).toByte()`
- vc18: `p[14] = m.roadFlag.coerceIn(0, 4).toByte()`

Independent diff audit between the archived generated vc17 source and CI-generated vc18 source confirms exactly one removed line and one added line, with no other `V16Navigation.kt` changes. `p[16] = m.annularDegrees.coerceIn(0, 255).toByte()` remains unchanged. Upstream roundabout construction remains `roadFlag=4`, direction mapping remains ROUNDABOUT -> 1, ML classifier/mapping is unchanged, and vc17 Valico-900 adaptive `WRITE_TYPE_NO_RESPONSE` fallback is unchanged.

GitHub Actions:
- workflow: `Build MotoLink V1.6 vc18 Roundabout Encoder Fix`
- run ID: `34657640187`
- result: PASS
- materialize exact vc17 lineage: PASS
- apply vc18 encoder fix: PASS
- vc18 source gates: PASS
- release compile: PASS
- unsigned identity: PASS (`it.motolink.app`, versionName `1.6`, versionCode `18`)
- artifact upload: PASS

CI artifact:
- artifact ID: `10286038621`
- artifact name: `MotoLink_V1.6_vc18_ROUNDABOUT_ENCODER_FIX_UNSIGNED_AND_SIGNING_TOOLS`
- artifact digest SHA-256: `610724a6edc3fc61cea640dc0a48142fdbdd3c00bce334d4ab3e1b9f9865977e`
- unsigned APK SHA-256: `af09070171c449f1feeae3f8d6ba6e7a4c6c73c11e830755097280df9aee27d6`

Signed physical-test APK:
- file: `MotoLink_V1.6_vc18_ROUNDABOUT_ENCODER_FIX.apk`
- SHA-256: `993d0d496f3af09523de0bb77ba20e69d1077863b0a5e7d81f6e4c88c7195bc8`
- package: `it.motolink.app`
- versionName: `1.6`
- versionCode: `18`
- APK Signature Scheme v3: PASS
- signing certificate SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100` (official MotoLink certificate)
- stored ZIP entries 4-byte alignment audit: PASS, 289/289 aligned.

Physical gate now required on Valico 625/625DSX:
1. install vc18 over vc17 (same package and official certificate, higher versionCode);
2. start BLE navigation normally;
3. drive a Google Maps route containing at least one roundabout;
4. verify that the TFT no longer shows the tollgate/"omino" symbol for ROUNDABOUT;
5. verify the TFT renders the native roundabout representation and, where supported, the intended exit sector;
6. export the MotoLink log after the test.

Expected vc18 software log remains model-level `ROUNDABOUT ... roadFlag=4 annular=<sector>` as in vc17; the decisive change is now the actual opcode `0x6A` byte 14 transmitted as `0x04` instead of the stale forced `0x02`. No claim of physical success is permitted until this gate is passed.

## R52 — 2026-09-12 — Valico 900 vc17 physical BLE navigation output PASS

**Status:** PHYSICALLY VERIFIED on VOGE Valico 900 for BLE turn-by-turn TFT output; roundabout-specific rendering not yet claimed.

The user physically tested MotoLink V1.6 vc17 on the VOGE Valico 900 and reported that, for the first time, the TFT displays the navigation arrows. This closes the principal physical failure observed on the 900 before vc17, where the same VOGE BLE service/characteristics connected but the first DEFAULT heartbeat write returned GATT status=3 and no navigation indication reached the TFT.

Interpretation frozen from this physical result:
- Valico 900 BLE navigation transport + TFT turn-by-turn output: PASS with vc17 lineage.
- The vc17 adaptive write strategy must be preserved in all later V1.6 candidates.
- Do not revert to DEFAULT-only behavior and do not alter the exact VOGE service/TX/RX UUIDs or queue/transport gating.
- This user report proves visible TFT arrow output, but without the corresponding runtime log it does not independently prove which internal write mode/path (DEFAULT vs adaptive NO_RESPONSE fallback) completed the successful session. If the log is later supplied, bind the physical PASS to the exact transport path.
- Roundabout-specific rendering on the 900 remains separate and is not claimed by this result.

Relationship to vc18:
- vc18 is derived from vc17 and changes only the opcode 0x6A roadFlag encoder line for roundabouts (`p[14]`), so the vc17 Valico-900 transport fix remains unchanged by source-diff audit.
- Before official V1.6 promotion, run at least one physical vc18/final-candidate smoke test on the Valico 900 to ensure ordinary arrows still display, in addition to the Valico 625/625DSX roundabout gate.

## R53 — 2026-09-12 — Valico 625 vc18 physical roundabout gate FAIL despite corrected roadFlag

**Status:** PHYSICAL ROUNDABOUT FAIL / VC18 BYTE-14 FIX EXERCISED / PRIOR SINGLE-CAUSE HYPOTHESIS FALSIFIED / NEXT CHANGE NOT YET AUTHORIZED.

Physical log `MotoLink_Log_20260912_185911.txt` from the VOGE Valico 625/625DSX was reviewed after installing the signed V1.6 vc18 roundabout encoder fix. The session beginning 18:46:50 establishes a healthy 625 DEFAULT-write transport: TX properties `0x8`, `write=true`, `noResponse=false`, CCCD status=0, and `READY verificato dopo ACK heartbeat 0x5A status=0` at 18:46:51. Ordinary TBT instructions are then queued normally.

During the physically failed roundabout test, the ML classifier repeatedly recognizes real roundabouts and produces multiple nonzero VOGE annular sectors. Examples include `ROUNDABOUT ... roadFlag=4 annular=9` starting at 18:49:30 and later `roadFlag=4 annular=8` from 18:49:48 onward. Before the later transport timeout, 78 ROUNDABOUT TBT updates are actually queued for BLE transmission; across the complete 18:xx log there are 84 accepted ROUNDABOUT updates, with the last six occurring only after transport became unavailable. Therefore the failure cannot be attributed to Maps recognition, the ML classifier, absence of annular data, or the previous vc17 model-level roadFlag bug.

The archived CI-generated vc18 source was re-opened and confirms the exact encoder used by the signed vc18 artifact: `p[14] = m.roadFlag.coerceIn(0, 4).toByte()` and `p[16] = m.annularDegrees.coerceIn(0, 255).toByte()`. Thus the stale vc17 hardcode forcing ROUNDABOUT to byte-14 value `2` is absent in vc18. Because the user still reports that the native roundabout indication does not appear on the 625 TFT, the previous hypothesis that byte 14 alone explained the complete roundabout failure is falsified. The encoder/OEM semantics still contain at least one unresolved field/value or TFT-specific condition.

A separate transport event occurs at 18:53:30: `callback write assente`, followed by `Timeout callback GATT`, fresh-scan recovery, and `Nessuna moto BLE compatibile trovata` at 18:53:43. This is real and should be tracked separately, but it does not explain the initial roundabout rendering failure because many ROUNDABOUT frames were queued during the healthy READY interval from 18:49:30 through 18:53:07.

Do not apply another speculative roundabout mapping. The next diagnostic should first establish the exact 20-byte opcode `0x6A` frame emitted for ROUNDABOUT and compare it byte-for-byte with the VOGE Global OEM builder/path, including any field currently held at zero (notably byte 15/17 if applicable), checksum, direction code and annular semantics. A physical observation is also still needed for vc18 at the failed roundabout: whether the TFT shows the old tollgate/"omino" icon, shows a normal direction/straight indication, or shows no maneuver icon at all. That distinction is necessary before selecting the next code change.


## R54 — 2026-09-12 — Chiarimento osservazione fisica vc18: tollgate/"omino" NON più visibile

**Status:** VC18 PARTIAL PHYSICAL IMPROVEMENT CONFIRMED / ROUNDABOUT STILL FAIL.

Chiarimento dell'utente sulla prova fisica vc18 sulla VOGE Valico 625/625DSX: durante le rotonde **non compare più il vecchio simbolo tollgate/"omino"**. Questo corregge l'ambiguità lasciata in R53.

Interpretazione aggiornata e congelata:
- il difetto vc17 che forzava `roadFlag=2` era reale e produceva il simbolo tollgate/"omino";
- la correzione vc18 che trasmette `roadFlag=4` ha quindi avuto **un effetto fisico osservabile positivo**: il simbolo tollgate/"omino" scompare;
- tuttavia il TFT della 625 continua a **non mostrare la rappresentazione corretta della rotonda**, quindi `roadFlag=4 + annular` da soli non sono ancora sufficienti per ottenere il rendering OEM desiderato;
- il log continua a mostrare ROUNDABOUT riconosciute e accodate con `roadFlag=4` e settori `annular` non nulli durante la sessione sana;
- resta da determinare cosa mostri esattamente il TFT al posto della rotonda (freccia normale/dritto oppure nessuna icona) prima di scegliere la prossima modifica.

Non reintrodurre `roadFlag=2`. Il prossimo lavoro sulle rotonde deve partire dal confronto byte-per-byte con il frame OEM `0x6A`, senza mapping speculativi.

## PHYSICAL DISCOVERY — VC18 625 ROUNDABOUT FALLBACKS TO STRAIGHT (2026-09-12)

Status: **PHYSICALLY VERIFIED / ROOT CAUSE NARROWED, NOT YET FIXED**.

User clarified the vc18 physical behavior on the Valico 625: the previous tollgate/"omino" symbol is gone, but when Google Maps required the **first exit at a roundabout**, the TFT displayed a **straight-ahead arrow** instead.

The corresponding real log shows MotoLink correctly classifying and accepting the maneuver as ROUNDABOUT with OEM roundabout flag and nonzero annular sector, e.g. at 18:53:05: classifier class 45 / section16=4 -> `annular=3`, then `maneuver=ROUNDABOUT`, `roadFlag=4`, `annular=3`, and the instruction is queued to BLE while the channel is still verified. This proves the remaining failure is downstream of Maps/ML classification.

Interpretation updated: do **not** conclude yet that only the annular numeric mapping is wrong. Because the encoder still uses the normal base direction code for ROUNDABOUT (`direction=1`, same as STRAIGHT), the observed straight arrow is consistent with the TFT ignoring the roundabout modifier and falling back to the base direction. Therefore at least one OEM condition/field/sequence required to activate roundabout rendering is still missing or wrong. `roadFlag=4` is necessary enough to remove the tollgate icon, but is not sufficient by itself to make the 625 render a roundabout.

Frozen conclusions from the physical evidence:
- `roadFlag=2` must **not** be restored: vc17 physically rendered the wrong tollgate/"omino" symbol.
- vc18 `roadFlag=4` physically removes that wrong symbol.
- Maps/ML roundabout recognition is working and produces nonzero annular sectors.
- On the 625, the remaining vc18 roundabout path falls back to the ordinary straight arrow for at least the first-exit case.
- No further numeric remapping should be guessed without comparing the complete OEM 0x6A frame/sequence or obtaining another direct physical discriminator.

Next engineering action is intentionally **not executed yet**: inspect/compare the complete OEM roundabout frame semantics and sequencing before creating another APK.

## R56 — 2026-09-12 — OEM VOGE Mapbox roundabout semantics recovered + vc19 candidate built

**Status:** OEM STATIC RE VERIFIED / IMPLEMENTED / CI PASS / OFFICIAL SIGNATURE PASS / PHYSICAL TEST PENDING.

The remaining vc18 Valico 625 failure was traced to a second, independent encoder error. The previous vc18 fix (`roadFlag=4`) was correct and physically removed the tollgate/"omino" symbol, but MotoLink still encoded every ROUNDABOUT with byte 10 `direction=1`, which is the same ordinary direction code as STRAIGHT. This matches the user's physical observation that a first-exit roundabout was rendered as a straight arrow.

Authoritative OEM evidence was recovered from VOGE Global 1.1.7 (`VOGE+Global_1.1.7_APKPure.xapk`, SHA-256 `d269788b4d9034aadefe4413591461db2b20cd01393ab4859aced16ed241aa2a`). The OEM opcode `0x6A` builder in `com.loncin.vogeglobal.bluetooth.h.j(NaviRoadInfoEntity)` writes `nextRoadDirection` to byte 10, `roadFlag` to byte 14, `naviOnOff` to byte 15 and `annularDegrees` to byte 16. The OEM navigation lifecycle uses `naviOnOff=0` during active navigation and `1` when exiting, so MotoLink's active-navigation byte 15 value is already correct.

The OEM road-flag mapping in `h7.a.e(int)` confirms: roundabout-entry icons map to `roadFlag=4`; service area maps to 3; tollgate maps to 2; tunnel maps to 1. Therefore vc18's byte-14 correction remains frozen and `roadFlag=2` must never be reintroduced for roundabouts.

The decisive finding comes from the OEM VOGE Mapbox observer `com.loncin.vogeglobal.map.mapbox.observer.h.a(...)`. For a Mapbox roundabout it does **not** force direction to straight. It combines all three independent fields: `nextRoadDirection = h7.a.c(modifier, drivingSide, bannerText)`, `roadFlag=4` when roundabout degrees are present, and `annularDegrees = h7.a.d(degrees, drivingSide=="left")`. The OEM Mapbox direction mapping is: straight=1; U-turn=2 for right-hand driving / 3 for left-hand driving; left=4; right=5; slight-left=6; slight-right=7; sharp-left=8; sharp-right=9. This explains the vc18 physical fallback exactly: MotoLink supplied valid roundabout flag/annular data but byte 10 still explicitly said straight.

The existing pinned OpenDash Google-Maps maneuver classifier already provides enough geometry to reconstruct the OEM direction deterministically. Its roundabout section labels are: section16 2=slight-right, 4=normal-right, 6=sharp-right, 8=straight, 10=slight-left, 12=normal-left, 14=sharp-left, 16=U-turn. MotoLink's established `annular = round(section16 * 12 / 16)` mapping remains unchanged, yielding the verified VOGE anchors 3=right, 6=straight, 9=left, 12=U-turn. For the current right-hand-driving test lineage, vc19 maps the same classifier sector to byte-10 OEM direction: 2→7, 4→5, 6→9, 8→1, 10→6, 12→4, 14→8, 16→2.

A minimal vc19 candidate was created on branch `build/v1.6-vc19-roundabout-direction-fix`, head commit `1f95f7caf90843f9cdb94256b989f5fc9138a357`. It is derived from vc18 and preserves the vc17 Valico-900 adaptive BLE transport and the vc18 roundabout `roadFlag=4` fix. The only behavioral addition is a roundabout-only `directionOverride` sourced from the already-classified exit sector; non-roundabout maneuvers continue to use the frozen `directionCode()` path. Encoder byte 10 now uses the override only when present. Diagnostic logging additionally records `dir=<value>` for accepted TBT instructions.

The prior physically failing first-exit example (`class=45`, `section16=4`, `annular=3`) is therefore expected in vc19 to be encoded as **direction=5 + roadFlag=4 + annular=3**, rather than vc18's **direction=1 + roadFlag=4 + annular=3**. This is the exact physical discriminator for the next 625 test.

GitHub Actions run `34718171044` completed SUCCESS. Source materialization from exact vc18 lineage, vc19 patch application, source assertions, release compilation, package identity check and artifact upload all passed. CI verified package `it.motolink.app`, `versionName=1.6`, `versionCode=19`. Artifact ID `10305916686`, artifact ZIP SHA-256 `00c13b70e3fc26aa678c51e7040fb0ee5286385e87f53c192c57668575a061b5`.

Generated/source fingerprints from the CI artifact:
- unsigned APK SHA-256: `dd0d4f299afdb7c3cf4216933c44e1c11351eb4cfe7ba2d3f41dad1e2d6b75df`
- generated `V16Navigation.kt` SHA-256: `e2f0e25a96f06158f2a2353bbb752188f9c2280d2da50e22d47487e7337f01f7`
- generated `V16ManeuverClassifier.kt` SHA-256: `bcb1d7db579fe2db11b6bf1fcac8091cfddec63fcb4f39416888b40fe156d55e`
- vc19 patcher SHA-256: `fc3b536e5fb61090765fb76699327d2d3aa038dd4aad4b815c58ea0d50c0f565`

The vc19 APK was then signed locally with the frozen official MotoLink release key. Signed test APK: `MotoLink_V1.6_vc19_OEM_ROUNDABOUT_DIRECTION_FIX.apk`, SHA-256 `959b95e6659b82bb773415c191099569f78fcdc447263d416ee2797fc6699903`. APK Signature Scheme v3 verification PASS, one signer, signing certificate SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`.

Physical gate remains mandatory before any promotion:
1. install vc19 on the Valico 625/625DSX;
2. reproduce a Google Maps first-exit roundabout;
3. verify that the TFT no longer shows the straight fallback and instead renders the correct right/roundabout instruction;
4. export the MotoLink log and confirm the corresponding accepted line includes `ROUNDABOUT ... roadFlag=4 annular=3 dir=5` for the same first-exit geometry;
5. if 625 passes, perform a short Valico 900 ordinary-arrow smoke test to prove the vc17 adaptive transport remains physically unaffected.

No official V1.6 freeze, merge or release is authorized by this static/CI result alone. Left-hand-driving roundabout behavior is not claimed by this candidate; the immediate physical gate is the right-hand-driving VOGE test environment already exercised.

---

## PHYSICAL ENDURANCE TEST — TROFEO 500 — 2026-09-13 — PHYSICALLY VERIFIED / LONG-RUN STABILITY

Source log: `DOC-20260913-WA0012.txt`, generated 2026-09-13 12:58:41. Wall-clock observation from first START 09:19:27 to final STOP 12:58:33: about 3 h 39 min, including deliberate/physical stops and restarts. User reported no visible operational problems while riding.

Observed stable behavior:
- EasyConn startup completed normally with live MEDIA_INIT 800x480, CAR_CTRL/CAR_DATA heartbeats, H264 800x480@30 and hardware encoder.
- Multiple brief H264/10920 consumer losses were recovered automatically without full teardown: 09:47:02→09:47:08 (~6 s), 09:58:14→09:58:15 (~1 s), 09:58:40→09:58:43 (~3 s), 11:04:22→11:04:26 (~4 s), 11:18:40→11:18:41 (~1 s), 12:42:58→12:43:04 (~6 s). Each reached `RECOVERY OK V1.5` with a new consumer generation and first real frame delivered. These were not reported as user-visible failures.
- At 10:06:56 the TFT closed H264 and media-control channels; phone keyguard/lock placeholder followed at 10:07:06; user STOP at 10:07:26 and a clean restart at 10:14:48/10:14:59. This pattern is compatible with a deliberate physical stop / TFT power-cycle.
- At 11:08:01 the TFT again closed H264 and media-control; keyguard/lock placeholder followed at 11:08:32. The app was later relaunched and START pressed at 11:15:58, with EasyConn recovered normally. Because no explicit STOP was logged before relaunch, classify this as `PHYSICAL STOP / PROCESS-RESTART COMPATIBLE`, not as a proven app crash.
- At 12:52:09 the TFT H264 and media-control channels closed and did not return. PXC stayed alive briefly, then both control channels became unreachable and repeated EC init attempts returned `EHOSTUNREACH (No route to host)` through 12:58:26. This is strongly consistent with the motorcycle/TFT/network endpoint being powered down or otherwise physically unavailable. The final STOP at 12:58:33 completed cleanly. The large `dropped` counter increase during this terminal interval occurred while `consumer=false` and therefore does not represent an in-ride video quality collapse.

Interpretation / gate:
- `PASS_WITH_EXPECTED_PHYSICAL_STOP_EVENTS`: no evidence in this run of a persistent EasyConn/H264 failure while the motorcycle endpoint remained available.
- Short H264 reconnects are real transport interruptions but the recovery path worked automatically in 1–6 s and the rider reported no visible problem.
- The long `No route to host` burst at the end is classified as endpoint-unreachable / bike-TFT unavailable, not an internal MotoLink transport fault.
- The generic `DISPLAY MANUALE FAILSAFE` warning logged at START is an armed safety-state diagnostic and must not be counted as a connection failure.
- No code change is authorized from this endurance log alone. Preserve current V1.5.1 EasyConn/H264 core behavior.


## PHYSICAL TEST — V1.6 vc19 ROUNDABOUT ON VOGE 625 — 2026-09-13
Status: PHYSICALLY VERIFIED FAIL FOR ROUNDABOUT ICON / BLE TRANSPORT PASS.

Authoritative physical log: `MotoLink_Log_20260913_122722.txt`
SHA-256: `7f13ec6627ba22f85fc2f5ef55dd758e36bed0f33bdfa6962958ae3fc8153030`

Physical observation from tester: on the VOGE 625, roundabouts still do not render as a roundabout symbol; the TFT shows only an ordinary directional arrow.

Technical evidence from the 2026-09-13 session:
- BLE link is healthy: connection state `status=0 state=2`, TX properties `0x8` / WRITE DEFAULT, CCCD `status=0`, verification heartbeat `0x5A` ACK `status=0`, READY established.
- No BLE write failure, callback timeout, disconnect or reconnect event was logged during the tested roundabout interval.
- 71 ROUNDABOUT TBT updates were accepted and 71 were queued to BLE.
- vc19 exit-direction mapping is definitely active in the runtime log, not only in source:
  - `annular=9 dir=4` observed 13 accepted updates;
  - `annular=8 dir=6` observed 36 accepted updates;
  - `annular=3 dir=5` observed 22 accepted updates.
- Every tested roundabout still carries `roadFlag=4` and the corresponding non-straight `dir` value; therefore the previous hypothesis that the missing roundabout icon was caused solely by forcing `direction=1` is rejected.

Conclusion:
- vc19 correctly transmits an exit-specific direction together with `roadFlag=4` and `annular`, but the VOGE 625 still renders only the base arrow.
- The TFT is demonstrably consuming the base direction field while the roundabout-specific semantics are still not being activated by the current `0x6A` payload.
- Do not revert `roadFlag=4` and do not revert the vc17 BLE transport fixes.
- Next reverse-engineering target is the OEM VOGE roundabout activation semantic beyond the already-verified `direction + roadFlag + annular`: exact byte layout/opcode/companion frame/order/state required by the 625 TFT. No further numeric guessing is authorized.

## R59 — 2026-09-13 — VOGE BLE roundabout protocol cross-check: OEM builder parity established; 625 firmware/A-B gate next

**Status:** OEM BYTE-LAYOUT VERIFIED / PUBLIC EXACT-PROTOCOL SEARCH COMPLETED / VC19 ROUNDABOUT-ICON FAILURE NOT EXPLAINED BY 0x6A FIELD LAYOUT / NEXT CHANGE NOT AUTHORIZED.

Following the vc19 physical test on the VOGE 625, the VOGE Global 1.1.7 binary was re-inspected at DEX level and the exact BLE navigation builder was reconstructed rather than inferred. Source archive: `VOGE+Global_1.1.7_APKPure.xapk`, SHA-256 `d269788b4d9034aadefe4413591461db2b20cd01393ab4859aced16ed241aa2a`; base APK `com.loncin.vogeglobal.apk`, SHA-256 `f1f013cb84ee56479a6d2b7fd9aaf1ad5aa2121e16edbe7ba28305a9efb12ad1`.

The OEM `NaviRoadInfoEntity` carries `routeRemainDist`, `routeRemainTime`, `arriveTime`, `curRoadRemainDist`, `curRoadName`, `nextRoadDirection`, `nextRoadName`, `isTunnel`, `roadFlag`, `naviOnOff`, and `annularDegrees`. The OEM BLE packet builder `com.loncin.vogeglobal.bluetooth.h.j(NaviRoadInfoEntity)` creates the same 20-byte opcode `0x6A` frame used by MotoLink: byte 0=`0x01`; byte 1=`0x6A`; bytes 2..4 current-road remaining distance; bytes 5..6 route remaining time; bytes 7..9 route remaining distance; byte 10 next-road direction; bytes 11..13 ETA hour/minute/PM; byte 14 roadFlag; byte 15 naviOnOff; byte 16 annularDegrees; byte 17 remains zero; byte 18 XOR checksum; byte 19=`0x04`. The shared OEM base-frame helper sets only bytes 0, 1 and 19 before the payload is populated. The OEM checksum helper XORs the complete 20-byte frame while byte 18 is still zero and writes the result to byte 18, matching MotoLink.

The Mapbox navigation path does not set `naviOnOff` while building ordinary live road-info updates, so its default value remains `0`; therefore MotoLink vc19 byte 15=`0` is not a missing roundabout-enablement bit. Byte 17 remains zero in the OEM builder as well. The Mapbox path sets `nextRoadDirection`, `roadFlag` and `annularDegrees` as independent values, with roundabout context represented by `roadFlag=4` plus a nonzero annular sector and an ordinary exit-direction code. This confirms the conceptual vc19 design and rules out the previously suspected hidden byte-15/17 activation flag for the 1.1.7 implementation.

The remaining four OEM road-text packets also match the MotoLink family and ordering: `0x6B` next-road chunk 1, `0x6C` next-road chunk 2, `0x6D` current-road chunk 1, `0x6E` current-road chunk 2. The VOGE navigation coroutine sends `0x6A -> 0x6B -> 0x6C -> 0x6D -> 0x6E` with the same approximately 100 ms pacing already used by MotoLink. Therefore there is currently no evidence in VOGE Global 1.1.7 for an additional companion opcode or alternate sequencing required solely to activate a roundabout glyph.

Public-source survey was also repeated using the exact VOGE BLE identifiers. The service `5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e`, write characteristic `6052202a-2928-4131-a2d0-456d5673ed2f`, and notify characteristic `ab9938d5-c354-4e2b-94f4-364e16ebcd33` surface in the open-source MOTO-HUB project, where they are treated as the VOGE's serial-shaped BLE channel. No public indexed VOGE implementation was found that contains a second navigation encoder or a solved roundabout-specific `0x6A` variant. MOTO-HUB's relevant code is diagnostic/time-sync work, not a turn-by-turn roundabout renderer. OpenDash/KTM roundabout support remains a different BLE protocol with dedicated KTM roundabout icon codes and is useful only for Google-Maps maneuver classification, not as a transferable VOGE wire format.

Current official VOGE documentation consistently describes this feature as basic/turn-by-turn navigation and refers to TFT indications as arrows. The latest publicly listed VOGE Global release is 1.1.18 (2026-03-01), whereas the locally reverse-engineered binary is 1.1.7. The latest 1.1.18 binary has not yet been locally decompiled in this lineage, so protocol identity between 1.1.7 and 1.1.18 must not be assumed.

**Decisive next gate:** run the official current VOGE Global app on the *same VOGE 625* through at least one known roundabout and observe the TFT. If the official app also renders only the ordinary exit-direction arrow, the vc19 behavior is OEM-equivalent for this TFT/firmware and the missing dedicated roundabout graphic must be classified as a 625 display/firmware behavior rather than a MotoLink protocol defect. If the official app renders a dedicated roundabout symbol, capture its BLE traffic (Android Bluetooth HCI snoop or equivalent) and compare the complete `0x6A..0x6E` sequence byte-for-byte against MotoLink; only the observed delta is then authorized for implementation.

A safe optional diagnostic candidate for the next build is TX-HEX logging only: record the exact 20-byte `0x6A` frame and associated `0x6B..0x6E` frames for each roundabout without changing any bytes or timing. This would make a later OEM HCI trace comparison deterministic. No change to `roadFlag`, `annular`, `direction`, byte 15, byte 17, checksum, frame order or pacing is authorized before the official-app same-bike A/B result or an actual OEM BLE capture.

---

## R60 — 2026-09-13 — Valico 900: freeze a 0 m identificato e guard vc20

**Stato:** ROOT CAUSE SOFTWARE IDENTIFICATA / VC20 BUILD+SIGNING PASS / TEST FISICO PENDING

Nuovo log fisico Valico 900: `MotoLink_Log_20260913_174052.txt`.

Evidenza decisiva della sessione 17:39:
- BLE READY alle 17:39:13 con canale operativo.
- 17:39:32: MotoLink accetta e accoda `STRAIGHT distance=0m`.
- 17:39:43: accetta e accoda la vera istruzione successiva `LEFT distance=270m`.
- 17:39:49: viene nuovamente accodata una transizione `STRAIGHT distance=0m`, seguita nello stesso secondo da `LEFT distance=270m`.
- 17:39:55: viene ancora accodata `STRAIGHT distance=0m`.
- La connessione BLE resta attiva fino alle 17:40:29, quindi il blocco sul TFT a 0 m non è spiegato da una disconnessione nel momento della transizione.

Conclusione: Google Maps può pubblicare notifiche/transizioni con distanza esplicita `0m`; la pipeline vc19 le considerava `hasRealDistance` e le inviava al TFT. Una transizione stale a 0 m poteva quindi sovrascrivere la successiva istruzione positiva già correttamente inviata, lasciando il TFT fermo a 0 m.

Correzione vc20 preregistrata/implementata: `distanceMeters == 0` viene trattato come **transition placeholder** e non viene inviato al TFT. MotoLink mantiene l'ultima istruzione positiva sul TFT finché arriva la successiva istruzione positiva. Il log vc20 emette `MAPS NAV V1.6 ZERO TRANSITION GUARD: distance=0 soppressa...`.

Perimetro congelato:
- nessuna modifica a UUID VOGE;
- nessuna modifica al fallback Valico 900 WRITE_TYPE_NO_RESPONSE vc17;
- nessuna modifica a roadFlag/annular vc18;
- nessuna modifica alla direzione roundabout vc19;
- nessuna modifica a EasyConn/H264/mirroring;
- unica modifica comportamentale: soppressione dell'invio BLE delle istruzioni Maps con `distanceMeters == 0`.

Separatamente, lo stesso log mostra disconnessioni BLE `status=19` alle 17:36:10 e 17:39:00 e `status=8` alle 17:40:29 con recovery già attivo. Questi eventi restano distinti dal freeze a 0 m, perché la sequenza che produce il freeze avviene durante un intervallo BLE READY stabile.

Branch vc20: `build/v1.6-vc20-zero-transition-guard`.
Commit workflow: `eed9d641901121d9a88f0799a05ce62384ac6c7c`.
Workflow run: `34781987582`.


Build vc20 completata con esito PASS:
- GitHub Actions run `34781987582`: SUCCESS; tutti gli step compile/verify/artifact PASS.
- artifact ID `10325192542`, digest SHA-256 `dc4063671ff51aec599e25f1f81979ef8340e8acf7a49b032041cdf48e39f9b1`.
- unsigned APK SHA-256 `54dce8bcc07bb53100e01afb86e268aae03141e1d65e22050b047df55aa7ad99`.
- APK firmato per test fisico: `MotoLink_V1.6_vc20_ZERO_TRANSITION_GUARD.apk`.
- signed APK SHA-256 `ad1eecc88637ddf0eb45a065d3f88fd2576be1fb331f30ce1e48b77c2a77586e`.
- package `it.motolink.app`, versionName `1.6`, versionCode `20`.
- APK Signature Scheme v3 PASS; certificato SHA-256 `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`.
- Nessuna promozione/merge/tag: vc20 resta candidata fisica V1.6.

---

## PHYSICAL DISCOVERY + FIX — VALICO 900 `KILOMETRAGGIO RESTANTE 0.0 km` / VC21 ROUTE REMAINING DISTANCE (2026-09-13)

**Status:** ROOT CAUSE PROVEN IN VC20 SOURCE / VC21 IMPLEMENTED + GITHUB BUILD PASS / PHYSICAL VALIDATION PENDING.

Physical evidence from Valico 900 shows the TFT label **“Kilometraggio restante”** fixed at **0.0 km** even while turn-by-turn navigation is active. This is a different field from the distance to the next maneuver. The previous vc20 guard correctly prevents transient `distanceMeters=0` maneuver updates from overwriting a valid turn, but it cannot repair the TFT's whole-route remaining-distance field.

Exact vc20 generated source inspection proves the cause: `TurnByTurnInstruction.routeRemainDistanceMeters` and `routeRemainTimeSeconds` existed, and the OEM-compatible 0x6A encoder already wrote them to bytes 7..9 and 5..6 respectively, but `GoogleMapsSourceAdapter.parse()` never populated either value. Therefore every outgoing vc20 0x6A frame encoded `routeRemainDistanceMeters ?: 0`, making bytes 7..9 permanently zero. On the Valico 900 this maps exactly to the observed TFT **0.0 km** remaining.

Public Google-Maps notification parser comparison (Garminuino) independently confirms that Google Maps exposes the destination summary as a three-part text of the form **duration · remaining distance · arrival time**, and parses the second component specifically as remaining route distance. This is distinct from the distance-to-turn field.

### vc21 implementation

Branch: `build/v1.6-vc21-route-remaining-distance`

Head / build commit: `d87e1e62de3c9e8a471ae7eee65688d2192c15ed`

Patch: `tools/apply_v16_vc21_route_remaining_distance.py`

vc21 changes only Maps route-summary extraction and propagation:

- separates the maneuver-distance text from the route summary;
- recognizes Google Maps summaries using `·` or `•` separators;
- parses component 1 as remaining time, component 2 as whole-route remaining distance, component 3 as ETA;
- fills `routeRemainDistanceMeters` and `routeRemainTimeSeconds` instead of leaving them null;
- keeps the latest positive route-summary values when an intermediate Maps notification temporarily omits the summary;
- includes route remaining distance/time in the notification fingerprint so changes can trigger a fresh TFT update;
- adds diagnostic accepted-line fields `routeRemain=<m>` and `routeTime=<s>`;
- preserves vc20 zero-transition guard, vc19 roundabout direction/roadFlag/annular behavior and vc17 Valico-900 adaptive `WRITE_TYPE_NO_RESPONSE` fallback unchanged.

GitHub Actions workflow: `Build MotoLink V1.6 vc21 Route Remaining Distance`
Run ID: `34783350891`
Result: **PASS** (materialization, vc21 patch, static verification, release compile, APK identity, artifact stage/upload).
Artifact ID: `10324889210`
Artifact digest SHA-256: `422d00617e21dda1f9bc84ba6489261b28cbe26b8d3e30d9129f5b4bfdaa9336`
Unsigned APK SHA-256: `e271bd723cc7ee962ae0d291074087776c44771558a4395d8455040fa6188dac`

Signed physical-test APK:
`MotoLink_V1.6_vc21_ROUTE_REMAINING_DISTANCE.apk`
SHA-256: `cedba0a0f8cc9beacacbf8c8c85258a72bb89223061511f75b298bfc120b3f9e`
Package: `it.motolink.app`
versionName: `1.6`
versionCode: `21`
APK Signature Scheme v3: PASS
Signing certificate SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

### Physical gate

On Valico 900, start a normal Google Maps route with destination several kilometres away. Expected log for a valid update now includes for example:
`... distance=270m routeRemain=12400m routeTime=1320s ...`
The TFT **Kilometraggio restante** must become non-zero and decrease during the route. If the log still shows `routeRemain=-1m`, the phone's current Maps notification layout is not exposing the three-part route summary to the surfaces currently inspected, and the next step is a diagnostic dump of non-sensitive RemoteViews text IDs/values before changing protocol bytes. If the log shows a positive `routeRemain` but the TFT remains 0.0 km, then the fault moves downstream to VOGE field semantics/units and must be compared against OEM traffic.

**Do not promote vc21 to official V1.6 until the Valico 900 physical gate passes. Main remains V1.5.1.**

---

## R62 — 2026-09-16 — sorgente V1.6 pubblicato su `main`; release/tag APK non ancora chiusi

**Stato:** SOURCE PUBLICATION PASS / `main` = V1.6 SOURCE / SIGNED VC21 APK VERIFIED LOCALLY / GITHUB RELEASE V1.6 PENDING / README STILL V1.5.1.

Il 16/09/2026 è stato completato con successo il passaggio del sorgente V1.6/vc21 sul branch pubblico `main`, preservando la lineage tecnica congelata fino a vc21. Il workflow definitivo usato per questa operazione è `Publish MotoLink V1.6 exact source to main`, run GitHub Actions `35129752963`, conclusione **SUCCESS**. Tutti gli step del job `publish-source` sono PASS: materializzazione della lineage vc21, verifica marker congelati, overlay sul `main` corrente, compilazione del source overlay, controllo identità e push finale.

Il commit pubblico risultante su `main` è:

`fc212a223710a297aa27113a676420bef32b975b` — `Publish MotoLink V1.6 official source`.

Il sorgente pubblico ora dichiara:
- `applicationId = it.motolink.app`
- `versionName = 1.6`
- `versionCode = 21`
- `minSdk = 29`
- `targetSdk = 36`
- dipendenza TensorFlow Lite `org.tensorflow:tensorflow-lite:2.14.0`
- BLE turn-by-turn, fallback adattivo Valico 900, roundabout encoding OEM vc19, Zero Transition Guard vc20 e route remaining distance/time vc21 inclusi nella lineage pubblicata.

Il file pubblico `docs/RELEASE_NOTES_V1.6.md` è presente su `main` e descrive la V1.6 come release BLE turn-by-turn separata dal mirroring EasyConn/Hotspot/QR, con recovery BLE, rotonde, guard a 0 m e campi di percorso restanti.

### Stato APK ufficiale candidata

L'APK più recente e congelato resta la vc21 firmata con la chiave ufficiale MotoLink:

`MotoLink_V1.6_vc21_ROUTE_REMAINING_DISTANCE.apk`

SHA-256:

`cedba0a0f8cc9beacacbf8c8c85258a72bb89223061511f75b298bfc120b3f9e`

Verifica locale ripetuta il 16/09/2026:
- APK Signature Scheme v3: **PASS**
- numero firmatari: 1
- certificato SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`
- unsigned parent vc21 SHA-256: `e271bd723cc7ee962ae0d291074087776c44771558a4395d8455040fa6188dac`
- artifact ZIP vc21 SHA-256: `422d00617e21dda1f9bc84ba6489261b28cbe26b8d3e30d9129f5b4bfdaa9336`

### Nota sulla pubblicazione GitHub finale

La pubblicazione **non è ancora completamente chiusa**. Al controllo del 16/09/2026, la lista Release GitHub continua a mostrare come ultima release pubblica `v1.5.1`; non è ancora presente una Release GitHub `v1.6` con asset `MotoLink_V1.6.apk`. Anche il `README.md` pubblico è ancora quello V1.5.1: badge/download diretto e sezione “MotoLink V1.5.1” puntano ancora a `v1.5.1`.

Quindi lo stato esatto è:

- **sorgente `main` V1.6:** completato;
- **release notes V1.6 su `main`:** completate;
- **APK vc21 firmato e verificato:** disponibile localmente e congelato;
- **tag/release GitHub `v1.6`:** non ancora pubblicati;
- **asset GitHub `MotoLink_V1.6.apk`:** non ancora pubblicato;
- **README/home GitHub aggiornato a V1.6:** non ancora completato.

Durante il tentativo di automatizzare la pubblicazione sono stati identificati due problemi di tooling, entrambi separati dal codice MotoLink:
1. `android-actions/setup-android@v3` sul runner corrente tentava di installare il pacchetto deprecato `tools` e falliva; il workflow source-publish definitivo ha bypassato questo problema usando direttamente lo `sdkmanager` preinstallato.
2. Il rebuild dell'APK unsigned sul toolchain corrente produce un SHA del contenitore ZIP diverso dal vecchio artifact vc21 pur mantenendo la stessa identità/versione; il confronto locale ha mostrato che i payload compressi sono uguali salvo `META-INF/version-control-info.textproto`, quindi non va confuso un mismatch byte-for-byte del container unsigned con una regressione del sorgente.

**Vincolo di verità aggiornato:** non dichiarare “V1.6 pubblicata” finché GitHub non espone realmente il tag/release `v1.6`, l'asset `MotoLink_V1.6.apk` e il README aggiornato. La pubblicazione del solo sorgente su `main` è già avvenuta e supersede la frase storica in R61 “Main remains V1.5.1”, ma non equivale ancora alla chiusura della release binaria.


---

## R63 — 2026-09-22 — V1.6 vc22 pubblicata + PayPal rinviato alla prossima versione dentro CREDITI

**Stato:** V1.6 VC22 RELEASE PUBBLICATA / FIX PROSSIMITÀ INCLUSO / PAYPAL DA IMPLEMENTARE NELLA PROSSIMA VERSIONE.

### Chiusura release V1.6 vc22

La release stabile **MotoLink V1.6 vc22** è stata pubblicata su GitHub ed è ora la release `latest` del progetto.

Identità release:
- package: `it.motolink.app`
- versionName: `1.6`
- versionCode: `22`
- asset ufficiale: `MotoLink_V1.6_VC22_PROXIMITY_HOTFIX_OFFICIAL_SIGNED.apk`
- SHA-256 asset GitHub: `dc3933db1738b31abf42b9fe449a50a6ed19653bb5812b6e6f89d73cbb5d8acf`
- certificato firma MotoLink SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`
- build release: PASS
- verifica firma ufficiale: PASS
- `README.md`: aggiornato a V1.6 vc22 con download diretto e stato release
- vecchia prerelease debug vc22: rimossa

La vc22 contiene il fix minimale della **Modalità tasca / prossimità**: con la funzione disattivata, `armProximityScreenOff()` non prosegue verso la registrazione del listener `TYPE_PROXIMITY` né verso l'acquisizione del `PROXIMITY_SCREEN_OFF_WAKE_LOCK`. EasyConn, H264, BLE e clock restano fuori dallo scope del fix.

Questa sezione supersede lo stato R62 che indicava la release GitHub V1.6 ancora pending.

### APPROVATO / CONFERMATO — PayPal nella prossima versione

Decisione utente del **22/09/2026**: il tasto PayPal per contributi volontari **non va in Supporto**. Deve essere inserito **dentro la schermata CREDITI** nella **prossima versione successiva alla V1.6 vc22**.

Regole congelate:
- posizione UI: **CREDITI**;
- funzione: contributo volontario / sostegno allo sviluppo MotoLink;
- il contributo resta completamente opzionale;
- nessuna funzione, badge, priorità, contenuto premium o vantaggio deve dipendere dal pagamento;
- MotoLink non deve raccogliere, processare, registrare o memorizzare credenziali di pagamento;
- il flusso PayPal deve aprirsi esternamente tramite pagina ufficiale PayPal;
- il link generico già validato `https://www.paypal.com/ncp/payment/Q75R67LUDDWV6` resta disponibile come fallback tecnico finché non viene eventualmente sostituito da una pagina PayPal Donazioni nativa definitivamente verificata;
- l'eventuale sostituzione del link deve essere riportata nel master e nel codice nello stesso aggiornamento;
- la dicitura utente potrà essere `Sostieni MotoLink`, `Donazioni` o equivalente; il testo definitivo va congelato al momento dell'implementazione grafica.

**DA IMPLEMENTARE:** aggiungere il pulsante nella schermata **Crediti** della prossima versione e validare apertura esterna, ritorno all'app e assenza di regressioni sulla schermata Crediti.

Questa decisione supersede esclusivamente il precedente target temporale “V1.6 OFFICIAL” delle sezioni PayPal del 11/09/2026. Tutte le verifiche e i vincoli PayPal già registrati restano validi, ma l'implementazione è ora rinviata alla prossima versione post-V1.6 vc22.


---

## R64 — 2026-09-23 — PayPal Donazioni nativo congelato per la prossima versione

**Stato:** PAYPAL DONATIONS HOSTED BUTTON CONFERMATO / DA IMPLEMENTARE IN CREDITI NELLA PROSSIMA VERSIONE.

L'utente ha fornito il codice HTML ufficiale generato da PayPal per il pulsante Donazioni. Il form usa:

- endpoint PayPal: `https://www.paypal.com/donate`
- metodo web originale: `POST`
- `hosted_button_id`: `N7BM2VCKWWKNN`
- immagine pulsante ufficiale PayPal Italia: `https://www.paypalobjects.com/it_IT/IT/i/btn/btn_donateCC_LG.gif`

Il parametro realmente vincolante per l'identità del pulsante hosted è quindi:

`N7BM2VCKWWKNN`

PayPal documenta che, per un Business Account, il `hosted_button_id` è il parametro assegnato al pulsante salvato/ospitato e identifica il pulsante live configurato nel conto PayPal Business.

### Target applicazione Android

MotoLink non deve incorporare il `<form>` HTML né gestire pagamenti in WebView. Il pulsante **Sostieni MotoLink** nella schermata **CREDITI** deve aprire esternamente il flusso ufficiale PayPal Donazioni associato al pulsante hosted.

Target URL candidato per `ACTION_VIEW` / browser esterno:

`https://www.paypal.com/donate?hosted_button_id=N7BM2VCKWWKNN`

Regole di implementazione congelate:
- posizione: **CREDITI**;
- label prevista: **Sostieni MotoLink**;
- apertura tramite browser/app PayPal esterna, mai checkout proprietario MotoLink;
- nessuna raccolta o memorizzazione di credenziali di pagamento da parte di MotoLink;
- contributo completamente volontario;
- nessuna funzione, badge, priorità, contenuto premium o altro vantaggio legato alla donazione;
- il vecchio link generico `https://www.paypal.com/ncp/payment/Q75R67LUDDWV6` resta solo come fallback storico e non è più il target primario previsto;
- prima del freeze della prossima release eseguire un test fisico del target URL su telefono: apertura pagina Donazioni corretta, identità destinatario/branding corretti, possibilità di inserire l'importo, ritorno all'app senza regressioni.

**Nota tecnica di verifica:** il codice fornito dall'utente usa un form `POST`. L'URL `GET` sopra è il target candidato per apertura nativa esterna e deve essere validato fisicamente prima della release. Se PayPal fornisce dalla dashboard un link condivisibile specifico per lo stesso `hosted_button_id`, quel link va preferito e congelato nel master/codice senza cambiare l'ID hosted.

Questa sezione supersede il precedente stato R63 che lasciava il flusso PayPal Donazioni nativo ancora non definito e il link NCP come fallback principale.

---

## IMPLEMENTED TEST — V1.6 VC23 PAYPAL DONATION BUTTON (2026-09-23)

Status: **TECHNICAL PASS / PHYSICAL TEST PENDING**.

È stata preparata una build test separata, senza modificare la release stabile V1.6 vc22 pubblicata.

Identità test:
- branch sorgente: `test/v1.6-vc23-paypal-donations`;
- package: `it.motolink.app`;
- versionCode: `23`;
- versionName: `1.6-vc23-test`;
- APK: `MotoLink_V1.6_VC23_PAYPAL_DONATION_TEST_OFFICIAL_SIGNED.apk`;
- SHA-256 APK: `3247d41d0c2424a0ea871f80ff2e3554362f9e1992f6c124fea006b6fe0a358d`;
- certificato firma SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100` — **PASS / stessa identità ufficiale MotoLink**;
- Gradle release build: **PASS**;
- APK ZIP integrity locale: **PASS**.

Implementazione:
- schermata: **CREDITI**;
- pulsante: **Sostieni MotoLink con PayPal**;
- azione: apertura esterna Android `ACTION_VIEW`;
- URL definitivo configurato: `https://www.paypal.com/donate/?hosted_button_id=N7BM2VCKWWKNN`;
- nessuna WebView di checkout e nessuna raccolta credenziali di pagamento dentro MotoLink;
- in caso di impossibilità ad aprire il link, viene mostrato un messaggio informativo locale;
- log locale dedicato all'apertura/fallimento del link.

GitHub storage discipline:
- nessuna nuova Release GitHub creata per vc23 test;
- artifact unsigned temporaneo rimosso dopo il download;
- artifact signed temporaneo rimosso dopo il download;
- file temporanei di handoff firma rimossi dal branch;
- workflow temporanei di build/sign/cleanup rimossi dal branch dopo il completamento.

Gate fisico ancora richiesto:
1. installazione come aggiornamento sopra V1.6 vc22;
2. apertura CREDITI e controllo visivo del pulsante;
3. tap sul pulsante;
4. verifica apertura della pagina PayPal Donazioni corretta con `hosted_button_id=N7BM2VCKWWKNN`;
5. ritorno a MotoLink senza regressioni della schermata CREDITI.

La promozione del pulsante nella prossima release resta **PENDING** fino al test fisico positivo.

---

## R66 — 2026-09-23 — CORREZIONE UI PAYPAL DONAZIONI / VC24 TEST

**Stato:** TECHNICAL PASS / PHYSICAL UI TEST PENDING.

La prima build PayPal vc23 viene dichiarata **SUPERSEDED** per la sola presentazione UI: il pulsante generico MotoLink era stato collocato erroneamente dentro la scheda **Autore**, mentre la richiesta approvata prevede il pulsante PayPal Donazioni ufficiale **sotto entrambe le schede Autore e Community e fuori da entrambe le griglie/card**.

Correzione implementata nella build test vc24:
- package: `it.motolink.app`;
- versionCode: `24`;
- versionName: `1.6-vc24-test`;
- APK: `MotoLink_V1.6_VC24_PAYPAL_DONATE_BUTTON_TEST_OFFICIAL_SIGNED.apk`;
- SHA-256 APK: `77ce1516e1bbe1db59950ff677afeb0efaf12436f58c0eeeeadd55b7bcb90020`;
- certificato firma MotoLink SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100` — **PASS**;
- Gradle release build: **PASS**;
- firma APK v2/v3 e metadata versionCode/versionName: **PASS**.

### Posizione UI congelata per il test

Nella schermata **CREDITI** l'ordine è:
1. scheda **Autore**;
2. scheda **Community**;
3. pulsante **PayPal Donazioni ufficiale**, separato e fuori da entrambe le schede;
4. copyright.

Il pulsante generico `Sostieni MotoLink con PayPal` della vc23 è stato rimosso.

### Pulsante PayPal ufficiale

La vc24 rende l'immagine ufficiale esattamente indicata dal codice PayPal fornito dall'utente:

`https://www.paypalobjects.com/it_IT/IT/i/btn/btn_donateCC_LG.gif`

con:

`hosted_button_id = N7BM2VCKWWKNN`

Il piccolo WebView è usato **solo come renderer del pulsante HTML ufficiale PayPal**; non ospita il checkout. Il submit viene intercettato localmente e il pagamento viene aperto esternamente tramite Android `ACTION_VIEW` verso:

`https://www.paypal.com/donate/?hosted_button_id=N7BM2VCKWWKNN`

Questa precisazione supersede la precedente formulazione R64 “non incorporare il form HTML”: il vincolo sostanziale resta che **il checkout/pagamento non deve avvenire dentro MotoLink e MotoLink non deve raccogliere credenziali di pagamento**. L'uso del form è limitato alla resa grafica del pulsante ufficiale richiesto.

### GitHub storage

La build vc24 non è stata pubblicata come Release. L'artifact firmato temporaneo è stato scaricato e poi eliminato da GitHub; i file di handoff firma sono stati rimossi. La release stabile V1.6 vc22 resta invariata.

### Gate fisico vc24

**PENDING:** verificare sul telefono che il pulsante appaia sotto Autore e Community, fuori dalle due card, con la grafica PayPal ufficiale; al tap deve aprire la pagina Donazioni PayPal corretta e il ritorno a MotoLink non deve alterare la schermata Crediti.

---

## 2026-09-25 — V1.6 VC27 FROZEN BASELINE: Pocket Mode V1.5.1 gate restore + VC26 EasyConn fix

Stato: **TECHNICAL PASS / PHYSICAL TEST PENDING**.

Baseline APK: `MotoLink_V1.6_VC27_POCKET_MODE_V151_GATE_RESTORE_EASYCONN_FIX_OFFICIAL_SIGNED.apk`.

Correzione Pocket Mode: in `MirrorService.armProximityScreenOff()` è stato ripristinato il gate della V1.5.1, sostituendo la regressione V1.6 `projection == null || !proximityGateAllowed` con `projection == null || !projectionReadyForProximity`. Il fix EasyConn VC26 resta integralmente preservato.

Freeze: la VC27 diventa baseline protetta per le integrazioni successive. EasyConn recovery, Pocket Mode/PROX, anti-auto-lock/lock path, MediaProjection core, H264 core e firma ufficiale non devono essere modificati fuori da uno scope esplicito e relativo test dedicato. Ogni futura build deve essere confrontata contro `MotoLink_V1.6_VC27_PAYLOAD_SHA256SUMS.txt`; ogni payload differente va dichiarato e giustificato prima della promozione.

GitHub non è stato modificato.
