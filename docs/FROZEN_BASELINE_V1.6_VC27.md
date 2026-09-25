# MotoLink V1.6 vc27 — Frozen Baseline Contract

Questa baseline è congelata per evitare regressioni durante integrazioni future.

## Identità

- Version name: `1.6`
- Version code: `27`
- Branch autorevole: `release/v1.6-vc27`
- APK validato: `MotoLink_V1.6_VC27_POCKET_MODE_V151_GATE_RESTORE_EASYCONN_FIX_OFFICIAL_SIGNED.apk`
- SHA-256 APK: `a757654f02961ae7ebadeb4a776b0b3d759f263ab483cad5995548da771e564d`
- Certificato ufficiale SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Comportamenti congelati

1. **EasyConn recovery**
   - La chiusura temporanea di 10920/10921 non equivale a perdita rete.
   - Recovery vivo con PXC live, link moto gestito/P2P live oppure endpoint EasyConn già risolto + Wi-Fi Android ancora attivo.
   - Nessun teardown dei listener EasyConn mentre il trasporto di sessione è considerato vivo.

2. **Modalità tasca**
   - Con funzione OFF non deve essere armato il percorso proximity.
   - Con funzione ON `armProximityScreenOff()` resta pendente finché `projectionReadyForProximity == true`.
   - Il gate V1.5.1 è autorevole e non deve essere sostituito da `proximityGateAllowed`.

3. **MediaProjection / H264**
   - Nessuna modifica non preregistrata a MediaProjection, encoder H264, H264FrameBus, geometria o clock EasyConn.
   - Il keyguard reale resta un confine Android: non si tenta di catturare contenuti sotto lock.

## Regola per integrazioni future

Ogni integrazione successiva deve:
- partire da questa baseline;
- produrre un diff esplicito dei file critici;
- dimostrare che EasyConn recovery e Modalità tasca non sono stati alterati accidentalmente;
- mantenere la firma ufficiale MotoLink;
- superare test statici e test fisici dedicati prima della promozione.

## Test fisici ancora richiesti

- Trofeo 500: navigatore → tachigrafo per almeno 3–5 minuti → ritorno al mirroring senza nuovo START.
- Modalità tasca: sensore coperto per almeno 5–10 minuti → FAR, senza ingresso nel keyguard e senza nuovo START.
