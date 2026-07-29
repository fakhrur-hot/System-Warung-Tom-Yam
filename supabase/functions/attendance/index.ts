/**
 * POST /api/attendance — ordering key (or admin for forced).
 * Handles CHECK_IN/CHECK_OUT with GPS radius validation.
 * Broadcasts on `admin-attendance` channel with device label.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const VALID_EVENTS = new Set(["CHECK_IN", "CHECK_OUT"]);
const EARTH_RADIUS_M = 6371000;

/**
 * Haversine distance between two lat/lon points in meters.
 */
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

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  // Auth: ordering key OR admin token (for forced check-out)
  const admin = await verifyAdminToken(req);
  const ordering = !admin ? await verifyOrderingKey(req) : null;

  if (!admin && !ordering) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or ordering key required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.event || !VALID_EVENTS.has(body.event)) {
    return errorResponse(422, "VALIDATION", "event must be CHECK_IN or CHECK_OUT");
  }

  if (typeof body.latitude !== "number" || typeof body.longitude !== "number") {
    return errorResponse(422, "VALIDATION", "latitude and longitude are required");
  }

  const forced = body.forced === true;
  const supabase = getSupabaseClient();

  // If not forced, validate GPS distance
  if (!forced) {
    const { data: cafeLocation, error: locError } = await supabase
      .from("cafe_location")
      .select("latitude, longitude, radius_meters")
      .limit(1)
      .single();

    if (locError || !cafeLocation) {
      return errorResponse(500, "SERVER_ERROR", "Café location not configured");
    }

    const distance = haversineDistance(
      body.latitude,
      body.longitude,
      cafeLocation.latitude,
      cafeLocation.longitude
    );

    if (distance > cafeLocation.radius_meters) {
      return errorResponse(403, "OUTSIDE_RADIUS", "You must be at the café to check in/out");
    }
  }

  // Determine device ID from the authenticated caller
  const deviceId = admin ? admin.id : ordering!.id;

  const now = new Date().toISOString();

  // Insert attendance record
  const { data: record, error: insertError } = await supabase
    .from("attendance")
    .insert({
      device_id: deviceId,
      event: body.event,
      latitude: body.latitude,
      longitude: body.longitude,
      forced,
      timestamp: now,
    })
    .select("*")
    .single();

  if (insertError) {
    return errorResponse(500, "SERVER_ERROR", insertError.message);
  }

  // Get device label for broadcast
  const { data: device } = await supabase
    .from("devices")
    .select("label")
    .eq("id", deviceId)
    .single();

  const deviceLabel = device?.label || "Unknown device";

  // Broadcast on admin-attendance
  try {
    const channel = supabase.channel("admin-attendance");
    await channel.send({
      type: "broadcast",
      event: body.event,
      payload: {
        type: body.event,
        deviceLabel,
        timestamp: now,
      },
    });
  } catch (_e) {
    // Non-critical
  }

  return jsonResponse({
    id: record.id,
    device_id: record.device_id,
    event: record.event,
    latitude: record.latitude,
    longitude: record.longitude,
    forced: record.forced,
    timestamp: record.timestamp,
  });
});
