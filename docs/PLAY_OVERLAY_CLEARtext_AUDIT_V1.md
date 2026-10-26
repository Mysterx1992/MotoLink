# MotoLink V1.8 — Audit overlay e traffico in chiaro per Google Play

Data: **24 ottobre 2026**. Origine: `main` ufficiale V1.8 (versionCode 34). Intervento separato dalle PR #17 (16 KB), #18 (privacy), #19 (cancellazione sessione) e #20 (mediaProjection FGS).

## Sintesi

1. **`SYSTEM_ALERT_WINDOW` non è un permesso inutilizzato.** MotoLink impiega superfici `TYPE_APPLICATION_OVERLAY` nel mirroring per:
   - comando schermo nero manuale: copertura pieno schermo e blocco dei tocchi;
   - pannello flottante dell'editor Adattamento, visualizzato sulle altre app;
   - accesso alla Gate Activity dal background in specifici percorsi della Modalità tasca.
   La concessione è gestita mediante la schermata speciale Android e verificata con `Settings.canDrawOverlays()`.
2. **Non richiedere overlay per la sola navigazione BLE**, che utilizza il proprio listener e l'autorizzazione alle notifiche. Lo START con Modalità tasca OFF prosegue verso il mirroring senza dover aprire la schermata overlay; l'editor Adattamento richiede invece il permesso solo quando l'utente lo abilita.
3. **`usesCleartextTraffic=true` era già nella V1.8:** consente comunicazioni HTTP non cifrate lato app. La rete TFT/EasyConn usa peer/porte locali e protocolli TCP con indirizzi dinamici, non sostituibili con una whitelist di nomi di dominio pubblici senza prove fisiche.
4. Il codice Android dell'Assistente usa URL **HTTPS** verso Supabase; pulsante PayPal e risorse web caricate dall'app usano HTTPS. Non è stata identificata nei componenti esaminati una necessità di HTTP verso questi specifici host pubblici.

## Modifica minima proposta (DRAFT)

`app/src/main/res/xml/network_security_config.xml` introduce:

- `base-config cleartextTrafficPermitted="true"` per mantenere il comportamento di compatibilità corrente anche dopo aver attivato una Network Security Configuration. **Non è una politica HTTPS-only generale.**
- `domain-config cleartextTrafficPermitted="false"` per `supabase.co`, `paypal.com` e `paypalobjects.com`, inclusi i sottodomini. Per questi host, richieste non cifrate tramite stack Android che rispettano la Network Security Policy vengono respinte.
- `AndroidManifest.xml` collega `@xml/network_security_config` senza rimuovere il permesso overlay né i permessi del mirroring.

Questa protezione circoscritta **non cifra il traffico tra telefono e TFT**. Android avverte che i socket TCP grezzi generalmente non rispettano `usesCleartextTraffic` / Network Security Policy, perché non è possibile classificare in automatico il loro payload. Né la whitelist né il flag del Manifest risolvono la sicurezza del protocollo EasyConn.

**Nessuna modifica** a host/porte TCP, risoluzione rete, pipeline MediaProjection-H.264, adattamento, prossimità, BLE, assistente, credenziali o PayPal. Questa PR cambia esclusivamente le impostazioni di policy Android per host internet noti. Prima di qualunque merge rimane necessario un test fisico di parità.

## Test prima di un eventuale merge

| Test | Criterio |
| --- | --- |
| Gradle build | APK debug compila e il Manifest include `networkSecurityConfig` |
| Test script `check_play_overlay_network.py` | Scope conservato, host protetti, overlay rimasto e richiesta ancora condizionata |
| Supabase Assistente | Domanda KB e domanda IA rispondono normalmente su rete Internet separata dalla Wi-Fi moto |
| PayPal | Pagina Crediti mostra ancora il pulsante e, solo alla pressione, apre il sito sicuro esterno |
| Home → START/STOP | Acquisizione schermo, notifica di sistema e arresto senza regressioni |
| Mirror EasyConn / WLAN Direct | Collegamento TFT, prima immagine H.264, rotazione e riconnessione come baseline V1.8 |
| Modalità tasca | Con autorizzazione overlay: NEAR, risveglio, schermo nero manuale e ripresa |
| Adattamento | Pannello flottante e quattro direzioni di calibrazione visibili e funzionanti |
| Navigazione BLE | Ricezione e invio delle normali frecce al TFT; **non** attendersi simboli grafici di rotonda |

**Nessuna certificazione di comportamento runtime** è implicata dalla sola compilazione o dal test di Manifest. Nessuna dichiarazione Play Console è stata ancora inserita.

## Descrizione opzionale all'utente per il permesso overlay

> MotoLink può richiedere “Mostra sopra altre app” per visualizzare il comando schermo nero, bloccare i tocchi involontari durante il mirroring e mostrare il pannello di Adattamento sopra l'app attualmente aperta. Su alcuni telefoni questo permesso è inoltre utilizzato dal percorso Modalità tasca per gestire la schermata di protezione mentre un'altra app è in primo piano. Puoi disattivare il permesso nelle Impostazioni Android, ma alcune di queste funzioni potrebbero non essere disponibili.

Questo testo è proposto per documentazione / eventuale spiegazione in-app futura; **non si aggiungono prompt ridondanti** nella V1.8 con questa PR.

## Altra superficie da esaminare separatamente

La schermata Crediti carica un piccolo modulo WebView contenente il pulsante ufficiale di donazione PayPal tramite HTTPS e un `JavascriptInterface` ristretto all'azione di apertura del browser. È opportuno valutare in una PR separata l'eliminazione di WebView + pixel senza alterare l'aspetto approvato e le donazioni. Non cambiare il percorso in questa PR: serve verifica visiva e funzionale.

## Riferimenti

- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [Manifest `usesCleartextTraffic` (nota sui socket grezzi)](https://developer.android.com/guide/topics/manifest/application-element)
- [Permesso SYSTEM_ALERT_WINDOW](https://developer.android.com/reference/android/Manifest.permission)
- [Google Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)

**Stato:** patch tecnica limitata + audit statico. Nessun merge, release o deploy autorizzato. La review Play e il test fisico rimangono aperti.
