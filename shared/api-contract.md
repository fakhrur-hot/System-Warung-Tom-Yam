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
Lang          = en | bm | zh | ta    # customer site; en is base, others dictionary-translated
TableState    = FREE | OCCUPIED
```

---

## 3. Realtime channels (Supabase Realtime / WSS)

| Channel | Publisher | Subscriber | Payload |
|---|---|---|---|
| `admin-orders` | backend | Admin APK | `{ type: "NEW_ORDER", order: Order }` |
| `order:<orderId>` | Admin APK → backend | Customer page | `{ status: OrderStatus, paymentMethod?: PaymentMethod }` |
| `admin-devices` | backend | Admin APK | `{ type: "JOIN_REQUEST", deviceId, label }` |
| `admin-attendance` | backend | Admin APK | `{ deviceLabel, event: AttendanceEvt, timestamp }` |
| `cafe-status` | backend | Ordering APK | `{ event: "CAFE_OPEN" \| "CAFE_CLOSED" }` |
| `settings` | backend | web + Ordering APK | `{ printLanguage: PrintLanguage, ... }` |
| `branding` | backend | Customer page | `{ cafeName, logoUrl }` |

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
  "name": { "en":"Coconut Rice", "bm":"Nasi Lemak", "zh":"椰浆饭", "ta":"நாசி லெமாக்",
            "doNotTranslate": false },
  "description": { "en":"...", "bm":"...", "zh":"...", "ta":"..." } }
```
`en` is the authored source; `bm/zh/ta` are dictionary-resolved with `en` fallback.

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

#### `GET /api/settings`  — public (safe subset) / superadmin (full)
```
200 → { "printLanguage":"EN", "timezone":"Asia/Kuala_Lumpur", "topN":5,
        "staffCanSendKitchen":false, "staffCanTakePayment":false }
```

#### `PUT /api/settings`  — admin
Partial update; broadcasts on `settings`.

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
```
req → { "tableId":"T3", "browserId":"<uuid>?",
        "items": [ { "menuItemId":"item_001", "quantity":2, "note":"less spicy" } ] }
201 → { "orderId":"<uuid>", "total":13.00, "status":"RECEIVED" }
409 → { "error":"TABLE_OCCUPIED" }
422 → { "error":"UNKNOWN_TABLE" | "ITEM_UNAVAILABLE" }
429 → { "error":"RATE_LIMITED" }
```
Each stored line snapshots `name/unitPrice/category` and carries `sentToKitchen=false`.
Side effect: broadcasts `NEW_ORDER` on `admin-orders`.

#### `POST /api/orders/:id/items`  — admin OR permitted staff
Append lines to an active order (amendment); each new line `sentToKitchen=false`; re-priced.
```
req → { "items": [ { "menuItemId","quantity","note" } ] }
200 → { ...order }
```

#### `POST /api/orders/:id/kitchen`  — admin OR staff w/ SEND_TO_KITCHEN
Marks unsent lines sent, stamps `sentToKitchenAt`, sets status `SENT_TO_KITCHEN`.
```
200 → { "order":{...}, "linesToPrint":[ OrderItem, ... ] }   # delta = new lines only
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

#### `DELETE /api/orders/:id`  — admin/staff anytime; customer only while RECEIVED
```
req → { "reason":"...", "cancelledBy":"admin|staff:<label>|customer" }
200 → { ...order status=CANCELLED }      # session ends, table → FREE
403 → { "error":"CANCEL_NOT_ALLOWED" }   # customer after SENT_TO_KITCHEN
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

#### `GET /api/reports/monthly?month=YYYY-MM`  — superadmin
As above for the month.

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
  quantity, note?, sentToKitchen: bool
}
```

---

## 6. Error envelope

All non-2xx responses:
```
{ "error": "<MACHINE_CODE>", "message": "<human readable, in printLanguage>" }
```
Common codes: `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `RATE_LIMITED`, `VALIDATION`,
`ADMIN_EXISTS`, `TABLE_OCCUPIED`, `NOT_SENT_TO_KITCHEN`, `CANCEL_NOT_ALLOWED`,
`INVALID_KEY`, `INVALID_INVITE`, `OUTSIDE_RADIUS`.
