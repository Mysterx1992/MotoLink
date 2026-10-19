# Guida rapida MotoLink

## Home

La Home mostra lo stato generale dell'app e, quando esiste una connessione reale, la moto collegata. Da qui puoi avviare il mirroring con **START** e raggiungere rapidamente le app preferite.

## Garage

Il Garage permette di gestire fino a 3 profili moto. Ogni profilo deve avere un nome. In base al modello e al flusso disponibile puoi aggiungere una moto tramite QR oppure creare un profilo locale senza QR.

Le regolazioni di Adattamento vengono associate al profilo moto e restano separate tra profili diversi.

## Preferite

Puoi selezionare fino a 4 app da rendere rapidamente accessibili dalla Home.

Android può richiedere una conferma di sistema quando viene avviata una nuova sessione MediaProjection.

## Supporto — Log

Il Log contiene gli eventi tecnici MotoLink e resta sul telefono. Non viene cancellato automaticamente: usa **Pulisci** quando vuoi azzerarlo.

Con **Condividi** puoi scegliere una condivisione esterna oppure l'invio volontario all'Assistente.

## Supporto — Assistente

Tocca il campo di scrittura, inserisci la domanda e inviala. L'Assistente è dedicato alle funzioni e ai problemi MotoLink.

Una normale chat non legge il Log. Per allegarlo usa:

**Supporto → Log → Condividi → Assistente**

## Impostazioni — Modalità tasca

La Modalità tasca usa le funzioni previste dall'app per proteggere lo schermo e ridurre i tocchi involontari durante il mirroring.

Durante il mirroring, la pressione rapida di **Volume Giù due volte** attiva/disattiva lo schermo nero con blocco tocchi previsto da MotoLink. Non equivale allo spegnimento hardware del display Android.

## Impostazioni — Adattamento

Adattamento permette di correggere manualmente come il contenuto viene mostrato sul display moto.

- La geometria di base prevista per il profilo viene applicata automaticamente.
- Attiva **Adattamento** quando vuoi aprire l'editor e fare una regolazione personale.
- Le correzioni vengono salvate automaticamente per il profilo moto interessato.
- La **X** chiude l'editor e porta la voce Adattamento su OFF, ma la personalizzazione resta applicata.
- **Adattamento OFF** significa editor chiuso: non annulla la geometria di base né le regolazioni personali salvate.
- Usa **Ripristina** solo quando vuoi tornare alla geometria di base prevista per il profilo.

## Impostazioni — VERSIONE

Tocca **VERSIONE** per vedere le novità e i bugfix della versione MotoLink installata. Le informazioni sono disponibili localmente e non richiedono Internet.

## Connessione e rientro nel mirroring

Se il TFT cambia temporaneamente modalità mentre la rete della moto resta disponibile, MotoLink mantiene la sessione e continua ad attendere il display. Quando il TFT torna alla modalità mirroring, l'app prova a riprendere automaticamente il flusso senza richiedere un nuovo START.

## Sicurezza durante la guida

Configura profili, app, permessi e regolazioni a veicolo fermo. Non interagire con lo smartphone in modo da distrarti dalla guida.

## Impostazioni — Ripristino Wi-Fi (VOGE Trofeo 500)

**Usalo solo a moto ferma e quando il TFT non riesce più a collegarsi all'hotspot del telefono.** Nella Trofeo 500 il telefono crea la rete Wi-Fi, mentre il TFT si collega automaticamente come client.

1. Apri **Impostazioni → Ripristino Wi-Fi**. Leggi l'avviso e premi **SÌ** per procedere, oppure **NO** per annullare.
2. Assicurati che hotspot e Bluetooth siano accesi. Ferma prima il mirroring MotoLink con STOP.
3. Di norma lascia SSID e password vuoti: la richiesta Android client→rete del secondo pulsante della vecchia diagnostica è nella direzione opposta rispetto alla connessione TFT→hotspot. Inserisci quelle credenziali solo se il TFT mostra esplicitamente una rete da associare dal telefono.
4. Premi **AVVIA**. MotoLink avvia ricerca Bluetooth Classic, interrogazione SDP, scansione BLE e, se trova un dispositivo VOGE, discovery GATT di sola lettura. Esegue anche una scansione Wi-Fi del telefono. Con SSID/password compilati richiede inoltre un'associazione Wi-Fi temporanea ad Android.
5. Al termine verifica il simbolo Wi-Fi sul TFT e che la moto si sia collegata all'hotspot. Premi **VERIFICA RISULTATO SUL TFT**: rispondi **SÌ** solo se la connessione funziona. Il messaggio **Successo!** rappresenta una conferma fornita dall'utente, non una lettura diretta del TFT.
6. Se non funziona, seleziona **NO** e condividi il log tramite **Supporto → Log**. Non ripetere la procedura durante la guida.

Il recupero non cambia SSID o password dell'hotspot e non scrive sulle caratteristiche GATT della moto. La password inserita per il test non viene registrata nel log. Può tuttavia disturbare temporaneamente le connessioni wireless.

Questa funzione è sperimentale: sono stati riferiti due recuperi Wi-Fi dopo l'uso della diagnostica originale, ma l'operazione specifica responsabile non è stata ancora isolata.

### APK laboratorio

L'APK di laboratorio è un'app separata con package Android it.motolink.app.recoverylab, firmata con certificato debug. Può essere installata accanto alla V1.7 ufficiale senza sostituirla. I dati delle due app sono separati.
