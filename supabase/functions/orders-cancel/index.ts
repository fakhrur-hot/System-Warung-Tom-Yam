/**
 * DELETE /api/orders/:id — cancel an order with reason.
 * Admin/staff can cancel anytime. Customer only within a short grace window after
 * placing (orders auto-print to the kitchen immediately, so there's no "RECEIVED but
 * not yet sent" state to gate on anymore — the window is time-based instead).
 * Sets status=CANCELLED, ends table session, sets purge_after.
 */
const CUSTOMER_CANCEL_WINDOW_MS = 60_000;
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "DELETE") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only DELETE is supported");
  }

  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;
  const isPrivileged = !!(admin || staff);

  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.reason || !body.cancelledBy) {
    return errorResponse(422, "VALIDATION", "reason and cancelledBy are required");
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
    return errorResponse(409, "ORDER_CLOSED", "Order is already closed");
  }

  // Non-privileged callers must be the customer cancelling their own order
  if (!isPrivileged) {
    if (body.cancelledBy !== "customer") {
      return errorResponse(403, "FORBIDDEN", "Only admin or staff can cancel with this cancelledBy value");
    }
    // Verify ownership via browser_id
    const callerBrowserId = req.headers.get("x-browser-id");
    if (!callerBrowserId || order.browser_id !== callerBrowserId) {
      return errorResponse(403, "FORBIDDEN", "You do not own this order");
    }
    const ageMs = Date.now() - new Date(order.created_at).getTime();
    if (ageMs > CUSTOMER_CANCEL_WINDOW_MS) {
      return errorResponse(403, "CANCEL_NOT_ALLOWED", "Cancellation window has passed — ask staff to cancel this order");
    }
  }

  // Set purge_after to now + 24 hours
  const purgeAfter = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();

  // Cancel order
  const { data: updated, error: updateError } = await supabase
    .from("orders")
    .update({
      status: "CANCELLED",
      cancel_reason: body.reason,
      cancelled_by: body.cancelledBy,
      purge_after: purgeAfter,
    })
    .eq("id", orderId)
    .select("*")
    .single();

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Broadcast cancellation on order:<orderId>
  try {
    const channel = supabase.channel(`order:${orderId}`);
    await channel.send({
      type: "broadcast",
      event: "STATUS_CHANGE",
      payload: mapOrderRow(updated),
    });
  } catch (_e) {
    // Non-critical
  }

  return jsonResponse(mapOrderRow(updated));
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
