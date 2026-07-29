# API Contract — System Warung Tom Yam

Version `1.0` (draft). This is the single source of truth shared by the website, the APK,
and the Supabase Edge Functions. Change it here first, then update the implementations.

Base URL: `https://<project>.pages.dev` (frontend) → Supabase Edge Functions under `/api/*`.
All traffic is HTTPS/WSS. All request/response bodies are JSON unless noted.

---

## 1. Authentication

Four caller types. Every endpoint below states which it requires.

| Auth type | Carried as | Issued by | Used by |
|---|---|---|---|
| **Superadmin JWT** | `Authorization: Bearer <supabase-jwt>` | Supabase Auth (email/password) | Website dashboard |
| **Admin session token** | `Authorization: Bearer <sessionToken>` | `POST /api/admin/handshake` | Admin APK |
| **Ordering API key** | `Authorization: Bearer <apiKey>` | device approval | Ordering APK |
| **Public + browser ID** | `X-Browser-Id: <uuid>` (no auth) | client localStorage | Customer web page |

Tokens/keys are stored hashed on the backend and never returned in any response after
issuance. They are never logged.

---

## 2. Enums (shared vocabulary)

```
OrderStatus   = RECEIVED | SENT_TO_KITCHEN | PREPARING | READY | COMPLETED | CANCELLED
OrderSource   = QR | STAFF
PaymentMethod = CASH | QR
Category      = FOOD | BEVERAGES | SIDE_DISHES | OTHERS
AttendanceEvt = CHECK_IN | CHECK_OUT
SessionEvt    = OPEN | CLOSE
DeviceRole    = ADMIN | ORDERING
DeviceStatus  = PENDING | APPROVED | REVOKED
Permission    = CREATE_ORDER | CANCEL_ORDER | SEND_TO_KITCHEN | TAKE_PAYMENT
PrintLanguage = EN | BM              # EN is the default/base
Lang          = en | bm | zh | ta | th    # customer site; en is base, others dictionary-translated
TableState    = FREE | OCCUPIED
```

---

## 3. Realtime channels (Supabase Realtime / WSS)

| Channel | Publisher | Subscriber | Events |
|---|---|---|---|
| `admin-orders` | backend | Admin APK | `NEW_ORDER`, `ITEMS_ADDED` |
| `order:<orderId>` | backend | Customer page | `STATUS_UPDATE` |
| `admin-devices` | backend | Admin APK, Ordering APK | `JOIN_REQUEST`, `APPROVED`, `REJECTED`, `FORCE_CHECKOUT` |
| `admin-attendance` | backend | Admin APK | `CHECK_IN`, `CHECK_OUT` |
| `cafe-status` | backend | Ordering APK | `CAFE_OPEN`, `CAFE_CLOSED` |
| `settings` | backend | web + Ordering APK | `SETTINGS_CHANGED` |
| `branding` | backend | Customer page | `BRANDING_CHANGED` |

### 3.1 Payload schemas per channel

#### `admin-orders`
```
NEW_ORDER   → { type: "NEW_ORDER", order: Order }
ITEMS_ADDED → { type: "ITEMS_ADDED", orderId, tableId, sessionNumber, linesToPrint: [ OrderItem, ... ] }
```
The admin APK's Realtime listener is what actually triggers kitchen printing on both
events — it's the only device with a printer attached, so this is the single path that
makes orders/amendments auto-print regardless of whether they came from a customer QR
scan, the admin app, or a staff device.

#### `order:<orderId>`
```
STATUS_UPDATE → { status: OrderStatus, paymentMethod?: PaymentMethod, updatedAt: "<iso>" }
```

#### `admin-devices`
```
JOIN_REQUEST   → { type: "JOIN_REQUEST", deviceId: "<uuid>", label: "<deviceModel>" }
APPROVED       → { type: "APPROVED", deviceId: "<uuid>" }
REJECTED       → { type: "REJECTED", deviceId: "<uuid>" }
FORCE_CHECKOUT → { type: "FORCE_CHECKOUT", deviceId: "<uuid>", timestamp: "<iso>" }
```

#### `admin-attendance`
```
CHECK_IN  → { type: "CHECK_IN", deviceLabel: "<string>", timestamp: "<iso>" }
CHECK_OUT → { type: "CHECK_OUT", deviceLabel: "<string>", timestamp: "<iso>" }
```

#### `cafe-status`
```
CAFE_OPEN   → { event: "CAFE_OPEN", timestamp: "<iso>" }
CAFE_CLOSED → { event: "CAFE_CLOSED", timestamp: "<iso>" }
```

#### `settings`
Full settings object broadcast on any change:
```
SETTINGS_CHANGED → { printLanguage: PrintLanguage, timezone: "<tz>", topN: <int>,
                     staffCanSendKitchen: <bool>, staffCanTakePayment: <bool>,
                     reportEmail?: "<email>" }
```

#### `branding`
```
BRANDING_CHANGED → { cafeName: "<string>", logoUrl: "<url>" }
```

Realtime is best-effort. Every consumer MUST reconcile on (re)connect via the relevant
`GET` (see catch-up sync, §4.3).

---

## 4. Endpoints

### 4.1 Identity, devices, invitations

#### `GET /api/rotating-key`  — superadmin
30-second HMAC-derived pairing key for the admin handshake. Never stored/logged.
```
200 → { "key": "483921", "expiresInSeconds": 18 }
```

#### `POST /api/admin/handshake`  — public (first-claim)
```
req  → { "deviceId": "<uuid>", "rotatingKey": "483921" }
200  → { "sessionToken": "<opaque>" }
409  → { "error": "ADMIN_EXISTS" }        # an admin device is already registered
401  → { "error": "INVALID_KEY" }          # outside the ±1 window (60s grace)
```

#### `GET /api/invite`  — admin
```
200 → { "token": "<opaque>", "url": "https://<host>/join?invite=<token>" }
```

#### `POST /api/invite/regenerate`  — admin
Invalidates the previous invitation token immediately.
```
200 → { "token": "<opaque>", "url": "..." }
```

#### `POST /api/register`  — public (ordering device, invite-gated)
```
req → { "inviteToken": "<opaque>", "deviceId": "<uuid>",
        "deviceModel": "Samsung Galaxy A23", "androidId": "<hash>", "appVersion": "1.0.0" }
201 → { "deviceId": "<uuid>", "status": "PENDING" }
403 → { "error": "INVALID_INVITE" }
```
Side effect: broadcasts `JOIN_REQUEST` on `admin-devices`.

#### `GET /api/devices/status?deviceId=<uuid>`  — public (poll)
```
200 → { "status": "PENDING" }
200 → { "status": "APPROVED", "role": "ORDERING", "apiKey": "<opaque>" }   # apiKey returned ONCE
200 → { "status": "REVOKED" }
```

#### `GET /api/devices`  — superadmin
```
200 → [ { "id","label","role","status","lastSeenAt","isCheckedIn" }, ... ]
```

#### `PATCH /api/devices/:id`  — superadmin OR admin
Approve/reject/rename/revoke/force-checkout. Role changes are NOT supported.
```
req → { "action": "APPROVE" | "REJECT" | "REVOKE" | "FORCE_CHECKOUT" | "RENAME",
        "label"?: "Counter phone" }
200 → { ...device }
```

### 4.2 Menu, branding, settings, café location

#### `GET /api/menu`  — public (Cache-Control 60s)
```
200 → { "configured": false }                      # before first push
200 → { "printLanguage": "EN",
        "categories": ["FOOD","BEVERAGES","SIDE_DISHES","OTHERS"],
        "items": [ MenuItem, ... ] }
```
`MenuItem`:
```
{ "id":"item_001", "category":"FOOD", "price":6.50, "available":true,
  "askMeDaily":false,
  "hasVariablePrice": false, "variablePriceDailyPrompt": false,
  "priceOption1": null, "priceOption2": null, "priceOption3": null,
  "name": { "en":"Coconut Rice", "bm":"Nasi Lemak", "zh":"椰浆饭", "ta":"நாசி லெமாக்", "th":"ข้าวมันกะทิ",
            "doNotTranslate": false },
  "description": { "en":"...", "bm":"...", "zh":"...", "ta":"...", "th":"..." } }
```
`en` is the authored source; `bm/zh/ta/th` are dictionary-resolved with `en` fallback.

`price` is always the single effective/active price — the only one customers or the website
ever see. `hasVariablePrice` items ("specials" with day-to-day pricing, e.g. market-price
fish) additionally carry up to 3 admin-defined presets (`priceOption1/2/3`), entered once
and editable anytime in Menu Management; `price` holds whichever preset is currently active.
When `variablePriceDailyPrompt` is true, the admin app's daily-login popup asks the admin to
pick that day's active preset; when false, the admin changes it manually instead. These
fields exist purely for the admin app's own bookkeeping (so a `GET /api/menu` → Room
round-trip on next login doesn't silently lose the special-item configuration) — the website
only ever needs to read `price`.

#### `PUT /api/menu`  — admin
Body is the full menu snapshot (same shape as GET `items`). Returns `{ "updatedAt": "<iso>" }`.

#### `GET /api/branding`  — public
```
200 → { "cafeName":"Warung Tom Yam", "logoUrl":"https://.../logo.jpg" }
200 → { "configured": false }
```

#### `PUT /api/branding`  — admin
```
req → { "cafeName":"...", "logoBase64":"<jpeg ≤200KB, NO_WRAP>" }
200 → { "cafeName","logoUrl" }        # also broadcasts on `branding`
```

#### `POST /api/menu-image`  — admin
Uploads one client-resized menu item thumbnail (JPEG, already cropped to 5:4 and
downscaled to 320×256 by the admin app) to the `menu-images` Storage bucket. The
returned URL goes into that item's `image` field on the next `PUT /api/menu`.
```
req → { "menuItemId":"item_001", "imageBase64":"<jpeg, NO_WRAP, ≤~220KB decoded>" }
200 → { "imageUrl":"https://.../menu-images/item_001-<ts>.jpg", "path":"item_001-<ts>.jpg" }
422 → VALIDATION (missing fields, bad base64, or over the size cap)
```
Each upload gets a unique, timestamped path rather than overwriting a fixed name —
Free-tier Storage has no Smart CDN cache invalidation, so a fixed path risks serving
a stale cached image after an edit.

#### `DELETE /api/menu-image`  — admin
Best-effort cleanup of a superseded image (called automatically after a successful
re-upload replaces an item's photo).
```
req → { "path":"item_001-<old-ts>.jpg" }
200 → { "deleted": true }
```

#### `GET /api/settings`  — public (safe subset) / superadmin (full)
```
200 → { "printLanguage":"EN", "timezone":"Asia/Kuala_Lumpur", "topN":5,
        "staffCanSendKitchen":false, "staffCanTakePayment":false,
        "reportEmail":"owner@example.com", "autoSendClosingReport":true }
```
Superadmin sees the full object; public callers receive only `printLanguage` and `timezone`.

#### `PUT /api/settings`  — admin
Partial update (merge semantics); broadcasts full settings on `settings` channel.
```
req → { "printLanguage":"BM" }           # any subset of fields
200 → { ...full settings }
422 → { "error":"VALIDATION" }
```

#### `PUT /api/cafe-location`  — admin
```
req → { "latitude":3.1390, "longitude":101.6869, "radiusMeters":100 }
200 → { ...location }
```

#### `GET /api/cafe-location`  — ordering key
```
200 → { "latitude","longitude","radiusMeters" }
```

### 4.3 Orders, table sessions, payment

#### `POST /api/orders`  — public (browser ID) OR staff/admin (key)
Backend validates the table exists, rejects if the table has an active session, **re-prices
server-side from the current menu snapshot** (client prices ignored), rate-limits by IP +
browser ID.

Orders **auto-print to the kitchen the instant they're placed** — every line is created
`sentToKitchen=true`, `sessionNumber=1`, and the order starts life already
`status=SENT_TO_KITCHEN` (there is no "received but not yet sent" phase anymore). This
applies uniformly regardless of source (customer QR, staff, or admin).
```
req → { "tableId":"T3", "browserId":"<uuid>?",
        "items": [ { "menuItemId":"item_001", "quantity":2, "note":"less spicy" } ] }
201 → { "orderId":"<uuid>", "total":13.00, "status":"SENT_TO_KITCHEN" }
409 → { "error":"TABLE_OCCUPIED" }
422 → { "error":"UNKNOWN_TABLE" | "ITEM_UNAVAILABLE" }
429 → { "error":"RATE_LIMITED" }
```
Each stored line snapshots `name/unitPrice/category`. Side effect: broadcasts `NEW_ORDER`
on `admin-orders` — the admin device's own Realtime listener is what actually triggers the
kitchen print (see §7), since that's the only device with a printer attached.

#### `POST /api/orders/:id/items`  — admin OR permitted staff
Append lines to an active order — one more "round" of ordering at an already-occupied
table. Also auto-prints immediately (`sentToKitchen=true`); each call's lines share the
next `sessionNumber` up from whatever's already on the order (the table's 1st order is
session 1, this is session 2, 3, …). **Capped at 10 sessions per order** — an 11th round
is rejected until the table is paid out and freed.
```
req → { "items": [ { "menuItemId","quantity","note" } ] }
200 → { ...order, "linesToPrint":[ OrderItem, ... ] }   # this round's new lines
409 → { "error":"SESSION_LIMIT" }   # already at 10 rounds on this order
```
Side effect: broadcasts `ITEMS_ADDED` on `admin-orders` (`{ orderId, tableId, sessionNumber,
linesToPrint }`) — again, this is what makes the admin device print this round, whether the
items were added from the admin app or a staff device.

#### `POST /api/orders/:id/kitchen`  — admin OR staff w/ SEND_TO_KITCHEN
**Reprint only.** Since every order/round already auto-prints on placement, this endpoint
no longer marks anything as sent or mutates status/timestamps — it just returns the order's
full current item list so the caller can reprint the whole ticket (e.g. the kitchen printer
jammed or ran out of paper).
```
200 → { "order":{...}, "linesToPrint":[ OrderItem, ... ] }   # ALL items, not a delta
409 → { "error":"ORDER_CLOSED" }
```

#### `PUT /api/orders/:id/status`  — admin
```
req → { "status":"PREPARING" | "READY" }
200 → { ...order }                       # broadcasts on order:<id>
```

#### `POST /api/orders/:id/payment`  — admin OR staff w/ TAKE_PAYMENT
Only valid after `SENT_TO_KITCHEN`.
```
req → { "method":"CASH" | "QR" }
200 → { ...order status=COMPLETED }      # session ends, table → FREE; writes PaymentTransaction
409 → { "error":"NOT_SENT_TO_KITCHEN" }
```
The admin app shows a "print customer receipt?" confirm dialog (10s auto-close = skip
printing) before calling this — the payment call itself doesn't know or care about that
choice, printing is purely a client-side decision made beforehand.

#### `DELETE /api/orders/:id`  — admin/staff anytime; customer only within 60s of placing
Customer self-cancellation used to be gated on `status === RECEIVED`, but orders no longer
pass through that state (they're `SENT_TO_KITCHEN` immediately) — so it's a time-based grace
window instead: `CUSTOMER_CANCEL_WINDOW_MS = 60_000` from `created_at`. After that, only
admin/staff can cancel (unchanged — any non-terminal order).
```
req → { "reason":"...", "cancelledBy":"admin|staff:<label>|customer" }
200 → { ...order status=CANCELLED }      # session ends, table → FREE
403 → { "error":"CANCEL_NOT_ALLOWED" }   # customer past the 60s window
```

#### `GET /api/orders?since=<iso>`  — admin (catch-up sync)
All active orders + status events since the timestamp. Called on every Realtime (re)connect.
```
200 → { "orders":[ Order, ... ], "serverTime":"<iso>" }
```

#### `GET /api/tables/:tableId/session`  — public
```
200 → { "state":"FREE" }
200 → { "state":"OCCUPIED" }                                  # another browser owns it
200 → { "state":"OCCUPIED", "order": Order }                  # caller's X-Browser-Id owns it
```

### 4.4 Sessions, attendance, aggregates, reports

#### `POST /api/sessions`  — admin
```
req → { "event":"OPEN" }                                  # broadcasts CAFE_OPEN
req → { "event":"CLOSE", "reason":"End of day", "closing":true }   # broadcasts CAFE_CLOSED
200 → { "sessionId","event","timestamp" }
```
A new `OPEN` while a prior session has no `CLOSE` implicitly closes the dangling one at last
backend activity.

#### `POST /api/attendance`  — ordering key (or admin for forced)
```
req → { "event":"CHECK_IN"|"CHECK_OUT", "latitude","longitude", "forced":false }
200 → { ...record }                                        # broadcasts on admin-attendance
403 → { "error":"OUTSIDE_RADIUS" }
```

#### `POST /api/aggregates`  — admin
Daily summary pushed at "Sign Out with Closing" and on demand (dashboard's only history source).
```
req → { "date":"2026-07-19", "totalOrders":42, "totalRevenue":315.50,
        "avgOrderValue":7.51, "paymentSplit":{ "cash":{"count":30,"amount":210.00},
        "qr":{"count":12,"amount":105.50} }, "cancelledCount":3, "cancelledValue":21.00,
        "topItemsPerCategory": { "FOOD":[...], "BEVERAGES":[...], "SIDE_DISHES":[...], "OTHERS":[...] } }
200 → { "date" }
```

#### `GET /api/metrics?period=today|week|month|last_month|monthly`  — superadmin
From aggregates + sessions; all boundaries in the café timezone.
```
200 → { "orders":N, "revenue":N, "openHours":N }
# period=monthly → [ { "month":"2026-07", "orders","revenue","openHours" }, ... x12 ]
```

#### `GET /api/reports/closing`  — admin/superadmin
Builds + emails the closing report (Brevo); also returns it for download.
```
200 → { "reportUrl":"<signed-url>", "emailSent":true, "date":"2026-07-19" }
409 → { "error":"NO_AGGREGATE" }           # no aggregate row for today
```

#### `GET /api/reports/monthly?month=YYYY-MM`  — superadmin
As above for the month.
```
200 → { "reportUrl":"<signed-url>", "emailSent":true, "month":"2026-07" }
422 → { "error":"VALIDATION" }             # invalid month format
```

---

## 5. Core object shapes

```
Order = {
  id, tableId, source: OrderSource, browserId?, status: OrderStatus,
  paymentMethod?: PaymentMethod, sentToKitchenAt?, cancelReason?, cancelledBy?,
  total, createdAt,
  items: [ OrderItem, ... ]
}
OrderItem = {
  id, menuItemId, nameSnapshot, unitPriceSnapshot, categorySnapshot: Category,
  quantity, note?, sentToKitchen: bool, sessionNumber: int
}
```
`sessionNumber` groups items by which order-placement round they belong to — 1 for the
table's initial order, 2+ for each subsequent round of items added while still occupied
(capped at 10). Every item's `sentToKitchen` is `true` by the time a client sees it —
auto-print means there's no longer a meaningful "unsent" state to represent.

---

## 6. Error envelope

All non-2xx responses:
```
{ "error": "<MACHINE_CODE>", "message": "<human readable, in printLanguage>" }
```

### 6.1 Error codes reference

| Code | HTTP | Used by |
|---|---|---|
| `UNAUTHORIZED` | 401 | Any endpoint — missing/invalid token/key |
| `FORBIDDEN` | 403 | Any endpoint — valid auth but insufficient permissions |
| `NOT_FOUND` | 404 | Any endpoint — resource not found |
| `RATE_LIMITED` | 429 | `POST /api/orders` — too many requests from IP/browser ID |
| `VALIDATION` | 422 | Any endpoint — request body fails schema validation |
| `ADMIN_EXISTS` | 409 | `POST /api/admin/handshake` — admin device already registered |
| `INVALID_KEY` | 401 | `POST /api/admin/handshake` — rotating key outside ±1 window |
| `INVALID_INVITE` | 403 | `POST /api/register` — invite token expired or invalid |
| `TABLE_OCCUPIED` | 409 | `POST /api/orders` — table already has an active session |
| `UNKNOWN_TABLE` | 422 | `POST /api/orders` — table ID not in the registry |
| `ITEM_UNAVAILABLE` | 422 | `POST /api/orders`, `POST /api/orders/:id/items` — menu item not available |
| `SESSION_LIMIT` | 409 | `POST /api/orders/:id/items` — order already at 10 rounds; pay out and free the table first |
| `NOT_SENT_TO_KITCHEN` | 409 | `POST /api/orders/:id/payment` — cannot pay before kitchen |
| `ORDER_CLOSED` | 409 | `POST /api/orders/:id/items`, `POST /api/orders/:id/kitchen` — order already completed/cancelled |
| `CANCEL_NOT_ALLOWED` | 403 | `DELETE /api/orders/:id` — customer past the 60s post-placement window |
| `OUTSIDE_RADIUS` | 403 | `POST /api/attendance` — device GPS outside café radius |
| `NO_AGGREGATE` | 409 | `GET /api/reports/closing` — no aggregate data for the date |
