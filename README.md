<p align="center">
  <img src="docs/images/banner-motolink.png" alt="MotoLink" width="100%">
</p>

<p align="center">
  <a href="https://github.com/Mysterx1992/MotoLink/releases/download/v1.5/MotoLink_V1.5.apk">
    <img src="https://img.shields.io/badge/SCARICA-MotoLink%20V1.5-39FF14?style=for-the-badge&logo=android&logoColor=000000" alt="Scarica MotoLink V1.5">
  </a>
  &nbsp;
  <a href="https://github.com/Mysterx1992/MotoLink/releases/latest">
    <img src="https://img.shields.io/badge/RELEASE-Ultima%20versione-181717?style=for-the-badge&logo=github&logoColor=ffffff" alt="Ultima release MotoLink">
  </a>
  &nbsp;
  <a href="https://chat.whatsapp.com/BNTmFxXQuOkGdYWHrX2rV0?s=cl&p=a&mlu=4">
    <img src="https://img.shields.io/badge/WHATSAPP-MotoLink%20Mirroring-25D366?style=for-the-badge&logo=whatsapp&logoColor=ffffff" alt="MotoLink Mirroring WhatsApp">
  </a>
</p>

# MotoLink

**Porta il mirroring del tuo smartphone sul display della moto compatibile, con profili dedicati, app preferite, adattamento del display e strumenti di supporto integrati.**

MotoLink è un'app Android progettata per gestire in un unico ambiente il collegamento con il display della moto, il mirroring dello smartphone e le principali funzioni di configurazione e assistenza.

> [!IMPORTANT]
> Configura MotoLink, i profili, le applicazioni e le regolazioni **a veicolo fermo**. Non utilizzare lo smartphone o MotoLink in modo da compromettere l'attenzione durante la guida.

---

## 🆕 MotoLink V1.5

La V1.5 raccoglie le correzioni emerse dai test fisici successivi alla V1.4 e consolida il comportamento su Voge Trofeo/Valico e CFMOTO.

### Novità e correzioni principali

- **Trofeo: adattamento predefinito ripristinato.** Alla prima installazione la geometria approvata viene applicata automaticamente anche con l'editor Adattamento chiuso.
- **Adattamento display corretto:** le regolazioni restano salvate, la X chiude l'editor senza perdere la personalizzazione e il pulsante **OK** delle istruzioni è stato sistemato.
- **Recovery della sessione migliorato:** quando il TFT cambia modalità, MotoLink resta in attesa e riprende automaticamente il mirroring quando il display torna disponibile, senza interrompere la sessione dopo un numero fisso di tentativi.
- **Recovery H264 reso affidabile:** il successo della riconnessione non viene più dedotto da semplici stringhe del Log, evitando falsi `RECOVERY OK`.
- **CFMOTO: corretto il lifecycle del consumer H264**, eliminando il caso in cui P2P/EasyConn risultavano collegati ma il video non partiva. La correzione è stata verificata fisicamente con collegamento MotoLink V1.5 → CFMOTO.
- **Assistente MotoLink ripristinato:** la release include la configurazione pubblica corretta del backend, senza secret privati nell'APK.
- **VERSIONE nelle Impostazioni:** toccando la voce VERSIONE si possono leggere offline le novità e i bugfix della versione installata.
- **Guida aggiornata** con il nuovo comportamento della voce VERSIONE.
- Il protocollo EasyConn wire-level, il clock e i percorsi di rete già validati restano separati dalle correzioni di recovery e display.

### Aggiornamento

Chi utilizza già una release ufficiale firmata con l'identità permanente MotoLink può installare `MotoLink_V1.5.apk` come aggiornamento.

> [!NOTE]
> Se sul telefono è installata una vecchia build MotoLink firmata con una firma differente, Android può richiedere una disinstallazione una tantum prima dell'installazione della release ufficiale.

---

## Scarica MotoLink

### APK ufficiale V1.5

<a href="https://github.com/Mysterx1992/MotoLink/releases/download/v1.5/MotoLink_V1.5.apk">
  <img src="https://img.shields.io/badge/⬇%20DOWNLOAD%20DIRETTO-MotoLink_V1.5.apk-39FF14?style=for-the-badge&logo=android&logoColor=000000" alt="Download diretto MotoLink V1.5">
</a>

**Pagina dell'ultima release:**  
https://github.com/Mysterx1992/MotoLink/releases/latest

**Versioni precedenti:**  
https://github.com/Mysterx1992/MotoLink/releases

> [!NOTE]
> Nella pagina della Release scarica il file che termina in `.apk`. I file **Source code (zip)** e **Source code (tar.gz)** generati automaticamente da GitHub contengono il codice sorgente e non sono applicazioni installabili.

Android può richiedere l'autorizzazione per installare app provenienti da questa fonte. È il normale controllo di sicurezza previsto per APK installati al di fuori di Google Play.

---

## MotoLink in azione

<table>
<tr>
<td width="50%" align="center"><img src="docs/images/home-motolink.png" alt="MotoLink Home"></td>
<td width="50%" align="center"><img src="docs/images/supporto-motolink.png" alt="MotoLink Supporto"></td>
</tr>
<tr>
<td width="50%" align="center"><img src="docs/images/preferite-motolink.png" alt="MotoLink App preferite"></td>
<td width="50%" align="center"><img src="docs/images/crediti-motolink.png" alt="MotoLink Crediti"></td>
</tr>
</table>

Gli screenshot originali dell'app sono disponibili in [`docs/screenshots`](docs/screenshots).

---

## Compatibilità moto

MotoLink è progettata per lavorare con display moto compatibili con il flusso di mirroring supportato dall'app.

La compatibilità reale può dipendere da modello della moto, display/T-Box, firmware, versione Android e implementazione EasyConn/Carbit presente sul display.

Moto sulle quali l'app è stata testata:

| Marca | Modello | Anno / versione | Display / sistema | Stato | Note |
|---|---|---|---|---|---|
| _Voge_ | _Trofeo_ | _2023_ | _TFT / EasyConn_ | ✅ Testata | _App implementata per questo specifico modello_ |
| _Voge_ | _Valico 900_ | _2026_ | _TFT / EasyConn_ | ✅ Testata | _App testata e funzionante con collegamento QR Code_ |
| _CFMOTO_ | _TFT compatibile testato_ | _—_ | _TFT / EasyConn_ | ✅ Collegamento testato | _Connessione P2P/EasyConn e avvio video verificati fisicamente con MotoLink V1.5_ |

> [!WARNING]
> Un display simile o appartenente allo stesso marchio **non garantisce automaticamente la compatibilità**. Modello e firmware devono essere verificati separatamente.

---

## Cosa fa MotoLink

### 🏍️ Connessione e mirroring

MotoLink gestisce il collegamento con il display moto compatibile e permette di avviare il mirroring direttamente dalla Home.

### 🏠 Garage e profili moto

Il Garage permette di salvare e gestire fino a tre profili moto. Ogni profilo può mantenere le proprie impostazioni e regolazioni di Adattamento.

Al primo START, se non esiste ancora un profilo, MotoLink permette di scegliere HOTSPOT oppure QR CODE e guida l'utente nella creazione completa del profilo prima di continuare automaticamente la connessione.

### ⭐ App preferite

Puoi selezionare fino a **quattro applicazioni preferite** e richiamarle rapidamente dalla Home.

### 📐 Adattamento display

MotoLink include strumenti per adattare manualmente l'immagine alla geometria del display della moto. Le regolazioni vengono mantenute per il relativo profilo moto e per orientamento.

### 📱 Modalità tasca

Durante il mirroring MotoLink mette a disposizione la gestione tramite sensore di prossimità e il comando con **doppio Volume Giù** per oscurare/riattivare lo schermo con blocco dei tocchi senza interrompere la sessione.

### 🧰 Log MotoLink

La sezione **Supporto → Log** mostra al massimo le ultime **50 righe** per mantenere l'interfaccia leggera.

Il Log tecnico completo:

- resta locale sul dispositivo;
- non viene cancellato automaticamente;
- rimane disponibile fino al comando esplicito **Pulisci**;
- può essere condiviso dall'utente quando serve assistenza;
- viene conservato integralmente nel file `.txt`, anche quando la schermata mostra soltanto le righe più recenti.

### 🤖 Assistente MotoLink

L'Assistente è dedicato esclusivamente al supporto tecnico MotoLink. Una normale conversazione **non legge automaticamente il Log**.

Per allegare volontariamente la diagnostica:

**Supporto → Log → Condividi → Assistente**

MotoLink applica un filtro alle informazioni tecniche prima dell'elaborazione.

---

## Permessi e privacy

MotoLink è stata progettata per ridurre al minimo le autorizzazioni richieste e **non utilizza servizi di Accessibilità**.

La scansione QR può utilizzare Google Code Scanner oppure il fallback interno quando necessario.

Le funzioni tecniche possono richiedere:

| Accesso / conferma | Quando viene usato | Perché serve |
|---|---|---|
| **Connessione moto / Wi-Fi** | Collegamento alla moto | Comunicazione con il TFT tramite Wi-Fi / Wi-Fi Direct. |
| **Fotocamera** | Solo quando serve il fallback scanner QR interno | Lettura del QR della moto. |
| **MediaProjection / condivisione schermo** | Avvio mirroring | Autorizzazione Android alla cattura dello schermo o di una singola app. |
| **Mostra sopra altre app** | Quando richiesto | Modalità tasca / schermo nero e pannello di Adattamento. |

Per maggiori informazioni consulta [`PRIVACY.md`](PRIVACY.md).

---

## Supporto e community

- **Assistente integrato:** Supporto → Assistente
- **Log tecnico:** Supporto → Log
- **Community MotoLink Mirroring:** [gruppo WhatsApp](https://chat.whatsapp.com/BNTmFxXQuOkGdYWHrX2rV0?s=cl&p=a&mlu=4)

La community contribuisce con test su moto e display differenti, feedback e supporto agli utenti.

---

## Stato del progetto

| Voce | Stato |
|---|---|
| Applicazione Android | ✅ Disponibile |
| Release pubblica | ✅ `v1.5` |
| Mirroring | ✅ Implementato |
| Garage / profili moto | ✅ Implementato |
| App preferite | ✅ Implementato |
| Adattamento display | ✅ Implementato |
| Modalità tasca | ✅ Implementata |
| Log tecnico | ✅ Implementato |
| Assistente MotoLink | ✅ Implementato |
| Compatibilità moto | 🔄 In aggiornamento con i test reali |

---

## Codice sorgente

Il codice sorgente di MotoLink è **pubblico e consultabile** in questa repository per finalità di studio, trasparenza e verifica.

> [!IMPORTANT]
> **MotoLink non è distribuito con una licenza open source.** Il software è protetto da copyright e tutti i diritti sono riservati. La disponibilità pubblica del sorgente non autorizza automaticamente copia, modifica, ridistribuzione o creazione di opere derivate. Consulta [`LICENSE`](LICENSE) per i termini completi.

---

## Avvertenza durante la guida

MotoLink è uno strumento di mirroring e supporto. **Non deve essere utilizzata come unica fonte di informazioni critiche durante la guida.** Configura destinazioni, applicazioni, profili e regolazioni prima di partire.

---

## Progetto e marchi

MotoLink è un progetto indipendente. I nomi, i marchi, i loghi, i sistemi operativi e i servizi di terze parti eventualmente citati appartengono ai rispettivi proprietari.

---

## Licenza e copyright

**Copyright © 2026 Emanuele. Tutti i diritti riservati.**

Il codice sorgente di MotoLink è pubblicamente consultabile per finalità di studio, trasparenza e verifica. Non è concessa alcuna autorizzazione a copiare, modificare, distribuire, sublicenziare, vendere, ripubblicare o creare opere derivate dal software, in tutto o in parte, senza previa autorizzazione scritta del titolare del copyright.

Per i termini completi consulta [`LICENSE`](LICENSE).

---

<p align="center">
  <strong>MotoLink V1.5</strong><br>
  Mirroring • Connessione • Supporto
</p>
