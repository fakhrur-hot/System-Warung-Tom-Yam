package com.razstudio.pos.data.local

import com.razstudio.pos.data.BackendGateway
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.ApiResult
import com.razstudio.pos.data.BrandingResponse
import com.razstudio.pos.data.CafeLocationResponse
import com.razstudio.pos.data.CreateOrderResponse
import com.razstudio.pos.data.DeviceDto
import com.razstudio.pos.data.DeviceStatusResponse
import com.razstudio.pos.data.InviteResponse
import com.razstudio.pos.data.KitchenResponse
import com.razstudio.pos.data.MenuImageUploadResponse
import com.razstudio.pos.data.MenuCategoryDto
import com.razstudio.pos.data.MenuItemDto
import com.razstudio.pos.data.MenuResponse
import com.razstudio.pos.data.NewOrderItem
import com.razstudio.pos.data.VoidLine
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.OrderItemDto
import com.razstudio.pos.data.OrdersSyncResponse
import com.razstudio.pos.data.RegisterResponse
import com.razstudio.pos.data.SessionResponse
import com.razstudio.pos.data.SettingsResponse
import com.razstudio.pos.data.lan.LanAddress
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-process, Room-backed [BackendGateway] used by a LAN **Server** device and by Kiosk Mode
 * (task 3.3 wires the binding; the implementations land in task 4 onwards).
 *
 * ### Implemented
 *
 * - **Orders** (tasks 4.1, 4.2) — create, `?since=` catch-up poll, kitchen slips, amendments,
 *   voiding unserved lines, status, payment, cancellation. Every transition is gated by the same
 *   [OrderActions] predicates the order-detail sheets use, so an endpoint and the button that calls
 *   it cannot disagree about what is permitted.
 * - **Menu** (task 4.3) — read, whole-snapshot replace, and image upload/delete backed by
 *   [LocalImageStore] instead of Supabase Storage (Requirement 7.3).
 * - **Settings** (task 4.4) — read/partial-write against Room, with cloud-web-only keys suppressed
 *   via [com.razstudio.pos.data.ModeCapabilities] rather than a mode comparison here.
 * - **Sessions, aggregates, tables** (task 4.5) — the café's open/close log and daily aggregate are
 *   written straight to Room, because off-cloud this device is the only place they exist.
 * - **Pairing and devices** (tasks 5.1, 5.2) — single-use time-limited pairing codes, registration,
 *   the approval poll, the device list, and approve/reject/revoke/rename. A Client's credential is
 *   minted at registration and handed over exactly once, on its first poll after approval, which is
 *   the contract `PendingApprovalScreen` already expects.
 *
 * ### Not implemented, and why the rest still throw
 *
 * Admin recovery, branding and attendance belong to later tasks. They still throw, which is
 * deliberate and preferable to returning plausible empty values: a stub answering
 * `ApiResult.Success(emptyList())` would let a half-built LAN Mode look like it was working while
 * silently losing data. Throwing means the first unimplemented call site is impossible to miss, and
 * the exception names the method so it is obvious which task owns it.
 *
 * ### Errors, not exceptions, for things a café can hit
 *
 * Anything a user can provoke — a closed order, an amendment that would empty a bill, a menu item
 * that no longer exists — returns [ApiResult.Error] with the same error codes the Edge Functions use.
 * The two backends are meant to be interchangeable, so a screen must not need to know which one
 * answered it.
 *
 * Cloud Mode never constructs this class — `BackendModule` provides it lazily, so none of this can
 * affect an existing install.
 */
@Singleton
class LocalBackend @Inject constructor(
    private val orderDao: OrderDao,
    private val menuDao: MenuDao,
    private val settingsDao: SettingsDao,
    private val tableDao: TableDao,
    private val cafeSessionDao: CafeSessionDao,
    private val dailyAggregateDao: DailyAggregateDao,
    private val modeRepository: ModeRepository,
    private val menuCategoryStore: MenuCategoryStore,
    private val localImageStore: LocalImageStore,
    private val pairedDeviceDao: PairedDeviceDao,
    private val pairingTokenDao: PairingTokenDao,
    private val lanAddress: com.razstudio.pos.data.lan.LanAddress,
    private val pushBus: com.razstudio.pos.data.lan.LanPushBus,
    private val appConfigStore: com.razstudio.pos.data.AppConfigStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : BackendGateway {

    internal companion object {
        /**
         * Rounds of items one order may accumulate, matching the `orders-items` Edge Function's cap.
         * The limit exists because each round is a separate kitchen slip and a separate line-item
         * group on the receipt; past ten the slip stops being readable at the pass.
         */
        private const val MAX_SESSIONS = 10

        /** Fixed-width ISO-8601 UTC, always 6 fractional digits — see [nowTimestamp]. */
        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * The device-status and role vocabulary, matching what the Cloud `devices` endpoint returns
         * and what `DevicesViewModel` / `PendingApprovalViewModel` compare against. String literals
         * scattered across the methods below would let a typo silently create a fourth status that
         * nothing matches, leaving a device stuck on the approval screen forever.
         */
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_APPROVED = "APPROVED"
        private const val STATUS_REVOKED = "REVOKED"
        private const val ROLE_ORDERING = "ORDERING"

        /**
         * The port the LAN Server listens on (task 6.2 binds it; the pairing QR has to carry it
         * now). 8765 is above the privileged range, outside IANA's registered block, and not one
         * of the ports an OEM's own on-device services tend to squat.
         */
        private const val LAN_PORT = 8765

        /**
         * Hashes a credential string using SHA-256.
         * The raw credential is never stored in the database — only the hash (Requirement 5.4).
         */
        /**
         * Exposed for [com.razstudio.pos.data.lan.LanServer], which authenticates an incoming
         * request by hashing the presented credential and matching it against the stored hash. It
         * has to use the *same* function — two hashers that drift would reject every device, and the
         * symptom (every staff phone 401s at once) points nowhere near the cause.
         */
        fun hashCredentialForLan(credential: String): String = hashCredential(credential)

        private fun hashCredential(credential: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(credential.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    override suspend fun adminHandshakeDebug(deviceId: String, cafeName: String): ApiResult<String> =
        notImplemented("adminHandshakeDebug")

    /**
     * Register a Client Device against a pairing code (task 5.1, Requirements 5.1, 5.3).
     *
     * The token must exist, be unexpired and unused; consuming it is what makes a code single-use, so
     * a photographed pairing screen cannot enrol a second device later.
     *
     * The device lands as `PENDING` and its credential is minted now but withheld — see
     * [pollDeviceStatus], which hands it over once the admin approves. Nothing a PENDING device can
     * present is accepted in the meantime, so an early credential grants nothing.
     *
     * Re-registering an already-approved device is refused rather than silently re-issuing. That path
     * would otherwise rotate a working device's credential out from under it, and the symptom — a
     * staff phone that suddenly cannot take orders mid-service — gives no hint of the cause.
     */
    override suspend fun register(
        inviteToken: String,
        deviceId: String,
        deviceModel: String,
        androidId: String,
        appVersion: String,
    ): ApiResult<RegisterResponse> {
        val nowMs = System.currentTimeMillis()

        val existing = pairedDeviceDao.getById(deviceId)
        if (existing != null && existing.status == STATUS_APPROVED) {
            return ApiResult.Error(
                "ALREADY_PAIRED",
                "This device is already paired and approved",
            )
        }

        pairingTokenDao.getValidToken(inviteToken, nowMs)
            ?: return ApiResult.Error(
                "INVALID_TOKEN",
                "Pairing code is invalid, expired, or already used",
            )

        val credential = UUID.randomUUID().toString()

        pairedDeviceDao.insert(
            PairedDevice(
                id = deviceId,
                // The model, not the androidId. This string is what the admin reads on the Devices
                // screen when deciding whether to approve; a 16-char opaque hex id tells them nothing
                // about which phone on the counter is asking, so they cannot approve safely.
                name = deviceModel.ifBlank { androidId },
                model = deviceModel,
                // LAN Mode is one ADMIN Server plus N ORDERING Clients — ModeCapabilities.secondaryAdmin
                // is false off-cloud, so ORDERING is the only role a pairing can produce here. This is
                // the design, not a placeholder: a second admin would need its own approval path and a
                // session token, neither of which exists off-cloud.
                role = ROLE_ORDERING,
                status = STATUS_PENDING,
                credentialHash = hashCredential(credential),
                lastSeenMs = nowMs,
                pendingCredential = credential,
            )
        )

        pairingTokenDao.markUsed(inviteToken, nowMs)

        return ApiResult.Success(RegisterResponse(deviceId = deviceId, status = STATUS_PENDING))
    }

    /**
     * Approval state for a waiting Client Device (task 5.1, Requirement 5.3).
     *
     * This is also where a newly approved device **receives its credential**, matching the Cloud
     * contract that `PendingApprovalScreen` already polls against: it reads `apiKey` from this
     * response and hands it to `SecureStorage`. Returning null here — as an earlier draft did — pairs
     * the device, shows it as approved, and leaves it permanently unable to authenticate, with no
     * error anywhere to explain why.
     *
     * Delivery is once. The raw value is cleared as it is handed over, so the hash becomes the only
     * remaining record (Requirement 5.4). A device that loses its credential afterwards has to be
     * revoked and paired again, which is the correct outcome: re-issuing on demand to anyone who
     * knows a device id would be a way to mint credentials.
     */
    override suspend fun pollDeviceStatus(deviceId: String): ApiResult<DeviceStatusResponse> {
        val device = pairedDeviceDao.getById(deviceId)
            ?: return ApiResult.Error("NOT_FOUND", "Device not found")

        val deliverNow = device.status == STATUS_APPROVED && device.pendingCredential != null
        if (deliverNow) {
            pairedDeviceDao.update(device.copy(pendingCredential = null, lastSeenMs = System.currentTimeMillis()))
        }

        return ApiResult.Success(
            DeviceStatusResponse(
                status = device.status,
                role = device.role,
                apiKey = if (deliverNow) device.pendingCredential else null,
                // Off-cloud there is no ADMIN_SECONDARY role and so no admin session token to issue;
                // ModeCapabilities.secondaryAdmin is false for both LAN and Kiosk.
                sessionToken = null,
            )
        )
    }

    override suspend fun recoverAdmin(recoveryToken: String, deviceId: String, deviceModel: String): ApiResult<String> =
        notImplemented("recoverAdmin")

    override suspend fun getRecoveryToken(): ApiResult<InviteResponse> =
        notImplemented("getRecoveryToken")

    /**
     * The current pairing code, minting one if none is live (task 5.2, Requirement 5.4).
     *
     * Idempotent on purpose: the admin opens the Devices screen, shows the code to a staff phone, the
     * screen recomposes, and the same code must still be on it. Minting per call would invalidate the
     * code the moment the admin looked away from it.
     *
     * The `url` is blank rather than a `https://…/join?invite=` link. Deep-link invitations need a
     * website ([ModeCapabilities.websiteInvites] is false off-cloud), so a URL here would be one that
     * resolves nowhere — the Devices screen renders the token as a QR, which is what a Client scans.
     */
    override suspend fun getInvite(role: String?): ApiResult<InviteResponse> {
        val url = when (val addr = lanAddress.resolve()) {
            is LanAddress.Result.Found -> "http://${addr.ip}:$LAN_PORT"
            is LanAddress.Result.Unavailable -> return ApiResult.Error("NO_NETWORK", addr.reason)
        }

        val nowMs = System.currentTimeMillis()
        val live = pairingTokenDao.getCurrentToken(nowMs)
        if (live != null) return ApiResult.Success(InviteResponse(token = live.token, url = url))

        val minted = pairingTokenDao.generateToken()
        return ApiResult.Success(InviteResponse(token = minted.token, url = url))
    }

    /**
     * Rotate the pairing code (task 5.2, Requirement 5.6).
     *
     * Only unused tokens are discarded. Already-approved devices are untouched — they authenticate
     * with their own credential, which was never derived from the token — so rotating after a code
     * leaks locks out the leak without knocking every staff phone in the café off the system.
     */
    override suspend fun regenerateInvite(role: String?): ApiResult<InviteResponse> {
        val url = when (val addr = lanAddress.resolve()) {
            is LanAddress.Result.Found -> "http://${addr.ip}:$LAN_PORT"
            is LanAddress.Result.Unavailable -> return ApiResult.Error("NO_NETWORK", addr.reason)
        }

        val nowMs = System.currentTimeMillis()
        pairingTokenDao.getCurrentToken(nowMs)?.let { pairingTokenDao.deleteToken(it.token) }

        val minted = pairingTokenDao.generateToken()
        return ApiResult.Success(InviteResponse(token = minted.token, url = url))
    }

    /**
     * Every paired Client Device, for the Devices screen (task 5.2, Requirement 5.4).
     *
     * `isCheckedIn` is reported false throughout: attendance is a GPS check-in feature that belongs to
     * the cloud `attendance` endpoint and has no off-cloud equivalent. False is the honest answer —
     * the alternative would be a screen showing staff as on shift based on nothing.
     */
    override suspend fun getDevices(): ApiResult<List<DeviceDto>> =
        ApiResult.Success(pairedDeviceDao.getAllOnce().map { it.toDto() })

    /**
     * Approve, reject, revoke or rename a device (task 5.2, Requirements 5.4, 5.6).
     *
     * REJECT and REVOKE both end in `REVOKED` and clear any undelivered credential, so a device
     * refused while still waiting cannot pick one up afterwards by continuing to poll. The row is
     * kept rather than deleted: the admin needs to see *that* a device was refused, and a deleted row
     * would simply let the same device register again into a fresh PENDING state, which looks
     * identical to a first attempt.
     */
    override suspend fun patchDevice(
        deviceId: String,
        action: String,
        label: String?,
    ): ApiResult<DeviceDto> {
        val device = pairedDeviceDao.getById(deviceId)
            ?: return ApiResult.Error("NOT_FOUND", "Device not found")

        val updated = when (action.uppercase()) {
            "APPROVE" -> device.copy(status = STATUS_APPROVED)

            // The credential is dropped here, not just the status changed — see the KDoc above.
            "REJECT", "REVOKE" -> device.copy(status = STATUS_REVOKED, pendingCredential = null)

            "RENAME" -> {
                val trimmed = label?.trim().orEmpty()
                if (trimmed.isBlank()) {
                    return ApiResult.Error("VALIDATION", "A device label cannot be blank")
                }
                device.copy(name = trimmed)
            }

            // Attendance has no off-cloud implementation, so there is no check-in to force. Reported
            // rather than silently succeeding, which would leave the admin believing it worked.
            "FORCE_CHECKOUT" -> return ApiResult.Error(
                "UNSUPPORTED",
                "Attendance check-in is not available off-cloud",
            )

            else -> return ApiResult.Error("VALIDATION", "Unknown action '$action'")
        }

        pairedDeviceDao.update(updated)
        return ApiResult.Success(updated.toDto())
    }

    /**
     * Record a café open/close event (task 4.5, Requirement 3.2).
     *
     * Off-cloud this row is the café's only record that it traded on a given day, so it is written
     * before the response is returned rather than being fire-and-forget. The log is append-only —
     * see [CafeSession] for why a CLOSE does not update its OPEN.
     */
    override suspend fun postSession(
        event: String,
        reason: String?,
        closing: Boolean,
    ): ApiResult<SessionResponse> {
        val id = UUID.randomUUID().toString()
        val timestamp = nowTimestamp()
        cafeSessionDao.insert(
            CafeSession(
                id = id,
                event = event,
                reason = reason?.trim()?.ifBlank { null },
                closing = closing,
                timestamp = timestamp,
            )
        )
        return ApiResult.Success(SessionResponse(sessionId = id, event = event, timestamp = timestamp))
    }

    /**
     * Store a business day's closing aggregate (task 4.5, Requirement 3.3).
     *
     * The payload is kept verbatim — see [DailyAggregate] for why it is not exploded into columns.
     * Upserted, so closing the same day twice replaces rather than duplicating; the café owner who
     * re-runs a close after fixing a mistake gets one row, not two disagreeing ones.
     */
    override suspend fun postAggregates(date: String, body: JSONObject): ApiResult<Unit> {
        dailyAggregateDao.upsert(
            DailyAggregate(
                date = date,
                payloadJson = body.toString(),
                updatedAt = nowTimestamp(),
            )
        )
        return ApiResult.Success(Unit)
    }

    /**
     * The café's menu, straight from Room (task 4.3).
     *
     * `configured` is false when there are no rows, which is how the admin screens tell "not set up
     * yet" from "set up and deliberately empty" — the same distinction the Cloud endpoint draws.
     *
     * Category order and translations come from [MenuCategoryStore] rather than the item rows,
     * because ordering is a property of the menu as a whole and there is nowhere on an item to hang
     * it. That is also where the Cloud path reads them from, so the two agree.
     */
    override suspend fun getMenu(): ApiResult<MenuResponse> {
        val items = menuDao.getAll()
        val translations = menuCategoryStore.getTranslations()
        val categories = menuCategoryStore.get().mapIndexed { index, name ->
            MenuCategoryDto(
                name = name,
                sortOrder = index,
                nameI18n = translations[name] ?: emptyMap(),
            )
        }
        return ApiResult.Success(
            MenuResponse(
                configured = items.isNotEmpty(),
                items = items.map { it.toDto() },
                categories = categories,
            )
        )
    }

    /**
     * Replace the whole menu (task 4.3).
     *
     * Whole-snapshot semantics, like the Cloud endpoint: the arrays are the complete menu, so items
     * absent from [menuItems] are deleted. That is what makes a deletion in the admin screen actually
     * remove the item rather than leaving an orphan that keeps appearing in order entry.
     *
     * Images are preserved across the replace. The menu JSON the admin screens build does not always
     * carry `image`, and a snapshot round-trip must not be able to silently detach every photo — so
     * an item that arrives without one keeps whatever it already had.
     */
    override suspend fun putMenu(menuItems: JSONArray, categories: JSONArray): ApiResult<Unit> {
        val existingById = menuDao.getAll().associateBy { it.id }
        val parsed = mutableListOf<MenuItem>()

        for (i in 0 until menuItems.length()) {
            val obj = menuItems.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank()) continue
            val nameObj = obj.optJSONObject("name")
            val prior = existingById[id]

            // `categories[]` minus the primary becomes extraCategories — the same reduction
            // ApiClient.getMenu performs, kept identical so a snapshot survives either backend.
            val extras = obj.optJSONArray("categories")?.let { arr ->
                (0 until arr.length())
                    .map { arr.optString(it, "").trim() }
                    .filter { it.isNotBlank() && it != obj.optString("category", "") }
                    .joinToString(",")
            } ?: prior?.extraCategories ?: ""

            val incomingImage = obj.optString("image", "")

            parsed += MenuItem(
                id = id,
                category = obj.optString("category", prior?.category ?: ""),
                extraCategories = extras,
                code = obj.optString("code", prior?.code ?: ""),
                price = obj.optDouble("price", prior?.price ?: 0.0),
                marketPrice = obj.optBoolean("marketPrice", prior?.marketPrice ?: false),
                available = obj.optBoolean("available", prior?.available ?: true),
                askMeDaily = obj.optBoolean("askMeDaily", prior?.askMeDaily ?: false),
                imageUrl = incomingImage.ifBlank { prior?.imageUrl ?: "" },
                imagePath = if (incomingImage.isBlank()) prior?.imagePath ?: "" else prior?.imagePath ?: "",
                nameEn = nameObj?.optString("en", "") ?: prior?.nameEn ?: "",
                nameBm = nameObj?.optString("bm", "") ?: prior?.nameBm ?: "",
                nameZh = nameObj?.optString("zh", "") ?: prior?.nameZh ?: "",
                nameTa = nameObj?.optString("ta", "") ?: prior?.nameTa ?: "",
                nameTh = nameObj?.optString("th", "") ?: prior?.nameTh ?: "",
                doNotTranslate = nameObj?.optBoolean("doNotTranslate", false) ?: false,
                hasVariablePrice = obj.optBoolean("hasVariablePrice", false),
                variablePriceDailyPrompt = obj.optBoolean("variablePriceDailyPrompt", false),
                // has() is true for an explicit JSON null (the preset emits "priceOption1": null),
                // so guard with !isNull or getDouble throws and the whole menu fails to save.
                priceOption1 = if (obj.has("priceOption1") && !obj.isNull("priceOption1")) obj.getDouble("priceOption1") else null,
                priceOption2 = if (obj.has("priceOption2") && !obj.isNull("priceOption2")) obj.getDouble("priceOption2") else null,
                priceOption3 = if (obj.has("priceOption3") && !obj.isNull("priceOption3")) obj.getDouble("priceOption3") else null,
            )
        }

        // Delete images belonging to items that are going away, before the rows are replaced —
        // otherwise the path is gone and the file is stranded in app-private storage forever.
        val keptIds = parsed.map { it.id }.toSet()
        existingById.values
            .filter { it.id !in keptIds && it.imagePath.isNotBlank() }
            .forEach { localImageStore.delete(it.imagePath) }

        menuDao.deleteAll()
        menuDao.upsertAll(parsed)

        if (categories.length() > 0) {
            val names = (0 until categories.length()).mapNotNull { i ->
                categories.optJSONObject(i)?.optString("name", "")?.ifBlank { null }
            }
            menuCategoryStore.set(names)

            val translations = mutableMapOf<String, Map<String, String>>()
            for (i in 0 until categories.length()) {
                val cat = categories.optJSONObject(i) ?: continue
                val name = cat.optString("name", "")
                if (name.isBlank()) continue
                val i18n = cat.optJSONObject("nameI18n") ?: continue
                translations[name] = i18n.keys().asSequence()
                    .associateWith { i18n.optString(it, "") }
                    .filterValues { it.isNotBlank() }
            }
            if (translations.isNotEmpty()) menuCategoryStore.setTranslations(translations)
        }

        return ApiResult.Success(Unit)
    }

    /**
     * Store a menu photo on the device instead of in Supabase Storage (task 4.3, Requirement 7.3).
     *
     * The previous image for the item is deleted first — without that, every replacement leaves its
     * predecessor behind in app-private storage, and a café that re-photographs its menu each season
     * slowly fills the tablet with files nothing references.
     */
    override suspend fun uploadMenuImage(
        menuItemId: String,
        imageBase64: String,
    ): ApiResult<MenuImageUploadResponse> {
        val item = menuDao.getAll().firstOrNull { it.id == menuItemId }
            ?: return ApiResult.Error("NOT_FOUND", "Menu item not found")

        return try {
            if (item.imagePath.isNotBlank()) localImageStore.delete(item.imagePath)

            val stored = localImageStore.save(menuItemId, imageBase64, System.currentTimeMillis())
            menuDao.upsertAll(listOf(item.copy(imageUrl = stored.url, imagePath = stored.path)))
            ApiResult.Success(MenuImageUploadResponse(imageUrl = stored.url, path = stored.path))
        } catch (e: IllegalArgumentException) {
            // Base64.decode on a malformed payload. Reported rather than thrown so the admin screen
            // shows "upload failed" instead of the app dying on a bad pick.
            ApiResult.Error("VALIDATION", e.message ?: "Image data could not be decoded")
        } catch (e: java.io.IOException) {
            ApiResult.Error("STORAGE_ERROR", e.message ?: "Could not write the image to storage")
        }
    }

    /**
     * Remove a stored menu photo (task 4.3), clearing the pointer on any item that referenced it so a
     * row cannot be left holding a `file://` URL for a file that no longer exists.
     */
    override suspend fun deleteMenuImage(path: String): ApiResult<Unit> {
        localImageStore.delete(path)
        menuDao.getAll()
            .filter { it.imagePath == path }
            .forEach { menuDao.upsertAll(listOf(it.copy(imageUrl = "", imagePath = ""))) }
        return ApiResult.Success(Unit)
    }

    /**
     * Create a new order on behalf of the admin device.
     *
     * The Server Device is the sole id assigner (Requirement 8.4): the id is a UUID minted here,
     * never accepted from a caller, so two devices cannot produce the same id — collision is
     * structurally impossible rather than made unlikely by a probability argument.
     *
     * Item snapshots (name, price, category) are resolved from [MenuDao] at insert time so a
     * later menu edit cannot retroactively change what a customer was billed.
     */
    override suspend fun createOrder(
        tableId: String?,
        items: List<NewOrderItem>,
        source: String,
        orderNumber: Int?,
    ): ApiResult<CreateOrderResponse> {
        val orderId = UUID.randomUUID().toString()
        val now = nowTimestamp()
        val menuIndex = menuDao.getAll().associateBy { it.id }
        val orderItems = items.map { it.toEntity(orderId, menuIndex, sessionNumber = 1) }
        val total = orderItems.sumOf { it.unitPriceSnapshot * it.quantity }
        orderDao.insertOrder(
            Order(
                id = orderId,
                tableId = tableId,
                // Kiosk's running number, minted by the caller from OrderNumberSequence so the
                // read-and-increment stays atomic. Null in every mode that has tables.
                orderNumber = orderNumber,
                source = source,
                status = OrderStatus.RECEIVED,
                total = total,
                createdAt = now,
            )
        )
        orderDao.insertOrderItems(orderItems)
        pushOrderChanged(orderId, OrderStatus.RECEIVED.name, tableId = tableId, total = total)
        return ApiResult.Success(
            CreateOrderResponse(orderId = orderId, total = total, status = "RECEIVED")
        )
    }

    /**
     * Fetch all orders whose [Order.createdAt] is strictly after [since].
     *
     * [since] is the timestamp the poller last received as `serverTime`, and the response carries the
     * next one. SQLite compares `createdAt` as text, which only tracks chronological order because
     * every timestamp this class writes is fixed-width — see [nowTimestamp] for why
     * `Instant.toString()` is not safe here.
     *
     * Delivery is at-least-once, deliberately; the comment on `serverTime` below is the argument.
     */
    override suspend fun getOrdersSince(since: String): ApiResult<OrdersSyncResponse> {
        // Captured BEFORE the read, and this ordering is the whole correctness argument.
        //
        // Taking it afterwards loses orders outright: the query returns rows as of T0, the timestamp
        // would be T1 > T0, and an order written in between is in neither this response (it did not
        // exist at T0) nor the next one (which asks for > T1). It is dropped permanently, and because
        // a poll response looks identical either way nothing would ever reveal it.
        //
        // Capturing first inverts the failure: an order written between T0 and the query is returned
        // now AND again next poll. Duplicate delivery is harmless — the poller upserts by primary key
        // — so this trades an invisible loss for a redundant write, which is the right way round.
        val serverTime = nowTimestamp()
        val orders = orderDao.getOrdersSince(since)
        val orderDtos = orders.map { order ->
            val items = orderDao.getItemsForOrder(order.id)
            order.toDto(items)
        }
        return ApiResult.Success(
            OrdersSyncResponse(orders = orderDtos, serverTime = serverTime)
        )
    }

    /**
     * Kitchen slip data for an order (task 4.2).
     *
     * Two shapes, matching the `orders-kitchen` Edge Function exactly so the two backends stay
     * interchangeable:
     *  - **no [sessionNumber]** — a reprint. Returns the order and ALL its lines, and mutates
     *    nothing. The kitchen printer failed or the slip was lost; nothing about the order changed.
     *  - **a [sessionNumber]** — a first send for that round. Marks only that round's lines
     *    `sentToKitchen`, persists, and returns only those lines.
     *
     * The guard is `isTerminal`, not [OrderActions.canSendToKitchen]. Those answer different
     * questions and conflating them would break reprinting: `canSendToKitchen` is the UI's
     * "is this a fresh order awaiting its first send" rule (RECEIVED only), whereas a paid-but-not-yet
     * -collected order must still be reprintable. A COMPLETED or CANCELLED order must not be.
     */
    override suspend fun sendToKitchen(orderId: String, sessionNumber: Int?): ApiResult<KitchenResponse> {
        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (order.status.isTerminal) {
            return ApiResult.Error("ORDER_CLOSED", "Cannot print a completed or cancelled order")
        }

        val items = orderDao.getItemsForOrder(orderId)

        if (sessionNumber == null) {
            return ApiResult.Success(
                KitchenResponse(
                    order = order.toDto(items),
                    linesToPrint = items.map { it.toDto() },
                )
            )
        }

        // Lines written before sessions existed carry 0/absent; the client shows them as round 1, so
        // resolve them the same way here rather than letting them fall out of every session filter.
        val sessionOf = { item: OrderItem -> if (item.sessionNumber <= 0) 1 else item.sessionNumber }
        val sessionItems = items.filter { sessionOf(it) == sessionNumber }
        if (sessionItems.isEmpty()) {
            return ApiResult.Error("NOT_FOUND", "No items found for session $sessionNumber")
        }

        val marked = sessionItems.map { it.copy(sentToKitchen = true) }
        orderDao.insertOrderItems(marked)

        val allAfter = items.map { item -> marked.firstOrNull { it.id == item.id } ?: item }
        return ApiResult.Success(
            KitchenResponse(
                order = order.toDto(allAfter),
                linesToPrint = marked.map { it.toDto() },
            )
        )
    }

    /**
     * Append a round of items to an open order (task 4.2).
     *
     * Mirrors `orders-items`: one call is one round, so the whole batch shares the next
     * [OrderItem.sessionNumber] and prints as a single kitchen slip rather than one slip per tap.
     * Prices are snapshotted from the current menu here, not taken from the caller.
     */
    override suspend fun addItemsToOrder(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> {
        if (items.isEmpty()) return ApiResult.Error("VALIDATION", "items[] is required")

        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (order.status.isTerminal) {
            return ApiResult.Error("ORDER_CLOSED", "Cannot amend a completed or cancelled order")
        }

        val existing = orderDao.getItemsForOrder(orderId)
        val nextSession = (existing.maxOfOrNull { it.sessionNumber } ?: 0) + 1
        if (nextSession > MAX_SESSIONS) {
            return ApiResult.Error(
                "SESSION_LIMIT",
                "This table has reached the maximum of $MAX_SESSIONS order rounds — pay out and free it first",
            )
        }

        val menuIndex = menuDao.getAll().associateBy { it.id }
        val newItems = items.map { it.toEntity(orderId, menuIndex, sessionNumber = nextSession) }
        orderDao.insertOrderItems(newItems)

        val all = existing + newItems
        val newTotal = all.sumOf { it.unitPriceSnapshot * it.quantity }
        // updateOrderTotal, never insertOrder: REPLACE on the parent cascades and deletes the very
        // lines just written. See OrderDao.updateOrderTotal.
        orderDao.updateOrderTotal(orderId, newTotal)
        pushOrderChanged(orderId, order.status.name, tableId = order.tableId, total = newTotal)
        return ApiResult.Success(order.copy(total = newTotal).toDto(all))
    }

    /**
     * Reduce or remove lines on an open order before payment (task 4.2).
     *
     * Same rules the `orders-items-void` Edge Function enforces — keep-quantities rather than whole
     * lines, no increases, and never empty the order — so a café behaves identically whichever
     * backend it is on.
     *
     * One deliberate difference: there is no local audit trail. The cloud path moves voided
     * quantities into `orders.voided_items_json`; Room has no such column, so off-cloud the reduced
     * quantity is simply gone. Recorded here rather than silently: adding the column is a schema
     * migration, and LAN/Kiosk cafés are single-site with the cashier physically present, so the
     * reconstruction value is much lower than it is for a multi-device cloud café.
     */
    override suspend fun voidOrderItems(
        orderId: String,
        lines: List<VoidLine>,
        reason: String,
    ): ApiResult<OrderDto> {
        if (lines.isEmpty()) return ApiResult.Error("VALIDATION", "lines[] is required")

        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (order.status.isTerminal) {
            return ApiResult.Error("ORDER_CLOSED", "Cannot amend a completed or cancelled order")
        }

        val existing = orderDao.getItemsForOrder(orderId)
        val keepById = mutableMapOf<String, Int>()
        for (line in lines) {
            val item = existing.firstOrNull { it.id == line.itemId }
                ?: return ApiResult.Error("ALREADY_VOIDED", "Line is not on this order any more")
            if (line.keepQuantity < 0) return ApiResult.Error("VALIDATION", "quantity must be >= 0")
            if (line.keepQuantity > item.quantity) {
                return ApiResult.Error("CANNOT_INCREASE", "Use Add items to order to increase a quantity")
            }
            keepById[line.itemId] = line.keepQuantity
        }

        var changed = false
        val kept = existing.mapNotNull { item ->
            val keep = keepById[item.id] ?: return@mapNotNull item
            when {
                keep == item.quantity -> item
                keep > 0 -> { changed = true; item.copy(quantity = keep) }
                else -> { changed = true; null }
            }
        }
        if (!changed) return ApiResult.Error("ALREADY_VOIDED", "Nothing on this order would change")
        if (kept.isEmpty()) {
            return ApiResult.Error(
                "WOULD_EMPTY_ORDER",
                "That would remove every line — cancel the order instead so it is recorded as one",
            )
        }

        orderDao.deleteItemsForOrder(orderId)
        orderDao.insertOrderItems(kept)
        val newTotal = kept.sumOf { it.unitPriceSnapshot * it.quantity }
        orderDao.updateOrderTotal(orderId, newTotal)
        pushOrderChanged(orderId, order.status.name, tableId = order.tableId, total = newTotal)
        return ApiResult.Success(order.copy(total = newTotal).toDto(kept))
    }

    /**
     * Move an order along the kitchen workflow (task 4.2) — `orders-status`, PREPARING/READY.
     *
     * Only these two are accepted. COMPLETED belongs to [processPayment] and CANCELLED to
     * [cancelOrder], both of which record more than a status; letting this endpoint set either would
     * be a way to close an order with no payment method and no cancellation reason.
     */
    override suspend fun updateOrderStatus(orderId: String, status: String): ApiResult<OrderDto> {
        val target = OrderStatus.fromWire(status)
        if (target != OrderStatus.PREPARING && target != OrderStatus.READY) {
            return ApiResult.Error("VALIDATION", "Only PREPARING and READY can be set here")
        }

        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (order.status.isTerminal) {
            return ApiResult.Error("ORDER_CLOSED", "Cannot change the status of a closed order")
        }

        orderDao.updateOrderStatus(orderId, target.name)
        pushOrderChanged(orderId, target.name, tableId = order.tableId, total = order.total)
        val updated = order.copy(status = target)
        return ApiResult.Success(updated.toDto(orderDao.getItemsForOrder(orderId)))
    }

    /**
     * Take payment and close the order (task 4.2).
     *
     * Gated by [OrderActions.canTakePayment] — the same predicate the two order-detail sheets use to
     * decide whether to show the Pay buttons, so the button and the endpoint cannot disagree about
     * what is payable.
     */
    override suspend fun processPayment(orderId: String, method: String): ApiResult<OrderDto> {
        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (order.status == OrderStatus.COMPLETED) {
            return ApiResult.Error("ALREADY_PAID", "This order has already been paid")
        }
        // A counter sale (Kiosk: no table) is rung up and paid in one action — the customer is
        // standing there. canTakePayment requires SENT_TO_KITCHEN or later, which encodes the
        // table-service rule that food reaches the kitchen before anyone is charged; applying it to
        // a grocery-style till would make every sale unpayable at the moment it is made.
        val isCounterSale = order.tableId == null
        if (!isCounterSale && !OrderActions.canTakePayment(order.status)) {
            return ApiResult.Error("PAYMENT_CONFLICT", "Order cannot be paid in its current status")
        }

        orderDao.completePayment(orderId, method)
        pushOrderChanged(orderId, OrderStatus.COMPLETED.name, tableId = order.tableId, total = order.total)
        val updated = order.copy(status = OrderStatus.COMPLETED, paymentMethod = method)
        return ApiResult.Success(updated.toDto(orderDao.getItemsForOrder(orderId)))
    }

    /**
     * Cancel an order (task 4.2). Gated by [OrderActions.canCancel] — any non-terminal order.
     */
    override suspend fun cancelOrder(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> {
        val order = orderDao.getOrderById(orderId)
            ?: return ApiResult.Error("NOT_FOUND", "Order not found")
        if (!OrderActions.canCancel(order.status)) {
            return ApiResult.Error("ORDER_CLOSED", "Order is already closed")
        }

        orderDao.cancelOrder(orderId, reason, cancelledBy)
        pushOrderChanged(orderId, OrderStatus.CANCELLED.name, tableId = order.tableId, total = order.total)
        return ApiResult.Success(Unit)
    }

    /**
     * Create a new order on behalf of an ordering-staff (Client) device.
     *
     * In LAN Mode, RBAC is enforced at the [LanServer] HTTP layer — only a properly credentialed
     * ORDERING device reaches this path. At the Room level, the order is identical; the id is still
     * Server-assigned (Requirement 8.4) and the source is fixed to "STAFF" per the BackendGateway
     * contract (staff orders cannot set the source header).
     */
    override suspend fun createOrderAsStaff(
        tableId: String,
        items: List<NewOrderItem>,
    ): ApiResult<CreateOrderResponse> = createOrder(tableId, items, source = "STAFF")

    /**
     * Fetch orders since a timestamp for an ordering-staff (Client) device.
     * Same query as the admin variant — staff catch-up sync reads the same Room tables.
     */
    override suspend fun getOrdersSinceAsStaff(since: String): ApiResult<OrdersSyncResponse> =
        getOrdersSince(since)

    // The staff variants delegate to the admin ones: the Room rows are identical, and per-role
    // permission is enforced one layer up at the LanServer HTTP boundary (task 6) using the same
    // StaffPermissions flags the Cloud path enforces in its Edge Functions. Duplicating the
    // permission logic here would give LAN Mode a second, drifting copy of the RBAC rules.

    override suspend fun sendToKitchenAsStaff(orderId: String, sessionNumber: Int?): ApiResult<KitchenResponse> =
        sendToKitchen(orderId, sessionNumber)

    override suspend fun addItemsToOrderAsStaff(orderId: String, items: List<NewOrderItem>): ApiResult<OrderDto> =
        addItemsToOrder(orderId, items)

    override suspend fun voidOrderItemsAsStaff(orderId: String, lines: List<VoidLine>, reason: String): ApiResult<OrderDto> =
        voidOrderItems(orderId, lines, reason)

    override suspend fun processPaymentAsStaff(orderId: String, method: String): ApiResult<OrderDto> =
        processPayment(orderId, method)

    override suspend fun cancelOrderAsStaff(orderId: String, reason: String, cancelledBy: String): ApiResult<Unit> =
        cancelOrder(orderId, reason, cancelledBy)

    /**
     * Read café settings from Room (task 4.4, Requirements 4.4 / 7.4).
     *
     * Cloud-web-only keys are suppressed by returning their *inert* value rather than whatever
     * happens to be stored, because [SettingsResponse] is a fixed-shape data class with no way to
     * omit a field. Suppression is driven by [ModeCapabilities], never by a mode comparison here —
     * that is the whole reason the capability type exists, and it means adding a fourth mode does not
     * require revisiting this method.
     *
     * Today only `customerOrderHoldSeconds` and `customerOrderAutoPrint` qualify: both exist purely
     * to pace the customer-facing web ordering flow, and off-cloud there is no such flow. Reporting
     * them as configured would put controls in the settings screen that do nothing.
     */
    override suspend fun getSettings(): ApiResult<SettingsResponse> {
        val s = settingsDao.get() ?: SystemSettings()
        val caps = modeRepository.currentCapabilities()

        return ApiResult.Success(
            SettingsResponse(
                printLanguage = s.printLanguage,
                timezone = s.timezone,
                topN = s.topN,
                staffCanSendKitchen = s.staffCanSendKitchen,
                staffCanTakePayment = s.staffCanTakePayment,
                // Customer-web pacing — meaningless without customer QR ordering.
                customerOrderHoldSeconds = if (caps.customerQrOrdering) s.customerOrderHoldSeconds else 0,
                customerOrderAutoPrint = if (caps.customerQrOrdering) s.customerOrderAutoPrint else false,
                todaysSpecial = s.todaysSpecial,
                reportEmail = s.reportEmail,
                businessDayStartHour = s.businessDayStartHour,
                defaultLangAdmin = s.defaultLangAdmin,
                defaultLangOrdering = s.defaultLangOrdering,
                // The customer surface does not exist off-cloud, but the stored preference is still
                // returned verbatim: it costs nothing, and blanking it would lose the café's choice
                // if they later switch back to Cloud Mode.
                defaultLangCustomer = s.defaultLangCustomer,
            )
        )
    }

    /**
     * Persist café settings to Room (task 4.4).
     *
     * A **partial** update, matching the Cloud `settings` endpoint: callers send only the keys they
     * changed (`AdminSettingsViewModel` posts one key at a time), so any key absent from [body] must
     * keep its current value rather than being reset to a default. That is why every read below goes
     * through the stored row as its fallback.
     *
     * Cloud-only keys are accepted and stored rather than rejected. A café that switches LAN → Cloud
     * should find its customer-web settings as it left them, and silently dropping a write the caller
     * believes succeeded is worse than storing a value that is currently unused.
     */
    override suspend fun putSettings(body: JSONObject): ApiResult<Unit> {
        val cur = settingsDao.get() ?: SystemSettings()
        settingsDao.upsert(
            cur.copy(
                printLanguage = body.optString("printLanguage", cur.printLanguage),
                timezone = body.optString("timezone", cur.timezone),
                topN = body.optInt("topN", cur.topN),
                staffCanSendKitchen = body.optBoolean("staffCanSendKitchen", cur.staffCanSendKitchen),
                staffCanTakePayment = body.optBoolean("staffCanTakePayment", cur.staffCanTakePayment),
                customerOrderHoldSeconds = body.optInt("customerOrderHoldSeconds", cur.customerOrderHoldSeconds),
                customerOrderAutoPrint = body.optBoolean("customerOrderAutoPrint", cur.customerOrderAutoPrint),
                todaysSpecial = body.optString("todaysSpecial", cur.todaysSpecial),
                reportEmail = body.optString("reportEmail", cur.reportEmail),
                businessDayStartHour = body.optInt("businessDayStartHour", cur.businessDayStartHour)
                    .coerceIn(0, 23),
                defaultLangAdmin = body.optString("defaultLangAdmin", cur.defaultLangAdmin),
                defaultLangOrdering = body.optString("defaultLangOrdering", cur.defaultLangOrdering),
                defaultLangCustomer = body.optString("defaultLangCustomer", cur.defaultLangCustomer),
            )
        )
        return ApiResult.Success(Unit)
    }

    /**
     * Café branding, including the Payment QR (task 15.3, Requirement 14.8).
     *
     * The café name comes from [AppConfigStore] — the same place the Setup Wizard writes it — so a
     * LAN café's name survives without a branding table.
     *
     * The Payment QR is served from disk rather than from a cloud bucket. **In LAN Mode the URL is an
     * HTTP one** pointing at this Server's own `/media/payment-qr`, because a Client Device cannot
     * open the Server's `file://` path — a URL that only resolves on the device that produced it
     * would leave staff phones with a Show QR button that opens an empty dialog in front of a paying
     * customer. In Kiosk Mode there are no peers, so the local `file://` path is correct and cheaper.
     */
    override suspend fun getBranding(): ApiResult<BrandingResponse> {
        val hash = appConfigStore.paymentQrHash()
        val stored = com.razstudio.pos.ui.util.PaymentQrPipeline.storedFileOrNull(appContext)

        val qrUrl: String? = when {
            hash == null || stored == null -> null
            modeRepository.currentCapabilities().staffDevices -> {
                // LAN: peers exist, so the URL has to be reachable from another device.
                val addr = lanAddress.resolve()
                if (addr is LanAddress.Result.Found) {
                    "http://${addr.ip}:$LAN_PORT/media/payment-qr"
                } else {
                    // No address means no Client could fetch it anyway; the local path at least
                    // lets the Server Device itself still display the code.
                    "file://${stored.absolutePath}"
                }
            }
            else -> "file://${stored.absolutePath}"
        }

        return ApiResult.Success(
            BrandingResponse(
                cafeName = appConfigStore.cafeName(),
                logoUrl = "",
                paymentQrHash = hash,
                paymentQrUrl = qrUrl,
            )
        )
    }

    /**
     * Update branding (task 15.3).
     *
     * The uploaded QR is **decoded before it is stored**, not after. An image that ZXing cannot read
     * is rejected here rather than being written and discovered later by a customer holding up their
     * banking app — and because the code carries no amount and the app records no transaction, a
     * silently unreadable or wrong QR leaves no trace to reconstruct from.
     */
    override suspend fun putBranding(
        cafeName: String,
        logoBase64: String?,
        paymentQrBase64: String?,
        paymentQrHash: String?,
        removePaymentQr: Boolean,
    ): ApiResult<BrandingResponse> {
        if (cafeName.isNotBlank()) appConfigStore.setCafeName(cafeName)

        if (removePaymentQr) {
            com.razstudio.pos.ui.util.PaymentQrPipeline.deleteFromInternal(appContext)
            appConfigStore.setPaymentQrHash(null)
            appConfigStore.setPaymentQrUrl(null)
            return getBranding()
        }

        if (paymentQrBase64 != null) {
            val bytes = runCatching {
                android.util.Base64.decode(
                    paymentQrBase64.substringAfter("base64,", paymentQrBase64),
                    android.util.Base64.DEFAULT,
                )
            }.getOrElse { return ApiResult.Error("VALIDATION", "Image data could not be decoded") }

            // Verify it is a readable QR before it becomes the café's payee code.
            if (com.razstudio.pos.ui.util.PaymentQrPipeline.decodeQrPayloadFromBytes(bytes) == null) {
                return ApiResult.Error("VALIDATION", "That image does not contain a scannable QR code")
            }

            val file = com.razstudio.pos.ui.util.PaymentQrPipeline.saveBytesToInternal(appContext, bytes)
            appConfigStore.setPaymentQrHash(
                com.razstudio.pos.ui.util.PaymentQrPipeline.computeSha256Hex(file)
            )
        }

        return getBranding()
    }

    /**
     * The café's tables as `(id, displayName)` (task 4.5, Requirement 3.2).
     *
     * Kiosk Mode has no tables at all — it is one counter with a running order number — so it returns
     * an empty list. Empty rather than an error on purpose: "this café has no tables" is a legitimate
     * answer that the table grid already renders (it shows its no-tables-configured state), whereas an
     * error would surface as a failure banner for something that is working as designed.
     *
     * No `qrToken` is produced in either mode: tokens exist to address a table from the customer web
     * flow, which needs a website. [ModeCapabilities.customerQrOrdering] is false off-cloud, and
     * [getTableTokens] stays unimplemented rather than inventing tokens nothing can resolve.
     */
    override suspend fun getTables(): ApiResult<List<Pair<String, String>>> {
        if (!modeRepository.currentCapabilities().tables) {
            return ApiResult.Success(emptyList())
        }
        return ApiResult.Success(tableDao.getAll().map { it.id to it.label })
    }

    override suspend fun getTableTokens(): ApiResult<Map<String, String>> =
        notImplemented("getTableTokens")

    /**
     * Replace the table registry (task 4.5). Returns the ids that were **skipped**, matching the
     * Cloud endpoint's `skippedInUse` contract.
     *
     * A table with a live order is never deleted. Deleting it would orphan that order — the grid
     * looks up labels by table id, so the order would still exist, still be payable, and show a raw
     * id where its table name should be. Those ids come back in the result so the caller can tell the
     * admin which tables it refused to remove, rather than silently keeping them.
     */
    override suspend fun putTables(tables: List<Pair<String, String>>): ApiResult<List<String>> {
        if (!modeRepository.currentCapabilities().tables) {
            return ApiResult.Error("UNSUPPORTED", "Kiosk Mode has no tables")
        }

        val desiredIds = tables.map { it.first }.toSet()
        val existing = tableDao.getAll()
        val busyIds = orderDao.getActiveOrders().map { it.tableId }.toSet()

        val skipped = mutableListOf<String>()
        for (table in existing) {
            if (table.id in desiredIds) continue
            if (table.id in busyIds) {
                skipped += table.id
                continue
            }
            tableDao.delete(table.id)
        }

        // Preserve the caller's ordering as sortOrder so the grid renders in the order the admin
        // arranged, which is how the Cloud path behaves.
        tables.forEachIndexed { index, (id, label) ->
            tableDao.insert(Table(id = id, label = label, sortOrder = index))
        }

        return ApiResult.Success(skipped)
    }

    override suspend fun getCafeLocation(): ApiResult<CafeLocationResponse> =
        notImplemented("getCafeLocation")

    override suspend fun putCafeLocation(lat: Double, lng: Double, radius: Int): ApiResult<Unit> =
        notImplemented("putCafeLocation")

    override suspend fun postAttendance(event: String, lat: Double, lng: Double, forced: Boolean): ApiResult<Unit> =
        notImplemented("postAttendance")

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * The timestamp format for everything [LocalBackend] writes, and the reason `?since=` can use a
     * plain SQL `>` comparison at all.
     *
     * [OrderDao.getOrdersSince] compares `createdAt` as **text**, so the format has to be
     * fixed-width or the comparison stops matching chronological order. `Instant.toString()` is not:
     * it omits the fractional part when it happens to be zero and drops trailing zero groups
     * otherwise, so the same instant can render as `…:05Z`, `…:05.123Z` or `…:05.123456Z`. Since
     * `'.'` (0x2E) sorts below `'Z'` (0x5A), `…:05.5Z` compares as LESS than `…:05Z` — meaning an
     * order at `.5` seconds is treated as older than one at `.0` in the same second, and with
     * `createdAt > since` it is skipped and never delivered.
     *
     * Six fixed fractional digits plus a literal `Z` makes every timestamp the same length, so text
     * order and time order coincide and that whole class of dropped order disappears.
     *
     * Rows written earlier by the Cloud path may carry Supabase's `+00:00` offset form instead. That
     * is harmless here: `'+'` (0x2B) sorts below both `'.'` and `'Z'`, so those rows sort no later
     * than they should, and they are in the past relative to any `since` a LAN poll supplies.
     */
    private fun nowTimestamp(): String = TIMESTAMP_FORMAT.format(Instant.now())

    /**
     * Announce a change to connected Clients (task 8.6, Requirement 6.5).
     *
     * Called at the **mutation sites** rather than from something watching Room. A Room observer
     * would fire after the fact and would have to diff to work out what changed; here the code that
     * performed the change already knows, which is what makes a real delta possible instead of a
     * "something happened, go re-read everything" ping.
     *
     * Fire-and-forget by construction — [com.razstudio.pos.data.lan.LanPushBus.publish] never blocks
     * and never throws, so taking a payment cannot fail because a staff phone's socket is wedged. A
     * push that is dropped is reconciled by the catch-up poll within one interval (Requirement 6.6),
     * which is the guarantee that makes not blocking here the right trade.
     *
     * Harmless in Cloud and Kiosk Mode: nothing subscribes, so the emission goes nowhere.
     */
    private fun pushOrderChanged(orderId: String, status: String, tableId: String? = null, total: Double? = null) {
        pushBus.publish(
            delta = com.razstudio.pos.data.lan.LanPushEnvelope.orderDelta(
                orderId = orderId,
                status = status,
                tableId = tableId,
                total = total,
            ),
            nowIso = nowTimestamp(),
        )
    }

    private fun notImplemented(method: String): Nothing = throw NotImplementedError(
        "LocalBackend.$method is not implemented yet — LAN/Kiosk backend arrives in task 4 onwards. " +
            "If you reached this from Cloud Mode, the BackendGateway binding picked the wrong " +
            "implementation; check ModeRepository.currentMode() and the device role."
    )

    /**
     * Convert a [NewOrderItem] to an [OrderItem] entity, resolving name/price/category snapshots
     * from [menuIndex] (pre-loaded for the whole batch so we do a single DB read per createOrder
     * call rather than N reads). Falls back gracefully when the menu item is not found — this
     * matches the DemoBackend pattern and means an unknown menuItemId produces a zero-price line
     * rather than crashing the whole order.
     */
    private fun NewOrderItem.toEntity(
        orderId: String,
        menuIndex: Map<String, MenuItem>,
        sessionNumber: Int,
    ): OrderItem {
        val menu = menuIndex[menuItemId]
        return OrderItem(
            id = UUID.randomUUID().toString(),
            orderId = orderId,
            menuItemId = menuItemId,
            // Backend bakes the English name as the line-item snapshot; clients re-resolve per locale
            // at display/print time from the live menu (PrintService.localizeItemNames).
            nameSnapshot = menu?.nameEn ?: menuItemId,
            unitPriceSnapshot = unitPrice ?: menu?.price ?: 0.0,
            categorySnapshot = menu?.category ?: "",
            quantity = quantity,
            note = note,
            sentToKitchen = false,
            sessionNumber = sessionNumber,
        )
    }

    private fun Order.toDto(items: List<OrderItem>): OrderDto = OrderDto(
        id = id,
        tableId = tableId,
        source = source,
        status = status.name,
        paymentMethod = paymentMethod,
        total = total,
        sentToKitchenAt = sentToKitchenAt,
        cancelReason = cancelReason,
        cancelledBy = cancelledBy,
        createdAt = createdAt,
        items = items.map { it.toDto() },
    )

    /**
     * Room row -> wire DTO for the Devices screen.
     *
     * `deviceIdentifier` and `id` are the same value off-cloud: the Cloud backend has its own row id
     * distinct from the device's self-generated identifier, whereas here the device identifier IS the
     * primary key, so there is no second id to report.
     */
    private fun PairedDevice.toDto(): DeviceDto = DeviceDto(
        id = id,
        deviceIdentifier = id,
        label = name,
        role = role,
        status = status,
        lastSeenAt = TIMESTAMP_FORMAT.format(java.time.Instant.ofEpochMilli(lastSeenMs)),
        isCheckedIn = false,
    )

    /** Room row -> wire DTO. The wire's `image` field is this row's [MenuItem.imageUrl]. */
    private fun MenuItem.toDto(): MenuItemDto = MenuItemDto(
        id = id,
        category = category,
        extraCategories = extraCategories,
        code = code,
        price = price,
        marketPrice = marketPrice,
        available = available,
        askMeDaily = askMeDaily,
        imageUrl = imageUrl,
        hasVariablePrice = hasVariablePrice,
        variablePriceDailyPrompt = variablePriceDailyPrompt,
        priceOption1 = priceOption1,
        priceOption2 = priceOption2,
        priceOption3 = priceOption3,
        nameEn = nameEn,
        nameBm = nameBm,
        nameZh = nameZh,
        nameTa = nameTa,
        nameTh = nameTh,
        doNotTranslate = doNotTranslate,
    )

    private fun OrderItem.toDto(): OrderItemDto = OrderItemDto(
        id = id,
        menuItemId = menuItemId,
        nameSnapshot = nameSnapshot,
        unitPriceSnapshot = unitPriceSnapshot,
        categorySnapshot = categorySnapshot,
        quantity = quantity,
        note = note,
        sentToKitchen = sentToKitchen,
        sessionNumber = sessionNumber,
    )
}
