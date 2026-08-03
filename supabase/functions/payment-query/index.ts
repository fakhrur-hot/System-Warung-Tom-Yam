/**
 * POST /functions/v1/payment-query — poll a gateway transaction's status. (PG-REQ-4a, task 6.2, 8.2)
 *
 * The persisted `gateway_transactions` row — written by payment-callback the moment a callback
 * lands — is authoritative once written. Requery against the acquirer is only meaningful same-day:
 * it is documented as returning nothing after 24 hours (F5), so this function requeries only while
 * still PENDING and within that window, and otherwise just answers from the stored row.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";
import { FIUU_HOSTS, mapFiuuStatus, parseRequeryResponse, requerySignature } from "../_shared/fiuu.ts";

const REQUERY_WINDOW_MS = 24 * 60 * 60 * 1000;

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
  const transactionId: string | undefined = body?.transactionId;
  if (!transactionId) {
    return errorResponse(422, "VALIDATION", "transactionId is required");
  }

  const supabase = getSupabaseClient();
  const { data: txn, error } = await supabase
    .from("gateway_transactions")
    .select("id, status, amount_sen, created_at")
    .eq("id", transactionId)
    .maybeSingle();

  if (error || !txn) {
    return errorResponse(404, "NOT_FOUND", "Transaction not found");
  }

  // Already terminal — the persisted row is authoritative, no need to ask the acquirer again.
  if (txn.status !== "PENDING") {
    return jsonResponse({ success: true, transactionId: txn.id, status: txn.status });
  }

  const ageMs = Date.now() - new Date(txn.created_at).getTime();
  const { data: config } = await supabase
    .from("gateway_config")
    .select("merchant_id, verify_key")
    .eq("id", 1)
    .maybeSingle();

  if (!config?.merchant_id || !config?.verify_key || ageMs > REQUERY_WINDOW_MS) {
    // Outside the requery window, or nothing to requery with. Still pending as far as we know —
    // the caller's own polling timeout (8.2) is what eventually gives up, not this endpoint.
    return jsonResponse({ success: true, transactionId: txn.id, status: "PENDING" });
  }

  try {
    const amountRinggit = (txn.amount_sen / 100).toFixed(2);
    const skey = requerySignature(txn.id, config.merchant_id, config.verify_key, amountRinggit);
    const requeryUrl =
      `${FIUU_HOSTS.requery}?merchantID=${encodeURIComponent(config.merchant_id)}` +
      `&txID=${encodeURIComponent(txn.id)}&amount=${amountRinggit}&skey=${skey}`;

    const resp = await fetch(requeryUrl);
    const text = await resp.text();
    const mapped = mapFiuuStatus(parseRequeryResponse(text));

    if (mapped !== "PENDING") {
      await supabase
        .from("gateway_transactions")
        .update({
          status: mapped,
          settled_at: new Date().toISOString(),
          gateway_response_json: { requery: text },
        })
        .eq("id", txn.id);
    }

    return jsonResponse({ success: true, transactionId: txn.id, status: mapped });
  } catch (e) {
    // A network hiccup reaching the acquirer is not the same thing as a failed payment.
    console.error("Requery failed:", e instanceof Error ? e.message : e);
    return jsonResponse({ success: true, transactionId: txn.id, status: "PENDING" });
  }
});
