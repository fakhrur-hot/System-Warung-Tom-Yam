/**
 * POST /functions/v1/payment-initiate — start a gateway payment attempt. (PG-REQ-4, task 6.2)
 *
 * Reached only through BackendGateway.initiatePayment / initiatePaymentAsStaff (A2) — the POS never
 * calls an Edge Function or an aggregator directly, and never holds the merchant secret. That
 * secret lives in `gateway_config`, read here with the service role, and is what signs the hosted
 * payment page URL this returns (F3).
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";
import { buildHostedPageUrl, fiuuChannelCode } from "../_shared/fiuu.ts";

// Same payable-state guard as orders-payment (Cash/QR) — a gateway payment is not exempt from it.
const PAYABLE_STATUSES = ["SENT_TO_KITCHEN", "PREPARING", "READY"];

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;
  if (!admin && !staff) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or staff token required");
  }

  const body = await req.json().catch(() => null);
  if (!body) {
    return errorResponse(422, "VALIDATION", "Invalid JSON body");
  }

  const orderId: string | undefined = body.orderId;
  const amountSen: number | undefined = body.amountSen;
  const paymentMethodCode: string | undefined = body.paymentMethodCode;
  const idempotencyKey: string | undefined = body.idempotencyKey;
  const currency: string = body.currency || "MYR";
  const isSandbox: boolean = body.isSandbox === true;
  const customerAuthCode: string | null = body.customerAuthCode ?? null;

  if (
    !orderId || typeof amountSen !== "number" || !Number.isFinite(amountSen) || amountSen <= 0 ||
    !paymentMethodCode || !idempotencyKey
  ) {
    return errorResponse(
      422,
      "VALIDATION",
      "orderId, amountSen, paymentMethodCode and idempotencyKey are required",
    );
  }

  const channelCode = fiuuChannelCode(paymentMethodCode);
  if (!channelCode) {
    return errorResponse(422, "VALIDATION", `Unsupported payment method: ${paymentMethodCode}`);
  }

  const supabase = getSupabaseClient();

  const { data: order, error: orderError } = await supabase
    .from("orders")
    .select("id, status, total")
    .eq("id", orderId)
    .single();

  if (orderError || !order) {
    return errorResponse(404, "NOT_FOUND", "Order not found");
  }
  if (!PAYABLE_STATUSES.includes(order.status)) {
    return errorResponse(409, "PAYMENT_CONFLICT", `Order cannot be paid in status ${order.status}`);
  }

  // The charged amount must match the order total — a mismatch is an error condition, never
  // something reconciled silently afterwards. (A8)
  const expectedSen = Math.round(Number(order.total) * 100);
  if (expectedSen !== amountSen) {
    return errorResponse(422, "AMOUNT_MISMATCH", "Charged amount does not match the order total");
  }

  const { data: config, error: configError } = await supabase
    .from("gateway_config")
    .select("merchant_id, verify_key, enabled_methods")
    .eq("id", 1)
    .maybeSingle();

  if (configError || !config?.merchant_id || !config?.verify_key) {
    return errorResponse(500, "GATEWAY_NOT_CONFIGURED", "Payment gateway is not configured for this café");
  }
  const enabledMethods: string[] = config.enabled_methods ?? [];
  if (!enabledMethods.includes(paymentMethodCode)) {
    return errorResponse(422, "CHANNEL_DISABLED", `${paymentMethodCode} is not enabled for this café`);
  }

  // Idempotency replay (A6, task 6.3): a retry of the same (orderId, amount) carries the same key
  // and lands on the same row. A row already SUCCESS is returned as-is — no new charge is ever
  // issued for an attempt that already succeeded.
  const { data: existing } = await supabase
    .from("gateway_transactions")
    .select("id, status, gateway_transaction_id")
    .eq("idempotency_key", idempotencyKey)
    .maybeSingle();

  if (existing?.status === "SUCCESS") {
    return jsonResponse({
      success: true,
      transactionId: existing.gateway_transaction_id ?? existing.id,
      status: "SUCCESS",
    });
  }

  const baseUrl = Deno.env.get("SUPABASE_URL")!.replace(/\/+$/, "");
  const amountRinggit = (amountSen / 100).toFixed(2);
  const callbackUrl = `${baseUrl}/functions/v1/payment-callback`;

  const checkoutUrl = buildHostedPageUrl({
    merchantId: config.merchant_id,
    verifyKey: config.verify_key,
    channelCode,
    orderId,
    amountRinggit,
    currency,
    isSandbox,
    returnUrl: callbackUrl,
    notifyUrl: callbackUrl,
    callbackUrl,
  });

  const { data: saved, error: upsertError } = await supabase
    .from("gateway_transactions")
    .upsert(
      {
        order_id: orderId,
        payment_method: paymentMethodCode,
        amount_sen: amountSen,
        status: "PENDING",
        gateway_response_json: { checkoutUrl, customerAuthCode },
        idempotency_key: idempotencyKey,
        is_sandbox: isSandbox,
      },
      { onConflict: "idempotency_key" },
    )
    .select("id")
    .single();

  if (upsertError || !saved) {
    console.error("Failed to persist gateway transaction:", upsertError?.message);
    return errorResponse(500, "PERSIST_FAILED", "Could not record the payment attempt");
  }

  // qrString is deliberately omitted — every Fiuu channel documented in this project's design is a
  // hosted page (F1, F6 #1), not a seamless API returning a QR payload to render ourselves.
  return jsonResponse({
    success: true,
    transactionId: saved.id,
    checkoutUrl,
    status: "PENDING",
  });
});
