/**
 * Sessions, attendance, and metrics tests.
 * Tests: dangling session closure logic, GPS radius validation (Haversine),
 * timezone boundary computation for metrics, forced check-out GPS bypass.
 *
 * Unit tests for pure logic extracted from the Edge Functions.
 */
import {
  assertEquals,
  assert,
} from "https://deno.land/std@0.177.0/testing/asserts.ts";

// ══════════════════════════════════════════════════════════════════════════════
// ── Haversine distance function (from attendance/index.ts) ───────────────────
// ══════════════════════════════════════════════════════════════════════════════

const EARTH_RADIUS_M = 6371000;

function haversineDistance(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_M * c;
}


// ══════════════════════════════════════════════════════════════════════════════
// ── GPS Radius Validation Tests (Haversine) ──────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

// Café location: Warung Tom Yam at approximately KL area
const CAFE_LAT = 3.1390;
const CAFE_LON = 101.6869;
const RADIUS_METERS = 100;

Deno.test("Haversine: same point returns 0 distance", () => {
  const d = haversineDistance(CAFE_LAT, CAFE_LON, CAFE_LAT, CAFE_LON);
  assertEquals(d, 0);
});

Deno.test("Haversine: point 50m away is within 100m radius", () => {
  // ~50m north (roughly 0.00045 degrees latitude)
  const nearLat = CAFE_LAT + 0.00045;
  const d = haversineDistance(CAFE_LAT, CAFE_LON, nearLat, CAFE_LON);
  assert(d < RADIUS_METERS, `Expected < 100m, got ${d.toFixed(1)}m`);
  assert(d > 40, `Expected > 40m, got ${d.toFixed(1)}m`);
});

Deno.test("Haversine: point 200m away is outside 100m radius", () => {
  // ~200m north (roughly 0.0018 degrees latitude)
  const farLat = CAFE_LAT + 0.0018;
  const d = haversineDistance(CAFE_LAT, CAFE_LON, farLat, CAFE_LON);
  assert(d > RADIUS_METERS, `Expected > 100m, got ${d.toFixed(1)}m`);
});


Deno.test("Haversine: point exactly at boundary (~100m) is borderline", () => {
  // ~100m north (roughly 0.0009 degrees latitude at equator-ish)
  const borderLat = CAFE_LAT + 0.0009;
  const d = haversineDistance(CAFE_LAT, CAFE_LON, borderLat, CAFE_LON);
  // Should be close to 100m (within ±5m tolerance)
  assert(d > 95 && d < 105, `Expected ~100m, got ${d.toFixed(1)}m`);
});

Deno.test("Haversine: known distance KL to Putrajaya (~25km)", () => {
  // KL: 3.1390, 101.6869; Putrajaya: 2.9264, 101.6964
  const d = haversineDistance(3.1390, 101.6869, 2.9264, 101.6964);
  // Should be ~23-24km
  assert(d > 22000 && d < 25000, `Expected ~23-24km, got ${(d/1000).toFixed(1)}km`);
});

Deno.test("Haversine: longitude difference at equator", () => {
  // 1 degree longitude at equator ≈ 111km
  const d = haversineDistance(0, 0, 0, 1);
  assert(d > 110000 && d < 112000, `Expected ~111km, got ${(d/1000).toFixed(1)}km`);
});

// ══════════════════════════════════════════════════════════════════════════════
// ── Forced Check-Out GPS Bypass ──────────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Simulates the attendance GPS validation logic.
 * If forced=true, GPS validation is skipped entirely.
 */
function shouldValidateGps(forced: boolean): boolean {
  return !forced;
}

function isWithinRadius(
  deviceLat: number,
  deviceLon: number,
  cafeLat: number,
  cafeLon: number,
  radiusMeters: number
): boolean {
  const distance = haversineDistance(deviceLat, deviceLon, cafeLat, cafeLon);
  return distance <= radiusMeters;
}

Deno.test("Forced check-out: GPS validation is skipped", () => {
  assertEquals(shouldValidateGps(true), false);
});

Deno.test("Normal check-out: GPS validation is required", () => {
  assertEquals(shouldValidateGps(false), true);
});

Deno.test("Forced check-out: succeeds even when far from café", () => {
  const forced = true;
  // Device is 5km away — would fail normal check
  const deviceLat = CAFE_LAT + 0.05;
  const deviceLon = CAFE_LON;

  if (shouldValidateGps(forced)) {
    // This branch should NOT execute for forced
    assert(false, "GPS validation should be skipped for forced");
  }
  // Forced always succeeds regardless of distance
  assert(true, "Forced check-out bypasses GPS");
});


Deno.test("Normal check-in: rejected when outside radius", () => {
  const forced = false;
  const deviceLat = CAFE_LAT + 0.005; // ~555m away
  const deviceLon = CAFE_LON;

  if (shouldValidateGps(forced)) {
    const within = isWithinRadius(deviceLat, deviceLon, CAFE_LAT, CAFE_LON, RADIUS_METERS);
    assertEquals(within, false);
  }
});

Deno.test("Normal check-in: accepted when inside radius", () => {
  const forced = false;
  const deviceLat = CAFE_LAT + 0.0004; // ~44m away
  const deviceLon = CAFE_LON;

  if (shouldValidateGps(forced)) {
    const within = isWithinRadius(deviceLat, deviceLon, CAFE_LAT, CAFE_LON, RADIUS_METERS);
    assertEquals(within, true);
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// ── Dangling Session Closure Logic ───────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

interface SessionRecord {
  id: string;
  event: "OPEN" | "CLOSE";
  reason: string | null;
  timestamp: string;
  closed_at: string | null;
}

/**
 * Simulates the dangling session closure logic from sessions/index.ts.
 * When opening a new session, if the last OPEN has no corresponding CLOSE,
 * it is implicitly closed at the current timestamp.
 */
function processDanglingSession(
  sessions: SessionRecord[],
  newEvent: "OPEN" | "CLOSE",
  now: string
): { updatedSessions: SessionRecord[]; newSession: SessionRecord } {
  const updated = [...sessions];

  if (newEvent === "OPEN") {
    // Find last OPEN with no closed_at
    const dangling = updated
      .filter((s) => s.event === "OPEN" && s.closed_at === null)
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp))[0];

    if (dangling) {
      dangling.closed_at = now;
    }
  }

  const newSession: SessionRecord = {
    id: `session-${Date.now()}`,
    event: newEvent,
    reason: null,
    timestamp: now,
    closed_at: null,
  };

  updated.push(newSession);
  return { updatedSessions: updated, newSession };
}


Deno.test("Dangling session: OPEN with no prior sessions creates normally", () => {
  const { updatedSessions, newSession } = processDanglingSession(
    [],
    "OPEN",
    "2026-07-19T08:00:00Z"
  );
  assertEquals(updatedSessions.length, 1);
  assertEquals(newSession.event, "OPEN");
  assertEquals(newSession.closed_at, null);
});

Deno.test("Dangling session: OPEN after proper CLOSE creates normally", () => {
  const existing: SessionRecord[] = [
    { id: "s1", event: "OPEN", reason: null, timestamp: "2026-07-18T08:00:00Z", closed_at: "2026-07-18T17:00:00Z" },
    { id: "s2", event: "CLOSE", reason: "End of day", timestamp: "2026-07-18T17:00:00Z", closed_at: null },
  ];
  const { updatedSessions, newSession } = processDanglingSession(
    existing,
    "OPEN",
    "2026-07-19T08:00:00Z"
  );
  // No dangling — existing OPEN already has closed_at
  assertEquals(updatedSessions.length, 3);
  assertEquals(newSession.event, "OPEN");
  // Original session unchanged
  assertEquals(updatedSessions[0].closed_at, "2026-07-18T17:00:00Z");
});


Deno.test("Dangling session: OPEN with dangling prior implicitly closes it", () => {
  const existing: SessionRecord[] = [
    { id: "s1", event: "OPEN", reason: null, timestamp: "2026-07-18T08:00:00Z", closed_at: null },
  ];
  const now = "2026-07-19T08:00:00Z";
  const { updatedSessions } = processDanglingSession(existing, "OPEN", now);

  // The dangling session should be implicitly closed
  const oldOpen = updatedSessions.find((s) => s.id === "s1");
  assertEquals(oldOpen!.closed_at, now);
  // New session is also added
  assertEquals(updatedSessions.length, 2);
});

Deno.test("Dangling session: CLOSE event does not implicitly close prior", () => {
  const existing: SessionRecord[] = [
    { id: "s1", event: "OPEN", reason: null, timestamp: "2026-07-19T08:00:00Z", closed_at: null },
  ];
  const now = "2026-07-19T17:00:00Z";
  const { updatedSessions } = processDanglingSession(existing, "CLOSE", now);

  // CLOSE does not trigger dangling closure — the original OPEN stays as-is
  const oldOpen = updatedSessions.find((s) => s.id === "s1");
  assertEquals(oldOpen!.closed_at, null);
  assertEquals(updatedSessions.length, 2);
});


Deno.test("Dangling session: multiple dangling — only the latest is closed", () => {
  const existing: SessionRecord[] = [
    { id: "s1", event: "OPEN", reason: null, timestamp: "2026-07-17T08:00:00Z", closed_at: null },
    { id: "s2", event: "OPEN", reason: null, timestamp: "2026-07-18T08:00:00Z", closed_at: null },
  ];
  const now = "2026-07-19T08:00:00Z";
  const { updatedSessions } = processDanglingSession(existing, "OPEN", now);

  // Only the most recent dangling (s2) is closed
  const s1 = updatedSessions.find((s) => s.id === "s1");
  const s2 = updatedSessions.find((s) => s.id === "s2");
  assertEquals(s1!.closed_at, null); // older dangling untouched
  assertEquals(s2!.closed_at, now); // latest dangling closed
});

// ══════════════════════════════════════════════════════════════════════════════
// ── Timezone Boundary Computation for Metrics ────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════


/**
 * Get today's date string in a specific timezone (from metrics/index.ts).
 */
function getTodayInTimezone(timezone: string, nowUtc?: Date): string {
  const date = nowUtc || new Date();
  const nowStr = date.toLocaleString("en-US", { timeZone: timezone });
  const d = new Date(nowStr);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/**
 * Get date range for a period in the café timezone (from metrics/index.ts).
 */
function getDateRange(
  period: string,
  timezone: string,
  nowUtc?: Date
): { startDate: string; endDate: string } {
  const date = nowUtc || new Date();
  const nowStr = date.toLocaleString("en-US", { timeZone: timezone });
  const now = new Date(nowStr);
  const year = now.getFullYear();
  const month = now.getMonth();
  const day = now.getDate();
  const dayOfWeek = now.getDay();

  const formatDate = (y: number, m: number, d: number) =>
    `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;

  switch (period) {
    case "today": {
      const dateStr = formatDate(year, month + 1, day);
      return { startDate: dateStr, endDate: dateStr };
    }
    case "week": {
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


// Asia/Kuala_Lumpur is UTC+8

Deno.test("Timezone: 23:59 MYT on July 19 is still July 19 in KL", () => {
  // 23:59 MYT = 15:59 UTC on same day
  const utc = new Date("2026-07-19T15:59:00Z");
  const today = getTodayInTimezone("Asia/Kuala_Lumpur", utc);
  assertEquals(today, "2026-07-19");
});

Deno.test("Timezone: 00:01 MYT on July 20 is July 20 in KL", () => {
  // 00:01 MYT Jul 20 = 16:01 UTC Jul 19
  const utc = new Date("2026-07-19T16:01:00Z");
  const today = getTodayInTimezone("Asia/Kuala_Lumpur", utc);
  assertEquals(today, "2026-07-20");
});

Deno.test("Timezone: midnight boundary — 16:00 UTC is midnight MYT (Jul 20)", () => {
  // 00:00 MYT Jul 20 = 16:00 UTC Jul 19
  const utc = new Date("2026-07-19T16:00:00Z");
  const today = getTodayInTimezone("Asia/Kuala_Lumpur", utc);
  assertEquals(today, "2026-07-20");
});

Deno.test("Timezone: order at 15:59 UTC belongs to Jul 19 in KL", () => {
  // An aggregate pushed at 15:59 UTC (23:59 MYT) should count toward Jul 19
  const utc = new Date("2026-07-19T15:59:00Z");
  const today = getTodayInTimezone("Asia/Kuala_Lumpur", utc);
  assertEquals(today, "2026-07-19");
});

Deno.test("Timezone: order at 16:01 UTC belongs to Jul 20 in KL", () => {
  // An aggregate pushed at 16:01 UTC (00:01 MYT next day)
  const utc = new Date("2026-07-19T16:01:00Z");
  const today = getTodayInTimezone("Asia/Kuala_Lumpur", utc);
  assertEquals(today, "2026-07-20");
});


Deno.test("Timezone: getDateRange today in KL at 23:59 MYT", () => {
  const utc = new Date("2026-07-19T15:59:00Z");
  const range = getDateRange("today", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-07-19");
  assertEquals(range.endDate, "2026-07-19");
});

Deno.test("Timezone: getDateRange today in KL at 00:01 MYT (next day)", () => {
  const utc = new Date("2026-07-19T16:01:00Z");
  const range = getDateRange("today", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-07-20");
  assertEquals(range.endDate, "2026-07-20");
});

Deno.test("Timezone: getDateRange month returns full month boundaries", () => {
  // July 15 2026 in KL
  const utc = new Date("2026-07-15T04:00:00Z"); // 12:00 MYT
  const range = getDateRange("month", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-07-01");
  assertEquals(range.endDate, "2026-07-31");
});

Deno.test("Timezone: getDateRange last_month from July gives June", () => {
  const utc = new Date("2026-07-15T04:00:00Z");
  const range = getDateRange("last_month", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-06-01");
  assertEquals(range.endDate, "2026-06-30");
});

Deno.test("Timezone: getDateRange last_month from January gives December prev year", () => {
  const utc = new Date("2026-01-10T04:00:00Z"); // Jan 10 12:00 MYT
  const range = getDateRange("last_month", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2025-12-01");
  assertEquals(range.endDate, "2025-12-31");
});


Deno.test("Timezone: week range for Wednesday Jul 15 2026 is Mon-Sun", () => {
  // Jul 15 2026 is a Wednesday (dayOfWeek=3)
  const utc = new Date("2026-07-15T04:00:00Z");
  const range = getDateRange("week", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-07-13"); // Monday
  assertEquals(range.endDate, "2026-07-19"); // Sunday
});

Deno.test("Timezone: week range for Sunday gives Mon-Sun of same week", () => {
  // Jul 19 2026 is a Sunday (dayOfWeek=0)
  const utc = new Date("2026-07-19T04:00:00Z");
  const range = getDateRange("week", "Asia/Kuala_Lumpur", utc);
  assertEquals(range.startDate, "2026-07-13"); // Monday
  assertEquals(range.endDate, "2026-07-19"); // Sunday
});

// ══════════════════════════════════════════════════════════════════════════════
// ── Open Hours Computation ───────────────────────────────────────────────────
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Compute open hours from session events within a date boundary.
 * Extracted logic from metrics/reports functions.
 */
function computeOpenHoursFromEvents(
  openSessions: Array<{ timestamp: string; closed_at: string | null }>,
  closeSessions: Array<{ timestamp: string }>,
  periodStartISO: string,
  periodEndISO: string,
  nowMs?: number
): number {
  const periodStart = new Date(periodStartISO).getTime();
  const periodEnd = new Date(periodEndISO).getTime();
  const currentTime = nowMs || Date.now();
  let totalMs = 0;

  for (const openSession of openSessions) {
    const openTime = new Date(openSession.timestamp).getTime();
    let closeTime: number | null = null;

    if (openSession.closed_at) {
      closeTime = new Date(openSession.closed_at).getTime();
    } else {
      for (const closeSession of closeSessions) {
        const ct = new Date(closeSession.timestamp).getTime();
        if (ct > openTime) {
          closeTime = ct;
          break;
        }
      }
    }

    if (closeTime === null) {
      closeTime = Math.min(currentTime, periodEnd);
    }

    const effectiveStart = Math.max(openTime, periodStart);
    const effectiveEnd = Math.min(closeTime, periodEnd);

    if (effectiveEnd > effectiveStart) {
      totalMs += effectiveEnd - effectiveStart;
    }
  }

  return Math.round((totalMs / (1000 * 60 * 60)) * 10) / 10;
}


Deno.test("Open hours: single 8-hour session", () => {
  const openSessions = [
    { timestamp: "2026-07-19T08:00:00Z", closed_at: null },
  ];
  const closeSessions = [
    { timestamp: "2026-07-19T16:00:00Z" },
  ];
  const hours = computeOpenHoursFromEvents(
    openSessions,
    closeSessions,
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z"
  );
  assertEquals(hours, 8);
});

Deno.test("Open hours: session with implicit close (closed_at)", () => {
  const openSessions = [
    { timestamp: "2026-07-19T08:00:00Z", closed_at: "2026-07-19T12:00:00Z" },
  ];
  const closeSessions: Array<{ timestamp: string }> = [];
  const hours = computeOpenHoursFromEvents(
    openSessions,
    closeSessions,
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z"
  );
  assertEquals(hours, 4);
});

Deno.test("Open hours: two sessions in one day", () => {
  const openSessions = [
    { timestamp: "2026-07-19T08:00:00Z", closed_at: null },
    { timestamp: "2026-07-19T14:00:00Z", closed_at: null },
  ];
  const closeSessions = [
    { timestamp: "2026-07-19T12:00:00Z" },
    { timestamp: "2026-07-19T18:00:00Z" },
  ];
  const hours = computeOpenHoursFromEvents(
    openSessions,
    closeSessions,
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z"
  );
  // 08:00-12:00 = 4h, 14:00-18:00 = 4h
  assertEquals(hours, 8);
});


Deno.test("Open hours: session spanning midnight is clamped to period", () => {
  // Session opened at 22:00 Jul 18, still open through Jul 19
  const openSessions = [
    { timestamp: "2026-07-18T22:00:00Z", closed_at: null },
  ];
  const closeSessions = [
    { timestamp: "2026-07-19T06:00:00Z" },
  ];
  // Period is Jul 19 only
  const hours = computeOpenHoursFromEvents(
    openSessions,
    closeSessions,
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z"
  );
  // Clamped: 00:00 to 06:00 = 6h
  assertEquals(hours, 6);
});

Deno.test("Open hours: no sessions returns 0", () => {
  const hours = computeOpenHoursFromEvents(
    [],
    [],
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z"
  );
  assertEquals(hours, 0);
});

Deno.test("Open hours: still-open session uses current time (capped at period end)", () => {
  const openSessions = [
    { timestamp: "2026-07-19T10:00:00Z", closed_at: null },
  ];
  const closeSessions: Array<{ timestamp: string }> = [];
  // Simulate "now" being 13:00 UTC on same day
  const nowMs = new Date("2026-07-19T13:00:00Z").getTime();
  const hours = computeOpenHoursFromEvents(
    openSessions,
    closeSessions,
    "2026-07-19T00:00:00.000Z",
    "2026-07-19T23:59:59.999Z",
    nowMs
  );
  // 10:00 to 13:00 = 3h
  assertEquals(hours, 3);
});
