import { handleDeletionRequest } from "./handler.ts";

// Synthetic tests: NEVER call production Supabase, NEVER delete real identities.
const BASE = "https://supabase.test";
const CFG = { supabaseUrl: BASE, serviceRoleKey: "test-service-only", anonKey: "test-public-only" };
const USER_ID = "11111111-1111-4111-8111-111111111111";
const TOKEN = "x".repeat(50);

function insist(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
}

function req(method = "POST", token = TOKEN, body?: string): Request {
  return new Request("https://motolink.test/functions/v1/motolink-delete-session?user_id=evil", {
    method, headers: { Authorization: `Bearer ${token}` }, body,
  });
}

function mockAuth(
  user: { id?: string; is_anonymous?: boolean } | null,
  authStatus = 200,
  deletionStatus = 200,
) {
  const seen: { url: string; method: string; auth: string }[] = [];
  const mock = (async (url: RequestInfo | URL, options?: RequestInit) => {
    const path = String(url);
    const headers = new Headers(options?.headers);
    seen.push({
      url: path,
      method: options?.method || "GET",
      auth: headers.get("Authorization") ?? "",
    });
    if (path === `${BASE}/auth/v1/user`) {
      return Response.json(user, { status: authStatus });
    }
    if (path === `${BASE}/auth/v1/admin/users/${USER_ID}`) {
      return new Response(null, { status: deletionStatus });
    }
    throw new Error("UNEXPECTED_URL");
  }) as typeof fetch;
  return { mock, seen };
}

Deno.test("reject wrong method and no bearer before any network request", async () => {
  const mock = mockAuth({ id: USER_ID, is_anonymous: true });
  insist((await handleDeletionRequest(req("GET"), CFG, mock.mock)).status === 405, "GET must be rejected");
  const noToken = new Request("https://motolink.test/", { method: "POST" });
  insist((await handleDeletionRequest(noToken, CFG, mock.mock)).status === 401, "no token must fail");
  insist(mock.seen.length === 0, "must not call Auth");
});

Deno.test("authenticated anonymous self-delete uses verified ID only", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true });
  const r = await handleDeletionRequest(req(), CFG, m.mock);
  insist(r.status === 200, "expected deletion success");
  insist((await r.json()).deleted === true, "success ack required");
  insist(m.seen.length === 2, "must verify before deleting");
  insist(m.seen[0].url.endsWith("/auth/v1/user"), "verified via Supabase Auth");
  insist(m.seen[0].auth === `Bearer ${TOKEN}`, "user token for verification only");
  insist(m.seen[1].url === `${BASE}/auth/v1/admin/users/${USER_ID}`, "only verified user deleted");
  insist(m.seen[1].method === "DELETE", "explicit delete");
  insist(m.seen[1].auth === "Bearer test-service-only", "server credential only");
});

Deno.test("permanent user cannot be deleted through anonymous-only endpoint", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: false });
  insist((await handleDeletionRequest(req(), CFG, m.mock)).status === 403, "reject permanent user");
  insist(m.seen.length === 1, "no admin DELETE");
});

Deno.test("missing verified identity or failed auth must never delete", async () => {
  for (const [u, status] of [[null, 200], [{ id: "evil", is_anonymous: true }, 200], [{ id: USER_ID, is_anonymous: true }, 401]] as const) {
    const m = mockAuth(u, status);
    const r = await handleDeletionRequest(req(), CFG, m.mock);
    insist(r.status !== 200, "reject");
    insist(m.seen.length === 1, "no admin delete");
  }
});

Deno.test("admin failure must not report success", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true }, 200, 503);
  const r = await handleDeletionRequest(req(), CFG, m.mock);
  insist(r.status === 503 && (await r.json()).deleted !== true, "fail closed");
});

Deno.test("missing configuration never uses network", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true });
  insist((await handleDeletionRequest(req(), { supabaseUrl: BASE }, m.mock)).status === 503, "reject missing secrets");
  insist(m.seen.length === 0, "no request");
});


Deno.test("forged user ID in request body is ignored in favor of verified Auth identity", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true });
  const forged = JSON.stringify({ user_id: "22222222-2222-4222-8222-222222222222", is_anonymous: true });
  const result = await handleDeletionRequest(req("POST", TOKEN, forged), CFG, m.mock);
  insist(result.status === 200, "own account deletion must still succeed");
  insist(m.seen.length === 2, "exactly one verification and one deletion");
  insist(m.seen[1].url === `${BASE}/auth/v1/admin/users/${USER_ID}`, "cannot select another identity");
});

Deno.test("malformed authentication is rejected before reaching Auth", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true });
  for (const h of [
    "Basic dXNlcjpwYXNz", "Bearer short", "Bearer " + TOKEN + " extra",
    "Bearer user-with-newline", "Bearer ", "bearer " + TOKEN,
  ]) {
    const request = new Request("https://motolink.test/", { method: "POST", headers: { Authorization: h } });
    const response = await handleDeletionRequest(request, CFG, m.mock);
    insist(response.status === 401, "bad bearer header must be rejected");
  }
  insist(m.seen.length === 0, "no network on malformed authorization");
});

Deno.test("verification body missing anonymous status cannot trigger admin call", async () => {
  const m = mockAuth({ id: USER_ID });
  const response = await handleDeletionRequest(req(), CFG, m.mock);
  insist(response.status === 403, "unknown user category must not be deleted");
  insist(m.seen.length === 1, "admin call blocked");
});

Deno.test("Auth lookup connection error must fail closed", async () => {
  const seen: string[] = [];
  const failingFetch = (async (url: RequestInfo | URL) => {
    seen.push(String(url));
    throw new Error("synthetic transport interruption");
  }) as typeof fetch;
  const response = await handleDeletionRequest(req(), CFG, failingFetch);
  insist(response.status === 503 && (await response.json()).deleted !== true, "no success on network error");
  insist(seen.length === 1 && seen[0] === `${BASE}/auth/v1/user`, "no administrative deletion");
});

Deno.test("unexpected Auth user response must fail closed", async () => {
  let count = 0;
  const badJson = (async () => {
    count++;
    return new Response("not-json", { status: 200 });
  }) as typeof fetch;
  const response = await handleDeletionRequest(req(), CFG, badJson);
  insist(response.status === 503, "invalid Auth response is not a deletion approval");
  insist(count === 1, "admin deletion never reached");
});

Deno.test("non-TLS Auth host configuration must be rejected", async () => {
  const m = mockAuth({ id: USER_ID, is_anonymous: true });
  const response = await handleDeletionRequest(req(), { ...CFG, supabaseUrl: "http://supabase.test" }, m.mock);
  insist(response.status === 503, "plain HTTP backend must be rejected");
  insist(m.seen.length === 0, "reject before network");
});

Deno.test("failed admin delete is never signalled as successful", async () => {
  for (const status of [401, 403, 404, 409, 429, 500]) {
    const m = mockAuth({ id: USER_ID, is_anonymous: true }, 200, status);
    const response = await handleDeletionRequest(req(), CFG, m.mock);
    insist(response.status === 503, "non-2xx admin result must be rejected");
    insist((await response.json()).deleted !== true, "must not tell Android to erase its only tokens");
    insist(m.seen.length === 2, "only one admin deletion attempt");
  }
});
