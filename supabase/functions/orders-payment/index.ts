/**
 * POST /api/orders/:id/payment — process payment for an order.
 * Only valid after SENT_TO_KITCHEN. Sets status=COMPLETED, ends table session.
 * Writes PaymentTransaction row.
 * Admin or staff with TAKE_PAYMENT permission.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

// Cash and static QR (no gateway leg) plus every gateway channel code this app knows about
// (apk/data/local/PaymentMethod.kt). A gateway payment reaches here only after its own checkout
// confirmed SUCCESS (task 8.2) — this endpoint completes the order the same way for every method,
// per designs.md's Integration Points table: "Status transitions unchanged: SENT_TO_KITCHEN →
// payment → COMPLETED", regardless of which method paid it. (task 6/7/8 audit, A4)
const VALID_METHODS = new Set([
  "CASH",
  "QR",
  "DUITNOW_QR",
  "TNG",
  "GRABPAY",
  "BOOST",
  "SHOPEEPAY",
  "FPX",
  "CARD",
]);

// The legacy `payment_transactions` table (0001_initial_schema.sql) has a `method` column typed as
// the `payment_method` **enum**, which only ever had 'CASH'/'QR'. It predates the gateway ledger
// (`gateway_transactions`, task 5.4/6.2) and nothing reads it — it is not worth widening its enum
// just to duplicate what `gateway_transactions` already records in full. Only write it for the two
// methods it can actually represent.
const LEGACY_AUDIT_LOG_METHODS = new Set(["CASH", "QR"]);

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  // Auth: admin or staff with TAKE_PAYMENT permission
  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;

  if (!admin && !staff) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or staff token required");
  }

  // If staff, check TAKE_PAYMENT permission
  if (staff && !admin) {
    const supabase = getSupabaseClient();
    const { data: setting } = await supabase
      .from("settings")
      .select("value")
      .eq("key", "staff_can_take_payment")
      .single();

    if (!setting || setting.value !== "true") {
      return errorResponse(403, "FORBIDDEN", "Staff does not have TAKE_PAYMENT permission");
    }
  }

  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.method) {
    return errorResponse(422, "VALIDATION", "method (CASH or QR) is required");
  }

  if (!VALID_METHODS.has(body.method)) {
    return errorResponse(422, "VALIDATION", `Unrecognised payment method: ${body.method}`);
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

  // Set purge_after to now + 24 hours
  const purgeAfter = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();

  // Conditional update — only succeeds if order is still in a payable status
  const { data: updated, error: updateError } = await supabase
    .from("orders")
    .update({
      status: "COMPLETED",
      payment_method: body.method,
      purge_after: purgeAfter,
    })
    .eq("id", orderId)
    .in("status", ["SENT_TO_KITCHEN", "PREPARING", "READY"])  // ← atomic guard
    .select("*")
    .single();

  if (updateError || !updated) {
    // Re-fetch to give a meaningful error
    const { data: currentOrder } = await supabase.from("orders").select("status").eq("id", orderId).single();
    if (currentOrder?.status === "COMPLETED") {
      return errorResponse(409, "ALREADY_PAID", "Order is already completed");
    }
    return errorResponse(409, "PAYMENT_CONFLICT", "Order cannot be paid in its current status");
  }

  // Legacy CASH/QR audit log — see LEGACY_AUDIT_LOG_METHODS above for why gateway methods are
  // skipped here rather than attempted and logged as a spurious failure on every gateway payment.
  if (LEGACY_AUDIT_LOG_METHODS.has(body.method)) {
    const { error: txError } = await supabase
      .from("payment_transactions")
      .insert({
        order_id: orderId,
        method: body.method,
        amount: order.total,
      });

    if (txError) {
      // Non-critical for the response, but log it
      console.error("Failed to write payment transaction:", txError.message);
    }
  }

  // Broadcast completion on order:<orderId>
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
