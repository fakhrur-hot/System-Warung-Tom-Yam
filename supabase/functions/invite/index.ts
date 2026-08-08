/**
 * Invite management — admin session token required.
 * GET  /api/invite           — get current invite token
 * POST /api/invite/regenerate — rotate the invite token
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors, requireWebsiteOrigin } from "../_shared/cors.ts";
import { verifyAdminToken, generateToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

/**
 * How long a freshly-minted invite stays valid.
 *
 * An invite is scanned within a minute of being shown in practice — the admin holds one phone up and
 * the joining device photographs it. Fifteen minutes is generous for that and short enough that a
 * code found later (a photo, a printed slip in a drawer, a screenshot in a group chat) is already
 * dead. See migration 0013 for why the column is nullable.
 */
const INVITE_TTL_MINUTES = 15;

/** Expiry stamp for a token minted now. */
function inviteExpiry(): string {
  return new Date(Date.now() + INVITE_TTL_MINUTES * 60_000).toISOString();
}

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

  // Which invite to operate on: `?role=admin` (or secondary) → SECONDARY-ADMIN (row id=2),
  // `?role=operator` → OPERATOR (row id=3, seeded by migration 0015), anything else → the
  // ordering-staff invite (row id=1). All mint /join links; `register` derives the granted role
  // from whichever token was scanned.
  //
  // `operator` used to be missing from this mapping, so it fell through to the id=1 default: the
  // admin app's "Add Operator" panel rendered the ORDERING token as its QR, and its Regenerate
  // button rotated the *staff* invite. An operator device that scanned it registered as ORDERING,
  // was issued an api_key instead of a session token, and could never finish connecting.
  const roleParam = (url.searchParams.get("role") ?? "").toLowerCase();
  const INVITE_TARGETS: Record<string, { id: number; role: string }> = {
    admin: { id: 2, role: "ADMIN_SECONDARY" },
    secondary: { id: 2, role: "ADMIN_SECONDARY" },
    admin_secondary: { id: 2, role: "ADMIN_SECONDARY" },
    operator: { id: 3, role: "OPERATOR" },
  };
  const inviteTarget = INVITE_TARGETS[roleParam] ?? { id: 1, role: "ORDERING" };
  const inviteId = inviteTarget.id;
  const inviteRole = inviteTarget.role;

  // Determine if this is a regenerate request
  const isRegenerate = req.method === "POST" && path.endsWith("/regenerate");
  const isGet = req.method === "GET";

  if (!isGet && !isRegenerate) {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Use GET or POST /regenerate");
  }

  // An invite QR built on a placeholder origin encodes a link to a site that does not exist, and
  // fails in a café with a phone already scanning it. Refuse to mint one instead.
  let base: string;
  try {
    base = requireWebsiteOrigin();
  } catch (e) {
    return errorResponse(500, "WEBSITE_ORIGIN_UNSET", (e as Error).message);
  }

  if (isRegenerate) {
    // Generate a new invite token and replace the target role's row
    const newToken = generateToken(16);
    const expiresAt = inviteExpiry();

    const { error } = await supabase
      .from("invites")
      .upsert(
        {
          id: inviteId,
          token: newToken,
          role: inviteRole,
          rotated_at: new Date().toISOString(),
          expires_at: expiresAt,
        },
        { onConflict: "id" }
      );

    if (error) {
      return errorResponse(500, "SERVER_ERROR", error.message);
    }

    // Invite links must point at the customer WEBSITE (Cloudflare Pages), NOT this Edge
    // Function's host — otherwise the /join App Link can never open the app. Driven by the
    // WEBSITE_ORIGIN secret (same one CORS uses).
    const inviteUrl = `${base}/join?invite=${newToken}`;

    return jsonResponse({ token: newToken, url: inviteUrl, role: inviteRole, expiresAt });
  }

  // GET — return current invite token for the target role (seed one if none exists)
  let { data: invite, error } = await supabase
    .from("invites")
    .select("token, expires_at")
    .eq("id", inviteId)
    .single();

  if (!invite || error) {
    // Seed an initial invite token
    const initialToken = generateToken(16);
    const seededExpiry = inviteExpiry();
    const { error: insertError } = await supabase
      .from("invites")
      .upsert(
        {
          id: inviteId,
          token: initialToken,
          role: inviteRole,
          rotated_at: new Date().toISOString(),
          expires_at: seededExpiry,
        },
        { onConflict: "id" }
      );

    if (insertError) {
      return errorResponse(500, "SERVER_ERROR", insertError.message);
    }
    invite = { token: initialToken, expires_at: seededExpiry };
  }

  const inviteUrl = `${base}/join?invite=${invite.token}`;

  return jsonResponse({
    token: invite.token,
    url: inviteUrl,
    role: inviteRole,
    expiresAt: invite.expires_at ?? null,
  });
});
