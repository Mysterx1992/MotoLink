# MotoLink — Gate finale Supabase per Google Play

**Stato al 24/10/2026: NON ATTIVO IN PRODUZIONE.**

Questa è la procedura controllata per completare la cancellazione della sola identità anonima dell'Assistente MotoLink. La sua pubblicazione non autorizza l'eliminazione di account esistenti. La procedura nasce dalla PR bozza #19 e dalla validazione integrata della PR bozza #22.

## Verifiche già eseguite da ChatGPT (read-only e sintetiche)

- Supabase progetto MotoLink, riferimento `yjklfhhchswnvqvgfrqk`, backend Assistant originale `motolink-assistant` v6 attivo, `verify_jwt=true`.
- Record reali al 24/10/2026: **13** utenti anonimi, **13** sessioni di autenticazione, **0** file Storage; nessuna eliminazione autorizzata o avviata.
- Solo `public.motolink_ai_usage` memorizza contatori giornalieri, non una cronologia delle domande.
- App: richiesta di eliminazione volontaria, token dell'utente **già esistente**, consenso esplicito, clear credenziali locali **solo dopo** `deleted:true`.
- Edge Function: controlla bearer JWT con il server Auth; rifiuta utenti non anonimi; estrae UID **unicamente** dalla risposta di Auth; usa `SUPABASE_SERVICE_ROLE_KEY` esclusivamente lato server; rifiuta input `user_id` nel payload.
- CI: compilazione Android, type-check Deno, 13 test di autorizzazione simulati e check documentazione PASS. Le simulazioni non provano la compatibilità con il backend reale.
- **Blocco osservato:** il precedente tentativo di distribuire il nuovo endpoint tramite gli strumenti collegati è stato respinto dai controlli di sicurezza dell'ambiente. Non aggirare né reinterpretare il blocco come deployment riuscito.

## Operazione amministrativa residua: distribuzione MANUALE da proprietario Supabase

1. Dal proprio PC, recuperare la bozza GitHub **PR #19** o branch `play/anonymous-session-deletion-v1` del repository `Mysterx1992/MotoLink`. Non utilizzare `main` prima del merge; lì la nuova funzione non è presente.
2. Aprire il progetto MotoLink su **[Supabase Dashboard — Edge Functions](https://supabase.com/dashboard/project/yjklfhhchswnvqvgfrqk/functions)**.
3. Usare Supabase CLI ufficiale, secondo [la procedura Supabase Deploy](https://supabase.com/docs/guides/functions/deploy). Da una copia locale della PR #19:
   - `supabase --version` e `supabase functions deploy --help` (verificare CLI corrente)
   - `supabase login`, con OAuth/login **solo nel proprio browser**
   - `supabase link --project-ref yjklfhhchswnvqvgfrqk`
   - `supabase functions deploy motolink-delete-session`
4. Controllare in Dashboard che **solo** la nuova funzione `motolink-delete-session` sia `ACTIVE`, con **`verify_jwt=true`**. Il file `supabase/config.toml` dichiara obbligatoriamente tale impostazione. **Mai** usare `--no-verify-jwt`.
5. Non cambiare `motolink-assistant`; non cancellare `auth.users`, non creare trigger `SECURITY DEFINER`, non copiare service_role in file dell'app, GitHub Secrets o chat. La chiave di servizio usata dal nuovo endpoint deve restare nelle variabili sicure dell'ambiente Supabase.

**Se il deployment fallisce o non mostra verify_jwt=true: STOP.** Non provare a sostituire l'Assistente esistente con la nuova funzione.

## Test reale controllato (solo DOPO un deployment verificato)

Un collaudo da PC Windows è preparato in `scripts/test_supabase_delete_disposable.ps1`. È **manual-only**, richiede conferma testuale e una sola **publishable key** (pubblica, non service_role). Non gira in CI.

La prova verifica nell'ordine:
1. Senza JWT, l'endpoint risponde `401/403`: nessuna identità creata.
2. Dopo conferma esplicita, una chiamata Auth crea **una nuova** identità anonima *disposable* e conserva il token solo in memoria.
3. Con quel JWT il nuovo endpoint risponde `deleted:true`, dopo verifica dell'UID sul server.
4. `GET /auth/v1/user` con lo stesso token non deve più poter recuperare l'identità cancellata.
5. Conteggio utenti esistenti prima/dopo invariato **se non si sono registrati altri utenti nel frattempo** (la variazione transient +1 → 0 si può verificare su dashboard con finestra di test controllata).

Non cancellare nessuno dei 13 utenti osservati: l'unico target è quello creato per il test. Non condividere JWT o chiavi segrete; non lanciare la prova su un dispositivo con dati/utenti reali. Il controllo di esistenza dopo il DELETE è necessario perché una semplice risposta 2xx non sostituisce una verifica.

Dopo il test amministrativo, validare **nella build di sviluppo MotoLink che incorpora PR #19**:
- Impostazioni → Elimina sessione Assistente → conferma → success solo dopo cancellazione remota;
- rilancio Assistente → viene creata una nuova identità;
- nessuna modifica a Garage, profili moto, Log, Wi-Fi, EasyConn o BLE;
- test errore/rete assente → il client non deve eliminare credenziali locali e deve offrire retry.

## Cosa NON è dimostrato ora

- Nuova funzione disponibile al pubblico: **NO** (backend non deployato, PR #19 e #22 non integrate).
- Cancellazione reale verificata: **NO**.
- URL esterno di Play Console pronto: **NO**: `DATA_DELETION.md` è attualmente su branch bozza; per Google Play deve essere disponibile su URL pubblico, stabile e accessibile fuori dall'app, con email ufficiale e percorso di richiesta.
- Cancellazione automatica dei dati presso fornitori esterni: **NON DICHIARATA**; eventuali log infrastrutturali/retention presso Supabase e Groq restano secondo le rispettive policy.
- Accettazione della dichiarazione Google Play: **NO**.

Una volta verificati tutti i punti, aggiornare `PRIVACY.md`, `DATA_DELETION.md` e la dichiarazione Data Safety con **funzione operativa**, e solo allora approvare l'integrazione finale e la pubblicazione.

**Contatto ufficiale privacy:** motolinkofficial.app@gmail.com.

Fonti: [Supabase Anonymous Sign-ins](https://supabase.com/docs/guides/auth/auth-anonymous), [Supabase Function Configuration](https://supabase.com/docs/guides/functions/function-configuration), [Supabase deployment](https://supabase.com/docs/guides/functions/deploy), [Google Play Account Deletion](https://support.google.com/googleplay/android-developer/answer/13327111).
