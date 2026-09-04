# Privacy — MotoLink

Questa pagina descrive il comportamento tecnico di MotoLink rispetto ai dati dell'utente.

## Dati conservati sul telefono

MotoLink può conservare localmente:

- profili moto e relative preferenze;
- app preferite;
- impostazioni dell'interfaccia e dell'adattamento;
- stato delle funzioni configurate dall'utente;
- Log tecnico MotoLink.

Il Log locale non viene cancellato automaticamente dall'app: resta disponibile fino al comando esplicito **Pulisci** o alla cancellazione dei dati dell'app.

## Log e diagnostica

Una normale conversazione con l'Assistente **non invia automaticamente il Log**.

Il Log può lasciare il dispositivo soltanto su azione volontaria dell'utente:

- **Condividi esternamente**: Android apre il normale pannello di condivisione;
- **Invia all'Assistente**: MotoLink prepara una diagnostica filtrata e la invia con la richiesta di assistenza.

Prima dell'invio all'Assistente viene applicato un filtro locale. Il backend applica inoltre un secondo filtro server-side.

## Assistente MotoLink

Per utilizzare l'Assistente, MotoLink crea una sessione tecnica anonima tramite Supabase Auth.

Quando l'utente invia una domanda, il backend può ricevere:

- testo della domanda;
- versione dell'app;
- eventuale diagnostica filtrata allegata volontariamente.

Le domande possono essere risolte dalla knowledge base MotoLink. Quando non è sufficiente, il backend può utilizzare il modello configurato tramite provider IA.

## Dati non conservati nel database applicativo MotoLink

L'architettura non prevede la memorizzazione nel database applicativo di:

- cronologia delle chat;
- testo delle domande;
- testo delle risposte;
- contenuto dei Log inviati all'Assistente.

Il backend mantiene un contatore tecnico aggregato composto da **giorno + numero di chiamate provider IA** per applicare il limite configurato.

## Fornitori

Supabase, provider IA, Android/Google e altri servizi coinvolti possono elaborare metadati tecnici o log infrastrutturali secondo le rispettive condizioni e policy.

## Permessi Android

In base alla funzione utilizzata MotoLink può richiedere autorizzazioni relative a:

- Internet e stato della rete;
- posizione su versioni Android precedenti quando richiesta dalle API Wi-Fi legacy;
- MediaProjection;
- servizio in primo piano;
- visualizzazione sopra altre app;
- wake lock e funzioni di prossimità previste dall'app.

MotoLink non richiede `QUERY_ALL_PACKAGES`: la visibilità delle app è limitata alle attività avviabili previste dal Manifest Android.

## Eliminazione dei dati locali

I dati locali possono essere rimossi dalle impostazioni Android dell'app oppure disinstallando MotoLink.
