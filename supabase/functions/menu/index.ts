/**
 * GET /api/menu — public: returns multilingual menu snapshot (CDN-cached 60s).
 * PUT /api/menu — admin: updates the full menu snapshot.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOperatorToken } from "../_shared/auth.ts";
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
  const admin = await verifyAdminToken(req) ?? await verifyOperatorToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  const validation = validateMenuSnapshot(body);
  if (!validation.ok) {
    return errorResponse(422, "VALIDATION", validation.error);
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

// Lightweight server-side guard so a malformed admin save cannot corrupt the customer menu.
function validateMenuSnapshot(body: unknown): { ok: true } | { ok: false; error: string } {
  if (!body || typeof body !== "object") {
    return { ok: false, error: "Body must be an object" };
  }
  const snapshot = body as Record<string, unknown>;

  if (!Array.isArray(snapshot.items)) {
    return { ok: false, error: "Body must contain an items array" };
  }
  if (snapshot.categories !== undefined && !Array.isArray(snapshot.categories)) {
    return { ok: false, error: "categories must be an array when provided" };
  }

  const seenIds = new Set<string>();
  for (let i = 0; i < snapshot.items.length; i++) {
    const item = snapshot.items[i];
    if (!item || typeof item !== "object") {
      return { ok: false, error: `Item ${i} must be an object` };
    }
    const it = item as Record<string, unknown>;

    if (typeof it.id !== "string" || it.id.trim() === "") {
      return { ok: false, error: `Item ${i} must have a non-empty id` };
    }
    if (seenIds.has(it.id)) {
      return { ok: false, error: `Duplicate item id: ${it.id}` };
    }
    seenIds.add(it.id);

    if (typeof it.category !== "string" || it.category.trim() === "") {
      return { ok: false, error: `Item ${i} (${it.id}) must have a category` };
    }
    if (typeof it.price !== "number" || it.price < 0) {
      return { ok: false, error: `Item ${i} (${it.id}) must have a non-negative price` };
    }
    if (typeof it.available !== "boolean") {
      return { ok: false, error: `Item ${i} (${it.id}) must have an available boolean` };
    }
    if (typeof it.askMeDaily !== "boolean") {
      return { ok: false, error: `Item ${i} (${it.id}) must have an askMeDaily boolean` };
    }
    if (!it.name || typeof it.name !== "object" || typeof (it.name as Record<string, string>).en !== "string") {
      return { ok: false, error: `Item ${i} (${it.id}) must have a name map with an English value` };
    }

    // Optional shape checks
    if (it.description !== undefined && typeof it.description !== "object") {
      return { ok: false, error: `Item ${i} (${it.id}) description must be a language map` };
    }
    if (it.image !== undefined && typeof it.image !== "string") {
      return { ok: false, error: `Item ${i} (${it.id}) image must be a string URL` };
    }
    if (it.categories !== undefined && !Array.isArray(it.categories)) {
      return { ok: false, error: `Item ${i} (${it.id}) categories must be an array` };
    }
  }

  return { ok: true };
}
