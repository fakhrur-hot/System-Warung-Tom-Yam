/**
 * Invite management — admin session token required.
 * GET  /api/invite           — get current invite token
 * POST /api/invite/regenerate — rotate the invite token
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, generateToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  // Verify admin session token
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Valid admin session token required");
  }

  const supabase = getSupabaseClient();
  const url = new URL(req.url);
  const path = url.pathname;

  // Which invite to operate on. `?role=admin` (or secondary) → the SECONDARY-ADMIN invite
  // (row id=2); anything else → the ordering-staff invite (row id=1). Both mint /join
  // links; `register` derives the granted role from whichever token was scanned.
  const roleParam = (url.searchParams.get("role") ?? "").toLowerCase();
  const isAdminInvite = roleParam === "admin" || roleParam === "secondary" || roleParam === "admin_secondary";
  const inviteId = isAdminInvite ? 2 : 1;
  const inviteRole = isAdminInvite ? "ADMIN_SECONDARY" : "ORDERING";

  // Determine if this is a regenerate request
  const isRegenerate = req.method === "POST" && path.endsWith("/regenerate");
  const isGet = req.method === "GET";

  if (!isGet && !isRegenerate) {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Use GET or POST /regenerate");
  }

  const base = (Deno.env.get("WEBSITE_ORIGIN") ?? "https://tani-tom-yam.pages.dev").replace(/\/+$/, "");

  if (isRegenerate) {
    // Generate a new invite token and replace the target role's row
    const newToken = generateToken(16);

    const { error } = await supabase
      .from("invites")
      .upsert(
        { id: inviteId, token: newToken, role: inviteRole, rotated_at: new Date().toISOString() },
        { onConflict: "id" }
      );

    if (error) {
      return errorResponse(500, "SERVER_ERROR", error.message);
    }

    // Invite links must point at the customer WEBSITE (Cloudflare Pages), NOT this Edge
    // Function's host — otherwise the /join App Link can never open the app. Driven by the
    // WEBSITE_ORIGIN secret (same one CORS uses).
    const inviteUrl = `${base}/join?invite=${newToken}`;

    return jsonResponse({ token: newToken, url: inviteUrl, role: inviteRole });
  }

  // GET — return current invite token for the target role (seed one if none exists)
  let { data: invite, error } = await supabase
    .from("invites")
    .select("token")
    .eq("id", inviteId)
    .single();

  if (!invite || error) {
    // Seed an initial invite token
    const initialToken = generateToken(16);
    const { error: insertError } = await supabase
      .from("invites")
      .upsert(
        { id: inviteId, token: initialToken, role: inviteRole, rotated_at: new Date().toISOString() },
        { onConflict: "id" }
      );

    if (insertError) {
      return errorResponse(500, "SERVER_ERROR", insertError.message);
    }
    invite = { token: initialToken };
  }

  const inviteUrl = `${base}/join?invite=${invite.token}`;

  return jsonResponse({ token: invite.token, url: inviteUrl, role: inviteRole });
});
