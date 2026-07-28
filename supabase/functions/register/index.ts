/**
 * POST /api/register — public (invite-gated).
 * Registers an ordering device using a valid invite token.
 * Broadcasts JOIN_REQUEST on admin-devices channel.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.inviteToken || !body.deviceId || !body.deviceModel) {
    return errorResponse(
      422,
      "VALIDATION",
      "inviteToken, deviceId, and deviceModel are required"
    );
  }

  const { inviteToken, deviceId, deviceModel, androidId, appVersion } = body;

  const supabase = getSupabaseClient();

  // Validate the invite token against ANY invite row and derive the role it grants.
  // Row id=1 → ORDERING staff; row id=2 → ADMIN_SECONDARY. A device joins the exact
  // role the scanned token belongs to.
  const { data: invite } = await supabase
    .from("invites")
    .select("token, role")
    .eq("token", inviteToken)
    .maybeSingle();

  if (!invite || invite.token !== inviteToken) {
    return errorResponse(403, "INVALID_INVITE", "Invite token is invalid or expired");
  }

  const grantedRole = invite.role === "ADMIN_SECONDARY" ? "ADMIN_SECONDARY" : "ORDERING";

  // Insert the device as PENDING in the granted role
  const { data: device, error } = await supabase
    .from("devices")
    .insert({
      device_identifier: deviceId,
      android_id: androidId ?? null,
      device_model: deviceModel,
      role: grantedRole,
      status: "PENDING",
      label: deviceModel,
      last_seen_at: new Date().toISOString(),
    })
    .select("id")
    .single();

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  // Broadcast JOIN_REQUEST on admin-devices channel (include role so the admin UI can
  // label a secondary-admin request distinctly from a staff request).
  try {
    const channel = supabase.channel("admin-devices");
    await channel.send({
      type: "broadcast",
      event: "JOIN_REQUEST",
      payload: {
        type: "JOIN_REQUEST",
        deviceId: device.id,
        label: deviceModel,
        role: grantedRole,
      },
    });
    supabase.removeChannel(channel);
  } catch (_e) {
    // Best-effort broadcast; don't fail the registration
  }

  return jsonResponse({ deviceId: device.id, status: "PENDING", role: grantedRole }, 201);
});
