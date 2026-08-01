/**
 * Owner recovery — permanent "keep-forever" key that restores MAIN ADMIN on a fresh device.
 *
 * GET  /api/admin-recovery       — admin only: returns the recovery token + QR url (to show
 *                                   the owner so they can save/print it).
 * POST /api/admin-recovery       — public: { recoveryToken, deviceId, deviceModel } — if the
 *                                   token matches, mints a Main Admin session for the device
 *                                   (reuses/dedups its row, like admin-handshake). QR-only:
 *                                   no password step, so the token must be kept secret.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors, requireWebsiteOrigin } from "../_shared/cors.ts";
import { verifyAdminToken, sha256, generateToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  const supabase = getSupabaseClient();

  if (req.method === "GET") {
    const admin = await verifyAdminToken(req);
    if (!admin) return errorResponse(401, "UNAUTHORIZED", "Admin token required");

    const { data } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "owner_recovery_token")
      .single();
    if (!data?.value) return errorResponse(500, "SERVER_ERROR", "Recovery token not set");

    // An owner-recovery QR built on a placeholder origin encodes a link to a site that does not
    // exist, and fails in a café with a phone already scanning it. Refuse to mint one instead.
    let base: string;
    try {
      base = requireWebsiteOrigin();
    } catch (e) {
      return errorResponse(500, "WEBSITE_ORIGIN_UNSET", (e as Error).message);
    }
    return jsonResponse({ token: data.value, url: `${base}/join?recover=${data.value}` });
  }

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Use GET or POST");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.recoveryToken || !body.deviceId) {
    return errorResponse(422, "VALIDATION", "recoveryToken and deviceId are required");
  }

  const { data: setting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "owner_recovery_token")
    .single();
  if (!setting?.value || setting.value !== body.recoveryToken) {
    return errorResponse(403, "INVALID_RECOVERY", "Invalid recovery key");
  }

  // Mint a Main Admin session for this device (reuse/dedup its row, same as admin-handshake).
  const sessionToken = generateToken(32);
  const tokenHash = await sha256(sessionToken);
  const deviceId = body.deviceId as string;

  const { data: existing } = await supabase
    .from("devices")
    .select("id")
    .eq("device_identifier", deviceId)
    .order("created_at", { ascending: true });

  if (existing && existing.length > 0) {
    const keepId = existing[0].id;
    const dupes = existing.slice(1).map((r) => r.id);
    if (dupes.length > 0) await supabase.from("devices").delete().in("id", dupes);
    await supabase
      .from("devices")
      .update({
        role: "ADMIN",
        status: "APPROVED",
        session_token_hash: tokenHash,
        last_seen_at: new Date().toISOString(),
      })
      .eq("id", keepId);
  } else {
    await supabase.from("devices").insert({
      device_identifier: deviceId,
      role: "ADMIN",
      status: "APPROVED",
      session_token_hash: tokenHash,
      label: body.deviceModel || "Recovered Admin",
      last_seen_at: new Date().toISOString(),
    });
  }

  return jsonResponse({ sessionToken });
});
