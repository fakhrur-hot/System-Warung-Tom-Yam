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
  // Admin-only
  const admin = await verifyAdminToken(req);
  if (!admin) {
    return errorResponse(401, "UNAUTHORIZED", "Admin token required");
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

    const menuItem = menuItems.get(item.menuItemId);
    if (!menuItem) {
      return errorResponse(422, "ITEM_UNAVAILABLE", `Menu item '${item.menuItemId}' not found or unavailable`);
    }

    // sentToKitchen is conditional on the customerOrderAutoPrint setting.
    // sessionNumber is always 1 for the initial order; later rounds appended
    // via POST /orders/:id/items get the next sessionNumber up.
    const lineItem: OrderItemLine = {
      id: crypto.randomUUID(),
      menuItemId: item.menuItemId,
      nameSnapshot: menuItem.name,
      unitPriceSnapshot: menuItem.price,
      categorySnapshot: menuItem.category,
      codeSnapshot: menuItem.code,
      marketPriceSnapshot: menuItem.marketPrice,
      quantity: item.quantity,
      note: item.note || null,
      sentToKitchen: autoPrint,
      sessionNumber: 1,
    };
    orderItems.push(lineItem);
    total += menuItem.price * item.quantity;
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

interface MenuItemInfo {
  name: string;
  price: number;
  category: string;
  code: string;
  marketPrice: boolean;
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
