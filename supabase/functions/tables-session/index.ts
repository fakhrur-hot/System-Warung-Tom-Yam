/**
 * GET /api/tables/:tableId/session — public endpoint.
 * Returns table state: FREE, OCCUPIED, or OCCUPIED with order details
 * (if caller's X-Browser-Id owns the active order).
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  const url = new URL(req.url);
  const tableId = url.searchParams.get("tableId") || extractTableIdFromPath(url.pathname);
  if (!tableId) {
    return errorResponse(422, "VALIDATION", "tableId is required");
  }

  const browserId = req.headers.get("x-browser-id") || null;
  const supabase = getSupabaseClient();

  // Resolve the identifier — it may be the opaque QR token (new) or the raw table id
  // (legacy QRs / admin). Grab the admin-entered display name for the customer UI.
  const { data: table, error: tableError } = await supabase
    .from("tables")
    .select("id, display_name")
    .or(`id.eq.${tableId},qr_token.eq.${tableId}`)
    .limit(1)
    .maybeSingle();

  if (tableError || !table) {
    return errorResponse(404, "UNKNOWN_TABLE", `Table '${tableId}' does not exist`);
  }
  const realTableId = table.id;
  const displayName = table.display_name ?? realTableId;

  // Café open/closed is derived from the most recent session event. Signing out WITH
  // closing posts a CLOSE row with closing=true — that (and only that) means the café is
  // shut for customers. A plain lock posts CLOSE with closing=false and stays "open".
  const { data: lastSession } = await supabase
    .from("sessions")
    .select("event, closing")
    .order("timestamp", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (lastSession?.event === "CLOSE" && lastSession?.closing === true) {
    return jsonResponse({ state: "CLOSED", displayName });
  }

  // Check for active order on this table (query by the resolved real id)
  const { data: activeOrder } = await supabase
    .from("orders")
    .select("*")
    .eq("table_id", realTableId)
    .not("status", "in", "(COMPLETED,CANCELLED)")
    .limit(1)
    .single();

  if (!activeOrder) {
    return jsonResponse({ state: "FREE", displayName });
  }

  // Table is occupied — check if caller owns it
  if (browserId && activeOrder.browser_id === browserId) {
    return jsonResponse({
      state: "OCCUPIED",
      displayName,
      order: mapOrderRow(activeOrder),
    });
  }

  return jsonResponse({ state: "OCCUPIED", displayName });
});

// ── Helpers ────────────────────────────────────────────────────────────────
function extractTableIdFromPath(pathname: string): string | null {
  // Pattern: /tables-session/T3 or /functions/v1/tables-session?tableId=T3
  const segments = pathname.split("/").filter(Boolean);
  // Look for table ID pattern (e.g., T1, T2, etc.) — usually last non-function segment
  // Since Supabase Edge Functions route to /functions/v1/<name>, the tableId
  // might be in the last segment if the URL is /functions/v1/tables-session/T3
  if (segments.length > 0) {
    const last = segments[segments.length - 1];
    // If it's not the function name itself, it might be the tableId
    if (last !== "tables-session") {
      return last;
    }
  }
  return null;
}

// deno-lint-ignore no-explicit-any
function mapOrderRow(row: any) {
  return {
    id: row.id,
    tableId: row.table_id,
    source: row.source,
    browserId: row.browser_id,
    status: row.status,
    paymentMethod: row.payment_method,
    sentToKitchenAt: row.sent_to_kitchen_at,
    cancelReason: row.cancel_reason,
    cancelledBy: row.cancelled_by,
    total: Number(row.total),
    createdAt: row.created_at,
    items: row.items_json || [],
  };
}
