/**
 * GET /api/branding — public: returns café branding (name + logo URL).
 * PUT /api/branding — admin: updates branding, uploads logo to Storage, broadcasts change.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOperatorToken } from "../_shared/auth.ts";
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

  // maybeSingle, not single: `single()` treats "no rows" as an ERROR, so a café whose branding
  // singleton was never seeded answered 500 here. That is the wrong answer to give a device that is
  // trying to set the café up — an unconfigured café is a normal state with a defined reply, and this
  // endpoint already has one (`configured: false`). A 500 instead reads as a broken backend and
  // stalls the setup flow at exactly the moment the row is expected to be missing.
  //
  // The row IS seeded by migration 0001, so a missing one means the schema is partly applied. Saying
  // "not configured yet" lets the app continue and lets the operator re-run the schema step; saying
  // "server error" tells them nothing they can act on.
  const { data, error } = await supabase
    .from("branding")
    .select("cafe_name, logo_url, updated_at, payment_qr_url, payment_qr_hash")
    .eq("id", 1)
    .maybeSingle();

  // A genuine query failure (table absent, permission denied) is still a 500 — that is not the same
  // thing as an empty table, and pretending it is would hide a broken deployment behind a skeleton.
  if (error) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read branding");
  }

  if (!data || !data.cafe_name) {
    return jsonResponse({ configured: false });
  }

  return jsonResponse({
    cafeName: data.cafe_name,
    logoUrl: versionedLogoUrl(data.logo_url, data.updated_at),
    // Payment QR: the URL is returned unversioned on purpose. Clients cache on the HASH, because the
    // object key is stable across replacements and a ?v= param would make every branding fetch look
    // like a change. See migration 0007 and PaymentQrResolver on the client.
    paymentQrUrl: data.payment_qr_url ?? null,
    paymentQrHash: data.payment_qr_hash ?? null,
  });
}

// ── PUT /api/branding ──────────────────────────────────────────────────────
async function handlePutBranding(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req) ?? await verifyOperatorToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.cafeName) {
    return errorResponse(422, "VALIDATION", "cafeName is required");
  }

  const supabase = getSupabaseClient();
  let logoUrl: string | null = null;
  let paymentQrUrl: string | null = null;
  let paymentQrHash: string | null = null;
  // Distinguish "not mentioned in this request" from "explicitly cleared". Omitting the field must
  // leave an existing QR alone (the admin is only renaming the café); sending null must remove it,
  // which is what makes the Show QR button disappear on every device (Requirement 14.5).
  const removePaymentQr = Object.prototype.hasOwnProperty.call(body, "paymentQrBase64") &&
    body.paymentQrBase64 === null;

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

  // Upload the payment QR if one was supplied. Mirrors the logo path above, with two differences:
  // the content type follows the uploaded bytes (a PNG must stay a PNG — re-encoding a dense QR can
  // smear its modules until a scanner cannot read it), and the caller-supplied SHA-256 is stored so
  // devices can detect a replacement.
  if (body.paymentQrBase64) {
    try {
      const binaryStr = atob(body.paymentQrBase64);
      const bytes = new Uint8Array(binaryStr.length);
      for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);

      const isPng = bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 &&
        bytes[2] === 0x4e && bytes[3] === 0x47;
      const objectKey = isPng ? "payment-qr.png" : "payment-qr.jpg";

      const { error: qrUploadError } = await supabase.storage
        .from("logos")
        .upload(objectKey, bytes, {
          contentType: isPng ? "image/png" : "image/jpeg",
          upsert: true,
        });
      if (qrUploadError) {
        return errorResponse(500, "SERVER_ERROR", `Payment QR upload failed: ${qrUploadError.message}`);
      }

      const { data: qrUrlData } = supabase.storage.from("logos").getPublicUrl(objectKey);
      paymentQrUrl = qrUrlData.publicUrl;
      paymentQrHash = typeof body.paymentQrHash === "string" ? body.paymentQrHash : null;
    } catch (_e) {
      return errorResponse(422, "VALIDATION", "Invalid base64 payment QR data");
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
  if (paymentQrUrl) {
    updatePayload.payment_qr_url = paymentQrUrl;
    updatePayload.payment_qr_hash = paymentQrHash;
  } else if (removePaymentQr) {
    updatePayload.payment_qr_url = null;
    updatePayload.payment_qr_hash = null;
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
    .select("cafe_name, logo_url, payment_qr_url, payment_qr_hash")
    .eq("id", 1)
    .single();

  const result = {
    cafeName: current?.cafe_name || body.cafeName,
    logoUrl: current?.logo_url || logoUrl,
    paymentQrUrl: current?.payment_qr_url ?? null,
    paymentQrHash: current?.payment_qr_hash ?? null,
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
