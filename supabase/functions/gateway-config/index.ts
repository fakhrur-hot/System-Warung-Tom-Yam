/**
 * GET/PUT /functions/v1/gateway-config — the café's payment-gateway configuration. (PG-REQ-2,
 * PG-REQ-8, task 7.1)
 *
 * GET is readable by admin or staff — a staff device has no `GatewayCredentialStore` of its own
 * (that only exists on the admin device), so this is how it learns which gateway tiles to show at
 * checkout (task 7.2). The response **never** carries `verify_key`/`secret_key` values, only
 * whether each is set — those two fields never leave this function once written.
 *
 * PUT is admin-only. `verifyKey`/`secretKey` omitted (not sent) means "leave unchanged" — this
 * endpoint never returns their values, so that is the only way a screen can distinguish "nothing
 * set yet" from "keep the existing one" without ever round-tripping a secret back to the client.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  const supabase = getSupabaseClient();

  if (req.method === "GET") {
    const admin = await verifyAdminToken(req);
    const staff = !admin ? await verifyOrderingKey(req) : null;
    if (!admin && !staff) {
      return errorResponse(401, "UNAUTHORIZED", "Admin or staff token required");
    }

    const { data: config } = await supabase
      .from("gateway_config")
      .select("merchant_id, verify_key, secret_key, is_sandbox, enabled_methods")
      .eq("id", 1)
      .maybeSingle();

    return jsonResponse(toDto(config));
  }

  if (req.method === "PUT") {
    const admin = await verifyAdminToken(req);
    if (!admin) {
      return errorResponse(403, "FORBIDDEN", "Only the admin device can change gateway settings");
    }

    const body = await req.json().catch(() => null);
    if (!body || typeof body.merchantId !== "string" || !body.merchantId.trim()) {
      return errorResponse(422, "VALIDATION", "merchantId is required");
    }

    const enabledMethods: unknown = body.enabledMethods;
    if (!Array.isArray(enabledMethods) || !enabledMethods.every((m) => typeof m === "string")) {
      return errorResponse(422, "VALIDATION", "enabledMethods must be a list of method codes");
    }
    // Deliberately NOT validated against a hardcoded channel list.
    //
    // An earlier version checked each code against the Fiuu adapter's channel map. That only held
    // while Fiuu was the assumed provider: a direct Touch 'n Go integration, a DuitNow rail via an
    // acquiring bank, and a PSP like 2C2P each name their channels differently, and Adyen turned
    // out not to offer ShopeePay at all. Baking any one vocabulary in here would reject a channel
    // the café had genuinely been onboarded for.
    //
    // The provider adapter is the right place for that check, because only it knows the
    // provider's vocabulary — and it cannot be written until merchant onboarding supplies real
    // endpoints and credentials. Until then this stores what the owner selected and
    // `payment-initiate` fails closed on anything it cannot actually route.

    // deno-lint-ignore no-explicit-any
    const update: Record<string, any> = {
      merchant_id: body.merchantId.trim(),
      is_sandbox: body.isSandbox !== false,
      enabled_methods: enabledMethods,
      updated_at: new Date().toISOString(),
    };
    // Present-but-blank clears a key deliberately (the settings screen's own "disconnect gateway"
    // path); absent leaves it untouched. Only `undefined` (key not sent at all) means "unchanged".
    if (body.verifyKey !== undefined) update.verify_key = body.verifyKey || null;
    if (body.secretKey !== undefined) update.secret_key = body.secretKey || null;

    const { data: saved, error } = await supabase
      .from("gateway_config")
      .upsert({ id: 1, ...update }, { onConflict: "id" })
      .select("merchant_id, verify_key, secret_key, is_sandbox, enabled_methods")
      .single();

    if (error || !saved) {
      console.error("gateway-config: upsert failed:", error?.message);
      return errorResponse(500, "PERSIST_FAILED", "Could not save gateway configuration");
    }

    return jsonResponse(toDto(saved));
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// deno-lint-ignore no-explicit-any
function toDto(config: any) {
  const merchantId: string = config?.merchant_id ?? "";
  const hasVerifyKey = !!config?.verify_key;
  const hasSecretKey = !!config?.secret_key;
  return {
    configured: merchantId.length > 0 && hasVerifyKey && hasSecretKey,
    merchantId,
    hasVerifyKey,
    hasSecretKey,
    isSandbox: config?.is_sandbox ?? true,
    enabledMethods: config?.enabled_methods ?? [],
  };
}
