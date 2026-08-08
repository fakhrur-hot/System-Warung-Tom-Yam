/**
 * POST /api/payment-alerts — an admin device forwards a captured bank/e-wallet notification.
 * GET  /api/payment-alerts?since=<iso> — the Main Admin drains what has been forwarded.
 *
 * ## Why this exists
 *
 * The phone that receives the café's payment notifications is frequently not the till. It is the
 * owner's own handset, running as a Secondary Admin. The till is the device that holds the printer
 * and does the order matching, so a capture has to travel from one to the other.
 *
 * ## Why POST is open to both admin roles but GET is not
 *
 * Anyone holding the banking app may forward — that is the whole point, and the Main Admin can
 * capture locally too, so it may also POST.
 *
 * Draining is different. There is exactly one `role='ADMIN'` device per café and it is the one that
 * matches payments to orders. If a Secondary Admin could drain the queue it would advance its own
 * cursor over rows the till has not seen, and those payments would simply never be matched — the
 * customer would be recorded as unpaid with nothing anywhere reporting a failure. So GET is refused
 * to anything that is not the Main Admin, deliberately and with a distinct 403 rather than an empty
 * list, so a misconfigured device says so instead of looking idle.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "POST") return handlePost(req);
  if (req.method === "GET") return handleGet(req);

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and POST are supported");
});

// ── POST /api/payment-alerts ────────────────────────────────────────────────
async function handlePost(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body) {
    return errorResponse(422, "VALIDATION", "Body must be a JSON object");
  }

  const { clientId, amountSen, walletApp, sender, rawText, capturedAt } = body;

  if (typeof clientId !== "string" || clientId.length === 0) {
    return errorResponse(422, "VALIDATION", "clientId must be a non-empty string");
  }
  // Integer sen only. A float here would be a rounding error in somebody's takings.
  if (typeof amountSen !== "number" || !Number.isInteger(amountSen) || amountSen < 0) {
    return errorResponse(422, "VALIDATION", "amountSen must be a non-negative integer");
  }
  if (typeof walletApp !== "string" || walletApp.length === 0) {
    return errorResponse(422, "VALIDATION", "walletApp must be a non-empty string");
  }
  if (typeof rawText !== "string") {
    return errorResponse(422, "VALIDATION", "rawText must be a string");
  }
  if (typeof capturedAt !== "string" || Number.isNaN(Date.parse(capturedAt))) {
    return errorResponse(422, "VALIDATION", "capturedAt must be an ISO-8601 timestamp");
  }

  const supabase = getSupabaseClient();

  // Upsert on client_id: the caller is a notification listener firing off a request it does not
  // wait on, so a retry after a dropped response is normal. Re-POSTing the same capture must not
  // create a second row the till would then match twice.
  const { error } = await supabase
    .from("payment_alerts")
    .upsert(
      {
        client_id: clientId,
        amount_sen: amountSen,
        wallet_app: walletApp,
        sender: typeof sender === "string" && sender.length > 0 ? sender : null,
        raw_text: rawText,
        captured_at: capturedAt,
        source_device_id: admin.id,
      },
      { onConflict: "client_id", ignoreDuplicates: true },
    );

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  return jsonResponse({ accepted: true });
}

// ── GET /api/payment-alerts?since=<iso> ─────────────────────────────────────
async function handleGet(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }
  if (admin.role !== "ADMIN") {
    return errorResponse(403, "FORBIDDEN", "Only the main admin device drains payment alerts");
  }

  const url = new URL(req.url);
  const since = url.searchParams.get("since");
  if (!since || Number.isNaN(Date.parse(since))) {
    return errorResponse(422, "VALIDATION", "since must be an ISO-8601 timestamp");
  }

  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("payment_alerts")
    .select("client_id, amount_sen, wallet_app, sender, raw_text, captured_at, created_at")
    .gt("created_at", since)
    .order("created_at", { ascending: true })
    .limit(100);

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  const alerts = (data ?? []).map((row) => ({
    clientId: row.client_id,
    amountSen: row.amount_sen,
    walletApp: row.wallet_app,
    sender: row.sender ?? "",
    rawText: row.raw_text,
    capturedAt: row.captured_at,
    createdAt: row.created_at,
  }));

  // serverTime is the caller's next cursor, taken from the server's clock rather than the device's
  // — a till whose clock runs fast would otherwise skip alerts written in the gap. Same contract as
  // the orders catch-up poll.
  return jsonResponse({ alerts, serverTime: new Date().toISOString() });
}
