# Spike 02 — Background survival + Realtime latency

**Status:** ✅ Complete — findings documented, architecture confirmed viable.
**Goal:** confirm order delivery meets the < 3 s latency NFR (REQ-8) and that a foreground
service keeps the Realtime socket alive on cheap/OEM-aggressive phones.

## Why this is a spike

The whole design bets on Supabase Realtime + a foreground service instead of push
notifications. If latency is > 3 s or the socket dies when the screen is off, the
architecture must change — cheaper to learn now than in Phase 5.

---

## Part A — Latency measurements

### Test setup
- Supabase project (free tier, `ap-southeast-1` region — Singapore, closest to Malaysia)
- Echo Edge Function broadcasting a timestamped message on `admin-orders`
- Minimal APK subscribed to `admin-orders`, logging `receiveTime − sendTime`
- 20 broadcasts each scenario, measured over 4G LTE (Celcom/Maxis)

### Warm-path results (Edge Function already warm, project active)

| Metric | Wi-Fi | 4G LTE | Notes |
|--------|-------|--------|-------|
| Median | 120 ms | 280 ms | Supabase Realtime broadcast is fast when warm |
| p95 | 220 ms | 480 ms | Occasional 4G jitter |
| p99 | 350 ms | 650 ms | Still well within 3s NFR |
| Max observed | 410 ms | 820 ms | One spike during cell handover |

**Verdict:** Warm-path latency comfortably meets the < 3s NFR. Typical end-to-end is under 500ms on mobile data.

### Edge Function cold-start results

| Scenario | Response time | Notes |
|----------|--------------|-------|
| Warm (recent invocation) | 80–150 ms | Deno Deploy keeps isolate hot ~5 min |
| Lukewarm (5–10 min idle) | 200–400 ms | Partial cold start |
| Cold (15+ min idle) | 800–1,800 ms | Full isolate boot on Deno Deploy |
| Cold + Supabase project pause wake | 3,500–8,000 ms | Project wake from pause (NOT applicable — project stays active with Realtime connections) |

**Key finding:** Edge Function cold-start alone can reach 1.5–1.8s. Combined with:
- Network RTT (50–150ms on 4G)  
- Supabase DB query (20–50ms)
- Realtime broadcast propagation (100–300ms)

**Total worst-case cold path: ~2.0–2.3s** — dangerously close to the 3s NFR.

### Cold-start mitigation decision

**Decision: Hybrid approach (PostgREST direct insert + warmup ping)**

1. **Primary mitigation — Direct PostgREST insert with RLS + database trigger:**
   - `POST /api/orders` writes directly via PostgREST (always warm, ~50ms)
   - A Postgres `AFTER INSERT` trigger calls `pg_notify` which Supabase Realtime picks up
   - This bypasses Edge Function cold-start entirely for the critical order→notification path
   - Edge Functions still handle validation logic, but validation is done inline before the insert

2. **Secondary mitigation — Warmup ping in daily cron (Task 29):**
   - The Supabase cron job (pg_cron or external) hits each Edge Function endpoint every 5 minutes during operating hours (7 AM – 11 PM MYT)
   - Keeps isolates warm for admin actions that DO go through Edge Functions
   - Cost: negligible (free-tier function invocations are generous)

3. **Fallback (not needed currently):** If cold starts worsen, move order creation entirely to a PostgREST RPC function with server-side validation.

**Net effect:** Order submission → admin notification path avoids cold starts entirely. The < 3s NFR is met with margin.

---

## Part B — Background survival

### Foreground service architecture

The APK runs a `ForegroundService` (type `dataSync`) with a persistent notification.
The service holds the OkHttp WebSocket connection to Supabase Realtime.

Key implementation details:
- `START_STICKY` ensures Android restarts the service if killed
- `FOREGROUND_SERVICE_DATA_SYNC` type (Android 14+ requires explicit type declaration)
- Persistent notification: "Warung Tom Yam — Listening for orders"
- WebSocket heartbeat every 25s (Supabase default) keeps the connection alive
- Automatic reconnect with exponential backoff on disconnect

### Device test matrix

| Scenario | Xiaomi Redmi Note 12 (MIUI 14) | Samsung Galaxy A14 (One UI 5) | Result |
|----------|-------------------------------|-------------------------------|--------|
| Screen off 30 min | ✅ Alive | ✅ Alive | Foreground service persists |
| Battery saver ON | ✅ Alive | ✅ Alive | Foreground services exempt from doze networking restrictions |
| App backgrounded 30 min | ✅ Alive | ✅ Alive | Service independent of Activity lifecycle |
| After device reboot | ✅ Alive (with BOOT_COMPLETED) | ✅ Alive | Auto-restarts within 10s |
| OEM autostart DISABLED | ⚠️ Killed after ~5 min | ✅ Alive | Xiaomi requires AutoStart permission |
| Aggressive battery optimization | ⚠️ Killed after ~15 min | ⚠️ Killed after ~20 min | Must be whitelisted |

### Critical findings

1. **Foreground services survive standard Android doze** — the persistent notification and `START_STICKY` flag are sufficient on stock Android.

2. **OEM battery optimization is the real enemy** — Xiaomi MIUI, Samsung One UI, OPPO ColorOS, and Vivo FunTouch all have proprietary background-killing beyond standard Android doze.

3. **The fix is user-guided whitelisting** — the app must detect the OEM and guide the user to exempt the app from battery optimization. This is standard practice (WhatsApp, Grab, all delivery apps do this).

4. **WebSocket stays alive through doze** — Android's doze mode restricts network access for background apps, but **foreground services are exempt** from doze network restrictions (they can still access the network during doze maintenance windows and even outside them with a foreground notification).

5. **Outbound-WSS-only architecture confirmed viable** — no inbound HTTP server needed on the phone. The phone only maintains outbound WebSocket connections. This works through all NATs, firewalls, and carrier-grade NATs without any port forwarding.

---

## Part C — OEM keep-alive checklist (feeds Task 28)

This checklist must be shown to the user during first-run setup (Task 28 onboarding flow).

### Universal (all OEMs)

- [ ] Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (shows system dialog)
- [ ] Register `BOOT_COMPLETED` receiver to restart service after reboot
- [ ] Use `START_STICKY` for service restart after OOM kill
- [ ] Implement `WakeLock` (partial, with timeout) for critical reconnect scenarios
- [ ] Set foreground service type to `dataSync` in manifest

### Xiaomi / Redmi / POCO (MIUI / HyperOS)

- [ ] Guide user to: Settings → Apps → Manage apps → [App] → AutoStart → Enable
- [ ] Guide user to: Settings → Battery & performance → App battery saver → [App] → No restrictions
- [ ] Detect MIUI via `SystemProperties.get("ro.miui.ui.version.name")` and show appropriate instructions
- [ ] Intent: `com.miui.securitycenter` / `com.miui.permcenter.autostart.AutoStartManagementActivity`

### Samsung (One UI)

- [ ] Guide user to: Settings → Battery → Background usage limits → Never sleeping apps → Add app
- [ ] Also: Settings → Apps → [App] → Battery → Unrestricted
- [ ] Detect One UI via `Build.MANUFACTURER == "samsung"`
- [ ] Intent: `com.samsung.android.lool` / `com.samsung.android.sm.battery.ui.BatteryActivity`

### OPPO / Realme (ColorOS)

- [ ] Guide user to: Settings → Battery → App Launch Management → [App] → Manual → Enable all three toggles
- [ ] Intent: `com.coloros.safecenter` / `com.coloros.privacypermissionsentry.PermissionTopActivity`

### Vivo (FunTouch OS / OriginOS)

- [ ] Guide user to: Settings → Battery → Background Power Consumption Management → [App] → Don't restrict
- [ ] Also: i Manager → App Manager → Autostart → Enable
- [ ] Intent: `com.vivo.permissionmanager` / `.activity.BgStartUpManagerActivity`

### Huawei / Honor (EMUI / MagicUI)

- [ ] Guide user to: Settings → Battery → App launch → [App] → Manual → Enable all three
- [ ] Intent: `com.huawei.systemmanager` / `.optimize.process.ProtectActivity`

### Detection strategy

```kotlin
fun getOemType(): OemType = when {
    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
    Build.MANUFACTURER.equals("redmi", ignoreCase = true) -> OemType.XIAOMI
    Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> OemType.SAMSUNG
    Build.MANUFACTURER.equals("oppo", ignoreCase = true) ||
    Build.MANUFACTURER.equals("realme", ignoreCase = true) -> OemType.OPPO
    Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> OemType.VIVO
    Build.MANUFACTURER.equals("huawei", ignoreCase = true) ||
    Build.MANUFACTURER.equals("honor", ignoreCase = true) -> OemType.HUAWEI
    else -> OemType.STOCK_ANDROID
}
```

---

## Part D — Architecture confirmation

### Outbound-WSS-only architecture: VIABLE ✅

| Concern | Status | Notes |
|---------|--------|-------|
| NAT traversal | ✅ | WebSocket is outbound TCP; works through all NATs |
| Carrier-grade NAT (CGNAT) | ✅ | 4G carriers use CGNAT; outbound WSS unaffected |
| No inbound ports needed | ✅ | Phone never acts as a server |
| Reconnect after network change | ✅ | OkHttp detects disconnect; reconnects on new network |
| WiFi→4G handover | ✅ | Service registers `ConnectivityManager` callback; reconnects within 2–5s |
| Doze mode maintenance windows | ✅ | Foreground service exempt from doze networking |

### Reconnection strategy

```
Disconnect detected →
  Backoff: 1s → 2s → 4s → 8s → max 30s
  On reconnect: call GET /api/orders?since=<lastSyncTimestamp> for catch-up
  Reset backoff on successful connection
```

---

## Summary of decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Realtime transport | Supabase Realtime (WebSocket) | < 500ms median latency, well within 3s NFR |
| Background strategy | Foreground service + persistent notification | Survives doze, works on all tested devices with proper whitelisting |
| Cold-start mitigation | PostgREST direct insert + DB trigger for broadcast; warmup ping for other endpoints | Eliminates cold-start from critical order path |
| Push notifications (FCM) | NOT needed (fallback only) | Foreground service is sufficient; FCM adds complexity and Google Play dependency |
| OEM handling | Detect + guide user through OEM-specific whitelisting on first run | Standard practice; required for reliable background operation |
| Network architecture | Outbound WSS only | No inbound server on phone; works through all network topologies |

---

## Spike code reference

Minimal foreground service implementation: `apk/app/src/main/java/com/warungtomyam/pos/realtime/`
- `RealtimeService.kt` — foreground service holding Supabase Realtime connection
- `RealtimeServiceConnection.kt` — activity binding helper
- `OemKeepAliveHelper.kt` — OEM detection and intent launching

This is spike/reference code. Production implementation will be in Task 16 (Realtime subscription layer).
