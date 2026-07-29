/**
 * GET /api/tables — public: returns the registered table list (id + display name).
 * PUT /api/tables — admin: replaces the full table registry from the admin phone's
 * local Room `tables` list (mirrors how `menu`/`branding` sync — the phone is the
 * source of truth, this just duplicates it to Supabase).
 *
 * Tables not present in the pushed set are removed from the registry, EXCEPT any
 * table still referenced by a row in `orders` (FK, no cascade) — those are kept
 * and reported back in `skippedInUse` rather than failing the whole push. This
 * matters whenever the phone's local list is behind the server's (fresh install,
 * local data wipe) — it must not delete a table out from under a live order.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "GET") {
    return handleGetTables();
  }
  if (req.method === "PUT") {
    return handlePutTables(req);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// ── GET /api/tables ──────────────────────────────────────────────────────────
async function handleGetTables(): Promise<Response> {
  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("tables")
    .select("id, display_name, qr_token")
    .order("id", { ascending: true });

  if (error) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read tables");
  }

  return jsonResponse({
    tables: (data ?? []).map((t) => ({
      id: t.id,
      displayName: t.display_name,
      qrToken: t.qr_token,
    })),
  });
}

// ── PUT /api/tables ───────────────────────────────────────────────────────────
async function handlePutTables(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !Array.isArray(body.tables)) {
    return errorResponse(422, "VALIDATION", "Body must contain a tables array");
  }

  for (const t of body.tables) {
    if (typeof t.id !== "string" || !t.id || typeof t.displayName !== "string") {
      return errorResponse(422, "VALIDATION", "Each table needs a string id and displayName");
    }
  }

  const supabase = getSupabaseClient();

  // Replace wholesale: delete rows not in the incoming set, then upsert the rest.
  // Exception: a table can be referenced by `orders.table_id` (FK, no cascade) —
  // if the phone's local list no longer has that table (fresh install, local
  // data wiped, etc.) we must NOT delete it out from under a live/historical
  // order. Skip those instead of letting the whole push fail with a 500.
  const incomingIds: string[] = body.tables.map((t: { id: string }) => t.id);

  const { data: existingRows, error: existingError } = await supabase
    .from("tables")
    .select("id");
  if (existingError) {
    return errorResponse(500, "SERVER_ERROR", `Failed to read existing tables: ${existingError.message}`);
  }

  const incomingIdSet = new Set(incomingIds);
  const candidateDeleteIds = (existingRows ?? [])
    .map((r) => r.id as string)
    .filter((id) => !incomingIdSet.has(id));

  let skippedInUse: string[] = [];
  if (candidateDeleteIds.length > 0) {
    const { data: referencedRows, error: referencedError } = await supabase
      .from("orders")
      .select("table_id")
      .in("table_id", candidateDeleteIds);
    if (referencedError) {
      return errorResponse(500, "SERVER_ERROR", `Failed to check table references: ${referencedError.message}`);
    }

    const referencedIds = new Set((referencedRows ?? []).map((r) => r.table_id as string));
    skippedInUse = candidateDeleteIds.filter((id) => referencedIds.has(id));
    const idsToDelete = candidateDeleteIds.filter((id) => !referencedIds.has(id));

    if (idsToDelete.length > 0) {
      const { error: deleteError } = await supabase
        .from("tables")
        .delete()
        .in("id", idsToDelete);
      if (deleteError) {
        return errorResponse(500, "SERVER_ERROR", `Failed to prune tables: ${deleteError.message}`);
      }
    }
  }

  const rows = body.tables.map((t: { id: string; displayName: string }) => ({
    id: t.id,
    display_name: t.displayName,
  }));

  if (rows.length > 0) {
    const { error: upsertError } = await supabase.from("tables").upsert(rows);
    if (upsertError) {
      return errorResponse(500, "SERVER_ERROR", `Failed to sync tables: ${upsertError.message}`);
    }
  }

  return jsonResponse({
    updatedAt: new Date().toISOString(),
    count: rows.length,
    skippedInUse,
  });
}
