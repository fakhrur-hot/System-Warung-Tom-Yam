/**
 * POST /api/admin/handshake — public (first-claim).
 * Claims the admin role for a device using the rotating key.
 * Returns a session token on success.
 * 409 if admin already exists, 401 if key is invalid.
 *
 * Debug-only alternate path: instead of `rotatingKey`, a request may send
 * `debugCafeName` (the café's name, deciphered client-side from a trivial
 * Caesar-shift the debug APK build shows). Only accepted when the deployment
 * secret `ALLOW_DEBUG_ADMIN` is exactly "true" — release deployments should
 * never set it. This exists purely so a developer/tester can claim the admin
 * slot without needing the website's live rotating key; it still creates a
 * real device row and session token via the same path as the rotating key.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { sha256, generateToken } from "../_shared/auth.ts";
import { validateRotatingKey } from "../_shared/rotating-key.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

function normalizeCafeName(name: string): string {
  return name.trim().toLowerCase().replace(/[^a-z0-9]/g, "");
}

// Up to this many admin devices can be simultaneously APPROVED. Previously a DB-level
// partial unique index hard-capped this at exactly 1, which meant a stale/replaced
// device row had to be manually deleted before any other device could ever claim the
// admin role again. Each approved device independently runs its own realtime listener
// and can print — fine for dev/testing with a handful of devices, but a real multi-
// device deployment could double-print the same kitchen slip if devices overlap.
const MAX_ADMIN_DEVICES = 10;

async function claimAdminDevice(deviceId: string) {
  const supabase = getSupabaseClient();

  const sessionToken = generateToken(32);
  const tokenHash = await sha256(sessionToken);

  // Reuse the row for this physical device rather than inserting a duplicate on every
  // re-handshake (which previously produced multiple identical "Admin Phone" rows that
  // all matched the current device). If earlier duplicates exist, keep the oldest and
  // delete the rest — self-healing cleanup for devices claimed under the old behaviour.
  const { data: existingRows, error: existingError } = await supabase
    .from("devices")
    .select("id")
    .eq("device_identifier", deviceId)
    .order("created_at", { ascending: true });

  if (existingError) {
    return { error: errorResponse(500, "SERVER_ERROR", existingError.message) };
  }

  if (existingRows && existingRows.length > 0) {
    const keepId = existingRows[0].id;
    const dupeIds = existingRows.slice(1).map((r) => r.id);
    if (dupeIds.length > 0) {
      await supabase.from("devices").delete().in("id", dupeIds);
    }

    const { error: updateError } = await supabase
      .from("devices")
      .update({
        role: "ADMIN",
        status: "APPROVED",
        session_token_hash: tokenHash,
        last_seen_at: new Date().toISOString(),
      })
      .eq("id", keepId);

    if (updateError) {
      return { error: errorResponse(500, "SERVER_ERROR", updateError.message) };
    }
    return { sessionToken };
  }

  // Genuinely new device — enforce the admin-device cap only here, not on re-handshake.
  const { count, error: countError } = await supabase
    .from("devices")
    .select("id", { count: "exact", head: true })
    .eq("role", "ADMIN")
    .eq("status", "APPROVED");

  if (countError) {
    return { error: errorResponse(500, "SERVER_ERROR", countError.message) };
  }

  if ((count ?? 0) >= MAX_ADMIN_DEVICES) {
    return {
      error: errorResponse(
        409,
        "ADMIN_EXISTS",
        `Maximum of ${MAX_ADMIN_DEVICES} admin devices already registered`
      ),
    };
  }

  const { error } = await supabase
    .from("devices")
    .insert({
      device_identifier: deviceId,
      role: "ADMIN",
      status: "APPROVED",
      session_token_hash: tokenHash,
      label: "Admin Phone",
      last_seen_at: new Date().toISOString(),
    })
    .select("id")
    .single();

  if (error) {
    return { error: errorResponse(500, "SERVER_ERROR", error.message) };
  }

  return { sessionToken };
}

// Simple in-memory rate limiter (per Edge Function instance)
const rateLimitMap = new Map<string, { count: number; resetAt: number }>();

function checkRateLimit(ip: string): boolean {
  const now = Date.now();
  const entry = rateLimitMap.get(ip);
  if (!entry || entry.resetAt < now) {
    rateLimitMap.set(ip, { count: 1, resetAt: now + 60_000 });
    return true; // allowed
  }
  if (entry.count >= 5) return false; // blocked
  entry.count++;
  return true; // allowed
}

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  const clientIp = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  if (!checkRateLimit(clientIp)) {
    return errorResponse(429, "RATE_LIMITED", "Too many attempts. Try again in 60 seconds.");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.deviceId || (!body.rotatingKey && !body.debugCafeName)) {
    return errorResponse(422, "VALIDATION", "deviceId and rotatingKey are required");
  }

  const { deviceId, rotatingKey, debugCafeName } = body;

  if (debugCafeName) {
    // Debug-only path — never available unless this deployment explicitly opts in.
    if (Deno.env.get("ALLOW_DEBUG_ADMIN") !== "true") {
      return errorResponse(401, "INVALID_KEY", "Rotating key is invalid or expired");
    }

    const supabase = getSupabaseClient();
    const { data: branding } = await supabase
      .from("branding")
      .select("cafe_name")
      .eq("id", 1)
      .single();

    if (!branding?.cafe_name || normalizeCafeName(branding.cafe_name) !== normalizeCafeName(debugCafeName)) {
      return errorResponse(401, "INVALID_KEY", "Rotating key is invalid or expired");
    }

    const result = await claimAdminDevice(deviceId);
    if ("error" in result) return result.error;
    return jsonResponse({ sessionToken: result.sessionToken });
  }

  // Validate rotating key against ±1 window
  const secret = Deno.env.get("ROTATING_KEY_SECRET");
  if (!secret) {
    return errorResponse(500, "SERVER_ERROR", "Rotating key secret not configured");
  }

  const valid = await validateRotatingKey(secret, rotatingKey);
  if (!valid) {
    return errorResponse(401, "INVALID_KEY", "Rotating key is invalid or expired");
  }

  const result = await claimAdminDevice(deviceId);
  if ("error" in result) return result.error;
  return jsonResponse({ sessionToken: result.sessionToken });
});
