#!/usr/bin/env python3
"""MotoLink Google Play privacy account-deletion static safety contract.

This checks source and documentation wiring ONLY. It performs no network call,
Supabase mutation, App Store submission or deletion.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EMAIL = "motolinkofficial.app@gmail.com"
errors = []


def check(ok: bool, label: str) -> None:
    print(("PASS" if ok else "FAIL") + ": " + label)
    if not ok:
        errors.append(label)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


client = read("app/src/main/java/it/motolink/app/MotoLinkAiClient.kt")
activity = read("app/src/main/java/it/motolink/app/MainActivity.kt")
ui = read("app/src/main/java/it/motolink/app/TrofeoDashboardView.kt")
handler = read("supabase/functions/motolink-delete-session/handler.ts")
entry = read("supabase/functions/motolink-delete-session/index.ts")
privacy = read("PRIVACY.md")
deletion = read("DATA_DELETION.md")
readme = read("README.md")

delete_start = client.find("fun deleteAnonymousSession()")
delete_end = client.find("private fun ensureAccessToken()", delete_start)
delete_method = client[delete_start:delete_end] if delete_start >= 0 and delete_end >= 0 else ""

check(delete_start >= 0 and delete_end > delete_start, "delete client method is isolated")
check("prefs.getString(\"access_token\"" in delete_method and
      "prefs.getString(\"refresh_token\"" in delete_method,
      "deletion uses existing stored session")
check("if (access.isNullOrBlank() && refresh.isNullOrBlank()) return@runCatching false" in delete_method,
      "no existing session yields no new anonymous Auth user")
check("ensureAccessToken()" not in delete_method and
      "createAnonymousSession()" not in delete_method,
      "deletion cannot create an Auth user as a side effect")
check('functions/v1/motolink-delete-session' in delete_method and
      'setRequestProperty("Authorization", "Bearer $token")' in delete_method,
      "deletion request uses authenticated separate function")
check("if (status !in 200..299 || !JSONObject(body).optBoolean(\"deleted\", false))" in delete_method,
      "local credential removal requires exact positive server acknowledgement")
success_position = delete_method.find("clearSession()")
ack_position = delete_method.find("if (status !in 200..299")
check(success_position > ack_position > 0, "client cannot clear identity before server confirmation")

check("onDeleteAssistantSessionClick = { confirmDeleteAssistantSession() }" in activity and
      "onPositive = { deleteAssistantSession() }" in activity and
      "aiIo.execute {" in activity and
      "fun deleteAssistantSession()" in activity,
      "confirmation, user-initiated action and background IO correctly wired")
check('Elimina sessione Assistente' in ui and
      'onDeleteAssistantSessionClick?.invoke()' in ui,
      "deletion control discoverable in Settings")

check('verify_jwt=true' in handler and
      "req.headers" not in handler,
      "separate endpoint retains explicit required verify_jwt declaration")
check('user?.is_anonymous !== true' in handler and
      "fetch(" not in handler.replace("httpFetch(", ""),
      "only server-verified anonymous identity may be deleted")
check('user.id' in handler and
      "method: \"DELETE\"" in handler,
      "delete request uses verified Auth user ID")
check('Deno.serve(' in entry and
      '"SUPABASE_SERVICE_ROLE_KEY"' in entry,
      "server-only secret wired through Edge Function environment")
check('SUPABASE_SERVICE_ROLE_KEY' not in client and
      'service_role' not in client.lower(),
      "service role is never embedded in Android client")
check('motolink-assistant' not in handler and
      "motolink-delete-session" in entry,
      "existing production assistant not modified by deletion handler")

check(all(EMAIL in x for x in [privacy, deletion, readme]),
      "same official privacy email appears in all public documents")
check("non è ancora disponibile" in privacy.lower() and
      "non è presente nella V1.8" in deletion,
      "docs do not misrepresent undeployed endpoint as operational")
check("Non inviare" in deletion and
      "token" in deletion.lower(),
      "public page explicitly warns against emailing Auth tokens")
check("DATA_DELETION.md" in readme and
      "DATA_DELETION.md" in privacy,
      "data deletion page linked from privacy policy and README")

if errors:
    print(f"FAIL: {len(errors)} static contract checks")
    sys.exit(1)
print("PASS: privacy deletion contract — source-only, not live deployment or physical test")
