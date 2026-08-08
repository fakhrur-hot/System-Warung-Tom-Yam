package com.razstudio.pos.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.MainActivity
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.json.OrderMapper
import com.razstudio.pos.data.json.ParseException
import com.razstudio.pos.data.json.toEntity
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.TableDao
import com.razstudio.pos.printing.PrintService
import com.razstudio.pos.ui.i18n.LanguageManager
import com.razstudio.pos.ui.i18n.uiStrings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Production foreground service that maintains a Supabase Realtime WebSocket connection
 * for the admin-orders channel. Implements catch-up sync on every (re)connect.
 *
 * Key behaviors:
 * - START_STICKY: Android restarts the service if the process is killed
 * - Foreground notification: prevents doze from restricting network access
 * - Automatic reconnection with exponential backoff
 * - On every (re)connect: calls GET /api/orders?since=<lastSeen> and reconciles with Room
 * - Parses NEW_ORDER broadcast events: inserts into Room, updates lastSeen
 * - Notification beep on each new order
 */
@AndroidEntryPoint
class RealtimeService : Service() {

    companion object {
        private const val TAG = "RealtimeService"
        private const val CHANNEL_ID = "realtime_channel"
        // Separate high-importance channel so new orders pop a heads-up alert WITH sound,
        // while the ongoing foreground notification stays silent/low.
        // v2: the channel is now silent (the alert tone is played by NewOrderSoundPlayer so it can
        // be picked and volume-adjusted). A channel's sound is immutable once created, so switching
        // to a silent channel REQUIRES a new id — the old one is deleted in createNotificationChannel.
        private const val NEW_ORDER_CHANNEL_ID = "new_orders_channel_v2"
        private const val LEGACY_NEW_ORDER_CHANNEL_ID = "new_orders_channel"
        private const val NEW_ORDER_NOTIF_BASE = 2000
        private const val SYNC_PREFS_NAME = "realtime_sync_prefs"
        private const val KEY_LAST_SEEN = "last_seen_timestamp"

        // Reconnection backoff
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        // Periodic catch-up poll — a low-power safety net so new orders are still picked
        // up within ~10s even when the WebSocket is silently stale (broadcast missed, or
        // a doze-throttled socket that hasn't tripped a reconnect yet). Reuses the same
        // GET /orders?since=lastSeen catch-up, so it's a cheap incremental fetch.
        private const val POLL_INTERVAL_MS = 10_000L

        // Task 8.2 (amended 2026-08-01) — LAN/Kiosk poll uses a longer fallback interval.
        // The push channel (task 8.4's WebSocket route) carries low-latency delivery in LAN Mode,
        // so a 3 s or 10 s poll would be redundant load on a phone-hosted server. 45 s sits
        // comfortably inside the 30–60 s window specified by the task while being a round number.
        // Requirement 6.3 is met because push already achieves parity with Cloud Mode's live path;
        // the poll is here as an eventual-consistency floor (Requirement 6.6), not the fast path.
        private const val LAN_POLL_INTERVAL_MS = 45_000L

        /** Task 8.7 reconnect backoff. Generous: a dropped socket costs latency, never correctness. */
        private const val LAN_PUSH_INITIAL_BACKOFF_MS = 2_000L
        private const val LAN_PUSH_MAX_BACKOFF_MS = 30_000L

        /**
         * Tighter catch-up interval used only while Ambient (screensaver) mode is on screen.
         *
         * The live Realtime socket does not deliver NEW_ORDER frames in the field (see
         * [performCatchUpSync]), so the ambient display's "new order" animation is only as fast as
         * this poll. 5s halves the worst-case delay while keeping the idle request footprint
         * modest — going much lower multiplies Edge Function invocations around the clock for a
         * purely cosmetic gain.
         */
        private const val AMBIENT_POLL_INTERVAL_MS = 5_000L

        /**
         * Set by the ambient overlay while it is visible. Volatile rather than a flow because the
         * polling loop only needs the latest value at each tick.
         */
        @Volatile
        var ambientModeActive: Boolean = false

        // How long the customerOrderAutoPrint setting is cached before the poll refetches it,
        // so toggling auto-print takes effect within ~this window without a GET every poll.
        private const val AUTO_PRINT_TTL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, RealtimeService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeService::class.java))
        }
    }

    private var webSocket: WebSocket? = null

    /** LAN push socket (task 8.7). Null whenever disconnected; the poll covers the gap. */
    private var lanPushSocket: WebSocket? = null
    private var lanPushBackoffMs = LAN_PUSH_INITIAL_BACKOFF_MS
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var isConnected = false
    private var lastMessageTime = 0L
    private var pollingJob: Job? = null

    // --- Kitchen auto-print (exactly-once) state ---
    // The live Realtime broadcast that was meant to trigger kitchen auto-printing is not
    // delivering frames in the field (the socket connects but no NEW_ORDER arrives), so
    // auto-printing is driven off the reliable catch-up poll instead. [printedKitchenIds]
    // records which order-item ids have already been printed on THIS device so the 10s
    // poll never reprints the same slip; it is seeded (without printing) on the first sync
    // after start so pre-existing orders are not printed retroactively.
    // Capped at 2000 entries to bound memory on long-running shifts.
    private val printedKitchenIds = LinkedHashSet<String>()
    private val autoPrintMutex = kotlinx.coroutines.sync.Mutex()
    private var firstSyncSeeded = false
    @Volatile private var cachedAutoPrint = true
    private var autoPrintFetchedAt = 0L

    // Live new-order alerting (independent of auto-print). Keyed by order-item id and seeded
    // on the first sync after start so pre-existing orders don't alert retroactively.
    // Capped at 2000 entries to bound memory on long-running shifts.
    private val notifiedItemIds = LinkedHashSet<String>()
    private var firstNotifySeeded = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    /** Add ids to a [LinkedHashSet], evicting oldest entries beyond [maxSize]. */
    private fun <T> LinkedHashSet<T>.addCapped(items: Collection<T>, maxSize: Int = 2000) {
        addAll(items)
        while (size > maxSize) remove(iterator().next())
    }

    private lateinit var syncPrefs: SharedPreferences

    @Inject lateinit var apiClient: ApiClient
    @Inject lateinit var orderDao: OrderDao
    @Inject lateinit var printService: PrintService
    @Inject lateinit var tableDao: TableDao
    @Inject lateinit var languageManager: LanguageManager
    @Inject lateinit var appConfig: com.razstudio.pos.data.AppConfigStore
    @Inject lateinit var modeRepository: com.razstudio.pos.data.ModeRepository
    @Inject lateinit var noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard
    @Inject lateinit var lanServer: com.razstudio.pos.data.lan.LanServer
    @Inject lateinit var secureStorage: com.razstudio.pos.data.SecureStorage
    @Inject lateinit var newOrderSound: NewOrderSoundPlayer
    @Inject lateinit var posNotificationStatus: PosNotificationStatus

    private fun s() = uiStrings(languageManager.language.value)

    // Task 18.1. `by lazy` because @Inject fields are not populated until onCreate, and a
    // property initialiser would read the guard before Hilt has set it.
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(noInternetGuard)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
            .pingInterval(25, TimeUnit.SECONDS)    // Match Supabase heartbeat interval
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        syncPrefs = getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        // Dependencies (apiClient, orderDao) are provided by Hilt via @AndroidEntryPoint.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        startForegroundWithNotification()
        startLanServerIfServer()
        connectLanPushSocket()
        connectToRealtime()
        startPeriodicPolling()
        // START_STICKY: system will restart this service after a kill
        return START_STICKY
    }

    /**
     * Low-power periodic catch-up: every [POLL_INTERVAL_MS] pull any orders newer than
     * lastSeen and reconcile. Guarded so repeated onStartCommand calls don't stack
     * multiple pollers. This is the belt-and-suspenders to the WebSocket — it guarantees
     * a new order is reflected within ~10s even if the live broadcast was missed.
     *
     * Also runs a stale-socket watchdog: if the WebSocket has been silent for more than
     * 90 seconds (3× the 25s ping interval) it is assumed dead and a reconnect is forced.
     */
    private fun startPeriodicPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = serviceScope.launch {
            while (true) {
                // Ambient mode tightens the interval so an idle station surfaces new orders sooner.
                // Off-cloud (LAN/Kiosk), use the longer fallback interval — the push channel
                // handles latency, the poll is only the eventual-consistency floor (task 8.2,
                // amended 2026-08-01; Requirements 6.3, 6.6).
                val baseInterval = when (modeRepository.currentMode()) {
                    com.razstudio.pos.data.OperatingMode.CLOUD -> POLL_INTERVAL_MS
                    else -> LAN_POLL_INTERVAL_MS
                }
                delay(if (ambientModeActive) AMBIENT_POLL_INTERVAL_MS else baseInterval)
                performCatchUpSync()

                // Stale-socket watchdog: force reconnect if no WS message in >90s
                val silentMs = System.currentTimeMillis() - lastMessageTime
                if (isConnected && lastMessageTime > 0 && silentMs > 90_000L) {
                    Log.w(TAG, "WebSocket silent for ${silentMs}ms — forcing reconnect")
                    isConnected = false
                    connectToRealtime()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        // Stopped with the service that owns it, so a LAN café's clients lose the server at the same
        // moment they lose everything else, rather than talking to a listener whose process is going
        // away underneath it.
        lanServer.stop()
        lanPushSocket?.close(1000, "Service destroyed")
        lanPushSocket = null
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        serviceJob.cancel()
        super.onDestroy()
    }

    // --- Catch-up Sync ---

    private fun getLastSeenTimestamp(): String {
        return syncPrefs.getString(KEY_LAST_SEEN, null)
            ?: Instant.now().minusSeconds(86400).toString() // Default: 24h ago
    }

    private fun setLastSeenTimestamp(timestamp: String) {
        syncPrefs.edit().putString(KEY_LAST_SEEN, timestamp).apply()
    }

    /**
     * On every (re)connect, fetch all orders since lastSeen and reconcile with Room.
     * This ensures zero lost orders even if WebSocket drops temporarily.
     */
    private fun performCatchUpSync() {
        // A host has nobody to catch up with: it *is* the source of truth, and every order already
        // lives in its own Room database. Polling here would mean issuing HTTP requests with no
        // backend to send them to — which is exactly how a host ended up reporting that it could
        // not reach itself.
        if (modeRepository.isLanHost()) return

        val client = apiClient
        val dao = orderDao

        serviceScope.launch {
            val since = getLastSeenTimestamp()
            Log.i(TAG, "Catch-up sync: fetching orders since $since")

            when (val result = client.getOrdersSince(since)) {
                is ApiResult.Success -> {
                    val response = result.data
                    Log.i(TAG, "Catch-up sync: received ${response.orders.size} orders")

                    for (orderDto in response.orders) {
                        reconcileOrder(dao, orderDto)
                    }

                    // Update lastSeen to server time
                    setLastSeenTimestamp(response.serverTime)

                    // Alert live (heads-up notification + sound) for genuinely new orders/
                    // added items, then auto-print freshly-sent kitchen items. Both run off
                    // this poll because the live broadcast is not delivering frames.
                    maybeNotifyNewOrders(response.orders)
                    autoPrintFromSync(response.orders)
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "Catch-up sync failed: ${result.code} - ${result.message}")
                }
                is ApiResult.NetworkError -> {
                    Log.e(TAG, "Catch-up sync network error: ${result.message}")
                }
            }
        }
    }

    /**
     * Reconcile a single order from the backend with Room.
     * Insert or update the order and its items.
     */
    private suspend fun reconcileOrder(dao: OrderDao, orderDto: OrderDto) {
        dao.insertOrder(orderDto.toEntity())
        if (orderDto.items.isNotEmpty()) {
            // Replace, never append. A split share is written to Room twice: once locally at
            // creation (insertShareIntoRoom, client-minted line ids) and again when this sync pulls
            // the server's copy (server-minted ids). REPLACE on differing primary keys is an
            // insert, so without clearing first every share ended up with two copies of each line
            // — and anything that summed them (the session's combined receipt) came out doubled.
            // The void path has done delete-then-insert for the same reason all along.
            dao.deleteItemsForOrder(orderDto.id)
            dao.insertOrderItems(orderDto.items.map { it.toEntity(orderDto.id) })
        }
    }

    /**
     * Auto-print kitchen slips for freshly-sent items seen by the catch-up poll. This is the
     * reliable auto-print path — in the field the live Realtime broadcast connects but does
     * not deliver NEW_ORDER frames, so [handleNewOrderMessage] never fires; every order still
     * arrives here within ~10s via the poll.
     *
     * Guarantees:
     * - Exactly-once: each order-item id is printed at most once per device via [printedKitchenIds].
     * - No history flood: the first sync after (re)start only SEEDS the set (prints nothing),
     *   so orders placed before this session started are never reprinted.
     * - Honors customerOrderAutoPrint: when OFF, items are recorded but not printed (they show
     *   up in the Pending Kitchen Prints buffer for manual printing), so a later flip to ON
     *   won't retroactively reprint them.
     * - Amendments: when an order already has printed kitchen items, its new lines print with
     *   the "ADDED" header.
     */
    private suspend fun autoPrintFromSync(orders: List<OrderDto>) {
        val autoPrintOn = isAutoPrintEnabled()
        autoPrintMutex.withLock {
            val shouldPrint = firstSyncSeeded && autoPrintOn
            for (orderDto in orders) {
                val items = orderDto.items.map { it.toEntity(orderDto.id) }
                val newlySent = items.filter { it.sentToKitchen && it.id !in printedKitchenIds }
                if (newlySent.isEmpty()) continue

                // A split share's items are already part of the bill someone else is still
                // sitting at — already sent, already cooking. Record them as seen so this poll
                // never reconsiders them, but never print a slip: there is no new food to make.
                if (orderDto.isSplitShare) {
                    printedKitchenIds.addCapped(newlySent.map { it.id })
                    continue
                }

                if (shouldPrint) {
                    // Print one slip per session (order round) so each round's ticket is
                    // separate and marked "Session #N" — the kitchen can tell a freshly
                    // placed round apart from earlier rounds already cooked/served.
                    newlySent.groupBy { it.sessionNumber }.toSortedMap().forEach { (session, sessionItems) ->
                        // If this session already had lines printed earlier, this is an added round.
                        val isAmendment = session > 1 ||
                            items.any { it.sessionNumber == session && it.sentToKitchen && it.id in printedKitchenIds }
                        printService.printKitchenSlip(
                            tableId = orderDto.tableId,
                            items = sessionItems,
                            isAmendment = isAmendment,
                            sessionNumber = session,
                            // Requirement 3.5 — a Kiosk slip is headed by its running number.
                            orderNumber = orderDto.orderNumber,
                        )
                        Log.i(
                            TAG,
                            "🖨️ Auto-printed ${sessionItems.size} kitchen line(s) for " +
                                "${orderDto.tableId ?: "#${orderDto.orderNumber}"} session $session " +
                                "(amendment=$isAmendment)"
                        )
                    }
                }
                // Record whether or not we printed, so the next poll never reprints these,
                // and an OFF→ON toggle doesn't retroactively print items confirmed while OFF.
                printedKitchenIds.addCapped(newlySent.map { it.id })
            }
            firstSyncSeeded = true
        }
    }

    /** customerOrderAutoPrint, cached for [AUTO_PRINT_TTL_MS] so the poll doesn't refetch every cycle. */
    private suspend fun isAutoPrintEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - autoPrintFetchedAt > AUTO_PRINT_TTL_MS) {
            when (val r = apiClient.getSettings()) {
                is ApiResult.Success -> {
                    cachedAutoPrint = r.data.customerOrderAutoPrint
                    autoPrintFetchedAt = now
                }
                else -> { /* keep last known value on transient failure */ }
            }
        }
        return cachedAutoPrint
    }

    // --- WebSocket Connection ---

    private fun startForegroundWithNotification() {
        posNotificationStatus.updateRealtimeStatus(s().notifListening)
        val notification = posNotificationStatus.buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Must match the manifest exactly or startForeground throws. See the manifest comment
            // for why this is remoteMessaging: dataSync is capped at ~6h/day on Android 15 and was
            // taking the till down mid-service.
            startForeground(
                PosNotificationStatus.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(PosNotificationStatus.NOTIFICATION_ID, notification)
        }
    }

    /**
     * The Realtime socket URL, or null when this device has no backend to point it at.
     *
     * ## Why null and not a best-effort string
     *
     * This used to interpolate whatever it found. With no stored Supabase URL and an empty
     * `BuildConfig.SUPABASE_URL` — which is exactly how the template ships — it produced
     * `"/realtime/v1/websocket?apikey=&vsn=1.0.0"`, OkHttp rejected the schemeless address with
     * `IllegalArgumentException: Expected URL scheme 'http' or 'https'`, and the exception escaped
     * `onStartCommand` and killed the process.
     *
     * That is a launch crash on a **fresh install**: the template carries no URL, CLOUD is the
     * default mode, so a new café's first run died before its owner could reach the Setup wizard.
     * It is the same schemeless-URL shape `ApiClient.baseUrl()` was hardened against — missed here
     * because this path builds its address by string replacement instead of going through it.
     */
    private fun getSupabaseRealtimeUrl(): String? {
        val supabaseUrl = appConfig.supabaseUrl().ifBlank { BuildConfig.SUPABASE_URL }
        if (!supabaseUrl.startsWith("http")) return null
        val anonKey = appConfig.supabaseAnonKey().ifBlank { BuildConfig.SUPABASE_ANON_KEY }
        if (anonKey.isBlank()) return null
        val baseUrl = supabaseUrl.replace("https://", "wss://")
        return "$baseUrl/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
    }

    /**
     * Run the LAN HTTP server for as long as this service lives (task 6.3, Requirements 4.1, 4.6).
     *
     * Hosted here rather than in its own service on purpose: this one already holds the foreground
     * notification and the wake lock that keep the admin device reachable through Doze. A second
     * service would need its own copy of both, and the two could then disagree — a café whose staff
     * phones can reach the server only while the admin screen happens to be awake is worse than one
     * where the whole thing is up or down together.
     *
     * Gated on LAN **and** ADMIN. Kiosk has no clients to serve, Cloud has Supabase, and a Client
     * Device starting its own server would answer its peers with an empty database.
     */
    private fun startLanServerIfServer() {
        // Cloud cafés run the server too, but PUSH-ONLY (task: cloud fast path). The REST routes are
        // the HTTP face of LocalBackend, which is a mirror here rather than the authority — staff must
        // keep reading and writing the cloud. What they gain is latency: a frame arrives in
        // milliseconds and triggers the same catch-up sync their poll runs, so the floor stops waiting
        // on a poll tick and the poll interval can be relaxed.
        if (modeRepository.currentMode() == com.razstudio.pos.data.OperatingMode.CLOUD) {
            if (lanServer.start(pushOnly = true)) {
                Log.i(TAG, "Cloud push server on ${lanServer.boundHost} (push-only)")
            } else {
                Log.w(TAG, "Cloud push server did not start — staff fall back to their poll")
            }
            return
        }

        // Was gated on the stored role, which is written when the owner taps "Host this café" —
        // often after this service has already started and decided not to serve. The café then had
        // no LAN server at all and no indication why. `isLanHost` reads pairing configuration
        // instead, which is settled long before the service runs.
        if (!modeRepository.isLanHost()) return

        if (lanServer.start()) {
            Log.i(TAG, "LAN server started on ${lanServer.boundHost}")
        } else {
            // Not fatal: the admin device still works standalone, and the pairing screen shows the
            // same reason LanAddress reported. Retried on the next service start.
            Log.w(TAG, "LAN server did not start — no usable network")
        }
    }

    /**
     * The LAN push socket (task 8.7, Requirements 6.5, 6.6, 6.4).
     *
     * ### A push is a TRIGGER, not a payload to apply
     *
     * This is the one decision worth understanding. The obvious reading of "apply received deltas"
     * is to mutate local state straight from the frame — and it is wrong here. The de-duplication
     * that Requirement 6.4 depends on lives in [autoPrintFromSync] and [maybeNotifyNewOrders], both
     * of which key off **order-item ids** and therefore need whole `OrderDto`s. A delta carries an
     * order id and a status, not its items.
     *
     * Applying deltas directly would mean a second path into printing that de-duplicates against
     * different data from the poll's. The two would eventually disagree, and the way that surfaces
     * is a kitchen slip printing twice in the middle of service — exactly what Property 6 exists to
     * prevent.
     *
     * So a `STATUS_UPDATE` runs [performCatchUpSync] immediately: the *same* function the poll runs,
     * with the same `?since=` window and the same de-dup sets. Push contributes latency and nothing
     * else. It costs one extra HTTP round trip after the frame — tens of milliseconds against a poll
     * interval measured in tens of seconds — and in exchange there is exactly one code path that can
     * print a slip.
     *
     * ### The poll is unchanged and still authoritative
     *
     * Every push may be lost — socket down, frame dropped, Client backgrounded — and the café still
     * converges on the next poll tick (Requirement 6.6). Nothing here is load-bearing on its own.
     */
    private fun connectLanPushSocket() {
        if (modeRepository.currentMode() != com.razstudio.pos.data.OperatingMode.LAN) return

        val base = appConfig.lanServerUrl()
        if (base.isBlank()) return
        val credential = secureStorage.getApiKey() ?: return

        lanPushSocket?.close(1001, "Reconnecting")
        lanPushSocket = null

        val url = base.trimEnd('/')
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/functions/v1/realtime"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $credential")
            .build()

        lanPushSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "LAN push connected")
                lanPushBackoffMs = LAN_PUSH_INITIAL_BACKOFF_MS
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = com.razstudio.pos.data.lan.LanPushEnvelope.decode(text) ?: return
                if (envelope.type != com.razstudio.pos.data.lan.LanPushEnvelope.Type.STATUS_UPDATE) return

                // Acknowledge before syncing, so the Server's view of delivery does not depend on
                // how long the follow-up fetch takes.
                runCatching {
                    webSocket.send(
                        com.razstudio.pos.data.lan.LanPushEnvelope(
                            type = com.razstudio.pos.data.lan.LanPushEnvelope.Type.ACK,
                            sessionId = envelope.sessionId,
                            messageId = envelope.messageId,
                            timestamp = envelope.timestamp,
                            ackFor = envelope.messageId,
                        ).encode()
                    )
                }

                serviceScope.launch { performCatchUpSync() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Not an error state for the café: the poll keeps working. Reconnect on backoff.
                Log.w(TAG, "LAN push disconnected (${t.message}) — poll continues to cover")
                lanPushSocket = null
                serviceScope.launch {
                    delay(lanPushBackoffMs)
                    lanPushBackoffMs = (lanPushBackoffMs * 2).coerceAtMost(LAN_PUSH_MAX_BACKOFF_MS)
                    connectLanPushSocket()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "LAN push closing: $code $reason")
                lanPushSocket = null
            }
        })
    }

    private fun connectToRealtime() {
        // Task 8.1 / Requirement 6.2: the Supabase Realtime socket only exists in Cloud Mode.
        //
        // The URL is built by string-replacing "https://" with "wss://". Off-cloud there is no
        // Supabase URL — and if one lingered, or the BuildConfig fallback filled in, the result is a
        // wss:// address that cannot resolve. OkHttp would then retry it on the backoff schedule
        // below, forever: a permanent background reconnect loop draining the battery of the one
        // tablet the café depends on, for a service that cannot exist in this topology.
        //
        // Returning before the URL is even constructed is deliberate — the point is that nothing is
        // attempted, not that a failed attempt is handled quietly. Live updates off-cloud come from
        // the periodic catch-up poll, which needs no socket.
        if (!modeRepository.currentCapabilities().realtimeWebSocket) {
            Log.i(TAG, "Realtime WebSocket disabled for ${modeRepository.currentMode()} — using catch-up poll only")
            return
        }
        // Close any existing socket cleanly before opening a new one —
        // prevents a leaked connection when onStartCommand fires more than once
        // (START_STICKY restart, or redundant calls from AdminHomeScreen).
        webSocket?.close(1001, "Reconnecting")
        webSocket = null

        val url = getSupabaseRealtimeUrl() ?: run {
            // Unconfigured, not broken. The owner still reaches Setup, and the catch-up poll covers
            // everything the socket would have carried.
            Log.i(TAG, "No backend configured yet — Realtime socket not started")
            return
        }

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected = true
                currentBackoffMs = INITIAL_BACKOFF_MS
                lastMessageTime = System.currentTimeMillis()

                // Join the admin-orders channel
                val joinPayload = """
                    {
                        "topic": "realtime:admin-orders",
                        "event": "phx_join",
                        "payload": {},
                        "ref": "1"
                    }
                """.trimIndent()
                webSocket.send(joinPayload)

                posNotificationStatus.updateRealtimeStatus(s().notifConnected)

                // Perform catch-up sync on every (re)connect
                performCatchUpSync()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageTime = System.currentTimeMillis()
                Log.d(TAG, "Message received: ${text.take(200)}")

                if (text.contains("NEW_ORDER")) {
                    handleNewOrderMessage(text)
                } else if (text.contains("ITEMS_ADDED")) {
                    handleItemsAddedMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: code=$code reason=$reason")
                isConnected = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                posNotificationStatus.updateRealtimeStatus(s().notifDisconnected)
                scheduleReconnect()
            }
        })
    }

    /**
     * Parse NEW_ORDER event from Realtime broadcast, persist to Room, and print the
     * kitchen slip. This is the single path that auto-prints a freshly-placed order
     * regardless of who placed it — customer QR, admin, or staff (staff devices have
     * no printer, so this admin-side listener is the only place their orders print).
     * Every line in a brand-new order is session 1, so this is never an amendment.
     */
    private fun handleNewOrderMessage(text: String) {
        val dao = orderDao

        serviceScope.launch {
            try {
                val json = JSONObject(text)
                val payload = json.optJSONObject("payload") ?: return@launch
                val orderJson = payload.optJSONObject("order") ?: return@launch

                val dto = OrderMapper.orderDto(orderJson)
                dao.insertOrder(dto.toEntity())
                if (dto.items.isNotEmpty()) {
                    // Replace, never append — see reconcileOrder: the local copy of a split
                    // share carries different line ids than the server's, so appending doubles it.
                    dao.deleteItemsForOrder(dto.id)
                    dao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
                }

                // Update lastSeen timestamp
                setLastSeenTimestamp(dto.createdAt)

                Log.i(TAG, "🔔 NEW_ORDER persisted: ${dto.id} for table ${dto.tableId}")

                // Only print to kitchen if at least one item is marked sentToKitchen.
                // When customerOrderAutoPrint is false, all items arrive with
                // sentToKitchen = false — still beep/badge, but skip the print.
                // A split share is bookkeeping for an already-open table, not a new order —
                // never a slip to print (see autoPrintFromSync's identical poll-path guard).
                val itemEntities = dto.items.map { it.toEntity(dto.id) }
                if (!dto.isSplitShare && itemEntities.any { it.sentToKitchen }) {
                    printService.printKitchenSlip(
                        orderNumber = dto.orderNumber,
                        tableId = dto.tableId,
                        items = itemEntities,
                        isAmendment = false
                    )
                }
            } catch (e: ParseException) {
                Log.e(TAG, "NEW_ORDER dropped — missing/null required field '${e.field}'", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse NEW_ORDER message", e)
            }

            // Play beep and broadcast to UI
            playNotificationBeep()
            val intent = Intent("com.razstudio.pos.NEW_ORDER")
            intent.putExtra("payload", text)
            sendBroadcast(intent)
        }
    }

    /**
     * Parse ITEMS_ADDED event (an amendment round appended to an already-occupied
     * table — see POST /api/orders/:id/items) and print just that round's delta.
     */
    private fun handleItemsAddedMessage(text: String) {
        serviceScope.launch {
            try {
                val json = JSONObject(text)
                val payload = json.optJSONObject("payload") ?: return@launch
                val tableId = payload.optString("tableId").takeIf { it.isNotBlank() } ?: return@launch
                val orderId = payload.optString("orderId").takeIf { it.isNotBlank() } ?: return@launch
                val linesArray = payload.optJSONArray("linesToPrint") ?: return@launch

                val newItems = OrderMapper.orderItemDtos(linesArray).map { it.toEntity(orderId) }
                if (newItems.isEmpty()) return@launch

                // Reflect the new lines in Room so the table's order detail view is current.
                orderDao.insertOrderItems(newItems)

                Log.i(TAG, "🔔 ITEMS_ADDED: ${newItems.size} line(s) for table $tableId")
                // Only print to kitchen if at least one added item is marked sentToKitchen.
                // When customerOrderAutoPrint is false, all added items arrive with
                // sentToKitchen = false — still update Room and beep, but skip the print.
                if (newItems.any { it.sentToKitchen }) {
                    // No orderNumber here, and that is not an omission: this path is fed by the
                    // Supabase realtime channel and bails above when `tableId` is blank, so it is
                    // structurally table-service only. Kiosk has no network and no tables, and
                    // reaches the printer through the till instead.
                    printService.printKitchenSlip(
                        tableId = tableId,
                        items = newItems,
                        isAmendment = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ITEMS_ADDED message", e)
            }
        }
    }

    /** Plays the café's configured alert tone at its configured volume. */
    private fun playNotificationBeep() {
        newOrderSound.play()
    }

    /**
     * Exponential backoff reconnection using a coroutine delay instead of
     * Handler.postDelayed — runs entirely on the service's IO scope, no
     * main-thread dependency.
     * 1s → 2s → 4s → 8s → 16s → 30s (capped)
     */
    private fun scheduleReconnect() {
        val backoff = currentBackoffMs
        Log.i(TAG, "Reconnecting in ${backoff}ms")
        currentBackoffMs = (backoff * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_BACKOFF_MS)
        serviceScope.launch {
            delay(backoff)
            if (!isConnected) {
                connectToRealtime()
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // Ongoing foreground-service channel — silent, low importance (never buzzes).
        val ongoing = NotificationChannel(
            CHANNEL_ID,
            "Order Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the app connected in the background to receive new orders"
            setShowBadge(false)
        }
        manager.createNotificationChannel(ongoing)

        // Live new-order channel — high importance so it still pops a heads-up alert and vibrates,
        // but SILENT: the alert tone is played by [NewOrderSoundPlayer] instead, because a channel's
        // sound cannot be changed once created and a channel cannot offer volume control at all.
        // Leaving the channel's own sound on would double-ping.
        val newOrders = NotificationChannel(
            NEW_ORDER_CHANNEL_ID,
            "New Orders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a new order or added items arrive"
            enableVibration(true)
            enableLights(true)
            setSound(null, null)
            setShowBadge(true)
        }
        manager.createNotificationChannel(newOrders)

        // Drop the pre-configurable-sound channel. Its sound is baked in and unchangeable, so
        // devices upgrading from an older build would keep hearing the old default tone on top of
        // the configured one.
        try {
            manager.deleteNotificationChannel(LEGACY_NEW_ORDER_CHANNEL_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete legacy new-order channel", e)
        }
    }

    /**
     * Fire a heads-up, sound-carrying notification for genuinely new orders (and added
     * rounds) as seen by the catch-up poll. Independent of the auto-print setting — the
     * café is alerted to every incoming order even when kitchen printing is buffered.
     * Seeds on the first sync after (re)start so historical orders don't alert.
     */
    private suspend fun maybeNotifyNewOrders(orders: List<OrderDto>) {
        autoPrintMutex.withLock {
            for (orderDto in orders) {
                val itemIds = orderDto.items.map { it.id }
                if (itemIds.isEmpty()) continue
                // Same reasoning as autoPrintFromSync: a split share is bookkeeping for an
                // already-open table, not a new order arriving — never worth a heads-up alert.
                if (orderDto.isSplitShare) {
                    notifiedItemIds.addCapped(itemIds)
                    continue
                }
                val newIds = itemIds.filter { it !in notifiedItemIds }
                if (newIds.isNotEmpty() && firstNotifySeeded) {
                    val wholeOrderNew = itemIds.all { it !in notifiedItemIds }
                    // Kiosk sales carry no table — the notification names the running number.
                    val tableName = orderDto.tableId?.let { resolveTableName(it) }
                        ?: orderDto.orderNumber?.let { "#$it" } ?: "—"
                    val title = if (wholeOrderNew) s().notifNewOrderTitle.format(tableName) else s().notifItemsAddedTitle.format(tableName)
                    val body = if (wholeOrderNew) {
                        s().notifNewOrderBody
                    } else {
                        s().notifItemsAddedBody.format(newIds.size)
                    }
                    postNewOrderNotification(orderDto.id, title, body)
                    Log.i(TAG, "🔔 Notified: $title")
                }
                notifiedItemIds.addCapped(itemIds)
            }
            firstNotifySeeded = true
        }
    }

    private fun postNewOrderNotification(orderId: String, title: String, body: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NEW_ORDER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // sound+vibrate on pre-O; channel drives O+
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        // Distinct id per order so alerts stack rather than overwrite one another.
        getSystemService(NotificationManager::class.java)
            .notify(NEW_ORDER_NOTIF_BASE + (orderId.hashCode() and 0xFFFF), notification)

        // The channel is silent by design (see createNotificationChannel), so the audible alert has
        // to come from here. This is the path that actually fires in the field — the live socket
        // does not deliver NEW_ORDER frames, so every real order is announced via the catch-up poll.
        newOrderSound.play()
    }

    /**
     * Table's admin-entered display name for the alert; falls back to the id.
     *
     * Kept non-null internally: the caller already handles the tableless (Kiosk) case by naming the
     * running number, so a null reaching here would be a bug rather than a state to render.
     */
    private suspend fun resolveTableName(tableId: String): String {
        val label = tableDao.getById(tableId)?.label?.trim()
        return if (label.isNullOrBlank()) tableId else label
    }

}

