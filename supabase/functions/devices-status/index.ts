/**
 * GET /api/devices/status?deviceId=<uuid> — public (poll).
 * Returns current device status. If APPROVED and API key hasn't been delivered,
 * generates and returns it (one-time delivery).
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { sha256, generateToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  const url = new URL(req.url);
  const deviceId = url.searchParams.get("deviceId");

  if (!deviceId) {
    return errorResponse(422, "VALIDATION", "deviceId query parameter is required");
  }

  const supabase = getSupabaseClient();

  const { data: device, error } = await supabase
    .from("devices")
    .select("id, status, role, api_key_hash, session_token_hash, key_delivered_at")
    .eq("id", deviceId)
    .single();

  if (error || !device) {
    return errorResponse(404, "NOT_FOUND", "Device not found");
  }

  if (device.status === "PENDING") {
    return jsonResponse({ status: "PENDING" });
  }

  if (device.status === "REVOKED") {
    return jsonResponse({ status: "REVOKED" });
  }

  // Status is APPROVED — mint and deliver the device's credential exactly once, keyed by
  // role: ordering staff get an api_key; secondary admins get a session token (the same
  // credential shape the rotating-key handshake issues a main admin).
  if (device.status === "APPROVED") {
    const alreadyDelivered = !!device.key_delivered_at;

    if (!alreadyDelivered && device.role === "ORDERING" && !device.api_key_hash) {
      const apiKey = generateToken(32);
      const keyHash = await sha256(apiKey);

      const { error: updateError } = await supabase
        .from("devices")
        .update({ api_key_hash: keyHash, key_delivered_at: new Date().toISOString() })
        .eq("id", deviceId);

      if (updateError) {
        return errorResponse(500, "SERVER_ERROR", updateError.message);
      }

      return jsonResponse({ status: "APPROVED", role: device.role, apiKey });
    }

    if (!alreadyDelivered && device.role === "ADMIN_SECONDARY" && !device.session_token_hash) {
      const sessionToken = generateToken(32);
      const tokenHash = await sha256(sessionToken);

      const { error: updateError } = await supabase
        .from("devices")
        .update({ session_token_hash: tokenHash, key_delivered_at: new Date().toISOString() })
        .eq("id", deviceId);

      if (updateError) {
        return errorResponse(500, "SERVER_ERROR", updateError.message);
      }

      return jsonResponse({ status: "APPROVED", role: device.role, sessionToken });
    }

    // Credential already delivered — just return status + role
    return jsonResponse({ status: "APPROVED", role: device.role });
  }

  return jsonResponse({ status: device.status });
});
