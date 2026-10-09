# MotoLink V1.7 vc32 — diagnostica notifiche Google Maps

**Stato:** modifica diagnostica in ramo separato, non ancora release ufficiale né correzione del difetto.

## Scopo

Capire perché, sul telefono della VOGE 625, MotoLink legge notifiche Maps con `maneuver=STRAIGHT` e `distanceMissing=true`, pur avendo BLE/GATT e heartbeat pronti.

## Dati ora presenti nel log

- `MAPS NAV V1.7 FIELD DIAG`: notificationId, ongoing, categoria normalizzata, numero di campi e icone, conteggio di campi con cifre/unità/distanze riconosciute; `siblingFragmentHint` segnala soltanto numero e unità in viste sorelle.
- `MAPS NAV V1.7 FIELD PROBE X/Y`: metadati di tutti i campi fino a 96, in blocchi di sei: origine, identificativo di vista filtrato, posizione nell'albero, lunghezza, presenza cifre/unità, numero/unità isolati, distanza riconosciuta, orario, route summary e cirillico.
- Log diagnostici strutturalmente identici limitati a una volta ogni 15 secondi.

**Privacy:** non vengono salvati testo originale delle notifiche, nomi stradali, destinazioni, coordinate, valori numerici delle distanze, screenshot, indirizzi Bluetooth o immagini; i nomi di risorsa non riconosciuti sono sostituiti da `other`. I metadati restano nel log locale finché l'utente non lo pulisce o lo condivide.

## Cosa NON cambia

- Interpretazione operativa di distanze, manovre, rotonde e icone.
- Il gate che sopprime invii privi di una distanza reale.
- Il BLE VOGE: discovery, GATT, heartbeat, service/characteristic UUID e pacchetti 0x6A–0x6E.
- EasyConn, MediaProjection, H264, Pocket Mode, UI e chiave di firma.

## Prova fisica necessaria

1. Usare una APK vc32 **firmata con la stessa chiave della release ufficiale**, installata sopra la vc31. Una build debug CI non è compatibile come aggiornamento della APK ufficiale.
2. Su moto ferma o con un passeggero, avviare una navigazione Google Maps reale e attendere una svolta con distanza visibile sul telefono.
3. Esportare il nuovo log, conservando insieme tutte le righe `FIELD DIAG` e `FIELD PROBE X/Y` della stessa fase.
4. Verificare se esiste una vista con distanza, una coppia numeri/unità, una vista non pertinente o nessun dato realmente esposto da Maps.
5. Correggere il parser solo quando il log e un test reale identificano la causa; mai fabbricare distanze o aggirare il gate.

## Gate di promozione

PR draft, compilazione CI Android, confronto degli invarianti contro vc31, prova fisica riuscita, quindi approvazione esplicita prima di merge/pubblicazione. La release pubblica resta V1.7 vc31.
