/**
 * GET /functions/v1/payment-transactions?orderId=<id> — every gateway attempt for an order, newest
 * first. (PG-REQ-5, task 6.1, 8.5)
 *
 * Drives the retry-history panel and crash recovery: reopening a mid-payment order needs to see
 * what was last attempted without asking the acquirer, which per F5 may no longer have an answer.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;
  if (!admin && !staff) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or staff token required");
  }

  const orderId = new URL(req.url).searchParams.get("orderId");
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const supabase = getSupabaseClient();
  const { data, error } = await supabase
    .from("gateway_transactions")
    .select("*")
    .eq("order_id", orderId)
    .order("created_at", { ascending: false });

  if (error) {
    console.error("payment-transactions: query failed:", error.message);
    return errorResponse(500, "QUERY_FAILED", "Could not load payment transactions");
  }

  return jsonResponse({ transactions: (data ?? []).map(mapRow) });
});

// deno-lint-ignore no-explicit-any
function mapRow(row: any) {
  return {
    id: row.id,
    orderId: row.order_id,
    paymentMethod: row.payment_method,
    amountSen: row.amount_sen,
    status: row.status,
    gatewayTransactionId: row.gateway_transaction_id,
    gatewayResponse: row.gateway_response_json == null ? null : JSON.stringify(row.gateway_response_json),
    isSandbox: row.is_sandbox,
    createdAt: row.created_at,
    settledAt: row.settled_at,
  };
}
