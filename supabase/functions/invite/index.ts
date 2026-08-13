/**
 * Invite management — admin session token required.
 * GET  /api/invite           — get current invite token
 * POST /api/invite/regenerate — rotate the invite token
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors, requireWebsiteOrigin } from "../_shared/cors.ts";
import { verifyAdminToken, generateToken, sha256 } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

// Invites no longer expire or auto-rotate (see migration 0020): a café's join code stays valid
// until an admin explicitly hits Regenerate, exactly like the Main Admin owner key. `expires_at`
// is left alone on existing rows (NULL already means "never expires" — see migration 0013) but is
// never set on newly minted rows.

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
    // Mint a new invite token, store only its hash, and hand back the plaintext exactly once —
    // the same shape as admin-recovery's owner-key regenerate. `token` is cleared so no plaintext
    // copy of the new code is left behind.
    const newToken = generateToken(16);

    const { error } = await supabase
      .from("invites")
      .upsert(
        {
          id: inviteId,
          token: null,
          token_hash: await sha256(newToken),
          role: inviteRole,
          rotated_at: new Date().toISOString(),
          expires_at: null,
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

    return jsonResponse({ token: newToken, url: inviteUrl, role: inviteRole, expiresAt: null });
  }

  // GET — return the current invite for the target role. A café that has never minted one for
  // this role gets one seeded now (hash-only, shown once). A café that already has a hash-only
  // row cannot get the plaintext back — same "cannot be shown again" rule as the owner key: the
  // admin hits Regenerate for a fresh code instead of this silently minting a new one, which
  // would be the rotation the café explicitly does not want happening on every page load.
  const { data: invite } = await supabase
    .from("invites")
    .select("token, token_hash, expires_at")
    .eq("id", inviteId)
    .maybeSingle();

  if (!invite) {
    const initialToken = generateToken(16);
    const { error: insertError } = await supabase
      .from("invites")
      .upsert(
        {
          id: inviteId,
          token: null,
          token_hash: await sha256(initialToken),
          role: inviteRole,
          rotated_at: new Date().toISOString(),
          expires_at: null,
        },
        { onConflict: "id" }
      );

    if (insertError) {
      return errorResponse(500, "SERVER_ERROR", insertError.message);
    }

    return jsonResponse({
      token: initialToken,
      url: `${base}/join?invite=${initialToken}`,
      role: inviteRole,
      expiresAt: null,
    });
  }

  // Hash-first, same precedence `register` uses: a café can hold both a hash (from a rotation
  // after this migration) and a leftover plaintext column from before it, and the hash always
  // wins once it exists.
  if (invite.token_hash) {
    return errorResponse(
      409,
      "INVITE_NOT_READABLE",
      "This invite code is stored as a hash and cannot be shown again. Regenerate to get a new one.",
    );
  }

  if (!invite.token) {
    return errorResponse(500, "SERVER_ERROR", "Invite row has neither a token nor a token hash");
  }

  return jsonResponse({
    token: invite.token,
    url: `${base}/join?invite=${invite.token}`,
    role: inviteRole,
    expiresAt: invite.expires_at ?? null,
  });
});
