# MotoLink V1.7 — release ufficiale

**Versione corrente:** `1.7` (`versionCode 33`) — download principale: [MotoLink1.7.apk](https://github.com/Mysterx1992/MotoLink/releases/download/v1.7/MotoLink1.7.apk).

La nuova versione migliora la freschezza delle distanze sul TFT attraverso una coda BLE che sostituisce gli aggiornamenti superati; tenta inoltre il ricollegamento automatico dopo lo spegnimento del quadro e mantiene STOP come arresto definitivo dei tentativi. Rimangono invariati il supporto multilingua e in russo, il riconoscimento delle rotonde, il protocollo VOGE e le funzioni di mirroring.

**Firma APK e integrità verificate.** La riduzione effettiva della latenza e il ciclo di riconnessione attendono conferma su moto.

Per note complete, vincoli e hash vedi [note ufficiali](RELEASE_NOTES_V1_7_VC33_OFFICIAL.md) e [master unico R70](MOTOLINK_MASTER_UNICO_CURRENT.md).

---

## Note storiche del primo rilascio V1.7 (conservate integralmente)

# MotoLink 1.7

MotoLink 1.7 è l'aggiornamento ufficiale successivo alla V1.6/vc28.

## Novità

- L'interfaccia segue automaticamente la lingua del telefono.
- Traduzioni complete incluse: Italiano, English, Русский e 中文.
- Per le lingue non ancora tradotte viene usato l'inglese.
- Tradotte anche la Guida iniziale e l'avviso di sicurezza durante la guida.
- Google Maps riconosce anche le notifiche russe, comprese unità cirilliche `м` / `км`, spazi Unicode, tempi e principali manovre testuali.
- Migliorata la navigazione BLE turn-by-turn sui telefoni impostati in russo, inclusa VOGE Valico 625 DSX.
- Recovery rete/TFT vc28, Modalità tasca, MediaProjection e pipeline H264 restano preservati.

## Identità

- package: `it.motolink.app`
- versionName: `1.7`
- versionCode: `30`
- branch: `release/v1.7`

## Versione App

Il pannello **Impostazioni → Versione App** mostra esclusivamente le novità della versione 1.7 installata e segue la lingua del telefono.

## Stato

- Source gate: PASS
- Android API 36 build: PASS
- Firma ufficiale MotoLink: pending materializzazione del backup privato originale nel runtime
- Pubblicazione release: pending firma ufficiale
