# Informativa privacy — MotoLink

Ultimo aggiornamento: 24 ottobre 2026.

Questa informativa descrive l'uso dei dati da parte dell'app Android **MotoLink** (`it.motolink.app`), inclusi gli strumenti di mirroring, navigazione BLE e supporto. Le funzioni facoltative richiedono autorizzazioni o azioni esplicite dell'utente.

## Contatto privacy e assistenza

**MotoLink — email ufficiale:** [motolinkofficial.app@gmail.com](mailto:motolinkofficial.app@gmail.com).

Puoi utilizzare questo indirizzo per richieste sulla privacy, per il supporto all'app e per chiedere informazioni sul trattamento o sulla cancellazione dei dati lato server. Le richieste vengono valutate in base ai dati effettivamente disponibili e alle possibilità tecniche; questa indicazione non implica una funzione automatica di eliminazione delle sessioni anonime.

## Dati e impostazioni conservati sul telefono

MotoLink può conservare sul dispositivo i profili delle moto, i dati di collegamento inseriti/scansionati, le preferenze relative ad app, schermata, orientamento e adattamento del display, le impostazioni delle funzioni e il **Log tecnico MotoLink**.

Il Log può contenere informazioni tecniche sulla connessione, sui dispositivi e sugli errori. Il Log locale non è cancellato automaticamente: per rimuoverlo, utilizzare **Supporto → Log → Pulisci**, oppure eliminare i dati dell'app dalle impostazioni Android. Le preferenze si rimuovono cancellando i dati dell'app o disinstallandola.

## Mirroring, rete locale, scansione QR e navigazione

- **Mirroring:** la funzione di acquisizione dello schermo utilizza l'autorizzazione MediaProjection concessa tramite Android. I contenuti catturati sono destinati al trasferimento sul TFT/dispositivo compatibile tramite la connessione locale. La funzione non invia il video al backend dell'Assistente MotoLink.
- **Connessione moto:** l'app utilizza i permessi Android necessari per Wi-Fi, Bluetooth/BLE, diagnostica di collegamento e, ove previsto dalla versione Android, autorizzazioni legacy di posizione. Alcuni dettagli tecnici possono comparire nel Log locale.
- **QR:** MotoLink usa Google Code Scanner e, se necessario, lo scanner interno CameraX. I servizi Google coinvolti possono trattare dati tecnici secondo le proprie informative.
- **Google Maps:** con l'accesso alle notifiche abilitato dall'utente, il listener MotoLink seleziona le notifiche provenienti da Google Maps (`com.google.android.apps.maps`) e ne interpreta le indicazioni di navigazione da inviare al TFT via BLE. L'accesso viene gestito nelle impostazioni Android e può essere revocato dall'utente.

## Assistente MotoLink e servizi esterni

L'**Assistente** è facoltativo e necessita di Internet. Quando viene utilizzato:

1. L'app crea o aggiorna una **sessione tecnica anonima** con **Supabase Auth**. I relativi token vengono conservati localmente nell'archivio privato dell'app, per consentire l'autenticazione delle richieste. Supabase gestisce i record e i metadati della sessione sul proprio servizio.
2. Alla pressione di **Invia**, MotoLink trasmette via HTTPS al backend Supabase il testo della domanda (massimo 1.200 caratteri), la versione dell'app e i metadati necessari al funzionamento della richiesta.
3. Il backend può rispondere tramite la propria knowledge base. Quando è necessario, può inoltrare il contenuto della domanda al fornitore di intelligenza artificiale **Groq** per ottenere una risposta.
4. Solo se l'utente seleziona **Supporto → Log → Condividi → Assistente**, MotoLink allega un estratto filtrato del Log, limitato a 6.000 caratteri. Il Log completo non viene inviato automaticamente. Il filtraggio locale e quello lato server riducono alcuni dati tecnici ma non garantiscono la rimozione di qualsiasi informazione che l'utente possa avere inserito nei messaggi.

**Non inserire password, codici, documenti o altri dati personali riservati nelle domande o nei Log da condividere.**

Secondo l'implementazione attuale, il **database applicativo MotoLink** non conserva il testo delle domande, le risposte o i Log inviati all'Assistente. Mantiene un contatore tecnico aggregato giornaliero per limitare l'uso del provider IA. Questa affermazione non esclude i record di autenticazione Supabase né eventuali log infrastrutturali o trattamenti effettuati da Supabase, Groq o dai relativi fornitori secondo le loro condizioni. I tempi di conservazione di tali sistemi non sono controllati direttamente dall'app Android.

## Condivisione esterna e donazioni

- **Condividi Log → Esterno:** soltanto dopo un'azione volontaria viene aperto il pannello di condivisione Android; il destinatario scelto dall'utente gestisce i dati ricevuti secondo le proprie condizioni.
- **Crediti / PayPal:** la schermata include elementi web caricati da PayPal (immagine del pulsante e risorsa di misurazione); il loro caricamento può comunicare informazioni tecniche di connessione al servizio. Se l'utente decide di sostenere MotoLink, il pagamento viene aperto fuori dall'app sul sito PayPal. MotoLink non riceve direttamente i dati della carta attraverso questa funzione. Le eventuali donazioni sono facoltative e non sbloccano funzioni.

## Permessi Android

In funzione delle caratteristiche e dei servizi usati, l'app può richiedere Internet, accesso/stato Wi-Fi, dispositivi vicini Bluetooth/BLE, fotocamera per scanner QR di riserva, posizione legacy su versioni Android precedenti, MediaProjection, servizio in primo piano, overlay e funzioni di prossimità/wake lock.

MotoLink non richiede `QUERY_ALL_PACKAGES`: la visibilità delle app è limitata alle attività avviabili per la funzione App preferite. I permessi possono essere consultati o revocati nelle impostazioni Android; la revoca può limitare le relative funzioni.

## Eliminazione e richieste sui dati

I dati locali possono essere eliminati tramite **Impostazioni Android → MotoLink → Spazio di archiviazione → Cancella dati** o disinstallando l'app. Cancellare dati o disinstallare l'app **non equivale necessariamente a eliminare il record della sessione anonima conservato da Supabase**. Per informazioni o per richiedere la verifica e, ove possibile, la cancellazione dei dati associati alla sessione tecnica lato server, scrivere a **motolinkofficial.app@gmail.com**. La procedura tecnica di identificazione della sessione e cancellazione dei record Supabase deve ancora essere completata e validata prima della distribuzione su Google Play.

## Documenti dei fornitori

- [Supabase — Privacy Policy](https://supabase.com/privacy)
- [Groq — Privacy Policy](https://groq.com/privacy-policy/)
- [Google — Privacy Policy](https://policies.google.com/privacy)
- [PayPal — Privacy Statement](https://www.paypal.com/it/legalhub/paypal/privacy-full)

Questa informativa va mantenuta coerente con le dichiarazioni **Sicurezza dei dati** in Google Play Console e aggiornata se cambiano funzionalità o fornitori.
