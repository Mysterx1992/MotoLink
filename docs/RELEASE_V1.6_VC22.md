# MotoLink V1.6 vc22 — Proximity Hotfix

## Identità release

- Package: `it.motolink.app`
- Version name: `1.6`
- Version code: `22`
- APK ufficiale preparato: `MotoLink_V1.6_VC22_PROXIMITY_HOTFIX_OFFICIAL_SIGNED.apk`
- SHA-256 APK: `8e5516a3c655eb1314aa57dfa5ab65328db8447cbf01018892004e4f00487067`
- Certificato MotoLink SHA-256: `9ae7bb26293441eb1bcea894774088a762e8eaecbf2a60297efadc20fc2a2100`

## Fix vc22

La patch firmata vc22 è volutamente minimale. In `MirrorService.armProximityScreenOff()` il gate che decide se proseguire con l'armamento usa `proximityGateAllowed`.

Quando **Modalità tasca = NO**, `proximityGateAllowed=false`: il metodo termina prima della registrazione del listener `TYPE_PROXIMITY` e prima dell'acquisizione del `PROXIMITY_SCREEN_OFF_WAKE_LOCK`. In questo modo il sensore non deve più spegnere lo schermo quando la funzione è disattivata.

Quando **Modalità tasca = SI** e il gate è disponibile, il comportamento di prossimità resta invariato.

## Scope preservato

Il fix non cambia il comportamento previsto di EasyConn, H264, navigazione BLE o clock.
