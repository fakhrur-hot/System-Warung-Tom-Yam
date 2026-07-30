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
import com.razstudio.pos.R
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
        private const val NEW_ORDER_CHANNEL_ID = "new_orders_channel"
        private const val NOTIFICATION_ID = 1001
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

    private fun s() = uiStrings(languageManager.language.value)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .pingInterval(25, TimeUnit.SECONDS)    // Match Supabase heartbeat interval
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        syncPrefs = getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        // Dependencies (apiClient, orderDao) are provided by Hilt via @AndroidEntryPoint.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        startForegroundWithNotification()
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
                delay(POLL_INTERVAL_MS)
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
                            sessionNumber = session
                        )
                        Log.i(
                            TAG,
                            "🖨️ Auto-printed ${sessionItems.size} kitchen line(s) for table " +
                                "${orderDto.tableId} session $session (amendment=$isAmendment)"
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
        val notification = buildNotification(s().notifListening)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getSupabaseRealtimeUrl(): String {
        val supabaseUrl = appConfig.supabaseUrl().ifBlank { BuildConfig.SUPABASE_URL }
        val baseUrl = supabaseUrl.replace("https://", "wss://")
        val anonKey = appConfig.supabaseAnonKey().ifBlank { BuildConfig.SUPABASE_ANON_KEY }
        return "$baseUrl/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
    }

    private fun connectToRealtime() {
        // Close any existing socket cleanly before opening a new one —
        // prevents a leaked connection when onStartCommand fires more than once
        // (START_STICKY restart, or redundant calls from AdminHomeScreen).
        webSocket?.close(1001, "Reconnecting")
        webSocket = null

        val request = Request.Builder()
            .url(getSupabaseRealtimeUrl())
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

                updateNotification(s().notifConnected)

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
                updateNotification(s().notifDisconnected)
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
                    dao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
                }

                // Update lastSeen timestamp
                setLastSeenTimestamp(dto.createdAt)

                Log.i(TAG, "🔔 NEW_ORDER persisted: ${dto.id} for table ${dto.tableId}")

                // Only print to kitchen if at least one item is marked sentToKitchen.
                // When customerOrderAutoPrint is false, all items arrive with
                // sentToKitchen = false — still beep/badge, but skip the print.
                val itemEntities = dto.items.map { it.toEntity(dto.id) }
                if (itemEntities.any { it.sentToKitchen }) {
                    printService.printKitchenSlip(
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

    private fun playNotificationBeep() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play notification beep", e)
        }
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

        // Live new-order channel — high importance so it pops a heads-up alert WITH the
        // device's notification ringtone + vibration each time an order arrives.
        val alertSound = android.media.RingtoneManager
            .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val newOrders = NotificationChannel(
            NEW_ORDER_CHANNEL_ID,
            "New Orders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts with sound when a new order or added items arrive"
            enableVibration(true)
            enableLights(true)
            setSound(alertSound, audioAttrs)
            setShowBadge(true)
        }
        manager.createNotificationChannel(newOrders)
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
                val newIds = itemIds.filter { it !in notifiedItemIds }
                if (newIds.isNotEmpty() && firstNotifySeeded) {
                    val wholeOrderNew = itemIds.all { it !in notifiedItemIds }
                    val tableName = resolveTableName(orderDto.tableId)
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
    }

    /** Table's admin-entered display name for the alert; falls back to the id. */
    private suspend fun resolveTableName(tableId: String): String {
        val label = tableDao.getById(tableId)?.label?.trim()
        return if (label.isNullOrBlank()) tableId else label
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}

