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

    @Inject lateinit var apiClient: ApiClient
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var orderDao: OrderDao
    @Inject lateinit var settingsDao: SettingsDao

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Ordering foreground service destroyed")
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
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
        val baseUrl = BuildConfig.SUPABASE_URL.replace("https://", "wss://")
        val anonKey = BuildConfig.SUPABASE_ANON_KEY
        return "$baseUrl/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
    }

    private fun connectToRealtime() {
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
                sendBroadcast(Intent(ACTION_CAFE_OPEN))
            }
            "CAFE_CLOSED" -> {
                Log.i(TAG, "Café closed")
                sendBroadcast(Intent(ACTION_CAFE_CLOSED))
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
                sendBroadcast(Intent(ACTION_FORCE_CHECKOUT))
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
            .setContentTitle("Warung Tom Yam")
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

