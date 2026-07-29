/**
 * POST /api/aggregates — admin token auth.
 * Upserts a daily summary row keyed on date.
 * Stores totalOrders, totalRevenue, avgOrderValue, paymentSplit,
 * cancelledCount, cancelledValue, topItemsPerCategory.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  // Auth: admin token required
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.date) {
    return errorResponse(422, "VALIDATION", "date is required");
  }

  // Validate date format YYYY-MM-DD
  if (!/^\d{4}-\d{2}-\d{2}$/.test(body.date)) {
    return errorResponse(422, "VALIDATION", "date must be in YYYY-MM-DD format");
  }

  const supabase = getSupabaseClient();

  // Upsert into aggregates table keyed on date
  const { error: upsertError } = await supabase
    .from("aggregates")
    .upsert(
      {
        date: body.date,
        total_orders: body.totalOrders ?? 0,
        total_revenue: body.totalRevenue ?? 0,
        avg_order_value: body.avgOrderValue ?? 0,
        payment_split_json: body.paymentSplit ?? {},
        cancelled_count: body.cancelledCount ?? 0,
        cancelled_value: body.cancelledValue ?? 0,
        top_items_json: body.topItemsPerCategory ?? {},
      },
      { onConflict: "date" }
    );

  if (upsertError) {
    return errorResponse(500, "SERVER_ERROR", upsertError.message);
  }

  return jsonResponse({ date: body.date });
});
