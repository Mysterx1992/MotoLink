# MotoLink V1.6 vc22 — Proximity Hotfix

## Identità release

- Package: `it.motolink.app`
- Version name: `1.6`
- Version code: `22`
- APK ufficiale preparato: `MotoLink_V1.6_VC22_PROXIMITY_HOTFIX_OFFICIAL_SIGNED.apk`
- SHA-256 APK: `8e5516a3c655eb1314aa57dfa5ab65328db8447cbf01018892004e4f00487067`
- Certificato MotoLink SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Fix vc22

Con **Modalità tasca = NO** MotoLink non deve più lasciare attivo il percorso di prossimità.

Il flusso vc22:

1. invia `ACTION_PROX_RELEASE` invece di `ACTION_PROX_ARM`;
2. azzera la policy proximity della sessione;
3. deregistra il listener `TYPE_PROXIMITY`;
4. rilascia qualsiasi `PROXIMITY_SCREEN_OFF_WAKE_LOCK` residuo;
5. impedisce a chiamate interne successive di riarmare la prossimità mentre la policy sessione è OFF.

Con **Modalità tasca = SI** il comportamento di prossimità resta disponibile.

## Scope preservato

Il fix non cambia il comportamento previsto di EasyConn, H264, navigazione BLE o clock.
