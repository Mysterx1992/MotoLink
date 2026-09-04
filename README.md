<p align="center">
  <img src="docs/images/banner-motolink.png" alt="MotoLink" width="100%">
</p>

# MotoLink

**Mirroring dello smartphone sui display moto compatibili, con profili, app preferite, adattamento display e supporto tecnico integrato.**

<p>
  <a href="https://github.com/Mysterx1992/MotoLink/releases/latest"><strong>⬇️ Scarica MotoLink APK</strong></a>
  &nbsp;•&nbsp;
  <a href="https://chat.whatsapp.com/BNTmFxXQuOkGdYWHrX2rV0?s=cl&p=a&mlu=4"><strong>💬 Gruppo MotoLink Mirroring</strong></a>
</p>

> L'APK ufficiale è pubblicato direttamente nella pagina **Releases**:  
> **https://github.com/Mysterx1992/MotoLink/releases/latest**

MotoLink è un'app Android pensata per portare il mirroring dello smartphone sul display di una moto compatibile. L'interfaccia riunisce connessione, profili moto, applicazioni preferite, strumenti di adattamento e supporto tecnico in un unico ambiente.

## Funzioni principali

- **Mirroring** verso display moto compatibili con il flusso supportato da MotoLink.
- **Garage** con profili moto e configurazione tramite QR o profilo locale quando previsto.
- **App preferite**: fino a quattro applicazioni richiamabili rapidamente dalla Home.
- **Adattamento display** con regolazioni manuali persistenti per profilo moto.
- **Modalità tasca** e schermo nero con blocco tocchi durante il mirroring.
- **Log MotoLink** locale e persistente fino al comando esplicito **Pulisci**.
- **Assistente MotoLink** per supporto tecnico sull'app.
- Invio volontario del **Log filtrato** all'Assistente tramite **Supporto → Log → Condividi → Assistente**.
- Collegamento diretto al gruppo **MotoLink Mirroring** per il supporto della community.

## MotoLink in azione

<table>
<tr>
<td width="50%"><img src="docs/images/home-motolink.png" alt="MotoLink Home"></td>
<td width="50%"><img src="docs/images/supporto-motolink.png" alt="MotoLink Supporto"></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/preferite-motolink.png" alt="MotoLink App preferite"></td>
<td width="50%"><img src="docs/images/crediti-motolink.png" alt="MotoLink Crediti"></td>
</tr>
</table>

Gli screenshot originali, senza composizione grafica, sono disponibili in [`docs/screenshots`](docs/screenshots).

## Download APK

La pagina ufficiale per scaricare l'ultima build pubblicata è:

### **[➡️ Apri Releases e scarica MotoLink APK](https://github.com/Mysterx1992/MotoLink/releases/latest)**

Per la release `v1.0`, se l'asset viene pubblicato con il nome `MotoLink_V1.0.apk`, il collegamento diretto al file sarà:

`https://github.com/Mysterx1992/MotoLink/releases/download/v1.0/MotoLink_V1.0.apk`

Scarica l'APK soltanto dalla repository ufficiale e, quando disponibile, confronta l'hash SHA-256 indicato nella Release.

## Requisiti Android

- Android **10 / API 29** o superiore.
- `compileSdk 36`
- `targetSdk 36`
- Java / JVM **17**

Alcune funzioni dipendono dalla versione Android, dai permessi concessi e dal comportamento del display moto. La compatibilità può variare tra modelli e firmware.

## Struttura del progetto

```text
MotoLink/
├── app/                         # Applicazione Android
├── gradle/                      # Gradle Wrapper
├── supabase/
│   ├── functions/              # Backend Assistente MotoLink
│   └── migrations/             # Quota server-side
├── docs/                        # Immagini, screenshot e documentazione
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Build Android

1. Clona la repository.
2. Apri la cartella del progetto con Android Studio.
3. Attendi la sincronizzazione Gradle.
4. Compila con **Build → Build APK(s)** oppure tramite Gradle Wrapper.

Il progetto usa il package Android:

`it.motolink.app`

La configurazione Supabase presente nell'app contiene soltanto **URL del progetto e publishable key**, valori pubblici destinati al client. Le chiavi private non devono essere inserite nel repository.

## Assistente MotoLink

L'Assistente è dedicato al supporto tecnico MotoLink.

Una normale conversazione **non legge automaticamente il Log**. Per allegare volontariamente la diagnostica:

**Supporto → Log → Condividi → Assistente**

MotoLink applica un filtro locale alla diagnostica; il backend esegue inoltre un secondo filtro prima dell'elaborazione.

Il backend è disponibile nella cartella [`supabase/functions/motolink-assistant`](supabase/functions/motolink-assistant).

### Secret server richiesti

I valori seguenti devono essere configurati nei **Secrets** della propria installazione Supabase e non devono mai essere committati:

- `AI_PROVIDER_API_KEY`
- `AI_PROVIDER_MODEL` *(opzionale)*

Il codice utilizza inoltre le variabili server fornite dall'ambiente Supabase per le operazioni interne.

## Privacy

- Profili, preferenze e Log tecnico vengono gestiti localmente dall'app secondo le funzioni previste.
- Il Log non viene inviato automaticamente durante la chat.
- Il database MotoLink non conserva la cronologia delle domande, delle risposte o il contenuto dei Log inviati all'Assistente.
- Il backend mantiene un contatore aggregato **giorno + numero di chiamate provider IA** per applicare il limite configurato.

Consulta [`PRIVACY.md`](PRIVACY.md) per maggiori dettagli.

## Sicurezza

**Non pubblicare mai** nel repository o nelle Issue:

- API key private;
- chiave server privata key;
- keystore e password di firma;
- token o credenziali;
- QR completi o dati personali sensibili.

Consulta [`SECURITY.md`](SECURITY.md).

## Supporto

- Assistente integrato: **Supporto → Assistente**
- Log tecnico: **Supporto → Log**
- Community: **[MotoLink Mirroring su WhatsApp](https://chat.whatsapp.com/BNTmFxXQuOkGdYWHrX2rV0?s=cl&p=a&mlu=4)**

Consulta anche [`SUPPORT.md`](SUPPORT.md).

## Sicurezza durante la guida

Configura profili, applicazioni, permessi e regolazioni a veicolo fermo. Non utilizzare lo smartphone o MotoLink in modo da distrarti durante la guida.

## Progetto

MotoLink è un progetto indipendente. I nomi e i marchi di produttori, sistemi operativi e servizi citati appartengono ai rispettivi proprietari.

Il codice sorgente è pubblico in questa repository. **La licenza del progetto non è definita in questo pacchetto**: prima di dichiarare formalmente una licenza open source, aggiungi il file `LICENSE` con la licenza che vuoi adottare.
