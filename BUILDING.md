# Compilazione MotoLink

## Requisiti

- Android Studio aggiornato con supporto per `compileSdk 36`.
- JDK 17.
- Connessione Internet per scaricare le dipendenze Gradle al primo sync.

## Android

Apri la root della repository in Android Studio e attendi la sincronizzazione Gradle.

Da interfaccia:

**Build → Build APK(s)**

Oppure con Gradle Wrapper:

```bash
./gradlew assembleDebug
```

Su Windows:

```bat
gradlew.bat assembleDebug
```

Package Android: `it.motolink.app`

## Assistente MotoLink / Supabase

Il codice server è in:

`supabase/functions/motolink-assistant/`

La migration per il contatore quota è in:

`supabase/migrations/`

Per una propria installazione server configura i secret nell'ambiente Supabase. Non inserirli nei file sorgente:

- `AI_PROVIDER_API_KEY`
- `AI_PROVIDER_MODEL` (opzionale)

È inoltre necessario abilitare gli **Anonymous Sign-Ins** nel progetto Supabase usato dall'app.


## Configurazione Assistente IA nel sorgente pubblico

Il repository pubblico non contiene riferimenti al backend MotoLink di produzione né credenziali del provider IA.
Per una build personale occorre configurare un proprio progetto backend e sostituire i placeholder in `app/src/main/res/values/ai_config.xml`.
Le variabili server `AI_PROVIDER_ENDPOINT`, `AI_PROVIDER_API_KEY` e `AI_PROVIDER_MODEL` devono essere impostate esclusivamente come secret/configurazione del backend e non nel client Android.
