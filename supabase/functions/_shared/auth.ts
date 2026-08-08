/**
 * Auth verification helpers for Edge Functions.
 * Supports superadmin JWT, admin session token, and ordering API key.
 */
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getSupabaseClient } from "./supabase.ts";

/**
 * SHA-256 hash a string and return hex digest.
 */
export async function sha256(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * Extract the Bearer token from an Authorization header.
 */
export function extractBearer(req: Request): string | null {
  const auth = req.headers.get("authorization");
  if (!auth?.startsWith("Bearer ")) return null;
  return auth.slice(7);
}

/**
 * Verify a Supabase JWT (superadmin). Returns the user object or null.
 */
export async function verifySuperadminJwt(
  req: Request
): Promise<{ id: string; email?: string } | null> {
  const token = extractBearer(req);
  if (!token) return null;

  const supabase = getSupabaseClient();
  const {
    data: { user },
    error,
  } = await supabase.auth.getUser(token);

  if (error || !user) return null;
  return { id: user.id, email: user.email };
}

/**
 * Verify an admin session token. Returns the device row or null.
 *
 * Accepts BOTH the main admin (role='ADMIN') and secondary admins
 * (role='ADMIN_SECONDARY') — they share full management authorization. Callers that
 * need to distinguish (e.g. printer-host-only behaviour) can inspect the returned
 * `role`; note printing itself is client-side, so most endpoints treat them alike.
 */
export async function verifyAdminToken(req: Request): Promise<{
  id: string;
  device_identifier: string;
  role: string;
  status: string;
} | null> {
  const token = extractBearer(req);
  if (!token) return null;

  const hash = await sha256(token);
  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("devices")
    .select("id, device_identifier, role, status")
    .eq("session_token_hash", hash)
    .in("role", ["ADMIN", "ADMIN_SECONDARY"])
    .eq("status", "APPROVED")
    .single();

  if (error || !data) return null;
  return data;
}

/** Verify an operator session token. Scoped to the narrow endpoint allowlist — this is
 *  deliberately NOT merged into verifyAdminToken, so every endpoint that doesn't explicitly call
 *  this one stays closed to OPERATOR by default (Requirement 9.2). */
export async function verifyOperatorToken(req: Request): Promise<{
  id: string; device_identifier: string; role: string; status: string;
} | null> {
  const token = extractBearer(req);
  if (!token) return null;
  const hash = await sha256(token);
  const supabase = getSupabaseClient();
  const { data, error } = await supabase
    .from("devices")
    .select("id, device_identifier, role, status")
    .eq("session_token_hash", hash)
    .eq("role", "OPERATOR")
    .eq("status", "APPROVED")
    .single();
  if (error || !data) return null;
  return data;
}

/**
 * Verify an ordering device API key. Returns the device row or null.
 */
export async function verifyOrderingKey(req: Request): Promise<{
  id: string;
  device_identifier: string;
  role: string;
  status: string;
} | null> {
  const token = extractBearer(req);
  if (!token) return null;

  const hash = await sha256(token);
  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("devices")
    .select("id, device_identifier, role, status")
    .eq("api_key_hash", hash)
    .eq("role", "ORDERING")
    .eq("status", "APPROVED")
    .single();

  if (error || !data) return null;
  return data;
}

/**
 * Generate a cryptographically random token (hex string).
 */
export function generateToken(bytes = 32): string {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return Array.from(buf)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
