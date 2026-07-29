/**
 * POST /api/orders/:id/kitchen — reprint the kitchen slip for an order, or
 * confirm/first-print a specific session's items.
 * Admin or staff with SEND_TO_KITCHEN permission.
 *
 * Without a request body (or no sessionNumber field):
 *   Pure "reprint everything" action — returns the full current item list as
 *   linesToPrint, does NOT mutate status/timestamps. Used when the kitchen printer
 *   jammed or ran out of paper after everything was already sentToKitchen=true.
 *
 * With body { sessionNumber: number }:
 *   "Confirm session" / first-time-print path — scopes the sentToKitchen mutation
 *   to ONLY that session's items, persists the update to items_json, and returns
 *   only that session's items as linesToPrint. Used by the B4 Pending_Section UI.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  // Auth: admin or staff with SEND_TO_KITCHEN permission
  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;

  if (!admin && !staff) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or staff token required");
  }

  // If staff, check SEND_TO_KITCHEN permission
  if (staff && !admin) {
    const supabase = getSupabaseClient();
    const { data: setting } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "staff_can_send_kitchen")
      .single();

    if (!setting || setting.value !== "true") {
      return errorResponse(403, "FORBIDDEN", "Staff does not have SEND_TO_KITCHEN permission");
    }
  }

  // Parse optional sessionNumber from request body
  let sessionNumber: number | undefined;
  if (req.headers.get("content-type")?.includes("application/json")) {
    const body = await req.json().catch(() => null);
    if (body && typeof body.sessionNumber === "number") {
      sessionNumber = body.sessionNumber;
    }
  }

  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const supabase = getSupabaseClient();

  // Fetch order
  const { data: order, error: orderError } = await supabase
    .from("orders")
    .select("*")
    .eq("id", orderId)
    .single();

  if (orderError || !order) {
    return errorResponse(404, "NOT_FOUND", "Order not found");
  }

  if (order.status === "COMPLETED" || order.status === "CANCELLED") {
    return errorResponse(409, "ORDER_CLOSED", "Cannot reprint a closed order's kitchen slip");
  }

  const items = (order.items_json as Record<string, unknown>[]) || [];

  // ── Session-scoped path (B4 confirm-session / first-time-print) ──────────
  if (sessionNumber !== undefined) {
    // Items created before per-session tracking (or via paths that omit it) have no
    // sessionNumber — treat those as session 1, matching how the client displays them.
    const sessionOf = (item: Record<string, unknown>) => Number(item.sessionNumber) || 1;
    const sessionItems = items.filter((item) => sessionOf(item) === sessionNumber);

    if (sessionItems.length === 0) {
      return errorResponse(404, "NOT_FOUND", `No items found for session ${sessionNumber}`);
    }

    // Mutate sentToKitchen = true for this session's items only
    const updatedItems = items.map((item) =>
      sessionOf(item) === sessionNumber
        ? { ...item, sentToKitchen: true }
        : item
    );

    // Persist the mutation
    const { error: updateError } = await supabase
      .from("orders")
      .update({ items_json: updatedItems })
      .eq("id", orderId);

    if (updateError) {
      return errorResponse(500, "SERVER_ERROR", updateError.message);
    }

    return jsonResponse({
      order: mapOrderRow({ ...order, items_json: updatedItems }),
      linesToPrint: sessionItems.map((item) => ({ ...item, sentToKitchen: true })),
      sessionNumber,
    });
  }

  // ── Whole-order reprint path (existing behavior, unchanged) ──────────────
  return jsonResponse({
    order: mapOrderRow(order),
    linesToPrint: items,
  });
});

// ── Helpers ────────────────────────────────────────────────────────────────
function extractOrderIdFromPath(pathname: string): string | null {
  const segments = pathname.split("/").filter(Boolean);
  for (const seg of segments) {
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(seg)) {
      return seg;
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
