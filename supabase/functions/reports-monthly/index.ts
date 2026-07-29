/**
 * GET /api/reports/monthly?month=YYYY-MM — superadmin auth.
 * Builds + emails the monthly report (Brevo); returns signed URL for download.
 * 422 if month format is invalid.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifySuperadminJwt } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  // Auth: superadmin JWT required
  const superadmin = await verifySuperadminJwt(req);
  if (!superadmin) {
    return errorResponse(401, "UNAUTHORIZED", "Superadmin JWT required");
  }

  const url = new URL(req.url);
  const month = url.searchParams.get("month");

  // Validate month format YYYY-MM
  if (!month || !/^\d{4}-\d{2}$/.test(month)) {
    return errorResponse(422, "VALIDATION", "month must be in YYYY-MM format");
  }

  // Parse and validate the month value
  const [yearStr, monthStr] = month.split("-");
  const year = parseInt(yearStr, 10);
  const monthNum = parseInt(monthStr, 10);
  if (monthNum < 1 || monthNum > 12 || year < 2000) {
    return errorResponse(422, "VALIDATION", "Invalid month value");
  }

  const supabase = getSupabaseClient();

  // Get date range for the month
  const startDate = `${year}-${String(monthNum).padStart(2, "0")}-01`;
  const lastDay = new Date(year, monthNum, 0).getDate();
  const endDate = `${year}-${String(monthNum).padStart(2, "0")}-${String(lastDay).padStart(2, "0")}`;

  // Fetch aggregates for the month
  const { data: aggregates } = await supabase
    .from("aggregates")
    .select("*")
    .gte("date", startDate)
    .lte("date", endDate)
    .order("date", { ascending: true });

  // Compute totals
  let totalOrders = 0;
  let totalRevenue = 0;
  let totalCancelled = 0;
  let totalCancelledValue = 0;
  let cashCount = 0;
  let cashAmount = 0;
  let qrCount = 0;
  let qrAmount = 0;

  if (aggregates) {
    for (const row of aggregates) {
      totalOrders += row.total_orders || 0;
      totalRevenue += row.total_revenue || 0;
      totalCancelled += row.cancelled_count || 0;
      totalCancelledValue += row.cancelled_value || 0;
      const split = row.payment_split || {};
      cashCount += split.cash?.count || 0;
      cashAmount += split.cash?.amount || 0;
      qrCount += split.qr?.count || 0;
      qrAmount += split.qr?.amount || 0;
    }
  }

  // Compute open hours for the month
  const openHours = await computeOpenHoursForRange(supabase, startDate, endDate);

  const avgOrderValue = totalOrders > 0 ? totalRevenue / totalOrders : 0;

  // Build HTML report
  const html = buildMonthlyReportHtml(
    month,
    totalOrders,
    totalRevenue,
    avgOrderValue,
    cashCount,
    cashAmount,
    qrCount,
    qrAmount,
    totalCancelled,
    totalCancelledValue,
    openHours,
    aggregates?.length || 0
  );

  // Upload to Supabase Storage
  const fileName = `monthly-report-${month}.html`;
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
          subject: `Monthly Report — ${month}`,
          htmlContent: html,
        }),
      });
      emailSent = emailResp.ok;
    } catch (_e) {
      console.error("Brevo email send failed");
    }
  }

  return jsonResponse({ reportUrl, emailSent, month });
});

// ── Helpers ────────────────────────────────────────────────────────────────

// deno-lint-ignore no-explicit-any
async function computeOpenHoursForRange(supabase: any, startDate: string, endDate: string): Promise<number> {
  const startISO = `${startDate}T00:00:00.000Z`;
  const endISO = `${endDate}T23:59:59.999Z`;

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

function buildMonthlyReportHtml(
  month: string,
  totalOrders: number,
  totalRevenue: number,
  avgOrderValue: number,
  cashCount: number,
  cashAmount: number,
  qrCount: number,
  qrAmount: number,
  cancelledCount: number,
  cancelledValue: number,
  openHours: number,
  daysActive: number
): string {
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Monthly Report — ${month}</title>
<style>
  body { font-family: sans-serif; margin: 2rem; }
  h1 { color: #333; }
  table { border-collapse: collapse; width: 100%; max-width: 500px; margin: 1rem 0; }
  th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
  th { background: #f5f5f5; }
</style>
</head>
<body>
<h1>Monthly Report — ${month}</h1>
<table>
  <tr><th>Metric</th><th>Value</th></tr>
  <tr><td>Days Active</td><td>${daysActive}</td></tr>
  <tr><td>Total Orders</td><td>${totalOrders}</td></tr>
  <tr><td>Total Revenue</td><td>RM ${totalRevenue.toFixed(2)}</td></tr>
  <tr><td>Avg Order Value</td><td>RM ${avgOrderValue.toFixed(2)}</td></tr>
  <tr><td>Cash Payments</td><td>${cashCount} (RM ${cashAmount.toFixed(2)})</td></tr>
  <tr><td>QR Payments</td><td>${qrCount} (RM ${qrAmount.toFixed(2)})</td></tr>
  <tr><td>Cancelled Orders</td><td>${cancelledCount} (RM ${cancelledValue.toFixed(2)})</td></tr>
  <tr><td>Open Hours (Total)</td><td>${openHours}h</td></tr>
</table>
</body>
</html>`;
}
