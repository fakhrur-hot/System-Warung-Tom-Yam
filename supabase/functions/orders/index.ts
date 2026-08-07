/**
 * POST /api/orders — create a new order (public via browser ID or staff/admin key).
 * GET /api/orders?since=<iso> — admin catch-up sync (all active + terminal after ts).
 *
 * Validates table, rejects if occupied, re-prices server-side, rate-limits,
 * broadcasts NEW_ORDER on admin-orders channel.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

// ── In-memory rate limiter ─────────────────────────────────────────────────
interface RateEntry {
  timestamps: number[];
}
const rateLimitMap = new Map<string, RateEntry>();
const RATE_LIMIT_MAX = 5;
const RATE_LIMIT_WINDOW_MS = 60_000;

function isRateLimited(key: string): boolean {
  const now = Date.now();
  const entry = rateLimitMap.get(key);
  if (!entry) {
    rateLimitMap.set(key, { timestamps: [now] });
    return false;
  }
  // Remove timestamps older than the window
  entry.timestamps = entry.timestamps.filter(
    (t) => now - t < RATE_LIMIT_WINDOW_MS
  );
  if (entry.timestamps.length >= RATE_LIMIT_MAX) {
    return true;
  }
  entry.timestamps.push(now);
  return false;
}

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  const url = new URL(req.url);

  if (req.method === "GET") {
    return handleGetOrders(req, url);
  }
  if (req.method === "POST") {
    return handleCreateOrder(req, url);
  }

  return errorResponse(405, "METHOD_NOT_ALLOWED", "Only GET and POST are supported");
});

// ── GET /api/orders?since=<iso> ────────────────────────────────────────────
async function handleGetOrders(req: Request, url: URL): Promise<Response> {
  // Admin OR an ordering-key device.
  //
  // This was admin-only, and that single line was the reason a staff phone's table grid never
  // updated: the catch-up sync every ordering device runs sends its ordering key here, got 401 on
  // every tick, and so had NEVER read a single order — its `orders` table was empty, not stale.
  // The 401 also reached the client's interceptor, which turns one into a session expiry, so the
  // staff device was additionally being logged out on a timer. One rejection, both symptoms.
  //
  // Staff already create orders, add rounds, send to the kitchen and take payment through this same
  // endpoint family, each of which accepts an ordering key. Being unable to READ the floor they are
  // working was the anomaly, not the safeguard.
  //
  // The response shape is deliberately identical for both callers rather than a trimmed staff
  // variant: terminal orders after `since` are what let a settled table drop out of the active set
  // and go green on the floor, and a second contract would be a second thing to keep in step.
  const admin = await verifyAdminToken(req);
  const ordering = !admin ? await verifyOrderingKey(req) : null;
  if (!admin && !ordering) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token or ordering key required");
  }

  const since = url.searchParams.get("since");
  const supabase = getSupabaseClient();

  let query;
  if (since) {
    // All active orders + all terminal orders that ended after `since`
    // Terminal orders have purge_after set; they ended roughly purge_after - 24h
    // We use created_at as a proxy but the spec says "ended after ts".
    // Actually: terminal orders that still exist (not purged) AND were completed/cancelled after `since`.
    // We use: active orders UNION terminal orders where purge_after > since (meaning they ended within 24h of since at most)
    // More precisely: purge_after = end_time + 24h, so end_time = purge_after - 24h.
    // Order ended after `since` means purge_after - 24h > since => purge_after > since + 24h? No.
    // Simpler: return all active orders + terminal orders where purge_after > now() AND created_at... 
    // Actually the safest approach per spec: return orders that are active OR (terminal AND purge_after > since)
    // This ensures any order that hasn't been purged and ended after `since` is included.
    const { data, error } = await supabase
      .from("orders")
      .select("*")
      .or(
        `status.not.in.(COMPLETED,CANCELLED),and(status.in.(COMPLETED,CANCELLED),purge_after.gt.${since})`
      )
      .order("created_at", { ascending: true });

    if (error) {
      return errorResponse(500, "SERVER_ERROR", error.message);
    }
    return jsonResponse({
      orders: (data || []).map(mapOrderRow),
      serverTime: new Date().toISOString(),
    });
  } else {
    // No since param — return all active orders
    const { data, error } = await supabase
      .from("orders")
      .select("*")
      .not("status", "in", "(COMPLETED,CANCELLED)")
      .order("created_at", { ascending: true });

    if (error) {
      return errorResponse(500, "SERVER_ERROR", error.message);
    }
    return jsonResponse({
      orders: (data || []).map(mapOrderRow),
      serverTime: new Date().toISOString(),
    });
  }
}

// ── POST /api/orders ───────────────────────────────────────────────────────
async function handleCreateOrder(req: Request, _url: URL): Promise<Response> {
  // Auth: admin, ordering device, or public with browser ID
  const admin = await verifyAdminToken(req);
  const orderingDevice = !admin ? await verifyOrderingKey(req) : null;

  const browserId = req.headers.get("x-browser-id") || undefined;
  const source: "STAFF" | "QR" = admin || orderingDevice ? "STAFF" : "QR";

  // For QR source, browserId is required
  if (source === "QR" && !browserId) {
    return errorResponse(422, "VALIDATION", "x-browser-id header required for QR orders");
  }

  // Rate limit by IP + browserId
  const ip =
    req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    req.headers.get("cf-connecting-ip") ||
    "unknown";
  const rateLimitKey = `${ip}:${browserId || "staff"}`;
  if (isRateLimited(rateLimitKey)) {
    return errorResponse(429, "RATE_LIMITED", "Too many orders. Please wait before trying again.");
  }

  const body = await req.json().catch(() => null);
  if (!body || !body.tableId || !Array.isArray(body.items) || body.items.length === 0) {
    return errorResponse(422, "VALIDATION", "tableId and items[] are required");
  }

  const { tableId, items } = body;
  const supabase = getSupabaseClient();

  // Resolve the incoming identifier — customers send the opaque QR token, admin/staff send
  // the raw id. Either way the order is stored against the real table id so admin views and
  // kitchen slips resolve correctly.
  const { data: table, error: tableError } = await supabase
    .from("tables")
    .select("id")
    .or(`id.eq.${tableId},qr_token.eq.${tableId}`)
    .limit(1)
    .maybeSingle();

  if (tableError || !table) {
    return errorResponse(422, "UNKNOWN_TABLE", `Table '${tableId}' does not exist`);
  }
  const realTableId = table.id;

  // Check for active session on this table (one_active_order_per_table enforced by DB too)
  const { data: activeOrder } = await supabase
    .from("orders")
    .select("id")
    .eq("table_id", realTableId)
    .not("status", "in", "(COMPLETED,CANCELLED)")
    .limit(1)
    .single();

  if (activeOrder) {
    return errorResponse(409, "TABLE_OCCUPIED", `Table '${realTableId}' already has an active order`);
  }

  // Load current menu snapshot for server-side pricing
  const { data: menuRow, error: menuError } = await supabase
    .from("menu_snapshot")
    .select("menu_json")
    .eq("id", 1)
    .single();

  if (menuError || !menuRow) {
    return errorResponse(500, "SERVER_ERROR", "Menu snapshot not available");
  }

  const menuJson = menuRow.menu_json;
  // Build a lookup from menu items
  const menuItems = buildMenuLookup(menuJson);

  // Read the customerOrderAutoPrint setting to decide whether new items are
  // sent to the kitchen immediately (autoPrint=true, the legacy behaviour) or
  // held for cashier confirmation (autoPrint=false, new "ping" mode).
  // Default to true when the row is missing so existing installs are unaffected.
  const { data: autoPrintSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "customer_order_auto_print")
    .single();
  const autoPrint = autoPrintSetting?.value !== "false"; // default true if row missing

  // Re-price and validate each item
  const orderItems: OrderItemLine[] = [];
  let total = 0;

  for (const item of items) {
    if (!item.menuItemId || !item.quantity || item.quantity < 1) {
      return errorResponse(422, "VALIDATION", "Each item must have menuItemId and quantity >= 1");
    }

    // ── Custom charge: a cashier-typed name + price, no menu item behind it ──────
    // Staff/admin only — a QR customer naming its own price would be a free lunch.
    if (isCustomChargeId(item.menuItemId)) {
      if (source !== "STAFF") {
        return errorResponse(403, "FORBIDDEN", "Only staff can add a custom charge");
      }
      const custom = customChargeLine(item, autoPrint);
      if (!custom) {
        return errorResponse(422, "VALIDATION", "A custom charge needs a name and a price above 0");
      }
      orderItems.push(custom);
      total += custom.unitPriceSnapshot * item.quantity;
      continue;
    }

    const menuItem = menuItems.get(item.menuItemId);
    if (!menuItem) {
      return errorResponse(422, "ITEM_UNAVAILABLE", `Menu item '${item.menuItemId}' not found or unavailable`);
    }

    // For a Small/Medium/Large item the client sends the chosen size + its price; validate
    // the price against the item's presets and bake the size into the name ("Nasi Goreng (L)").
    const priced = resolveSizedLine(menuItem, item);

    // sentToKitchen is conditional on the customerOrderAutoPrint setting.
    // sessionNumber is always 1 for the initial order; later rounds appended
    // via POST /orders/:id/items get the next sessionNumber up.
    const lineItem: OrderItemLine = {
      id: crypto.randomUUID(),
      menuItemId: item.menuItemId,
      nameSnapshot: priced.name,
      unitPriceSnapshot: priced.price,
      categorySnapshot: menuItem.category,
      codeSnapshot: menuItem.code,
      marketPriceSnapshot: menuItem.marketPrice,
      quantity: item.quantity,
      note: item.note || null,
      sentToKitchen: autoPrint,
      sessionNumber: 1,
    };
    orderItems.push(lineItem);
    total += priced.price * item.quantity;
  }

  const now = new Date().toISOString();

  // Insert order.
  // autoPrint=true  → status SENT_TO_KITCHEN + sent_to_kitchen_at timestamp (legacy behaviour).
  // autoPrint=false → status RECEIVED (initial default) + sent_to_kitchen_at null
  //                   (cashier must confirm before the kitchen receives the order).
  const { data: newOrder, error: insertError } = await supabase
    .from("orders")
    .insert({
      table_id: realTableId,
      source,
      browser_id: browserId || null,
      status: autoPrint ? "SENT_TO_KITCHEN" : "RECEIVED",
      sent_to_kitchen_at: autoPrint ? now : null,
      items_json: orderItems,
      total: Math.round(total * 100) / 100,
    })
    .select("*")
    .single();

  if (insertError) {
    // Handle unique constraint violation (race condition)
    if (insertError.code === "23505") {
      return errorResponse(409, "TABLE_OCCUPIED", `Table '${tableId}' already has an active order`);
    }
    return errorResponse(500, "SERVER_ERROR", insertError.message);
  }

  // Broadcast NEW_ORDER on admin-orders channel
  try {
    const channel = supabase.channel("admin-orders");
    await channel.send({
      type: "broadcast",
      event: "NEW_ORDER",
      payload: mapOrderRow(newOrder),
    });
  } catch (_e) {
    // Non-critical: order was created successfully even if broadcast fails
  }

  return jsonResponse(
    { orderId: newOrder.id, total: newOrder.total, status: newOrder.status },
    201
  );
}

// ── Helpers ────────────────────────────────────────────────────────────────
interface OrderItemLine {
  id: string;
  menuItemId: string;
  nameSnapshot: string;
  unitPriceSnapshot: number;
  categorySnapshot: string;
  codeSnapshot?: string;
  marketPriceSnapshot?: boolean;
  quantity: number;
  note: string | null;
  sentToKitchen: boolean;
  sessionNumber: number;
}

// ── Custom charges ─────────────────────────────────────────────────────────
// A bill line the cashier typed by hand — corkage, a replacement plate, a special order — with no
// menu item behind it. The client marks it by prefixing its menuItemId with `CUSTOM:` (plus a UUID,
// so two different manual charges stay two lines) and sends `customName` + `unitPrice` alongside.
// Both are trusted only for STAFF-source orders; the caps below match the APK's own.
const CUSTOM_CHARGE_ID_PREFIX = "CUSTOM:";
const CUSTOM_CHARGE_NAME_MAX = 60;
const CUSTOM_CHARGE_PRICE_MAX = 99_999.99;

function isCustomChargeId(menuItemId: unknown): boolean {
  return typeof menuItemId === "string" && menuItemId.startsWith(CUSTOM_CHARGE_ID_PREFIX);
}

/**
 * Build a custom-charge line, or null when the name or price is unusable — which is the 422 at the
 * call site rather than a silently free line on the customer's bill.
 */
// deno-lint-ignore no-explicit-any
function customChargeLine(item: any, autoPrint: boolean): OrderItemLine | null {
  const name = typeof item.customName === "string"
    ? item.customName.trim().slice(0, CUSTOM_CHARGE_NAME_MAX)
    : "";
  const price = Number(item.unitPrice);
  if (!name) return null;
  if (!Number.isFinite(price) || price <= 0 || price > CUSTOM_CHARGE_PRICE_MAX) return null;
  return {
    id: crypto.randomUUID(),
    menuItemId: item.menuItemId,
    nameSnapshot: name,
    unitPriceSnapshot: Math.round(price * 100) / 100,
    categorySnapshot: "",
    codeSnapshot: "",
    marketPriceSnapshot: false,
    quantity: item.quantity,
    note: item.note || null,
    sentToKitchen: autoPrint,
    sessionNumber: 1,
  };
}

interface MenuItemInfo {
  name: string;
  price: number;
  category: string;
  code: string;
  marketPrice: boolean;
  hasVariablePrice: boolean;
  // Allowed size prices (Small/Medium/Large) for a variable-price item; used to validate a
  // client-chosen unitPrice so a tampered price can't be stored.
  priceOptions: number[];
}

// Collect the Small/Medium/Large preset prices present on a menu item.
function priceOptionsOf(mi: Record<string, unknown>): number[] {
  return [mi.priceOption1, mi.priceOption2, mi.priceOption3]
    .map((v) => Number(v))
    .filter((n) => Number.isFinite(n) && n > 0);
}

/**
 * Resolve a line's price + display name for a Small/Medium/Large ("variable price") item.
 * A client-supplied unitPrice is honored only when it matches one of the item's presets
 * (anti-tamper); the chosen size (e.g. "L") is baked inline into the name → "Nasi Goreng (L)".
 */
function resolveSizedLine(
  menuItem: MenuItemInfo,
  item: { unitPrice?: unknown; size?: unknown },
): { name: string; price: number } {
  let price = menuItem.price;
  const chosen = Number(item.unitPrice);
  if (
    Number.isFinite(chosen) && chosen > 0 &&
    menuItem.hasVariablePrice && menuItem.priceOptions.includes(chosen)
  ) {
    price = chosen;
  }
  let name = menuItem.name;
  if (typeof item.size === "string" && item.size.trim()) {
    const size = item.size.trim().slice(0, 8).replace(/[()]/g, "");
    if (size) name = `${name} (${size})`;
  }
  return { name, price };
}

// Menu item names are stored as a localized-name object ({ en, bm, zh, ta, th,
// doNotTranslate }), not a plain string — see shared/api-contract.md §4. A
// server-side snapshot needs exactly one display string, so this picks `en`
// (falling back to the first non-empty locale) rather than storing the whole
// object, which would otherwise get serialized verbatim into `nameSnapshot`.
function extractDisplayName(rawName: unknown): string {
  if (typeof rawName === "string") return rawName;
  if (rawName && typeof rawName === "object") {
    const localized = rawName as Record<string, unknown>;
    const en = localized.en;
    if (typeof en === "string" && en.trim()) return en;
    for (const value of Object.values(localized)) {
      if (typeof value === "string" && value.trim()) return value;
    }
  }
  return "";
}

function buildMenuLookup(menuJson: unknown): Map<string, MenuItemInfo> {
  const map = new Map<string, MenuItemInfo>();
  if (!menuJson || typeof menuJson !== "object") return map;

  // Menu structure: { categories: [{ id, name, items: [{ id, name, price, available }] }] }
  // Or flat: { items: [...] }
  const menu = menuJson as Record<string, unknown>;

  if (Array.isArray(menu.categories)) {
    for (const cat of menu.categories) {
      const category = cat as Record<string, unknown>;
      const catName = extractDisplayName(category.name) || "Uncategorized";
      if (Array.isArray(category.items)) {
        for (const item of category.items) {
          const mi = item as Record<string, unknown>;
          if (mi.id && mi.available !== false) {
            map.set(mi.id as string, {
              name: extractDisplayName(mi.name),
              price: Number(mi.price) || 0,
              category: catName,
              code: (mi.code as string) || "",
              marketPrice: mi.marketPrice === true,
              hasVariablePrice: mi.hasVariablePrice === true,
              priceOptions: priceOptionsOf(mi),
            });
          }
        }
      }
    }
  }

  if (Array.isArray(menu.items)) {
    for (const item of menu.items) {
      const mi = item as Record<string, unknown>;
      if (mi.id && mi.available !== false) {
        map.set(mi.id as string, {
          name: extractDisplayName(mi.name),
          price: Number(mi.price) || 0,
          category: (mi.category as string) || "Uncategorized",
          code: (mi.code as string) || "",
          marketPrice: mi.marketPrice === true,
          hasVariablePrice: mi.hasVariablePrice === true,
          priceOptions: priceOptionsOf(mi),
        });
      }
    }
  }

  return map;
}

// deno-lint-ignore no-explicit-any
function mapOrderRow(row: any) {
  return {
    id: row.id,
    tableId: row.table_id,
    source: row.source,
    browserId: row.browser_id,
    status: row.status,
    paymentMethod: row.payment_method,
    sentToKitchenAt: row.sent_to_kitchen_at,
    cancelReason: row.cancel_reason,
    cancelledBy: row.cancelled_by,
    total: Number(row.total),
    createdAt: row.created_at,
    items: row.items_json || [],
  };
}
