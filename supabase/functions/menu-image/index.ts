/**
 * POST /api/menu-image — admin: uploads a single menu-item thumbnail to Storage,
 * returns its public URL. The admin app resizes/crops client-side (5:4, max 320px
 * wide) before calling this, so bodies stay small; this function just persists the
 * already-prepared JPEG and hands back a URL to store on the menu item.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const MAX_BASE64_LENGTH = 300_000; // ~220KB decoded — generous headroom over a 320px-wide JPEG

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "DELETE") {
    return handleDeleteImage(req);
  }
  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST and DELETE are supported");
  }

  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  const menuItemId = body?.menuItemId;
  const imageBase64 = body?.imageBase64;

  if (typeof menuItemId !== "string" || !menuItemId) {
    return errorResponse(422, "VALIDATION", "menuItemId is required");
  }
  if (typeof imageBase64 !== "string" || !imageBase64) {
    return errorResponse(422, "VALIDATION", "imageBase64 is required");
  }
  if (imageBase64.length > MAX_BASE64_LENGTH) {
    return errorResponse(422, "VALIDATION", "Image too large — resize before upload");
  }

  let bytes: Uint8Array;
  try {
    const binaryStr = atob(imageBase64);
    bytes = new Uint8Array(binaryStr.length);
    for (let i = 0; i < binaryStr.length; i++) {
      bytes[i] = binaryStr.charCodeAt(i);
    }
  } catch (_e) {
    return errorResponse(422, "VALIDATION", "Invalid base64 image data");
  }

  const supabase = getSupabaseClient();

  // Unique path per upload (not overwritten) — Free-tier Storage has no Smart CDN
  // cache invalidation, so reusing a fixed filename risks serving a stale cached
  // image after an edit. The old object (if any) is deleted separately by the
  // client once the new URL is confirmed saved.
  const path = `${menuItemId}-${Date.now()}.jpg`;

  const { error: uploadError } = await supabase.storage
    .from("menu-images")
    .upload(path, bytes, {
      contentType: "image/jpeg",
      cacheControl: "31536000", // 1 year — safe since the filename is unique per upload
      upsert: false,
    });

  if (uploadError) {
    return errorResponse(500, "SERVER_ERROR", `Image upload failed: ${uploadError.message}`);
  }

  const { data: urlData } = supabase.storage.from("menu-images").getPublicUrl(path);

  return jsonResponse({ imageUrl: urlData.publicUrl, path });
});

// ── DELETE /api/menu-image — admin: remove a superseded image by storage path ──
async function handleDeleteImage(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  const path = body?.path;
  if (typeof path !== "string" || !path) {
    return errorResponse(422, "VALIDATION", "path is required");
  }

  const supabase = getSupabaseClient();
  const { error } = await supabase.storage.from("menu-images").remove([path]);

  if (error) {
    return errorResponse(500, "SERVER_ERROR", `Image delete failed: ${error.message}`);
  }

  return jsonResponse({ deleted: true });
}
