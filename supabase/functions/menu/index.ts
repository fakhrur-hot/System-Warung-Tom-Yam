/**
 * GET /api/menu — public: returns multilingual menu snapshot (CDN-cached 60s).
 * PUT /api/menu — admin: updates the full menu snapshot.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";
import { corsHeaders } from "../_shared/cors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "GET") {
    return handleGetMenu();
  }
  if (req.method === "PUT") {
    return handlePutMenu(req);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// ── GET /api/menu ──────────────────────────────────────────────────────────
async function handleGetMenu(): Promise<Response> {
  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("menu_snapshot")
    .select("menu_json")
    .eq("id", 1)
    .single();

  if (error || !data) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read menu snapshot");
  }

  const menuJson = data.menu_json;

  // If not configured yet, return simple indicator
  if (
    menuJson &&
    typeof menuJson === "object" &&
    (menuJson as Record<string, unknown>).configured === false
  ) {
    return new Response(JSON.stringify({ configured: false }), {
      status: 200,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
        "Cache-Control": "public, max-age=60",
      },
    });
  }

  return new Response(JSON.stringify(menuJson), {
    status: 200,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=60",
    },
  });
}

// ── PUT /api/menu ──────────────────────────────────────────────────────────
async function handlePutMenu(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !Array.isArray(body.items)) {
    return errorResponse(422, "VALIDATION", "Body must contain an items array");
  }

  const supabase = getSupabaseClient();
  const now = new Date().toISOString();

  const { error } = await supabase
    .from("menu_snapshot")
    .update({ menu_json: body, updated_at: now })
    .eq("id", 1);

  if (error) {
    return errorResponse(500, "SERVER_ERROR", error.message);
  }

  return jsonResponse({ updatedAt: now });
}
