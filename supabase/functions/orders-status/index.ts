/**
 * PUT /api/orders/:id/status — admin updates order status.
 * Allowed transitions: PREPARING, READY.
 * Broadcasts on order:<orderId> channel.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const ALLOWED_STATUSES = new Set(["PREPARING", "READY"]);

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "PUT") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only PUT is supported");
  }

  // Admin only
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.status) {
    return errorResponse(422, "VALIDATION", "status is required");
  }

  if (!ALLOWED_STATUSES.has(body.status)) {
    return errorResponse(422, "VALIDATION", "status must be PREPARING or READY");
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
    return errorResponse(409, "ORDER_CLOSED", "Cannot update a closed order");
  }

  // Update status
  const { data: updated, error: updateError } = await supabase
    .from("orders")
    .update({ status: body.status })
    .eq("id", orderId)
    .select("*")
    .single();

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Broadcast on order:<orderId>
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
