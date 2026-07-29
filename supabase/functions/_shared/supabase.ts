/**
 * Supabase client initialization for Edge Functions.
 * Uses the service_role key to bypass RLS.
 */
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

export function getSupabaseClient() {
  const url = Deno.env.get("SUPABASE_URL")!;
  const key =
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ??
    Deno.env.get("SUPABASE_SECRET_KEY")!;
  return createClient(url, key, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
}
