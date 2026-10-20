# MotoLink V1.8 — Note di rilascio

## Novità

- **Ripristino Wi-Fi VOGE Trofeo 500** nelle Impostazioni, con conferma e un pulsante AVVIA.
- Scansione Bluetooth Classic, SDP, BLE/GATT in modalità diagnostica senza scritture, seguita dalla scansione Wi-Fi del telefono. Non cambia SSID o password né forza il TFT a connettersi.
- Richiesta Android «Dispositivi nelle vicinanze» soltanto quando necessario, con avvio dopo il consenso.
- Finestre di recupero con stile nero/verde MotoLink e messaggi di avanzamento.
- Impostazioni scorrevoli: la voce **Versione app** è raggiungibile senza spostare la barra inferiore.
- **Guida iniziale** migliorata: schede più grandi e leggibili, posizionate nello spazio disponibile senza coprire il tasto o elemento spiegato.
- Pannello **Versione app** aggiornato per mostrare esclusivamente le novità V1.8, nelle lingue supportate.

**Versione:** 1.8 · **versionCode:** 34 · **applicationId:** `it.motolink.app`.

Le finestre e la richiesta dei permessi sono state verificate sul telefono nella build sperimentale Lab2. Il **recupero effettivo dell'hotspot su TFT Trofeo 500 con questa integrazione** necessita ancora di test fisico specifico: completare la diagnostica non equivale a provare il ripristino. Usare soltanto a moto ferma.

Il motore START, la navigazione BLE, EasyConn e il protocollo TFT mantengono il funzionamento preesistente. La V1.7 rimane disponibile per rollback storico; l'aggiornamento normale richiede lo stesso certificato ufficiale. Il master unico resta privato e non è pubblicato qui.
