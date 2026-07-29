/**
 * GET /api/metrics?period=today|week|month|last_month|monthly — superadmin JWT auth.
 * Computes orders, revenue, and openHours from aggregates + sessions.
 * All date boundaries use the café timezone from settings.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifySuperadminJwt } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const VALID_PERIODS = new Set(["today", "week", "month", "last_month", "monthly"]);

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
  const period = url.searchParams.get("period") || "today";

  if (!VALID_PERIODS.has(period)) {
    return errorResponse(422, "VALIDATION", "period must be today, week, month, last_month, or monthly");
  }

  const supabase = getSupabaseClient();

  // Fetch timezone from settings
  const { data: tzSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "timezone")
    .single();

  const timezone = tzSetting?.value || "Asia/Kuala_Lumpur";

  if (period === "monthly") {
    return handleMonthlyMetrics(supabase, timezone);
  }

  // Get date range for the period in the café timezone
  const { startDate, endDate } = getDateRange(period, timezone);

  // Query aggregates for orders and revenue
  const { data: aggregates } = await supabase
    .from("aggregates")
    .select("total_orders, total_revenue")
    .gte("date", startDate)
    .lte("date", endDate);

  let orders = 0;
  let revenue = 0;
  if (aggregates) {
    for (const row of aggregates) {
      orders += row.total_orders || 0;
      revenue += row.total_revenue || 0;
    }
  }

  // Query sessions for open hours
  const openHours = await computeOpenHours(supabase, startDate, endDate);

  return jsonResponse({ orders, revenue, openHours });
});

/**
 * Handle the "monthly" period — returns an array of 12 months.
 */
// deno-lint-ignore no-explicit-any
async function handleMonthlyMetrics(supabase: any, timezone: string): Promise<Response> {
  const now = getNowInTimezone(timezone);
  const results: Array<{ month: string; orders: number; revenue: number; openHours: number }> = [];

  for (let i = 0; i < 12; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    const year = d.getFullYear();
    const month = d.getMonth(); // 0-based
    const monthStr = `${year}-${String(month + 1).padStart(2, "0")}`;

    const startDate = `${year}-${String(month + 1).padStart(2, "0")}-01`;
    const lastDay = new Date(year, month + 1, 0).getDate();
    const endDate = `${year}-${String(month + 1).padStart(2, "0")}-${String(lastDay).padStart(2, "0")}`;

    const { data: aggregates } = await supabase
      .from("aggregates")
      .select("total_orders, total_revenue")
      .gte("date", startDate)
      .lte("date", endDate);

    let orders = 0;
    let revenue = 0;
    if (aggregates) {
      for (const row of aggregates) {
        orders += row.total_orders || 0;
        revenue += row.total_revenue || 0;
      }
    }

    const openHours = await computeOpenHours(supabase, startDate, endDate);

    results.push({ month: monthStr, orders, revenue, openHours });
  }

  return jsonResponse(results);
}

/**
 * Compute open hours from sessions within a date range.
 * Sum durations between OPEN and CLOSE (or implicit close via closed_at).
 */
// deno-lint-ignore no-explicit-any
async function computeOpenHours(supabase: any, startDate: string, endDate: string): Promise<number> {
  // Get all OPEN sessions that overlap the period
  const startISO = `${startDate}T00:00:00.000Z`;
  const endISO = `${endDate}T23:59:59.999Z`;

  const { data: openSessions } = await supabase
    .from("sessions")
    .select("*")
    .eq("event", "OPEN")
    .lte("timestamp", endISO)
    .order("timestamp", { ascending: true });

  if (!openSessions || openSessions.length === 0) return 0;

  // Get all CLOSE sessions in the period for matching
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

    // Find the close time: either closed_at (implicit) or the next CLOSE event
    let closeTime: number | null = null;

    if (openSession.closed_at) {
      // Implicitly closed (dangling)
      closeTime = new Date(openSession.closed_at).getTime();
    } else {
      // Find the next CLOSE event after this OPEN
      if (closeSessions) {
        for (const closeSession of closeSessions) {
          const ct = new Date(closeSession.timestamp).getTime();
          if (ct > openTime) {
            closeTime = ct;
            break;
          }
        }
      }
    }

    // If no close found, the session is still open — use current time or period end
    if (closeTime === null) {
      closeTime = Math.min(Date.now(), periodEnd);
    }

    // Clamp to period boundaries
    const effectiveStart = Math.max(openTime, periodStart);
    const effectiveEnd = Math.min(closeTime, periodEnd);

    if (effectiveEnd > effectiveStart) {
      totalMs += effectiveEnd - effectiveStart;
    }
  }

  // Convert ms to hours, rounded to 1 decimal
  return Math.round((totalMs / (1000 * 60 * 60)) * 10) / 10;
}

/**
 * Get date range (YYYY-MM-DD) for a period in the café timezone.
 */
function getDateRange(period: string, timezone: string): { startDate: string; endDate: string } {
  const now = getNowInTimezone(timezone);
  const year = now.getFullYear();
  const month = now.getMonth();
  const day = now.getDate();
  const dayOfWeek = now.getDay(); // 0=Sun

  switch (period) {
    case "today": {
      const dateStr = formatDate(year, month + 1, day);
      return { startDate: dateStr, endDate: dateStr };
    }
    case "week": {
      // Monday-based week
      const mondayOffset = dayOfWeek === 0 ? 6 : dayOfWeek - 1;
      const monday = new Date(year, month, day - mondayOffset);
      const sunday = new Date(year, month, day + (6 - mondayOffset));
      return {
        startDate: formatDate(monday.getFullYear(), monday.getMonth() + 1, monday.getDate()),
        endDate: formatDate(sunday.getFullYear(), sunday.getMonth() + 1, sunday.getDate()),
      };
    }
    case "month": {
      const lastDay = new Date(year, month + 1, 0).getDate();
      return {
        startDate: formatDate(year, month + 1, 1),
        endDate: formatDate(year, month + 1, lastDay),
      };
    }
    case "last_month": {
      const prevMonth = month === 0 ? 11 : month - 1;
      const prevYear = month === 0 ? year - 1 : year;
      const lastDay = new Date(prevYear, prevMonth + 1, 0).getDate();
      return {
        startDate: formatDate(prevYear, prevMonth + 1, 1),
        endDate: formatDate(prevYear, prevMonth + 1, lastDay),
      };
    }
    default:
      return { startDate: formatDate(year, month + 1, day), endDate: formatDate(year, month + 1, day) };
  }
}

/**
 * Get the current time as a Date object in the specified timezone.
 */
function getNowInTimezone(timezone: string): Date {
  const nowStr = new Date().toLocaleString("en-US", { timeZone: timezone });
  return new Date(nowStr);
}

function formatDate(year: number, month: number, day: number): string {
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}
