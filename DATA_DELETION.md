# MotoLink — richiesta di eliminazione account tecnico e dati

_Ultimo aggiornamento: 24 ottobre 2026._

Questa è la risorsa pubblica di MotoLink per richiedere l'eliminazione dei dati e dell'eventuale **identità tecnica anonima** associata all'Assistente MotoLink.

## Richiedere la cancellazione dall'app

Nella futura versione dell'app che includerà questa funzione: apri **Impostazioni → Elimina sessione Assistente → ELIMINA**. Dopo la conferma, l'app invia una richiesta autenticata al server, che deve eliminare soltanto la sessione anonima associata a quel telefono. La conferma di successo apparirà **solo dopo** la risposta positiva del server.

**Stato attuale:** questa procedura è ancora in preparazione e non è presente nella V1.8 distribuita su GitHub. Non va dichiarata operativa prima della messa in servizio del relativo endpoint Supabase.

## Richiedere la cancellazione senza app installata

Puoi inviare una richiesta via email al contatto ufficiale:

**[motolinkofficial.app@gmail.com](mailto:motolinkofficial.app@gmail.com?subject=MotoLink%20-%20Richiesta%20cancellazione%20dati)**

Oggetto suggerito: **MotoLink — Richiesta cancellazione dati**.

Puoi indicare se vuoi eliminare informazioni sull'Assistente, chiedere informazioni sui dati trattati oppure ricevere supporto per l'eliminazione dei dati locali.

**Non inviare** password, token di sessione, chiavi API, codici QR della moto, log completi o altri dati riservati. Per identificare un account anonimo rimasto sul server potrebbe non bastare l'indirizzo email da cui scrivi: l'identità tecnica non è collegata automaticamente a un'email e l'assistenza non deve cancellare account sulla base di identificativi non verificati. In questa eventualità ti verrà spiegato cosa è tecnicamente identificabile e cosa no.

## Cosa viene eliminato

- **Identità tecnica anonima dell'Assistente:** quando la procedura server è attiva e l'utente è autenticato, viene richiesta la cancellazione del relativo utente Supabase Auth, con invalidazione della sessione di refresh.
- **Impostazioni e log sul telefono:** non sono rimossi dal comando relativo all'Assistente, perché sono dati locali. Per eliminarli usa **Supporto → Log → Pulisci**, oppure le impostazioni Android per cancellare i dati dell'app.
- **Domande e risposte:** il database applicativo MotoLink non conserva cronologie di conversazioni; eventuali log o trattamenti infrastrutturali presso Supabase, Groq e altri fornitori restano soggetti alle rispettive condizioni e politiche di conservazione.

L'eliminazione dell'utente Supabase non annulla immediatamente un token JWT già rilasciato fino alla sua scadenza. Dopo la cancellazione, se si usa di nuovo l'Assistente, viene creata una **nuova** identità tecnica anonima.

## Informazioni e privacy

Leggi anche l'[Informativa privacy MotoLink](PRIVACY.md). Per ogni richiesta scrivi a **motolinkofficial.app@gmail.com**.
