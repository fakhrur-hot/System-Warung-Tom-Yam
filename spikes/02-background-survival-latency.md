# Spike 02 — Background survival + Realtime latency

**Status:** ⏳ needs a Supabase project + 2 real Android phones to run (Task 3).
**Goal:** confirm order delivery meets the < 3 s latency NFR (REQ-8) and that a foreground
service keeps the Realtime socket alive on cheap/OEM-aggressive phones.

## Why this is a spike

The whole design bets on Supabase Realtime + a foreground service instead of push
notifications. If latency is > 3 s or the socket dies when the screen is off, the
architecture must change — cheaper to learn now than in Phase 5.

## Part A — latency

1. Stand up the Supabase project (see `supabase/README.md`) and one echo Edge Function that
   broadcasts a timestamped message on `admin-orders`.
2. Minimal APK: subscribe to `admin-orders`, log `receiveTime − sendTime`.
3. Trigger 20 broadcasts over 4G (not Wi-Fi) and record the distribution.

```kotlin
// pseudo — measure end-to-end broadcast latency
channel.on("NEW_ORDER") { msg ->
    val latencyMs = now() - msg.sentAtEpochMs
    log("latency=$latencyMs ms")
}
```

**Gate:** median < 3 s, p95 < 3 s over mobile data. Also measure Edge Function cold-start
and Supabase wake-from-pause (pause the project, hit an endpoint, time the first response).

## Part B — background survival

Minimal APK with a foreground service holding the subscription. On **at least one
Xiaomi/Redmi and one Samsung** budget device:

| Scenario | Socket stays alive? | Notes |
|---|---|---|
| Screen off 30 min | ☐ | |
| Battery saver ON | ☐ | |
| App backgrounded 30 min | ☐ | |
| After device reboot (BOOT_COMPLETED restart) | ☐ | |
| OEM autostart disabled (worst case) | ☐ | |

Record which OEM keep-alive steps were required (battery-exemption prompt, Xiaomi AutoStart,
Samsung "never sleeping apps"). These feed Task 28.

## Deliverable

Measured latency numbers + the survival table + the OEM keep-alive checklist. If the gate
fails, note the fallback (e.g., add FCM high-priority push as a wake nudge).

### Results (fill in)
> _pending — run on Supabase + devices._
