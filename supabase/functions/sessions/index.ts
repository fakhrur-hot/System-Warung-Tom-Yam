/**
 * POST /api/sessions — admin token auth.
 * Manages café OPEN/CLOSE events.
 * On OPEN: if prior session has no CLOSE, implicitly closes the dangling one.
 * Broadcasts CAFE_OPEN/CAFE_CLOSED on `cafe-status` channel.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const VALID_EVENTS = new Set(["OPEN", "CLOSE"]);

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
  if (!body || !body.event || !VALID_EVENTS.has(body.event)) {
    return errorResponse(422, "VALIDATION", "event must be OPEN or CLOSE");
  }

  const supabase = getSupabaseClient();
  const now = new Date().toISOString();

  // sessions is an append-only event log (OPEN/CLOSE rows, paired by timestamp
  // ordering — see reports-closing/metrics), not a row you update in place.
  // If OPEN: check whether the most recent row is itself an OPEN with no
  // matching CLOSE after it (a dangling session, e.g. app crashed before
  // closing) and insert a synthetic CLOSE first so pairing stays consistent.
  if (body.event === "OPEN") {
    const { data: lastSession } = await supabase
      .from("sessions")
      .select("*")
      .order("timestamp", { ascending: false })
      .limit(1)
      .single();

    if (lastSession && lastSession.event === "OPEN") {
      await supabase.from("sessions").insert({
        event: "CLOSE",
        reason: "Auto-closed: dangling session before new OPEN",
        closing: false,
        timestamp: now,
      });
    }
  }

  // Insert the new session event
  const { data: session, error: insertError } = await supabase
    .from("sessions")
    .insert({
      event: body.event,
      reason: body.reason || null,
      closing: body.closing === true,
      timestamp: now,
    })
    .select("*")
    .single();

  if (insertError) {
    return errorResponse(500, "SERVER_ERROR", insertError.message);
  }

  // Broadcast on cafe-status channel
  const broadcastEvent = body.event === "OPEN" ? "CAFE_OPEN" : "CAFE_CLOSED";
  try {
    const channel = supabase.channel("cafe-status");
    await channel.send({
      type: "broadcast",
      event: broadcastEvent,
      payload: { event: broadcastEvent, timestamp: now },
    });
  } catch (_e) {
    // Non-critical
  }

  return jsonResponse({
    sessionId: session.id,
    event: session.event,
    timestamp: session.timestamp,
  });
});
