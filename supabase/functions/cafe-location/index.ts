/**
 * PUT /api/cafe-location — admin: set GPS-lock coordinates and radius.
 * GET /api/cafe-location — ordering device: read configured location.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "PUT") {
    return handlePutCafeLocation(req);
  }
  if (req.method === "GET") {
    return handleGetCafeLocation(req);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// ── PUT /api/cafe-location ─────────────────────────────────────────────────
async function handlePutCafeLocation(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body) {
    return errorResponse(422, "VALIDATION", "Body must be a JSON object");
  }

  const { latitude, longitude, radiusMeters } = body;

  // Validate latitude
  if (typeof latitude !== "number" || latitude < -90 || latitude > 90) {
    return errorResponse(422, "VALIDATION", "latitude must be a number between -90 and 90");
  }

  // Validate longitude
  if (typeof longitude !== "number" || longitude < -180 || longitude > 180) {
    return errorResponse(422, "VALIDATION", "longitude must be a number between -180 and 180");
  }

  // Validate radiusMeters
  if (!Number.isInteger(radiusMeters) || radiusMeters <= 0) {
    return errorResponse(422, "VALIDATION", "radiusMeters must be a positive integer");
  }

  const supabase = getSupabaseClient();
  const now = new Date().toISOString();

  const { data, error } = await supabase
    .from("cafe_location")
    .update({
      latitude,
      longitude,
      radius_meters: radiusMeters,
      updated_at: now,
    })
    .eq("id", 1)
    .select("latitude, longitude, radius_meters")
    .single();

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  return jsonResponse({
    latitude: data.latitude,
    longitude: data.longitude,
    radiusMeters: data.radius_meters,
  });
}

// ── GET /api/cafe-location ─────────────────────────────────────────────────
async function handleGetCafeLocation(req: Request): Promise<Response> {
  // The admin sets the location (PUT is admin-only) and its own Settings screen must be able to
  // read it back, so accept the admin token here too — not just an ordering device's key.
  const admin = await verifyAdminToken(req);
  const device = admin ? null : await verifyOrderingKey(req);
  if (!admin && !device) {
    return errorResponse(401, "UNAUTHORIZED", "Admin or ordering credentials required");
  }

  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("cafe_location")
    .select("latitude, longitude, radius_meters")
    .eq("id", 1)
    .single();

  if (error || !data) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read café location");
  }

  // If not configured (latitude is null), return 404
  if (data.latitude === null) {
    return errorResponse(404, "NOT_CONFIGURED", "Café location has not been configured");
  }

  return jsonResponse({
    latitude: data.latitude,
    longitude: data.longitude,
    radiusMeters: data.radius_meters,
  });
}
