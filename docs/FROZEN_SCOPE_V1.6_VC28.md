# MotoLink V1.6 vc28 — Frozen Scope Contract

La vc28 modifica esclusivamente il recovery di trasporto/sessione e il reattach della Home. Tutto il resto resta congelato dalla vc27.

## Identità

- Version name: `1.6`
- Version code: `28`
- Branch autorevole: `release/v1.6-vc28`
- Stato: **scope frozen / physical validation pending**

## File modificabili nella hotfix vc28

- `app/src/main/java/it/motolink/app/MainActivity.kt`
- `app/src/main/java/it/motolink/app/BikeNetworkConnector.kt`
- metadati/versione e documentazione

Qualsiasi modifica ad altri componenti richiede un nuovo gate esplicito.

## Invarianti da preservare

Non modificare senza test dedicato:
- `MirrorService.kt` e gate `projectionReadyForProximity`;
- proximity / anti-auto-lock;
- MediaProjection;
- H264 encoder e H264FrameBus;
- EasyConnServers protocol/wire behavior;
- clock EasyConn;
- adattamento/geometria video;
- BLE navigation;
- firma ufficiale MotoLink.

## Recovery vc28

- Se PXC è vivo: attendere il reconnect naturale del TFT senza teardown.
- Se anche PXC cade: mantenere MediaProjection/encoder e ricostruire il trasporto.
- Invalidare endpoint EasyConn stale dopo un init fallito durante recovery.
- Rebind alla rete moto/P2P e fresh discovery prima di nuovi init.
- Dichiarare successo solo dopo FIRST FRAME reale.
- Non mostrare “Sessione attiva” quando il consumer H264 è assente.

## Regola integrazioni future

Ogni modifica futura deve partire da questa branch/commit line, produrre diff dei file critici e dimostrare che proximity, MediaProjection, H264 e il recovery vc28 non sono stati alterati accidentalmente.
