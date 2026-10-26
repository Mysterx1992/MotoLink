// MotoLink anonymous technical-session deletion — NOT DEPLOYED by this PR.
//
// Deployment requirement: Supabase Edge Function `motolink-delete-session` with
// verify_jwt=true. This is a separate endpoint; do not modify the live AI one.
//
// The only identity deleted is the authenticated caller's own anonymous Auth user.
// Never accept user_id from the body/query string, and never expose service_role in APK.
const HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

function respond(status: number, message: Record<string, unknown>): Response {
  return new Response(JSON.stringify(message), { status, headers: HEADERS });
}

export type DeletionConfig = {
  supabaseUrl?: string;
  serviceRoleKey?: string;
  anonKey?: string;
};

export async function handleDeletionRequest(
  request: Request,
  config: DeletionConfig,
  httpFetch: typeof fetch = fetch,
): Promise<Response> {
  if (request.method !== "POST") {
    return respond(405, { error: "Metodo non consentito." });
  }
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9._-]{24,})$/.exec(header);
  if (!match) return respond(401, { error: "Sessione non valida." });

  const url = config.supabaseUrl?.replace(/\/$/, "");
  const serviceKey = config.serviceRoleKey;
  const anonKey = config.anonKey;
  if (!url || !url.startsWith("https://") || !serviceKey || !anonKey) {
    return respond(503, { error: "Servizio cancellazione non configurato." });
  }

  try {
    // Server-side Auth check, not a local decode of the user-supplied JWT.
    const verification = await httpFetch(`${url}/auth/v1/user`, {
      headers: {
        "apikey": anonKey,
        "Authorization": `Bearer ${match[1]}`,
      },
      cache: "no-store",
    });
    if (!verification.ok) {
      return respond(401, { error: "Sessione scaduta o non valida." });
    }
    const user = await verification.json();
    // Fail closed unless Supabase confirms a true anonymous Auth identity.
    if (user?.is_anonymous !== true || typeof user?.id !== "string" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id)) {
      return respond(403, { error: "La sessione non è un'identità anonima eliminabile." });
    }

    // Admin credential remains only in Supabase Edge Function environment.
    const deletion = await httpFetch(`${url}/auth/v1/admin/users/${user.id}`, {
      method: "DELETE",
      headers: {
        "apikey": serviceKey,
        "Authorization": `Bearer ${serviceKey}`,
      },
      cache: "no-store",
    });
    if (!deletion.ok) {
      return respond(503, { error: "Eliminazione non riuscita. Riprova più tardi." });
    }
    return respond(200, { deleted: true });
  } catch {
    // Never log identities, tokens, Auth responses or network diagnostics.
    return respond(503, { error: "Eliminazione temporaneamente non disponibile." });
  }
}
