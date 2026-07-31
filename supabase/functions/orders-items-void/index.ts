/**
 * POST /orders-items-void/:id — remove lines from an active order before payment.
 *
 * The cashier case this exists for: a customer is settling up and says an item never came. They want
 * to pay for what they actually got, now. Without this the only options were to overcharge them or to
 * cancel the whole order — which would also throw away everything that *was* served.
 *
 * Admin or permitted staff only. Deliberately NOT open to customers (unlike ../orders-items, which
 * lets a customer add rounds to their own order): a customer-reachable endpoint that reduces the bill
 * is a way to eat for free. Voiding is a cashier decision made face to face.
 *
 * Voided lines move from items_json into voided_items_json with who/when/why, and `total` is
 * recomputed from the lines that remain. Voiding every line is refused — that is a cancellation, and
 * ../orders-cancel already records it as one so the reports can tell the two apart.
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

  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;
  if (!admin && !staff) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or staff credentials required");
  }

  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  // lines[] carries the quantity to KEEP for each line, not the quantity to remove — a line reads
  // "2× Teh Tarik" and the cashier turns it down to 1, so the number they set is the number that
  // stays. 0 keeps nothing and removes the line outright.
  const body = await req.json().catch(() => null);
  if (!body || !Array.isArray(body.lines) || body.lines.length === 0) {
    return errorResponse(422, "VALIDATION", "lines[] is required");
  }
  const reason = typeof body.reason === "string" && body.reason.trim()
    ? body.reason.trim().slice(0, 200)
    : "Item not served";

  const supabase = getSupabaseClient();

  const { data: order, error: orderError } = await supabase
    .from("orders")
    .select("*")
    .eq("id", orderId)
    .single();

  if (orderError || !order) {
    return errorResponse(404, "NOT_FOUND", "Order not found");
  }

  // A paid or cancelled order is settled history. Re-opening it here would change a figure the
  // day's reports have already counted, with no compensating record on the payment side.
  if (order.status === "COMPLETED" || order.status === "CANCELLED") {
    return errorResponse(409, "ORDER_CLOSED", "Cannot amend a completed or cancelled order");
  }

  const existingItems = (order.items_json as Record<string, unknown>[]) || [];

  // Requested keep-quantities, validated against the line they name.
  const keepByPositions = new Map<string, number>();
  for (const entry of body.lines) {
    const id = String(entry?.id ?? "");
    const line = existingItems.find((l) => String(l.id) === id);
    if (!line) {
      // A second device may have already amended this order.
      return errorResponse(409, "ALREADY_VOIDED", `Line '${id}' is not on this order any more`);
    }
    const originalQty = Number(line.quantity) || 0;
    const keep = Number(entry?.quantity);
    if (!Number.isInteger(keep) || keep < 0) {
      return errorResponse(422, "VALIDATION", "Each line needs an integer quantity >= 0");
    }
    // Increasing is deliberately refused. A larger quantity has to be priced from the current menu
    // and printed to the kitchen, which is what ../orders-items exists for; allowing it here would
    // add unpriced, uncooked food to a bill.
    if (keep > originalQty) {
      return errorResponse(
        422,
        "CANNOT_INCREASE",
        "Use Add items to order to increase a quantity — this endpoint only reduces",
      );
    }
    keepByPositions.set(id, keep);
  }

  const voidedAt = new Date().toISOString();
  const voidedBy = admin ? "admin" : "staff";

  const kept: Record<string, unknown>[] = [];
  const newVoids: Record<string, unknown>[] = [];
  let changed = false;

  for (const line of existingItems) {
    const id = String(line.id);
    if (!keepByPositions.has(id)) {
      kept.push(line);
      continue;
    }
    const originalQty = Number(line.quantity) || 0;
    const keep = keepByPositions.get(id)!;
    if (keep === originalQty) {
      // Named but unchanged — not an error, just nothing to do for this line.
      kept.push(line);
      continue;
    }
    changed = true;
    // The audit entry records the quantity actually taken off, so a partial reduction is
    // distinguishable from dropping the whole line.
    newVoids.push({
      ...line,
      quantity: originalQty - keep,
      originalQuantity: originalQty,
      remainingQuantity: keep,
      voidedAt,
      voidedBy,
      voidReason: reason,
    });
    if (keep > 0) kept.push({ ...line, quantity: keep });
  }

  if (!changed) {
    return errorResponse(409, "ALREADY_VOIDED", "Nothing on this order would change");
  }
  if (kept.length === 0) {
    return errorResponse(
      409,
      "WOULD_EMPTY_ORDER",
      "That would remove every line — cancel the order instead so it is recorded as a cancellation",
    );
  }

  const existingVoids = (order.voided_items_json as Record<string, unknown>[]) || [];

  // Recomputed from the surviving lines rather than subtracted from the old total, so a total that
  // had already drifted for any reason is corrected here instead of carrying the error forward.
  const newTotal = Math.round(
    kept.reduce(
      (sum, line) => sum + Number(line.unitPriceSnapshot || 0) * Number(line.quantity || 0),
      0,
    ) * 100,
  ) / 100;

  const { data: updated, error: updateError } = await supabase
    .from("orders")
    .update({
      items_json: kept,
      voided_items_json: [...existingVoids, ...newVoids],
      total: newTotal,
    })
    .eq("id", orderId)
    .select("*")
    .single();

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Tell the other devices, so a second cashier's open sheet and the customer's status page both
  // stop showing a line that is no longer billed. Non-critical: the void itself already succeeded,
  // and the 10s catch-up poll reconciles anything that misses this frame.
  try {
    const channel = supabase.channel("admin-orders");
    await channel.send({
      type: "broadcast",
      event: "ITEMS_VOIDED",
      payload: {
        orderId,
        tableId: updated.table_id,
        voidedItemIds: newVoids.map((line) => String(line.id)),
        total: newTotal,
      },
    });
  } catch (_e) {
    // ignore
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
