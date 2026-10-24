import { handleDeletionRequest } from "./handler.ts";

// This entrypoint is intentionally inactive until explicitly deployed to
// Supabase with verify_jwt=true. Never expose service_role in Android builds.
Deno.serve((request: Request) => handleDeletionRequest(request, {
  supabaseUrl: Deno.env.get("SUPABASE_URL"),
  serviceRoleKey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY"),
  anonKey: Deno.env.get("SUPABASE_ANON_KEY"),
}));
