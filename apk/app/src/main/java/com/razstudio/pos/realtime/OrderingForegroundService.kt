package com.razstudio.pos.realtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.razstudio.pos.BuildConfig
import com.razstudio.pos.MainActivity
import com.razstudio.pos.R
import com.razstudio.pos.data.ApiClient
import com.razstudio.pos.data.SecureStorage
import com.razstudio.pos.data.json.OrderMapper
import com.razstudio.pos.data.json.ParseException
import com.razstudio.pos.data.json.toEntity
import com.razstudio.pos.data.local.OrderDao
import com.razstudio.pos.data.local.SettingsDao
import com.razstudio.pos.data.local.SystemSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Foreground service for ordering staff devices.
 *
 * Responsibilities:
 * - Persistent notification with FOREGROUND_SERVICE_TYPE_LOCATION
 * - WebSocket subscription to `cafe-status` channel (CAFE_OPEN / CAFE_CLOSED events)
 * - WebSocket subscription to `admin-devices` channel (FORCE_CHECKOUT events)
 * - Battery optimization exemption prompt on first start
 * - Broadcasts local intents: CAFE_OPEN, CAFE_CLOSED, FORCE_CHECKOUT
 * - START_STICKY + exponential backoff reconnection
 */
@AndroidEntryPoint
class OrderingForegroundService : Service() {

    companion object {
        /** Push reconnect backoff. Starts quick, tops out at a minute — the poll covers the gap. */
        private const val CLOUD_PUSH_INITIAL_BACKOFF_MS = 2_000L
        private const val CLOUD_PUSH_MAX_BACKOFF_MS = 60_000L

        private const val TAG = "OrderingFgService"
        private const val CHANNEL_ID = "ordering_staff_channel"
        private const val NOTIFICATION_ID = 2001

        // Broadcast actions
        const val ACTION_CAFE_OPEN = "com.razstudio.pos.CAFE_OPEN"
        const val ACTION_CAFE_CLOSED = "com.razstudio.pos.CAFE_CLOSED"
        const val ACTION_FORCE_CHECKOUT = "com.razstudio.pos.FORCE_CHECKOUT"
        const val ACTION_NEW_ORDER = "com.razstudio.pos.STAFF_NEW_ORDER"
        const val ACTION_SETTINGS_CHANGED = "com.razstudio.pos.SETTINGS_CHANGED"

        // Reconnection backoff
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        fun start(context: Context) {
            val intent = Intent(context, OrderingForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderingForegroundService::class.java))
        }
    }

    private var webSocket: WebSocket? = null
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var isConnected = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    /** Cloud-mode push socket to the admin device; null whenever it is down (the poll covers). */
    private var cloudPushSocket: WebSocket? = null
    private var cloudPushBackoffMs = CLOUD_PUSH_INITIAL_BACKOFF_MS

    @Inject lateinit var apiClient: ApiClient
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var orderDao: OrderDao
    @Inject lateinit var settingsDao: SettingsDao
    @Inject lateinit var appConfig: com.razstudio.pos.data.AppConfigStore
    @Inject lateinit var modeRepository: com.razstudio.pos.data.ModeRepository
    @Inject lateinit var noInternetGuard: com.razstudio.pos.data.net.NoInternetGuard
    @Inject lateinit var lanServerLocator: com.razstudio.pos.data.lan.LanServerLocator
    @Inject lateinit var staffOrderSync: com.razstudio.pos.data.local.StaffOrderSync

    // Task 18.1. `by lazy` because @Inject fields are not populated until onCreate, and a property
    // initialiser would read the guard before Hilt has set it.
    private val client by lazy {
        OkHttpClient.Builder()
            .dns(noInternetGuard)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Dependencies (apiClient, secureStorage, orderDao, settingsDao) are provided
        // by Hilt via @AndroidEntryPoint.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Ordering foreground service started")
        startForegroundWithNotification()
        requestBatteryOptimizationExemption()
        connectToRealtime()
        connectCloudPushSocket()
        return START_STICKY
    }

    /**
     * Subscribe to the admin device's push socket in Cloud mode (the café's local fast path).
     *
     * ### Why a staff device listens to the admin at all in Cloud mode
     *
     * The cloud is authoritative and stays authoritative — every write still goes to Supabase. What
     * it is not is prompt: this device learns about a change on its own poll tick. The admin already
     * knows the instant it makes the change, and it is on the same Wi-Fi, so it says so directly.
     *
     * ### A frame is a TRIGGER, not a payload
     *
     * The frame is not applied. It calls [StaffOrderSync.syncNow] — the same function the poll calls,
     * with the same watermark and the same reconcile. That is deliberate and load-bearing: a second
     * path that mutated state straight from frames would de-duplicate against different data than the
     * poll, and the way that disagreement surfaces is a kitchen slip printing twice mid-service.
     *
     * ### Every failure is survivable
     *
     * No admin on the network, socket refused, revoked credential, frame dropped, app backgrounded —
     * all of it costs latency and nothing else, because the poll still runs. That is why this never
     * reports an error to the user: there is nothing for them to do about it.
     */
    private fun connectCloudPushSocket() {
        if (modeRepository.currentMode() != com.razstudio.pos.data.OperatingMode.CLOUD) return
        val credential = secureStorage.getApiKey() ?: return

        serviceScope.launch {
            // The admin's address is not configured anywhere in a Cloud café, so it is discovered:
            // last-known, then the DHCP gateway (the admin when it hosts the hotspot), then mDNS.
            val found = lanServerLocator.locate()
            val base = (found as? com.razstudio.pos.data.lan.LanServerLocator.Result.Reachable)?.url
            if (base == null) {
                Log.i(TAG, "No café push server found — poll continues to cover")
                return@launch
            }

            val url = base.trimEnd('/')
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") + "/functions/v1/realtime"

            cloudPushSocket?.close(1001, "Reconnecting")
            cloudPushSocket = client.newWebSocket(
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $credential")
                    .build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "Cloud push connected to $url")
                        cloudPushBackoffMs = CLOUD_PUSH_INITIAL_BACKOFF_MS
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val envelope = com.razstudio.pos.data.lan.LanPushEnvelope.decode(text) ?: return
                        if (envelope.type != com.razstudio.pos.data.lan.LanPushEnvelope.Type.STATUS_UPDATE) return
                        // ACK before syncing: the server's view of delivery must not depend on how
                        // long our follow-up fetch takes.
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
                        serviceScope.launch { staffOrderSync.syncNow() }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Cloud push disconnected (${t.message}) — poll continues to cover")
                        cloudPushSocket = null
                        serviceScope.launch {
                            delay(cloudPushBackoffMs)
                            cloudPushBackoffMs =
                                (cloudPushBackoffMs * 2).coerceAtMost(CLOUD_PUSH_MAX_BACKOFF_MS)
                            connectCloudPushSocket()
                        }
                    }
                },
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Ordering foreground service destroyed")
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        cloudPushSocket?.close(1000, "Service destroyed")
        cloudPushSocket = null
        serviceJob.cancel()
        super.onDestroy()
    }

    // --- Battery Optimization ---

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption")
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization exemption", e)
            }
        }
    }

    // --- WebSocket Connection ---

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Staff mode active")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
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
        val request = Request.Builder()
            .url(getSupabaseRealtimeUrl())
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected = true
                currentBackoffMs = INITIAL_BACKOFF_MS

                // Join cafe-status channel
                val joinCafeStatus = """
                    {
                        "topic": "realtime:cafe-status",
                        "event": "phx_join",
                        "payload": {},
                        "ref": "1"
                    }
                """.trimIndent()
                webSocket.send(joinCafeStatus)

                // Join admin-devices channel for FORCE_CHECKOUT
                val joinDevices = """
                    {
                        "topic": "realtime:admin-devices",
                        "event": "phx_join",
                        "payload": {},
                        "ref": "2"
                    }
                """.trimIndent()
                webSocket.send(joinDevices)

                // Join admin-orders channel for real-time order updates
                val joinOrders = """
                    {
                        "topic": "realtime:admin-orders",
                        "event": "phx_join",
                        "payload": {},
                        "ref": "3"
                    }
                """.trimIndent()
                webSocket.send(joinOrders)

                // Join settings channel for RBAC permission updates
                val joinSettings = """
                    {
                        "topic": "realtime:settings",
                        "event": "phx_join",
                        "payload": {},
                        "ref": "4"
                    }
                """.trimIndent()
                webSocket.send(joinSettings)

                updateNotification("Staff mode active — connected")

                // Re-fetch café status on reconnect
                serviceScope.launch {
                    fetchCafeStatusOnReconnect()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: ${text.take(200)}")
                handleMessage(text)
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
                updateNotification("Staff mode — reconnecting...")
                scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val payload = json.optJSONObject("payload") ?: return
            val topic = json.optString("topic", "")

            when {
                topic.contains("cafe-status") -> handleCafeStatusEvent(payload)
                topic.contains("admin-devices") -> handleDeviceEvent(payload)
                topic.contains("admin-orders") -> handleNewOrderEvent(payload)
                topic.contains("settings") -> handleSettingsEvent(payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
        }
    }

    private fun handleCafeStatusEvent(payload: JSONObject) {
        val event = payload.optString("event", "")
        when (event) {
            "CAFE_OPEN" -> {
                Log.i(TAG, "Café opened")
                // Explicit package: this app's own receiver is already RECEIVER_NOT_EXPORTED on
                // API 33+ (see OrderingViewModel), so this can't reach another app either way —
                // setPackage is defense-in-depth Android's lint recommends for implicit broadcasts.
                sendBroadcast(Intent(ACTION_CAFE_OPEN).setPackage(packageName))
            }
            "CAFE_CLOSED" -> {
                Log.i(TAG, "Café closed")
                sendBroadcast(Intent(ACTION_CAFE_CLOSED).setPackage(packageName))
            }
        }
    }

    private fun handleDeviceEvent(payload: JSONObject) {
        val type = payload.optString("type", "")
        if (type == "FORCE_CHECKOUT") {
            val targetDeviceId = payload.optString("deviceId", "")
            val myDeviceId = secureStorage.getDeviceId()
            if (targetDeviceId == myDeviceId) {
                Log.i(TAG, "Admin forced check-out for this device")
                sendBroadcast(Intent(ACTION_FORCE_CHECKOUT).setPackage(packageName))
            }
        }
    }

    /**
     * Handle NEW_ORDER events from admin-orders channel.
     * Persists new orders to Room so the staff Table View stays in sync.
     */
    private fun handleNewOrderEvent(payload: JSONObject) {
        val dao = orderDao
        val orderJson = payload.optJSONObject("order") ?: return

        serviceScope.launch {
            try {
                val dto = OrderMapper.orderDto(orderJson)
                dao.insertOrder(dto.toEntity())
                if (dto.items.isNotEmpty()) {
                    // Replace, never append — see RealtimeService.reconcileOrder: a split share's
                    // local rows carry different line ids than the server's copy of the same lines.
                    dao.deleteItemsForOrder(dto.id)
                    dao.insertOrderItems(dto.items.map { it.toEntity(dto.id) })
                }

                Log.i(TAG, "NEW_ORDER persisted for staff: ${dto.id} table ${dto.tableId}")
                sendBroadcast(Intent(ACTION_NEW_ORDER))
            } catch (e: ParseException) {
                Log.e(TAG, "NEW_ORDER dropped — missing/null required field '${e.field}'", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle NEW_ORDER", e)
            }
        }
    }

    /**
     * Handle SETTINGS_CHANGED events from settings channel.
     * Updates Room settings so RBAC permissions refresh automatically.
     */
    private fun handleSettingsEvent(payload: JSONObject) {
        val dao = settingsDao

        serviceScope.launch {
            try {
                val settings = SystemSettings(
                    printLanguage = payload.optString("printLanguage", "EN"),
                    timezone = payload.optString("timezone", "Asia/Kuala_Lumpur"),
                    topN = payload.optInt("topN", 5),
                    staffCanSendKitchen = payload.optBoolean("staffCanSendKitchen", false),
                    staffCanTakePayment = payload.optBoolean("staffCanTakePayment", false)
                )
                dao.upsert(settings)
                Log.i(TAG, "Settings updated from broadcast: kitchen=${settings.staffCanSendKitchen}, payment=${settings.staffCanTakePayment}")
                sendBroadcast(Intent(ACTION_SETTINGS_CHANGED))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle SETTINGS_CHANGED", e)
            }
        }
    }

    /**
     * On reconnect, re-fetch café status. The UI (OrderingViewModel) handles
     * state transitions based on broadcast events.
     */
    private suspend fun fetchCafeStatusOnReconnect() {
        // Broadcast a generic "reconnected" so the ViewModel can re-fetch state
        // The ViewModel itself will call getCafeLocation and determine open/closed
        Log.i(TAG, "Reconnected — ViewModel should re-fetch café state")
    }

    /**
     * Exponential backoff reconnection.
     */
    private fun scheduleReconnect() {
        Log.i(TAG, "Reconnecting in ${currentBackoffMs}ms")
        android.os.Handler(mainLooper).postDelayed({
            if (!isConnected) {
                connectToRealtime()
            }
        }, currentBackoffMs)

        currentBackoffMs = (currentBackoffMs * BACKOFF_MULTIPLIER).toLong()
            .coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Staff Mode",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the staff mode active for attendance and orders"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // The café's own name, not a hardcoded one — this notification sits in the shade for
            // the whole shift, and a hardcoded name meant every café showed one café's on the staff phone.
            .setContentTitle(appConfig.cafeName().ifBlank { getString(R.string.app_name) })
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

