/**
 * POST /functions/v1/payment-callback — the acquirer's server-to-server payment notification.
 * (PG-REQ-4, task 6.2b, 6.2c)
 *
 * Hit by Fiuu directly, never by the POS — there is no admin/staff bearer token here, and this
 * function has `verify_jwt = false` in config.toml for the same reason every other endpoint does:
 * Supabase's own JWT gate only understands Supabase-issued JWTs and would 401 this before the
 * handler ever ran. The **only** authentication on this endpoint is the acquirer's own `skey`
 * signature (F3) — everything below fails closed if it does not verify.
 *
 * ### The ACK is exact, and getting it wrong is expensive (F4)
 *
 * A Supabase Edge Function returns JSON by default. Fiuu's callback contract requires the literal
 * plaintext `CBTOKEN:MPSTATOK`, **no HTML, no JSON wrapper**, `Content-Type: text/plain`. Get this
 * wrong and Fiuu retries 3 times at 15-minute intervals — a café sees a payment "fail" for up to 45
 * minutes after the money has actually moved. This function returns that exact string for every
 * request whose signature verifies, regardless of what our own processing did with it: the ACK's
 * job is to stop the acquirer's retry loop, not to report our internal outcome.
 *
 * ### Why this row is written the moment the callback lands (6.2c, F5)
 *
 * Requery is only valid for 24 hours ("no result available for transactions more than 1 day"), so
 * `gateway_transactions` — not the acquirer — is the source of truth after that window. Task 8.5's
 * crash recovery depends on the write happening here, synchronously, rather than being re-derived
 * from a later requery that may no longer have an answer.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { corsHeaders, handleCors } from "../_shared/cors.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { mapFiuuStatus, verifyCallbackSignature } from "../_shared/fiuu.ts";

const ACK = "CBTOKEN:MPSTATOK";

function ackResponse(): Response {
  return new Response(ACK, {
    status: 200,
    headers: { ...corsHeaders, "Content-Type": "text/plain" },
  });
}

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    // Not a valid acquirer callback — no ACK earned.
    return new Response("Method not allowed", { status: 405, headers: corsHeaders });
  }

  const fields = await readFields(req);

  const tranId = fields.tranID ?? fields.tranid;
  const orderId = fields.orderid ?? fields.orderId;
  const status = fields.status;
  const domain = fields.domain;
  const amount = fields.amount;
  const currency = fields.currency ?? "MYR";
  const paydate = fields.paydate;
  const appcode = fields.appcode;
  const skey = fields.skey;

  if (!tranId || !orderId || !status || !domain || !amount || !paydate || !appcode || !skey) {
    console.error("payment-callback: missing required field(s)", Object.keys(fields));
    // Malformed — cannot verify a signature we don't have all the pieces of. No ACK: a genuine
    // Fiuu callback always carries every one of these fields, so this is not one.
    return new Response("Missing required field(s)", { status: 400, headers: corsHeaders });
  }

  const supabase = getSupabaseClient();

  const { data: config } = await supabase
    .from("gateway_config")
    .select("secret_key")
    .eq("id", 1)
    .maybeSingle();

  if (!config?.secret_key) {
    console.error("payment-callback: gateway_config has no secret_key; cannot verify callback");
    return new Response("Gateway not configured", { status: 500, headers: corsHeaders });
  }

  const verified = verifyCallbackSignature(
    { tranId, orderId, status, domain, amount, currency, paydate, appcode, skey },
    config.secret_key,
  );

  if (!verified) {
    // F3: a callback whose skey does not verify must be treated as a failure and must never mark
    // an order paid. This may be a forged POST — it does not get the ACK, so a legitimate retry
    // (if this really was Fiuu, e.g. a transient signing mismatch) still has 2 more attempts.
    console.error(`payment-callback: signature mismatch for order ${orderId}, tranID ${tranId}`);
    return new Response("Signature verification failed", { status: 400, headers: corsHeaders });
  }

  const mapped = mapFiuuStatus(status);

  // The row was created by payment-initiate under the (orderId, amountSen) idempotency key. This
  // callback doesn't carry that key, so it is matched by order_id + PENDING — there is at most one
  // PENDING gateway attempt per order by construction (upsert on idempotency_key in payment-initiate).
  const { data: txn, error: findError } = await supabase
    .from("gateway_transactions")
    .select("id")
    .eq("order_id", orderId)
    .eq("status", "PENDING")
    .maybeSingle();

  if (findError || !txn) {
    // Nothing pending for this order — most likely a duplicate callback after we already settled
    // it. Still ACK: from the acquirer's point of view the notification was received correctly.
    console.error(`payment-callback: no PENDING gateway_transactions row for order ${orderId}`);
    return ackResponse();
  }

  const { error: updateError } = await supabase
    .from("gateway_transactions")
    .update({
      status: mapped,
      gateway_transaction_id: tranId,
      gateway_response_json: fields,
      settled_at: mapped === "PENDING" ? null : new Date().toISOString(),
    })
    .eq("id", txn.id);

  if (updateError) {
    // Persisting failed, but the signature was genuine and the acquirer must stop retrying this
    // notification — a 5xx here would just trigger 3 more retries of a delivery that already
    // succeeded. Log loudly; task 8.2's polling loop (querying by transaction id) is the backstop.
    console.error(`payment-callback: failed to persist transaction ${txn.id}:`, updateError.message);
  }

  return ackResponse();
});

/** Fiuu posts these as form fields, not JSON. Falls back to query params for a GET-style redirect. */
async function readFields(req: Request): Promise<Record<string, string>> {
  const fields: Record<string, string> = {};
  const contentType = req.headers.get("content-type") ?? "";

  if (contentType.includes("application/json")) {
    const body = await req.json().catch(() => ({}));
    for (const [k, v] of Object.entries(body)) fields[k] = String(v);
    return fields;
  }

  try {
    const form = await req.formData();
    for (const [k, v] of form.entries()) fields[k] = String(v);
    if (Object.keys(fields).length > 0) return fields;
  } catch {
    // Not form-encoded — fall through to query params.
  }

  const url = new URL(req.url);
  for (const [k, v] of url.searchParams.entries()) fields[k] = v;
  return fields;
}
