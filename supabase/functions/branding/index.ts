/**
 * GET /api/branding — public: returns café branding (name + logo URL).
 * PUT /api/branding — admin: updates branding, uploads logo to Storage, broadcasts change.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "GET") {
    return handleGetBranding();
  }
  if (req.method === "PUT") {
    return handlePutBranding(req);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// The logo object is always stored under the same key ("logo.jpg"), so its public
// URL is identical on every re-upload — which lets browsers, service workers, and
// the CDN serve a stale image indefinitely after the café changes its logo. Append
// the branding row's updated_at as a version param so each change yields a fresh URL
// that busts every cache layer, while unchanged logos still cache normally.
function versionedLogoUrl(logoUrl: string | null, updatedAt: string | null): string | null {
  if (!logoUrl) return logoUrl;
  const version = updatedAt ? new Date(updatedAt).getTime() : "";
  if (!version || Number.isNaN(version)) return logoUrl;
  const sep = logoUrl.includes("?") ? "&" : "?";
  return `${logoUrl}${sep}v=${version}`;
}

// ── GET /api/branding ──────────────────────────────────────────────────────
async function handleGetBranding(): Promise<Response> {
  const supabase = getSupabaseClient();

  const { data, error } = await supabase
    .from("branding")
    .select("cafe_name, logo_url, updated_at")
    .eq("id", 1)
    .single();

  if (error || !data) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read branding");
  }

  if (!data.cafe_name) {
    return jsonResponse({ configured: false });
  }

  return jsonResponse({
    cafeName: data.cafe_name,
    logoUrl: versionedLogoUrl(data.logo_url, data.updated_at),
  });
}

// ── PUT /api/branding ──────────────────────────────────────────────────────
async function handlePutBranding(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.cafeName) {
    return errorResponse(422, "VALIDATION", "cafeName is required");
  }

  const supabase = getSupabaseClient();
  let logoUrl: string | null = null;

  // Upload logo if base64 provided
  if (body.logoBase64) {
    try {
      // Decode base64 to Uint8Array
      const binaryStr = atob(body.logoBase64);
      const bytes = new Uint8Array(binaryStr.length);
      for (let i = 0; i < binaryStr.length; i++) {
        bytes[i] = binaryStr.charCodeAt(i);
      }

      // Upload to Supabase Storage (logos bucket, overwrite)
      const { error: uploadError } = await supabase.storage
        .from("logos")
        .upload("logo.jpg", bytes, {
          contentType: "image/jpeg",
          upsert: true,
        });

      if (uploadError) {
        return errorResponse(500, "SERVER_ERROR", `Logo upload failed: ${uploadError.message}`);
      }

      // Get public URL
      const { data: urlData } = supabase.storage
        .from("logos")
        .getPublicUrl("logo.jpg");

      logoUrl = urlData.publicUrl;
    } catch (e) {
      return errorResponse(422, "VALIDATION", "Invalid base64 logo data");
    }
  }

  // Update branding row
  const now = new Date().toISOString();
  const updatePayload: Record<string, unknown> = {
    cafe_name: body.cafeName,
    updated_at: now,
  };
  if (logoUrl) {
    updatePayload.logo_url = logoUrl;
  }

  const { error: updateError } = await supabase
    .from("branding")
    .update(updatePayload)
    .eq("id", 1);

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Read back to get current logo_url (in case no new upload)
  const { data: current } = await supabase
    .from("branding")
    .select("cafe_name, logo_url")
    .eq("id", 1)
    .single();

  const result = {
    cafeName: current?.cafe_name || body.cafeName,
    logoUrl: current?.logo_url || logoUrl,
  };

  // Broadcast BRANDING_CHANGED on the branding Realtime channel
  try {
    const channel = supabase.channel("branding");
    await channel.send({
      type: "broadcast",
      event: "BRANDING_CHANGED",
      payload: result,
    });
  } catch (_e) {
    // Non-critical: update succeeded even if broadcast fails
  }

  return jsonResponse(result);
}
