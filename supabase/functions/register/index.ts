/**
 * POST /api/register — public.
 * Registers an ordering device either via a valid invite token (Secondary Admin,
 * Operator, or an invite-based staff join), or — for Ordering staff only — with NO
 * invite at all (staff-self-register spec: an Ordering device is the lowest-privilege
 * role and already needs an Admin's explicit approval before anything is granted, so
 * the up-front invite step is removable for it the same way the sibling barber-queue
 * app's device approval needs no invite either). Broadcasts JOIN_REQUEST either way.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { sha256 } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.deviceId || !body.deviceModel) {
    return errorResponse(
      422,
      "VALIDATION",
      "deviceId and deviceModel are required"
    );
  }

  const { inviteToken, deviceId, deviceModel, androidId, appVersion } = body;

  const supabase = getSupabaseClient();

  let grantedRole: string;

  if (inviteToken) {
    // Validate the invite token against ANY invite row and derive the role it grants.
    // Row id=1 → ORDERING staff; row id=2 → ADMIN_SECONDARY; row id=3 → OPERATOR. A device joins
    // the exact role the scanned token belongs to.
    //
    // Hash-first, plaintext-fallback (migration 0020, same precedence admin-recovery already uses
    // for the owner key): a row minted after 0020 carries only `token_hash`, so the presented
    // token is hashed and compared against that. A row still carrying a plaintext `token` from
    // before the migration keeps working via the fallback rather than locking out a café mid-service.
    const presentedHash = await sha256(inviteToken);
    let { data: invite } = await supabase
      .from("invites")
      .select("token, role, expires_at")
      .eq("token_hash", presentedHash)
      .maybeSingle();

    if (!invite) {
      const { data: legacy } = await supabase
        .from("invites")
        .select("token, role, expires_at")
        .eq("token", inviteToken)
        .maybeSingle();
      invite = legacy;
    }

    if (!invite) {
      return errorResponse(403, "INVALID_INVITE", "Invite token is invalid or expired");
    }

    // Expiry is enforced here rather than at the QR, because this is the only place that cannot be
    // skipped: a scanned link goes straight to this endpoint, and anything the app checks first is a
    // courtesy the app could get wrong or an attacker could bypass entirely.
    //
    // A NULL expiry means "never expires" — every row minted after migration 0020 (invites no
    // longer expire or auto-rotate) as well as every pre-0013 row. The message deliberately does
    // not distinguish expired from invalid: someone holding a code that is merely stale learns
    // nothing useful from being told so, and the café's own staff will simply ask the admin to
    // regenerate either way.
    if (invite.expires_at && new Date(invite.expires_at) < new Date()) {
      return errorResponse(403, "INVALID_INVITE", "Invite token is invalid or expired");
    }

    grantedRole = invite.role === "ADMIN_SECONDARY" ? "ADMIN_SECONDARY"
                : invite.role === "OPERATOR" ? "OPERATOR"
                : "ORDERING";
  } else {
    // Staff self-register (.kiro/specs/staff-self-register): no invite table touched
    // at all. Always ORDERING — a device can never self-declare an elevated role by
    // simply omitting an invite token (that spec's Requirement 1.3).
    grantedRole = "ORDERING";
  }

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
