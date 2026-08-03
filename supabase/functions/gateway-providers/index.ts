/**
 * GET/PUT /functions/v1/gateway-providers — per-provider payment credentials. (PG-REQ-2, PG-REQ-8)
 *
 * The multi-provider successor to `gateway-config`, which held one merchant id + verify key +
 * secret key and so could only ever describe a single aggregator. Touch 'n Go direct and DuitNow
 * through an acquiring bank are separate merchant relationships with different credential shapes
 * and a callback URL each.
 *
 * GET is readable by admin or staff — a staff device has no local credential store, so this is how
 * it learns which channels to show at checkout. It returns each provider's **field spec** (so the
 * settings screen renders the right form without hardcoding one per provider), whether the
 * provider is integrated or still awaiting onboarding, and for each credential field only whether
 * a value is **set** — never the value. Secrets do not leave this function once written.
 *
 * PUT is admin-only. A credential field omitted from the request is left unchanged, which is what
 * lets the settings screen show a masked "already set" placeholder without ever round-tripping a
 * secret back to the client and returning it verbatim.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";
import { adapterFor, describeProviders } from "../_shared/gateway-registry.ts";

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

    const { data: rows } = await supabase
      .from("gateway_providers")
      .select("provider, credentials, enabled_methods, is_sandbox, is_enabled");

    // deno-lint-ignore no-explicit-any
    const byProvider = new Map<string, any>((rows ?? []).map((r: any) => [r.provider, r]));

    const providers = describeProviders().map((spec) => {
      const row = byProvider.get(spec.provider);
      const stored = (row?.credentials ?? {}) as Record<string, string>;
      // Only whether each field has a value — never the value itself.
      const fieldsSet: Record<string, boolean> = {};
      for (const field of spec.credentialFields) {
        fieldsSet[field.key] = typeof stored[field.key] === "string" && stored[field.key].length > 0;
      }
      const configured = spec.credentialFields
        .filter((f) => f.required)
        .every((f) => fieldsSet[f.key]);

      return {
        ...spec,
        configured,
        fieldsSet,
        enabledMethods: row?.enabled_methods ?? [],
        isSandbox: row?.is_sandbox ?? true,
        // A provider can never be live at the counter unless its adapter is actually implemented,
        // regardless of what is stored — a stub cannot take money and must not look like it can.
        isEnabled: (row?.is_enabled ?? false) && spec.status === "AVAILABLE" && configured,
      };
    });

    return jsonResponse({ providers });
  }

  if (req.method === "PUT") {
    const admin = await verifyAdminToken(req);
    if (!admin) {
      return errorResponse(403, "FORBIDDEN", "Only the admin device can change gateway settings");
    }

    const body = await req.json().catch(() => null);
    const provider: string | undefined = body?.provider;
    if (!provider) {
      return errorResponse(422, "VALIDATION", "provider is required");
    }

    const adapter = adapterFor(provider);
    if (!adapter) {
      return errorResponse(422, "VALIDATION", `Unknown provider: ${provider}`);
    }

    const incoming = (body.credentials ?? {}) as Record<string, unknown>;
    const unknownField = Object.keys(incoming).find(
      (k) => !adapter.credentialFields.some((f) => f.key === k),
    );
    if (unknownField) {
      return errorResponse(422, "VALIDATION", `${adapter.displayName} has no field '${unknownField}'`);
    }

    const { data: existing } = await supabase
      .from("gateway_providers")
      .select("credentials")
      .eq("provider", provider)
      .maybeSingle();

    // Merge rather than replace: an omitted field keeps its stored value, so the settings screen
    // can leave a masked secret untouched. A field sent as "" clears it deliberately.
    const merged: Record<string, string> = { ...(existing?.credentials ?? {}) };
    for (const [key, value] of Object.entries(incoming)) {
      if (typeof value !== "string") {
        return errorResponse(422, "VALIDATION", `${key} must be a string`);
      }
      if (value === "") delete merged[key];
      else merged[key] = value;
    }

    const enabledMethods: unknown = body.enabledMethods ?? [];
    if (!Array.isArray(enabledMethods) || !enabledMethods.every((m) => typeof m === "string")) {
      return errorResponse(422, "VALIDATION", "enabledMethods must be a list of strings");
    }

    const { error } = await supabase
      .from("gateway_providers")
      .upsert({
        provider,
        credentials: merged,
        enabled_methods: enabledMethods,
        is_sandbox: body.isSandbox !== false,
        is_enabled: body.isEnabled === true,
        updated_at: new Date().toISOString(),
      }, { onConflict: "provider" });

    if (error) {
      console.error("gateway-providers: upsert failed:", error.message);
      return errorResponse(500, "PERSIST_FAILED", "Could not save provider configuration");
    }

    // Echo the same secret-free shape a GET returns, so the client never has to guess what stuck.
    const fieldsSet: Record<string, boolean> = {};
    for (const field of adapter.credentialFields) {
      fieldsSet[field.key] = typeof merged[field.key] === "string" && merged[field.key].length > 0;
    }
    const configured = adapter.credentialFields.filter((f) => f.required).every((f) => fieldsSet[f.key]);

    return jsonResponse({
      provider,
      configured,
      fieldsSet,
      enabledMethods,
      isSandbox: body.isSandbox !== false,
      isEnabled: body.isEnabled === true && adapter.status === "AVAILABLE" && configured,
    });
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});
