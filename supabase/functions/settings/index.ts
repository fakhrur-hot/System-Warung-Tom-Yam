/**
 * GET /api/settings — public subset (printLanguage, timezone) or full (admin/superadmin).
 * PUT /api/settings — admin: partial update of settings key-value store, broadcasts change.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey, verifySuperadminJwt } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

// Map DB key names to API camelCase names
const KEY_MAP: Record<string, string> = {
  print_language: "printLanguage",
  timezone: "timezone",
  top_n_items: "topN",
  staff_can_send_kitchen: "staffCanSendKitchen",
  staff_can_take_payment: "staffCanTakePayment",
  report_email: "reportEmail",
  closing_report_auto: "autoSendClosingReport",
  customer_order_auto_print: "customerOrderAutoPrint",
  customer_order_hold_seconds: "customerOrderHoldSeconds",
  todays_special: "todaysSpecial",
  business_day_start_hour: "businessDayStartHour",
};

// Allowed values for the customer "hold before kitchen" delay (seconds).
const CUSTOMER_HOLD_OPTIONS = [10, 15, 30, 60];

// Reverse map: camelCase → DB key
const REVERSE_KEY_MAP: Record<string, string> = {};
for (const [dbKey, apiKey] of Object.entries(KEY_MAP)) {
  REVERSE_KEY_MAP[apiKey] = dbKey;
}

// Public keys (visible without auth) — the customer ordering website reads the hold
// delay without an admin token, so it must be public.
const PUBLIC_KEYS = new Set([
  "printLanguage",
  "timezone",
  "customerOrderHoldSeconds",
  "todaysSpecial", // shown on the customer menu
]);

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method === "GET") {
    return handleGetSettings(req);
  }
  if (req.method === "PUT") {
    return handlePutSettings(req);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and PUT are supported");
});

// ── GET /api/settings ──────────────────────────────────────────────────────
async function handleGetSettings(req: Request): Promise<Response> {
  const supabase = getSupabaseClient();

  // Full settings are returned to any authenticated café device: the admin (its own
  // Settings screen), a superadmin, OR a registered ordering device — the latter must be
  // able to read its own staffCanSendKitchen/staffCanTakePayment permissions, which are NOT
  // public. Without the ordering-key branch a staff device always read them as false, so
  // admin permission changes never took effect on staff devices.
  const admin = await verifyAdminToken(req);
  const superadmin = !admin ? await verifySuperadminJwt(req) : null;
  const ordering = !admin && !superadmin ? await verifyOrderingKey(req) : null;
  const isAuthorized = !!admin || !!superadmin || !!ordering;

  const { data, error } = await supabase
    .from("settings")
    .select("key, value");

  if (error || !data) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read settings");
  }

  const result = buildSettingsObject(data, isAuthorized);
  return jsonResponse(result);
}

// ── PUT /api/settings ──────────────────────────────────────────────────────
async function handlePutSettings(req: Request): Promise<Response> {
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
  }

  const body = await req.json().catch(() => null);
  if (!body || typeof body !== "object") {
    return errorResponse(422, "VALIDATION", "Body must be a JSON object");
  }

  const supabase = getSupabaseClient();

  // Validate and update each provided key
  for (const [apiKey, value] of Object.entries(body)) {
    const dbKey = REVERSE_KEY_MAP[apiKey];
    if (!dbKey) {
      return errorResponse(422, "VALIDATION", `Unknown setting: ${apiKey}`);
    }

    // Validate values
    const validationError = validateSetting(apiKey, value);
    if (validationError) {
      return errorResponse(422, "VALIDATION", validationError);
    }

    const { error } = await supabase
      .from("settings")
      .update({ value: String(value) })
      .eq("key", dbKey);

    if (error) {
      return errorResponse(500, "SERVER_ERROR", error.message);
    }
  }

  // Read back full settings to return and broadcast
  const { data, error: readError } = await supabase
    .from("settings")
    .select("key, value");

  if (readError || !data) {
    return errorResponse(500, "SERVER_ERROR", "Failed to read back settings");
  }

  const fullSettings = buildSettingsObject(data, true);

  // Broadcast SETTINGS_CHANGED on the settings Realtime channel
  try {
    const channel = supabase.channel("settings");
    await channel.send({
      type: "broadcast",
      event: "SETTINGS_CHANGED",
      payload: fullSettings,
    });
  } catch (_e) {
    // Non-critical
  }

  return jsonResponse(fullSettings);
}

// ── Helpers ────────────────────────────────────────────────────────────────

function buildSettingsObject(
  rows: Array<{ key: string; value: string }>,
  full: boolean
): Record<string, unknown> {
  const result: Record<string, unknown> = {};

  for (const row of rows) {
    const apiKey = KEY_MAP[row.key];
    if (!apiKey) continue;

    // If not authorized, only include public keys
    if (!full && !PUBLIC_KEYS.has(apiKey)) continue;

    // Convert value to appropriate type
    result[apiKey] = coerceValue(apiKey, row.value);
  }

  return result;
}

function coerceValue(apiKey: string, value: string): unknown {
  switch (apiKey) {
    case "topN":
      return parseInt(value, 10);
    case "customerOrderHoldSeconds":
      return parseInt(value, 10);
    case "businessDayStartHour":
      return parseInt(value, 10);
    case "staffCanSendKitchen":
    case "staffCanTakePayment":
    case "autoSendClosingReport":
    case "customerOrderAutoPrint":
      return value === "true";
    default:
      return value;
  }
}

function validateSetting(apiKey: string, value: unknown): string | null {
  switch (apiKey) {
    case "printLanguage":
      if (value !== "EN" && value !== "BM") {
        return "printLanguage must be EN or BM";
      }
      break;
    case "topN": {
      const n = Number(value);
      if (!Number.isInteger(n) || n < 1 || n > 20) {
        return "topN must be an integer between 1 and 20";
      }
      break;
    }
    case "customerOrderHoldSeconds": {
      const n = Number(value);
      if (!CUSTOMER_HOLD_OPTIONS.includes(n)) {
        return `customerOrderHoldSeconds must be one of ${CUSTOMER_HOLD_OPTIONS.join(", ")}`;
      }
      break;
    }
    case "businessDayStartHour": {
      const n = Number(value);
      if (!Number.isInteger(n) || n < 0 || n > 23) {
        return "businessDayStartHour must be an integer 0–23";
      }
      break;
    }
    case "staffCanSendKitchen":
    case "staffCanTakePayment":
    case "autoSendClosingReport":
    case "customerOrderAutoPrint":
      if (typeof value !== "boolean") {
        return `${apiKey} must be a boolean`;
      }
      break;
    case "reportEmail":
      if (typeof value !== "string") {
        return "reportEmail must be a string";
      }
      break;
    case "timezone":
      if (typeof value !== "string" || value.length === 0) {
        return "timezone must be a non-empty string";
      }
      break;
    case "todaysSpecial":
      if (typeof value !== "string") {
        return "todaysSpecial must be a string";
      }
      if (value.length > 200) {
        return "todaysSpecial must be 200 characters or fewer";
      }
      break;
  }
  return null;
}
