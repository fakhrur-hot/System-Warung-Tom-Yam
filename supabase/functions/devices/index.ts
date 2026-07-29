/**
 * Device management — superadmin JWT or admin session token.
 * GET  /devices      — list all devices (superadmin only)
 * PATCH /devices/:id — approve/reject/rename/revoke/force-checkout
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import {
  verifySuperadminJwt,
  verifyAdminToken,
} from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  const url = new URL(req.url);
  const pathParts = url.pathname.split("/").filter(Boolean);
  // Path patterns: /devices or /devices/:id

  if (req.method === "GET") {
    return handleListDevices(req);
  }

  if (req.method === "PATCH") {
    // Extract device ID from path — last segment
    const deviceId = pathParts[pathParts.length - 1];
    if (!deviceId || deviceId === "devices") {
      return errorResponse(422, "VALIDATION", "Device ID is required in path");
    }
    return handlePatchDevice(req, deviceId);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Use GET or PATCH");
});

async function handleListDevices(req: Request): Promise<Response> {
  // Superadmin (website dashboard) OR admin session token (POS "Devices & Staff").
  // The POS admin device lists the café's devices with its session token; the PATCH
  // handler below already accepts the same credential, so GET must too — otherwise a
  // valid admin token 401s here and looks like an expired session.
  const superadmin = await verifySuperadminJwt(req);
  const admin = superadmin ? null : await verifyAdminToken(req);
  if (!superadmin && !admin) {
    return errorResponse(
      401,
      "UNAUTHORIZED",
      "Superadmin JWT or admin session token required"
    );
  }

  const supabase = getSupabaseClient();
  const { data, error } = await supabase
    .from("devices")
    .select("id, device_identifier, label, role, status, last_seen_at, is_checked_in")
    .order("created_at", { ascending: false });

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  const devices = (data ?? []).map((d) => ({
    id: d.id,
    // Client matches this against its own local device id to mark the current device.
    deviceIdentifier: d.device_identifier,
    label: d.label,
    role: d.role,
    status: d.status,
    lastSeenAt: d.last_seen_at,
    isCheckedIn: d.is_checked_in,
  }));

  return jsonResponse(devices);
}

async function handlePatchDevice(
  req: Request,
  deviceId: string
): Promise<Response> {
  // Superadmin OR admin session token
  const superadmin = await verifySuperadminJwt(req);
  const admin = superadmin ? null : await verifyAdminToken(req);

  if (!superadmin && !admin) {
    return errorResponse(
      401,
      "UNAUTHORIZED",
      "Superadmin JWT or admin session token required"
    );
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.action) {
    return errorResponse(422, "VALIDATION", "action is required");
  }

  const { action, label } = body;
  const validActions = ["APPROVE", "REJECT", "REVOKE", "FORCE_CHECKOUT", "RENAME", "PROMOTE_MAIN"];
  if (!validActions.includes(action)) {
    return errorResponse(
      422,
      "VALIDATION",
      `action must be one of: ${validActions.join(", ")}`
    );
  }

  const supabase = getSupabaseClient();

  // Fetch current device
  const { data: device, error: fetchError } = await supabase
    .from("devices")
    .select("*")
    .eq("id", deviceId)
    .single();

  if (fetchError || !device) {
    return errorResponse(404, "NOT_FOUND", "Device not found");
  }

  let updatePayload: Record<string, unknown> = {};
  let broadcastEvent: string | null = null;
  let broadcastPayload: Record<string, unknown> | null = null;

  switch (action) {
    case "APPROVE": {
      // Only flip status here. The device's credential (ordering api_key OR secondary-admin
      // session token) is minted once, on the device's own `devices-status` poll — the
      // plaintext is returned exactly once to the device that owns it and never to the
      // approver. (Pre-generating the hash here would leave the poll unable to deliver a
      // plaintext it can no longer reproduce.)
      updatePayload = {
        status: "APPROVED",
      };
      broadcastEvent = "APPROVED";
      broadcastPayload = { type: "APPROVED", deviceId };
      break;
    }

    case "REJECT": {
      updatePayload = { status: "REVOKED" };
      broadcastEvent = "REJECTED";
      broadcastPayload = { type: "REJECTED", deviceId };
      break;
    }

    case "REVOKE": {
      updatePayload = { status: "REVOKED" };
      break;
    }

    case "FORCE_CHECKOUT": {
      updatePayload = { is_checked_in: false };
      broadcastEvent = "FORCE_CHECKOUT";
      broadcastPayload = {
        type: "FORCE_CHECKOUT",
        deviceId,
        timestamp: new Date().toISOString(),
      };
      break;
    }

    case "RENAME": {
      if (!label) {
        return errorResponse(422, "VALIDATION", "label is required for RENAME");
      }
      updatePayload = { label };
      break;
    }

    case "PROMOTE_MAIN": {
      // Make this device the Main Admin (printer host) and demote every OTHER current main
      // to Secondary — exactly one printer host at a time. Devices learn their new role on
      // their next self role-check (devices-status).
      if (device.role !== "ADMIN" && device.role !== "ADMIN_SECONDARY") {
        return errorResponse(409, "NOT_ADMIN", "Only an admin device can be promoted to Main");
      }
      await supabase
        .from("devices")
        .update({ role: "ADMIN_SECONDARY" })
        .eq("role", "ADMIN")
        .neq("id", deviceId);
      updatePayload = { role: "ADMIN" };
      broadcastEvent = "ROLE_CHANGED";
      broadcastPayload = { type: "ROLE_CHANGED", deviceId };
      break;
    }
  }

  const { data: updated, error: updateError } = await supabase
    .from("devices")
    .update(updatePayload)
    .eq("id", deviceId)
    .select("id, device_identifier, device_model, role, status, label, is_checked_in, last_seen_at, created_at")
    .single();

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Broadcast if needed
  if (broadcastEvent && broadcastPayload) {
    try {
      const channel = supabase.channel("admin-devices");
      await channel.send({
        type: "broadcast",
        event: broadcastEvent,
        payload: broadcastPayload,
      });
      supabase.removeChannel(channel);
    } catch (_e) {
      // Best-effort broadcast
    }
  }

  return jsonResponse(updated);
}
