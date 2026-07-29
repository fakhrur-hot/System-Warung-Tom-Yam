# Chaos & Reconnect Testing Runbook

> **Purpose**: Validate that System Warung Tom Yam recovers gracefully from real-world
> failures — dropped connections, offline periods, backend outages, and cold starts.
> Every test here must pass before the full-day field rehearsal (Task 30).

---

## 1. Chaos Test Scenarios

### 1.1 WebSocket Kill Mid-Order

**Scenario**: The WebSocket connection drops while an order is in-flight (e.g., network
blip, Android kills the socket, or the phone transitions between Wi-Fi and cellular).

**Setup**:
1. Admin APK is online, connected via Realtime to `admin-orders` channel.
2. A customer submits an order via the PWA (`POST /api/orders`).

**Execution**:
1. While the order is being submitted (or immediately after), kill the WebSocket:
   - **On device**: Toggle Airplane mode ON for 3–5 seconds, then OFF.
   - **Via Supabase dashboard**: Terminate the Realtime connection for the test device (if possible).
   - **Via Android ADB**: `adb shell svc wifi disable && sleep 5 && adb shell svc wifi enable`
2. Wait for the device to reconnect.

**Expected Behaviour**:
- The APK detects the WebSocket disconnection (heartbeat timeout or socket close event).
- On reconnect, the APK calls `GET /api/orders?since=<lastKnownTimestamp>` to fetch any
  orders missed during the disconnect window.
- The missed order appears on the Admin Table View within 3 seconds of reconnection.
- No duplicate orders are shown (idempotent by order ID).
- The order status is accurate (matches what the backend holds).

**Pass Criteria**:
- [ ] Order received on admin APK after reconnect — no data loss.
- [ ] No duplicate entries in the Table View.
- [ ] Catch-up completed within 3 seconds of network restoration.
- [ ] No crash or ANR on the APK during reconnect.

---

### 1.2 Airplane-Mode Staff Order (Offline Queue Flush)

**Scenario**: A staff member's phone loses connectivity while taking orders. Orders are
queued locally via `PendingOrder` and flushed when connectivity returns.

**Setup**:
1. Staff phone is signed in, approved, and connected.
2. Confirm at least one order can be placed successfully online (baseline).

**Execution**:
1. Enable **Airplane mode** on the staff phone.
2. Place **2 orders** from the staff ordering interface (different tables).
3. Verify orders are saved to the local `PendingOrder` queue (Room database).
4. Re-enable network (disable Airplane mode).
5. Wait for WorkManager to trigger the pending-order sync job.

**Expected Behaviour**:
- Orders are persisted locally in `PendingOrder` table immediately.
- The UI shows a "Pending — will sync when online" indicator.
- On network restoration, WorkManager fires the sync worker.
- Both orders are submitted to `POST /api/orders` in sequence.
- The backend accepts both (no duplicates due to idempotency keys).
- The Admin APK receives both orders via Realtime broadcast.
- The `PendingOrder` rows are deleted after successful submission.

**Pass Criteria**:
- [ ] Both orders arrive on admin APK after re-enable — no loss.
- [ ] No duplicate orders on the backend (check `orders` table).
- [ ] PendingOrder queue is empty after flush.
- [ ] Flush completes within 10 seconds of network restoration.
- [ ] Staff UI updates from "Pending" to "Submitted" for both orders.

---

### 1.3 Backend Down During Payment (No Double-Pay)

**Scenario**: The backend fails or times out during the payment confirmation request.
The system must not record a double payment or leave the order in an inconsistent state.

**Setup**:
1. An order exists in `SENT_TO_KITCHEN` status (ready for payment).
2. Backend is reachable (confirm with a health check first).

**Execution**:
1. Initiate payment on the admin APK: `POST /api/orders/:id/payment` with
   `{ method: "CASH", amount: 15.00 }`.
2. **Simulate backend failure** during the request — choose one method:
   - **Kill Supabase Edge Function mid-flight**: pause the Supabase project from the
     dashboard immediately after sending the request (timing-critical).
   - **Network interrupt on device**: toggle Airplane mode ON 500ms after tapping "Confirm Payment".
   - **Throttle via Cloudflare**: temporarily block the IP to simulate a 503.
3. The request will timeout or return an error.
4. Restore connectivity / unpause Supabase.
5. The admin retries payment (either manually or via auto-retry).

**Expected Behaviour**:
- The payment endpoint is **idempotent**: if the same order + same idempotency key is
  submitted again, the backend returns success without creating a second payment record.
- The `payment_transactions` table contains exactly **one** record for this order.
- The order transitions to `COMPLETED` exactly once.
- If the first request actually succeeded on the backend (but the response was lost),
  the retry detects this and returns the existing payment record.

**Pass Criteria**:
- [ ] Exactly 1 row in `payment_transactions` for the order (no double-pay).
- [ ] Order status is `COMPLETED` (not stuck in `SENT_TO_KITCHEN`).
- [ ] Admin UI reflects the correct final state after retry.
- [ ] No orphaned payment records or inconsistent session state.
- [ ] The table session is properly ended (table returns to FREE).

---

### 1.4 Supabase Wake-from-Pause During Service

**Scenario**: Supabase free-tier projects pause after 7 days of inactivity. If the
keep-alive cron (Task 29) fails, the first request of the day hits a paused project.

**Setup**:
1. Pause the Supabase project manually from the Supabase dashboard.
2. Have the admin APK and customer PWA ready to make requests.

**Execution**:
1. From the admin APK, attempt to open a session (`POST /api/sessions`).
2. Measure the time from request to successful response.
3. Alternatively, from a customer phone, scan the QR and attempt to load the menu
   (`GET /api/menu`).
4. Record the wake time.

**Expected Behaviour**:
- Supabase wakes from pause within ~30–60 seconds (documented behaviour).
- The first request may timeout (Edge Functions have a 60s limit).
- Subsequent requests succeed normally after wake.
- The APK and PWA handle the initial failure gracefully:
  - APK: shows "Connecting to server..." with a retry button.
  - PWA: shows "Loading menu..." with auto-retry.

**Pass Criteria**:
- [ ] Supabase wakes successfully (project status returns to ACTIVE).
- [ ] Wake time recorded: ______ seconds (expected: 30–60s).
- [ ] First request fails gracefully (no crash, clear user feedback).
- [ ] Second request succeeds within normal latency (< 3s).
- [ ] All Realtime subscriptions reconnect after wake.
- [ ] Recovery path documented for the ops runbook.

**Mitigation**: The daily GitHub Actions keep-alive ping (Task 29) prevents this scenario
in production. This test validates the recovery path in case the cron fails.

---

## 2. Pre-Flight Test Matrix

All tests below must pass before proceeding to the full-day field rehearsal (Task 30).

| Operational Zone | Risk Area | Pre-Flight Test | Pass Criteria |
|---|---|---|---|
| Android APK | Background Killers | Turn off screen, leave phone idle for 45 min, send test order via Supabase dashboard. | Beep within 3 seconds |
| Printers | Concurrency Lock | Trigger 3 simultaneous receipts + kitchen slip while one is already printing. | Queue resolves cleanly, no garbled output |
| Network | Flight Mode / Off-Grid | Airplane mode → 2 orders → re-enable network. | Catch-up sync reconciles, no duplicates |
| Edge Functions | Cold Start Latency | Leave system idle for 2 hours, submit order from 4G cellular. | End-to-end < 3 seconds |

### Pre-Flight Test Execution Steps

#### Test A: Background Killers (Android APK)

1. Start the admin APK, confirm Realtime connection is active.
2. Turn off the screen (power button). Do **not** kill the app.
3. Wait **45 minutes** (set a timer).
4. From the Supabase dashboard SQL editor, insert a test order:
   ```sql
   INSERT INTO orders (table_id, status, items, created_at)
   VALUES ('table-01', 'NEW', '[{"name":"Test Item","qty":1,"price":5.00}]', now());
   ```
5. Listen for the notification beep on the phone.
6. **Pass**: beep heard within 3 seconds of insert.

#### Test B: Printer Concurrency Lock

1. Connect the Bluetooth printer and confirm pairing.
2. Queue a kitchen slip print job (e.g., mark an order as "Send to Kitchen").
3. Immediately (within 1 second), trigger 3 manual receipt prints from the admin UI.
4. Observe the printer output.
5. **Pass**: all 4 documents print sequentially without garbled/overlapping text.

#### Test C: Flight Mode / Off-Grid

1. Staff phone is connected and approved.
2. Enable Airplane mode.
3. Place 2 orders from the staff UI (different tables).
4. Disable Airplane mode.
5. Wait for sync.
6. **Pass**: both orders appear on admin APK, no duplicates in database.

#### Test D: Cold Start Latency

1. Do not interact with the system for 2 hours (no API calls, no WebSocket activity).
2. From a phone on 4G cellular (not Wi-Fi), submit an order via the customer PWA.
3. Measure time from "Place Order" tap to the beep/notification on the admin APK.
4. **Pass**: total elapsed time < 3 seconds.

---

## 3. Test Log Template

Use this template to record test results during execution:

| Test ID | Date | Tester | Result | Notes |
|---|---|---|---|---|
| 1.1 — WS Kill | | | PASS / FAIL | |
| 1.2 — Airplane Queue | | | PASS / FAIL | |
| 1.3 — Payment Idempotent | | | PASS / FAIL | |
| 1.4 — Supabase Wake | | | PASS / FAIL | |
| A — Background Killer | | | PASS / FAIL | Beep time: ___s |
| B — Printer Concurrency | | | PASS / FAIL | |
| C — Flight Mode | | | PASS / FAIL | |
| D — Cold Start | | | PASS / FAIL | Latency: ___s |

---

## 4. Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| Catch-up sync returns empty | `purge_after` too aggressive | Verify purge job keeps orders for 24h minimum |
| Orders duplicated after reconnect | Missing idempotency check | Verify order ID dedup on APK insert |
| Payment recorded twice | Missing idempotency key on payment endpoint | Add/verify `Idempotency-Key` header |
| No beep after 45 min idle | Foreground service killed by OEM | Check battery optimization whitelist (Task 28) |
| Printer garbles output | Missing queue lock in print service | Verify `PrintJobQueue` uses sequential dispatch |
| Cold start > 3s | Edge Function cold boot | Verify keep-alive cron is active (Task 29) |

---

## 5. Cloudflare WAF & Rate Limiting Configuration

### Purpose

Protect the Supabase free-tier quota (**500,000 Edge Function invocations/month**) from
bot traffic, credential stuffing, and automated abuse. At ~100 legitimate orders/day the
system uses a fraction of this budget — but a single bot crawl or brute-force attack could
exhaust it in hours.

### WAF Rules (Free Tier)

Cloudflare's free plan includes 5 custom WAF rules. We use 2:

| Rule | Match | Action | Notes |
|------|-------|--------|-------|
| **Rule 1 — Block Attack Vectors** | URI path contains `/api/*` AND (body/query matches common SQLi patterns like `' OR 1=1`, `UNION SELECT`, or XSS payloads like `<script>`, `javascript:`) | Block | Catches the low-hanging fruit. Patterns from OWASP Top 10. |
| **Rule 2 — JS Challenge Suspicious IPs** | Threat score > 14 OR known bot category (verified bot = allow, unverified bot = challenge) | JS Challenge | Cloudflare assigns threat scores from IP reputation. JS challenges stop simple scripts while allowing real browsers. |

**Deployment strategy**: Start both rules in **Log (monitor) mode** for at least 1 week.
Review the Security Events log to confirm no false positives on legitimate customer or
admin traffic. Only then switch the action from "Log" to "Block" / "JS Challenge".

### Rate Limiting Rules

| Route Pattern | Limit | Window | Action | Rationale |
|---|---|---|---|---|
| `/api/*` (all API routes) | 10 requests/second per IP | 10s sliding window | Block for 60s | Normal customer usage is 2–3 requests on page load. 10/s allows bursts during menu browsing without letting scrapers through. |
| `/api/orders` (POST only) | 5 requests/second per IP | 10s sliding window | Block for 60s | Order submission is a single tap. Even the fastest staff member won't exceed 1 order/second. 5/s gives headroom while blocking automated order spam. |

### Implementation Steps

1. **Via Cloudflare Dashboard** (recommended for first-time setup):
   - Navigate to **Security → WAF → Custom Rules → Create Rule**
   - Configure expression using the visual builder or raw expression:
     ```
     (http.request.uri.path contains "/api/" and
      http.request.body contains "UNION SELECT")
     ```
   - Set action to **Log** initially
   - Repeat for Rate Limiting: **Security → WAF → Rate Limiting Rules → Create Rule**

2. **Via Wrangler CLI** (if using Workers routing):
   ```bash
   # Rate limiting can be configured in wrangler.toml for Workers-based routing
   # However, for Pages projects, the Dashboard method above is simpler
   ```

3. **Monitor phase** (first week):
   - Action set to **Log** (not Block)
   - Check **Security → Events** tab daily
   - Look for any legitimate requests being flagged (false positives)
   - Adjust expressions if needed (e.g., whitelist admin IP ranges)

4. **Enforce phase** (after 1 week):
   - Review the log — confirm zero false positives on real traffic
   - Switch Rule 1 action from "Log" to **Block**
   - Switch Rule 2 action from "Log" to **JS Challenge**
   - Switch Rate Limiting action from "Log" to **Block for 60s**
   - Continue monitoring weekly

### Impact Analysis

| Traffic Type | Effect | Explanation |
|---|---|---|
| Customer PWA loads (HTML, JS, CSS) | **Unaffected** | Static assets served from Cloudflare CDN cache. WAF rules target `/api/*` paths only. |
| Authenticated admin/staff API traffic | **Passes through** | Low volume (well under 10 req/s per IP). Admin typically makes 1–2 API calls per action. |
| Customer order submission | **Passes through** | Single POST request per order. Even busy service is ~1 order/minute from any single customer IP. |
| Bot/crawler traffic | **Throttled or blocked** | Bots hitting `/api/*` are rate-limited or challenged before requests reach Supabase Edge Functions. |
| Legitimate web crawlers (Googlebot) | **Allowed via managed rule** | Cloudflare's "Verified Bot" category is allowed through. SEO not impacted. |

### Monitoring

- **Weekly**: Check **Cloudflare Analytics → Security → Events** tab.
  - Review blocked/challenged requests
  - Confirm no legitimate traffic is being caught
  - Note any new attack patterns
- **Monthly**: Cross-reference Supabase Edge Function invocation count with Cloudflare
  request count. A large gap (CF requests >> Supabase invocations) confirms rate limiting
  is doing its job.
- **Alert**: Cloudflare free tier does not support alerts. Rely on the weekly manual check
  or set a calendar reminder.

---

*Last updated: Task 27 — Phase 10 Hardening*
