/**
 * GET /api/rotating-key — superadmin JWT required.
 * Returns a 30-second HMAC-derived pairing key for the admin handshake.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifySuperadminJwt } from "../_shared/auth.ts";
import { getRotatingKeyInfo } from "../_shared/rotating-key.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  // Handle CORS preflight
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "GET") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET is supported");
  }

  // Verify superadmin JWT
  const user = await verifySuperadminJwt(req);
  if (!user) {
    return errorResponse(401, "UNAUTHORIZED", "Valid superadmin JWT required");
  }

  // Derive rotating key
  const secret = Deno.env.get("ROTATING_KEY_SECRET");
  if (!secret) {
    return errorResponse(500, "SERVER_ERROR", "Rotating key secret not configured");
  }

  const { key, expiresInSeconds } = await getRotatingKeyInfo(secret);
  return jsonResponse({ key, expiresInSeconds });
});
