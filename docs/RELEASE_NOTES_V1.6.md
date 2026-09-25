# MotoLink V1.6 vc28 — Full TFT Recovery Hotfix

MotoLink V1.6 vc28 nasce dal test fisico Trofeo 500 del 25/09/2026. La vc27 ha dimostrato di recuperare correttamente la chiusura temporanea dei soli canali media; il nuovo log ha però mostrato un secondo scenario: il TFT può chiudere nello stesso istante H264, Media e tutti i PXC e rendere non più raggiungibile il precedente endpoint EasyConn.

## Identità

- Package: `it.motolink.app`
- Version name: `1.6`
- Version code: `28`
- Branch: `release/v1.6-vc28`
- Stato: **recovery hotfix source / physical validation pending**

## Evidenza fisica che ha motivato vc28

Nel test reale Trofeo 500:
- prima dell'evento video e heartbeat PXC erano sani;
- il TFT ha chiuso contemporaneamente `10920`, `10921`, PXC#1 e PXC#2;
- i successivi tentativi al vecchio endpoint EasyConn sono arrivati a `EHOSTUNREACH (No route to host)`;
- MediaProjection/encoder sono rimasti vivi ma senza consumer H264;
- una successiva ricreazione della MainActivity poteva mostrare erroneamente “Sessione attiva”.

## Fix vc28

### 1. Recovery trasporto completo

Quando il trasporto di sessione non è più realmente vivo, MotoLink:
1. mantiene MediaProjection e encoder attivi;
2. invalida `lastResolved` e l'expected peer EasyConn;
3. non considera più un generico default Wi-Fi come prova sufficiente della rete moto;
4. riaggancia il trasporto del profilo:
   - Hotspot: stessa Wi-Fi moto ricordata esclusivamente in RAM, con fallback rete locale;
   - QR/classic Wi-Fi: nuova richiesta Android tramite il profilo salvato;
   - WLAN Direct/P2P: nuova discovery/connessione P2P;
5. rifà mDNS EasyConn o il direct init P2P;
6. attende `10920` e un vero FIRST FRAME prima di dichiarare recovery completato.

### 2. Endpoint stale

Dopo un `EC INIT` fallito sul vecchio endpoint durante recovery, l'IP non viene più ritentato indefinitamente: viene invalidato e parte il percorso di rebind + rediscovery.

### 3. Session reattach Home

Se MainActivity viene ricreata mentre MirrorService è ancora vivo ma `H264FrameBus.hasActiveConsumer() == false`, la Home non mostra più “Sessione attiva”. Mostra invece “Mirroring da ripristinare” e START rimane utilizzabile per riavviare il recovery senza richiedere una nuova MediaProjection.

## Componenti preservati dalla vc27

La vc28 non modifica:
- gate Modalità tasca `projectionReadyForProximity`;
- proximity / anti-auto-lock;
- MediaProjection core;
- encoder H264 / H264FrameBus;
- geometria/adattamento video;
- protocollo EasyConn/PXC;
- clock EasyConn.

## Gate richiesto

Test Trofeo 500:
1. avvia mirroring;
2. passa a tachigrafo/altro TFT fino a provocare anche la perdita PXC;
3. attendi il ritorno/riaccensione del trasporto TFT;
4. verifica che MotoLink riagganci rete + EasyConn e torni al FIRST FRAME senza nuovo consenso MediaProjection;
5. verifica che la Home non dichiari “Sessione attiva” con consumer assente.
