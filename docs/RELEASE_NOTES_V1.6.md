# MotoLink V1.6 vc27 — Frozen Baseline

MotoLink V1.6 vc27 consolida la V1.6 con due bugfix mirati emersi dai test fisici, senza modificare il core video oltre quanto necessario.

## Identità

- Package: `it.motolink.app`
- Version name: `1.6`
- Version code: `27`
- Baseline: **FROZEN**
- APK ufficiale validato localmente: `MotoLink_V1.6_VC27_POCKET_MODE_V151_GATE_RESTORE_EASYCONN_FIX_OFFICIAL_SIGNED.apk`
- SHA-256 APK: `a757654f02961ae7ebadeb4a776b0b3d759f263ab483cad5995548da771e564d`
- Certificato MotoLink SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Bugfix vc27

### EasyConn / Trofeo 500 — reconnect persistente

Quando il TFT esce dalla schermata di mirroring e chiude temporaneamente i canali media `10920/10921`, MotoLink non deve interpretare immediatamente l'evento come perdita reale della rete moto.

Il recovery resta vivo se esiste almeno uno dei segnali di sessione:
- PXC ancora attivo;
- link moto gestito da MotoLink / Wi-Fi Direct ancora attivo;
- endpoint EasyConn già risolto nella sessione corrente e rete Android ancora su Wi-Fi.

Questo evita la terminazione prematura del recovery durante il passaggio navigatore → tachigrafo e consente al TFT di riaprire naturalmente EasyConn/H264 quando si torna al mirroring.

### Modalità tasca — ripristino gate V1.5.1

La V1.6 aveva sostituito il gate di armamento della prossimità `projectionReadyForProximity` con `proximityGateAllowed`. I test fisici hanno mostrato che, dopo permanenze prolungate con display spento per prossimità, il dispositivo poteva entrare nel keyguard reale e invalidare MediaProjection.

La vc27 ripristina esattamente il gate della V1.5.1:

`projection == null || !projectionReadyForProximity`

La richiesta di Modalità tasca resta pendente finché MediaProjection e il percorso video non sono realmente pronti.

## Contratto di freeze

La vc27 è la baseline congelata per le integrazioni successive.

Le modifiche future non devono alterare senza test dedicato:
- recovery EasyConn / PXC / 10920 / 10921 / 10922;
- Modalità tasca / proximity / anti-auto-lock;
- MediaProjection core;
- encoder H264 e H264FrameBus;
- clock EasyConn;
- firma ufficiale MotoLink.

Ogni integrazione futura deve essere verificata contro questa baseline con diff e hash dei componenti critici prima della promozione.

## Stato validazione

- Fix sorgente: applicato.
- Firma APK locale vc27: verificata.
- APK v2/v3: PASS.
- Gate fisico Modalità tasca prolungata: da completare sulla moto/telefono reale.
- Gate fisico reconnect Trofeo 500: da completare con test navigatore → tachigrafo → navigatore.
