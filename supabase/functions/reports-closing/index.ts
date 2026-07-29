/**
 * GET /api/reports/closing — admin/superadmin auth.
 * Builds + emails the closing report (Brevo); returns signed URL for download.
 * 409 if no aggregate row for today (café timezone).
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifySuperadminJwt } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  // Auth: admin or superadmin
  const admin = await verifyAdminToken(req);
  const superadmin = !admin ? await verifySuperadminJwt(req) : null;

  if (!admin && !superadmin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or superadmin auth required");
  }

  const supabase = getSupabaseClient();

  // Get timezone from settings
  const { data: tzSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "timezone")
    .single();

  const timezone = tzSetting?.value || "Asia/Kuala_Lumpur";

  // Business-day start hour (default 15 = 3 PM) so late-night cafés anchor the report to the
  // opening day, not the post-midnight calendar date.
  const { data: bdSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "business_day_start_hour")
    .single();
  const startHour = parseInt(bdSetting?.value ?? "15", 10);

  // The business-day date this report belongs to.
  const todayStr = getBusinessDayInTimezone(timezone, Number.isFinite(startHour) ? startHour : 15);

  // Fetch aggregate for today
  const { data: aggregate, error: aggError } = await supabase
    .from("aggregates")
    .select("*")
    .eq("date", todayStr)
    .single();

  if (aggError || !aggregate) {
    return errorResponse(409, "NO_AGGREGATE", "No aggregate data for today");
  }

  // Compute open hours for today
  const openHours = await computeOpenHoursForDate(supabase, todayStr);

  // Build HTML report
  const html = buildClosingReportHtml(aggregate, openHours, todayStr);

  // Upload to Supabase Storage
  const fileName = `closing-report-${todayStr}.html`;
  const { error: uploadError } = await supabase.storage
    .from("reports")
    .upload(fileName, html, {
      contentType: "text/html",
      upsert: true,
    });

  if (uploadError) {
    console.error("Storage upload error:", uploadError.message);
  }

  // Generate signed URL (60 min expiry)
  const { data: signedUrl } = await supabase.storage
    .from("reports")
    .createSignedUrl(fileName, 3600);

  const reportUrl = signedUrl?.signedUrl || "";

  // Send email via Brevo
  let emailSent = false;
  const { data: emailSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "report_email")
    .single();

  const reportEmail = emailSetting?.value;
  const brevoApiKey = Deno.env.get("BREVO_API_KEY");

  if (reportEmail && brevoApiKey) {
    try {
      const emailResp = await fetch("https://api.brevo.com/v3/smtp/email", {
        method: "POST",
        headers: {
          "api-key": brevoApiKey,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          sender: { email: reportEmail, name: "Warung Tom Yam" },
          to: [{ email: reportEmail }],
          subject: `Closing Report — ${todayStr}`,
          htmlContent: html,
        }),
      });
      emailSent = emailResp.ok;
    } catch (_e) {
      // Email send failed — non-critical
      console.error("Brevo email send failed");
    }
  }

  return jsonResponse({ reportUrl, emailSent, date: todayStr });
});

// ── Helpers ────────────────────────────────────────────────────────────────

/**
 * The current business-day date (YYYY-MM-DD) in [timezone]: if the local hour is before
 * [startHour], the business day is still yesterday's date (e.g. a 3 AM close with a 3 PM
 * start hour belongs to the previous calendar day).
 */
function getBusinessDayInTimezone(timezone: string, startHour: number): string {
  const nowStr = new Date().toLocaleString("en-US", { timeZone: timezone });
  const d = new Date(nowStr);
  if (d.getHours() < startHour) {
    d.setDate(d.getDate() - 1);
  }
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

// deno-lint-ignore no-explicit-any
async function computeOpenHoursForDate(supabase: any, dateStr: string): Promise<number> {
  const startISO = `${dateStr}T00:00:00.000Z`;
  const endISO = `${dateStr}T23:59:59.999Z`;

  const { data: openSessions } = await supabase
    .from("sessions")
    .select("*")
    .eq("event", "OPEN")
    .lte("timestamp", endISO)
    .order("timestamp", { ascending: true });

  if (!openSessions || openSessions.length === 0) return 0;

  const { data: closeSessions } = await supabase
    .from("sessions")
    .select("*")
    .eq("event", "CLOSE")
    .gte("timestamp", startISO)
    .order("timestamp", { ascending: true });

  let totalMs = 0;
  const periodStart = new Date(startISO).getTime();
  const periodEnd = new Date(endISO).getTime();

  for (const openSession of openSessions) {
    const openTime = new Date(openSession.timestamp).getTime();
    let closeTime: number | null = null;

    if (openSession.closed_at) {
      closeTime = new Date(openSession.closed_at).getTime();
    } else if (closeSessions) {
      for (const closeSession of closeSessions) {
        const ct = new Date(closeSession.timestamp).getTime();
        if (ct > openTime) {
          closeTime = ct;
          break;
        }
      }
    }

    if (closeTime === null) {
      closeTime = Math.min(Date.now(), periodEnd);
    }

    const effectiveStart = Math.max(openTime, periodStart);
    const effectiveEnd = Math.min(closeTime, periodEnd);

    if (effectiveEnd > effectiveStart) {
      totalMs += effectiveEnd - effectiveStart;
    }
  }

  return Math.round((totalMs / (1000 * 60 * 60)) * 10) / 10;
}

// deno-lint-ignore no-explicit-any
function buildClosingReportHtml(aggregate: any, openHours: number, date: string): string {
  const paymentSplit = aggregate.payment_split || {};
  const cashCount = paymentSplit.cash?.count || 0;
  const cashAmount = paymentSplit.cash?.amount || 0;
  const qrCount = paymentSplit.qr?.count || 0;
  const qrAmount = paymentSplit.qr?.amount || 0;

  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Closing Report — ${date}</title>
<style>
  body { font-family: sans-serif; margin: 2rem; }
  h1 { color: #333; }
  table { border-collapse: collapse; width: 100%; max-width: 500px; margin: 1rem 0; }
  th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
  th { background: #f5f5f5; }
  .total { font-weight: bold; }
</style>
</head>
<body>
<h1>Closing Report — ${date}</h1>
<table>
  <tr><th>Metric</th><th>Value</th></tr>
  <tr><td>Total Orders</td><td>${aggregate.total_orders}</td></tr>
  <tr><td>Total Revenue</td><td>RM ${Number(aggregate.total_revenue).toFixed(2)}</td></tr>
  <tr><td>Avg Order Value</td><td>RM ${Number(aggregate.avg_order_value).toFixed(2)}</td></tr>
  <tr><td>Cash Payments</td><td>${cashCount} (RM ${Number(cashAmount).toFixed(2)})</td></tr>
  <tr><td>QR Payments</td><td>${qrCount} (RM ${Number(qrAmount).toFixed(2)})</td></tr>
  <tr><td>Cancelled Orders</td><td>${aggregate.cancelled_count} (RM ${Number(aggregate.cancelled_value).toFixed(2)})</td></tr>
  <tr><td>Open Hours</td><td>${openHours}h</td></tr>
</table>
</body>
</html>`;
}
