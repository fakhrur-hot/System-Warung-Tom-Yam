/**
 * The café's ONE secret: the owner key. Restores MAIN ADMIN on any fresh device.
 *
 * GET  /api/admin-recovery            — admin only: the QR url, when this café still stores its key
 *                                        in the legacy plaintext form. A café whose key is stored as
 *                                        a hash cannot answer this — see below — and gets 409.
 * POST /api/admin-recovery/regenerate — admin only: mint a NEW key, store only its hash, and return
 *                                        the plaintext ONCE for the QR. The only moment it exists.
 * POST /api/admin-recovery            — public: { recoveryToken, deviceId, deviceModel } — hashes the
 *                                        presented key and mints a Main Admin session if it matches.
 *
 * ### Hashed at rest, so the key is never readable anywhere
 *
 * The key used to sit in `settings.owner_recovery_token` as plaintext and be compared with `!==`,
 * which meant anyone who could read that table — or call the GET above — held the café. It is now
 * stored only as `owner_recovery_token_hash`, exactly like `devices.session_token_hash` already is.
 * The plaintext exists in one place: the QR the owner saved. Nothing on the server can reproduce it,
 * which is the point — a leaked database no longer hands over the café.
 *
 * That is also why there is no "show me my key again". Lose the QR and you regenerate, which
 * invalidates the old one. A key that can be re-read on demand is not a secret, it is a lookup.
 *
 * ### Why there is no rotating key any more
 *
 * There was a second mechanism — a time-windowed rotating key validated against `ROTATING_KEY_SECRET`
 * in `admin-handshake`. It had no callers left in the app, so it was one more secret to provision, one
 * more clock-skew failure mode, and one more branch to reason about, for no path anyone used. Removed;
 * this key is the whole story.
 *
 * ### Backward compatibility is not optional here
 *
 * This endpoint is how an owner gets back into their own café. A café provisioned before the hash
 * existed still has the plaintext row, so the POST below falls back to comparing against it. Breaking
 * that would lock a real owner out of a running café with no second way in.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors, requireWebsiteOrigin } from "../_shared/cors.ts";
import { verifyAdminToken, sha256, generateToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";
import { pruneRevokedDevices } from "../_shared/devices.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  const supabase = getSupabaseClient();

  if (req.method === "GET") {
    const admin = await verifyAdminToken(req);
    if (!admin) return errorResponse(401, "UNAUTHORIZED", "Admin token required");

    // Hash FIRST — the same precedence the POST validator uses. A café can hold BOTH rows (a
    // stalled old-style provisioning leaves plaintext behind, a later one writes the hash), and
    // this endpoint used to serve the plaintext in that state while the POST below only accepted
    // the hash: the app then displayed a QR that sign-in could never accept, and the owner who
    // dutifully saved it was locked out. If a hash exists, the plaintext row is dead weight from
    // a past life — never a key anyone can sign in with — so it must never be minted into a QR.
    const { data: hashed } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "owner_recovery_token_hash")
      .single();
    if (hashed?.value) {
      return errorResponse(
        409,
        "KEY_NOT_READABLE",
        "This café's owner key is stored as a hash and cannot be shown again. " +
          "Regenerate to get a new QR — the old key stops working.",
      );
    }

    const { data } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "owner_recovery_token")
      .single();
    if (!data?.value) {
      return errorResponse(500, "SERVER_ERROR", "Owner key not set");
    }

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

  // ── POST /regenerate — mint a new key, keep only its hash, return it once ──────
  if (new URL(req.url).pathname.endsWith("/regenerate")) {
    const admin = await verifyAdminToken(req);
    if (!admin) return errorResponse(401, "UNAUTHORIZED", "Admin token required");

    let base: string;
    try {
      base = requireWebsiteOrigin();
    } catch (e) {
      return errorResponse(500, "WEBSITE_ORIGIN_UNSET", (e as Error).message);
    }

    const key = generateToken(32);
    await supabase
      .from("settings")
      .upsert({ key: "owner_recovery_token_hash", value: await sha256(key) });
    // The plaintext row is deleted, not left behind: leaving it would mean the café still has a
    // readable copy of a key we just promised is unreadable.
    await supabase.from("settings").delete().eq("key", "owner_recovery_token");

    return jsonResponse({ token: key, url: `${base}/join?recover=${key}` });
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.recoveryToken || !body.deviceId) {
    return errorResponse(422, "VALIDATION", "recoveryToken and deviceId are required");
  }

  // Hash first, plaintext second. A café provisioned before this change still has the plaintext row
  // and must keep working — see the note at the top of this file.
  const presented = body.recoveryToken as string;
  const { data: hashRow } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "owner_recovery_token_hash")
    .single();

  let accepted = false;
  if (hashRow?.value) {
    accepted = hashRow.value === (await sha256(presented));
  } else {
    const { data: legacy } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "owner_recovery_token")
      .single();
    accepted = !!legacy?.value && legacy.value === presented;
  }
  if (!accepted) return errorResponse(403, "INVALID_RECOVERY", "Invalid owner key");

  // Mint a Main Admin session for this device (reuse/dedup its row, same as admin-handshake).
  const sessionToken = generateToken(32);
  const tokenHash = await sha256(sessionToken);
  const deviceId = body.deviceId as string;

  const { data: existing } = await supabase
    .from("devices")
    .select("id")
    .eq("device_identifier", deviceId)
    .order("created_at", { ascending: true });

  // The row's primary key, returned to the caller below. This endpoint matches by
  // `device_identifier`, but every later device-scoped call (`devices-status`, `devices`,
  // `attendance`) looks up `devices.id` — a device that signs in here and never learns its
  // row id 404s on its very next status poll and tears the fresh session down.
  let rowId: string | null = null;

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
    rowId = keepId;
  } else {
    const { data: inserted } = await supabase
      .from("devices")
      .insert({
        device_identifier: deviceId,
        role: "ADMIN",
        status: "APPROVED",
        session_token_hash: tokenHash,
        label: body.deviceModel || "Recovered Admin",
        last_seen_at: new Date().toISOString(),
      })
      .select("id")
      .single();
    rowId = inserted?.id ?? null;
  }


  // The owner signing back in is the right moment to tidy the device list.
  //
  // Revocations are soft, so a café that has replaced a few phones accumulates dead rows it has no
  // way to clear. Pruning only when a device is revoked converges eventually, but leaves an existing
  // backlog sitting there until the next revocation — which for a café that has finished swapping
  // its hardware may be never.
  //
  // An owner recovery is the one event that is always deliberate, always authenticated, and always
  // performed by the person who owns the list. Doing it here means a device list that has grown
  // untidy is cleaned up the next time the owner scans their key, with nothing to remember and no
  // button to find.
  //
  // Best-effort and after the session is minted: a recovery that succeeded must never be reported
  // as failed because the housekeeping behind it did not.
  await pruneRevokedDevices(supabase);
  // `deviceId` is the devices row PRIMARY KEY — the same contract as `register`'s response,
  // and deliberately the same field name. Callers must store it for device-scoped polls.
  return jsonResponse({ sessionToken, deviceId: rowId });
});
