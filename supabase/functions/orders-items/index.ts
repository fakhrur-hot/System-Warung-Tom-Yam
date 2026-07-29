/**
 * POST /api/orders/:id/items — append amendment lines to an active order.
 * Admin or permitted staff only. Re-prices from current menu snapshot.
 * New lines auto-print to the kitchen immediately (sentToKitchen=true) and are
 * tagged with the next sessionNumber up from whatever's already on the order —
 * each call to this endpoint represents one more "round" of ordering at the same
 * still-occupied table. Capped at MAX_SESSIONS rounds per order.
 */
import { serve } from "https://deno.land/std@0.177.0/http/server.ts";
import { handleCors } from "../_shared/cors.ts";
import { verifyAdminToken, verifyOrderingKey } from "../_shared/auth.ts";
import { getSupabaseClient } from "../_shared/supabase.ts";
import { errorResponse, jsonResponse } from "../_shared/errors.ts";

const MAX_SESSIONS = 10;

serve(async (req) => {
  const corsResp = handleCors(req);
  if (corsResp) return corsResp;

  if (req.method !== "POST") {
    return errorResponse(405, "METHOD_NOT_ALLOWED", "Only POST is supported");
  }

  // Auth: admin or staff with ordering key — OR a customer amending their OWN order.
  // A customer has no token, only an x-browser-id; ownership is verified against the
  // order's browser_id once the order is loaded below (so customers can add rounds while
  // waiting, just like the admin/staff side).
  const admin = await verifyAdminToken(req);
  const staff = !admin ? await verifyOrderingKey(req) : null;
  const browserId = (!admin && !staff) ? (req.headers.get("x-browser-id") || null) : null;
  if (!admin && !staff && !browserId) {
    return errorResponse(401, "UNAUTHORIZED", "Admin/staff token or x-browser-id required");
  }

  // Extract orderId from URL path or query param
  const url = new URL(req.url);
  const orderId = url.searchParams.get("orderId") || extractOrderIdFromPath(url.pathname);
  if (!orderId) {
    return errorResponse(422, "VALIDATION", "orderId is required");
  }

  const body = await req.json().catch(() => null);
  if (!body || !Array.isArray(body.items) || body.items.length === 0) {
    return errorResponse(422, "VALIDATION", "items[] is required");
  }

  const supabase = getSupabaseClient();

  // Fetch existing order
  const { data: order, error: orderError } = await supabase
    .from("orders")
    .select("*")
    .eq("id", orderId)
    .single();

  if (orderError || !order) {
    return errorResponse(404, "NOT_FOUND", "Order not found");
  }

  // A customer (no token) may only amend the order they placed.
  if (!admin && !staff && order.browser_id !== browserId) {
    return errorResponse(403, "FORBIDDEN", "You can only add to your own order");
  }

  // Only allow amendments to active orders
  if (order.status === "COMPLETED" || order.status === "CANCELLED") {
    return errorResponse(409, "ORDER_CLOSED", "Cannot amend a completed or cancelled order");
  }

  // Load menu snapshot for re-pricing
  const { data: menuRow } = await supabase
    .from("menu_snapshot")
    .select("menu_json")
    .eq("id", 1)
    .single();

  if (!menuRow) {
    return errorResponse(500, "SERVER_ERROR", "Menu snapshot not available");
  }

  const menuItems = buildMenuLookup(menuRow.menu_json);

  // Read the auto-print setting server-side; default true if row is missing
  const { data: autoPrintSetting } = await supabase
    .from("settings")
    .select("value")
    .eq("key", "customer_order_auto_print")
    .single();
  const autoPrint = autoPrintSetting?.value !== "false";

  const existingItems = (order.items_json as Record<string, unknown>[]) || [];

  const currentMaxSession = existingItems.reduce(
    (max, item) => Math.max(max, Number(item.sessionNumber) || 1),
    0,
  );
  const nextSession = currentMaxSession + 1;
  if (nextSession > MAX_SESSIONS) {
    return errorResponse(
      409,
      "SESSION_LIMIT",
      `This table has reached the maximum of ${MAX_SESSIONS} order rounds — please pay out and free the table first`,
    );
  }

  const newItems: Record<string, unknown>[] = [];
  let additionalTotal = 0;

  for (const item of body.items) {
    if (!item.menuItemId || !item.quantity || item.quantity < 1) {
      return errorResponse(422, "VALIDATION", "Each item must have menuItemId and quantity >= 1");
    }

    const menuItem = menuItems.get(item.menuItemId);
    if (!menuItem) {
      return errorResponse(422, "ITEM_UNAVAILABLE", `Menu item '${item.menuItemId}' not found or unavailable`);
    }

    // Small/Medium/Large: honor the client's chosen size price (validated) and bake the size
    // into the display name ("Nasi Goreng (L)").
    const priced = resolveSizedLine(menuItem, item);

    // sentToKitchen follows the customerOrderAutoPrint setting: true means print
    // immediately (current default), false means hold for cashier confirmation.
    const lineItem = {
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
      sessionNumber: nextSession,
    };
    newItems.push(lineItem);
    additionalTotal += priced.price * item.quantity;
  }

  const updatedItems = [...existingItems, ...newItems];
  const newTotal = Math.round((Number(order.total) + additionalTotal) * 100) / 100;

  // Update order
  const { data: updated, error: updateError } = await supabase
    .from("orders")
    .update({
      items_json: updatedItems,
      total: newTotal,
    })
    .eq("id", orderId)
    .select("*")
    .single();

  if (updateError) {
    return errorResponse(500, "SERVER_ERROR", updateError.message);
  }

  // Broadcast ITEMS_ADDED on admin-orders — this is how the admin device's printer
  // learns to print this round's kitchen slip, regardless of whether the items were
  // added from the admin app itself or a staff device (staff devices have no printer).
  try {
    const channel = supabase.channel("admin-orders");
    await channel.send({
      type: "broadcast",
      event: "ITEMS_ADDED",
      payload: {
        orderId,
        tableId: updated.table_id,
        sessionNumber: nextSession,
        linesToPrint: newItems,
      },
    });
  } catch (_e) {
    // Non-critical: items were added successfully even if broadcast fails
  }

  return jsonResponse({ ...mapOrderRow(updated), linesToPrint: newItems });
});

// ── Helpers ────────────────────────────────────────────────────────────────
function extractOrderIdFromPath(pathname: string): string | null {
  // Pattern: /orders-items/<orderId> or similar
  const segments = pathname.split("/").filter(Boolean);
  // Last segment might be the orderId if it looks like a UUID
  for (const seg of segments) {
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(seg)) {
      return seg;
    }
  }
  return null;
}

interface MenuItemInfo {
  name: string;
  price: number;
  category: string;
  code: string;
  marketPrice: boolean;
  hasVariablePrice: boolean;
  priceOptions: number[];
}

// Collect the Small/Medium/Large preset prices present on a menu item.
function priceOptionsOf(mi: Record<string, unknown>): number[] {
  return [mi.priceOption1, mi.priceOption2, mi.priceOption3]
    .map((v) => Number(v))
    .filter((n) => Number.isFinite(n) && n > 0);
}

/**
 * Resolve a line's price + display name for a Small/Medium/Large item. A client unitPrice is
 * honored only when it matches a preset (anti-tamper); the size is baked inline into the name.
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
