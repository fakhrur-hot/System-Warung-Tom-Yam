package com.razstudio.pos.data.local

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages full database export/import as JSON envelope v2.
 * Only exports entities that exist locally in Room.
 */
@Singleton
class DatabaseBackupManager @Inject constructor(
    private val menuDao: MenuDao,
    private val orderDao: OrderDao,
    private val tableDao: TableDao,
    private val settingsDao: SettingsDao,
    private val printerConfigDao: PrinterConfigDao,
    private val pendingOrderDao: PendingOrderDao,
    private val printJobDao: PrintJobDao
) {

    companion object {
        private const val CURRENT_VERSION = 2
    }

    /**
     * Reads all tables and builds a JSON envelope v2 string.
     */
    suspend fun exportToJson(): String {
        val root = JSONObject()
        root.put("version", CURRENT_VERSION)
        root.put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))

        // Tables
        val tablesArray = JSONArray()
        tableDao.getAll().forEach { table ->
            tablesArray.put(JSONObject().apply {
                put("id", table.id)
                put("label", table.label)
                put("sortOrder", table.sortOrder)
            })
        }
        root.put("tables", tablesArray)

        // Menu Items
        //
        // Ten of MenuItem's twenty columns used to be dropped here: `code`, `extraCategories`,
        // `marketPrice`, `imageUrl`, `imagePath`, `hasVariablePrice`, `variablePriceDailyPrompt`
        // and the three price options. Export claims to save all local data, so a cafe that
        // restored from its own backup silently lost every item code, every photo reference, its
        // market-price flags and all its price tiers — and only found out when a customer ordered
        // something that no longer had a price.
        //
        // Everything MenuItem holds is written now. Reading stays tolerant of absence, so a backup
        // taken before this change still imports.
        val menuArray = JSONArray()
        menuDao.getAll().forEach { item ->
            menuArray.put(JSONObject().apply {
                put("id", item.id)
                put("category", item.category)
                put("extraCategories", item.extraCategories)
                put("code", item.code)
                put("price", item.price)
                put("marketPrice", item.marketPrice)
                put("available", item.available)
                put("askMeDaily", item.askMeDaily)
                put("imageUrl", item.imageUrl)
                put("imagePath", item.imagePath)
                put("nameEn", item.nameEn)
                put("nameBm", item.nameBm)
                put("nameZh", item.nameZh)
                put("nameTa", item.nameTa)
                put("nameTh", item.nameTh)
                put("doNotTranslate", item.doNotTranslate)
                put("hasVariablePrice", item.hasVariablePrice)
                put("variablePriceDailyPrompt", item.variablePriceDailyPrompt)
                put("priceOption1", item.priceOption1 ?: JSONObject.NULL)
                put("priceOption2", item.priceOption2 ?: JSONObject.NULL)
                put("priceOption3", item.priceOption3 ?: JSONObject.NULL)
            })
        }
        root.put("menuItems", menuArray)

        // Orders
        val ordersArray = JSONArray()
        orderDao.getAllOrders().forEach { order ->
            ordersArray.put(JSONObject().apply {
                put("id", order.id)
                put("tableId", order.tableId)
                put("source", order.source)
                put("status", order.status.name)
                put("paymentMethod", order.paymentMethod ?: JSONObject.NULL)
                put("total", order.total)
                put("sentToKitchenAt", order.sentToKitchenAt ?: JSONObject.NULL)
                put("cancelReason", order.cancelReason ?: JSONObject.NULL)
                put("cancelledBy", order.cancelledBy ?: JSONObject.NULL)
                put("createdAt", order.createdAt)
            })
        }
        root.put("orders", ordersArray)

        // Order Items
        val orderItemsArray = JSONArray()
        orderDao.getAllOrderItems().forEach { item ->
            orderItemsArray.put(JSONObject().apply {
                put("id", item.id)
                put("orderId", item.orderId)
                put("menuItemId", item.menuItemId)
                put("nameSnapshot", item.nameSnapshot)
                put("unitPriceSnapshot", item.unitPriceSnapshot)
                put("categorySnapshot", item.categorySnapshot)
                put("quantity", item.quantity)
                put("note", item.note ?: JSONObject.NULL)
                put("sentToKitchen", item.sentToKitchen)
            })
        }
        root.put("orderItems", orderItemsArray)

        // Settings
        val settings = settingsDao.get()
        if (settings != null) {
            root.put("settings", JSONObject().apply {
                put("id", settings.id)
                put("printLanguage", settings.printLanguage)
                put("timezone", settings.timezone)
                put("topN", settings.topN)
                put("staffCanSendKitchen", settings.staffCanSendKitchen)
                put("staffCanTakePayment", settings.staffCanTakePayment)
            })
        } else {
            root.put("settings", JSONObject.NULL)
        }

        // Printer Configs
        val printerArray = JSONArray()
        printerConfigDao.getAll().forEach { pc ->
            printerArray.put(JSONObject().apply {
                put("id", pc.id)
                put("name", pc.name)
                put("address", pc.address ?: JSONObject.NULL)
                put("transport", pc.transport.name)
                put("drawerKick", pc.drawerKick.name)
                put("paperWidth", pc.paperWidth.name)
                put("printerRole", pc.printerRole.name)
                put("isActive", pc.isActive)
                put("categoryFilter", pc.categoryFilter ?: JSONObject.NULL)
            })
        }
        root.put("printerConfigs", printerArray)

        // Pending Orders
        val pendingArray = JSONArray()
        pendingOrderDao.getAll().forEach { po ->
            pendingArray.put(JSONObject().apply {
                put("id", po.id)
                put("tableId", po.tableId)
                put("itemsJson", po.itemsJson)
                put("createdAt", po.createdAt)
                put("retryCount", po.retryCount)
            })
        }
        root.put("pendingOrders", pendingArray)

        // Print Jobs
        val printJobsArray = JSONArray()
        printJobDao.getAll().forEach { pj ->
            printJobsArray.put(JSONObject().apply {
                put("id", pj.id)
                put("printerId", pj.printerId)
                put("documentType", pj.documentType)
                put("payload", pj.payload)
                put("status", pj.status)
                put("createdAt", pj.createdAt)
                put("retryCount", pj.retryCount)
                put("lastError", pj.lastError ?: JSONObject.NULL)
            })
        }
        root.put("printJobs", printJobsArray)

        return root.toString(2)
    }

    /**
     * Parses the JSON backup and returns a preview of entity counts.
     * Validates the version field.
     */
    suspend fun importFromJson(json: String): BackupPreview {
        val root = JSONObject(json)
        val version = root.optInt("version", -1)
        if (version < 1 || version > CURRENT_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version. Expected 1-$CURRENT_VERSION.")
        }
        val exportedAt = root.optString("exportedAt", "unknown")
        val tableCount = root.optJSONArray("tables")?.length() ?: 0
        val menuItemCount = root.optJSONArray("menuItems")?.length() ?: 0
        val orderCount = root.optJSONArray("orders")?.length() ?: 0
        val orderItemCount = root.optJSONArray("orderItems")?.length() ?: 0
        val hasPrinterConfigs = (root.optJSONArray("printerConfigs")?.length() ?: 0) > 0
        val hasSettings = !root.isNull("settings")

        return BackupPreview(
            version = version,
            exportedAt = exportedAt,
            tableCount = tableCount,
            menuItemCount = menuItemCount,
            orderCount = orderCount,
            orderItemCount = orderItemCount,
            hasPrinterConfigs = hasPrinterConfigs,
            hasSettings = hasSettings
        )
    }

    /**
     * Clears all existing data then inserts entities from the backup JSON.
     *
     * @param restoreHardwareConfig When true (the default), printer configs from the JSON are
     *   applied to this device. The Google Drive café-bundle path decides this per restore rather
     *   than always passing one value — see `SignInViewModel.hardwareConfigFitsThisDevice`. A Sunmi
     *   till's printer set must not be written onto a replacement phone that has no such hardware,
     *   or every print silently goes nowhere; but a like-for-like replacement *should* keep its
     *   printers, which is the point of the bundle. (HW-REQ-8)
     */
    suspend fun applyImport(json: String, restoreHardwareConfig: Boolean = true) {
        val root = JSONObject(json)

        // Clear existing data (order matters due to FK constraints)
        orderDao.deleteAllOrderItems()
        orderDao.deleteAllOrders()
        printJobDao.deleteAll()
        printerConfigDao.deleteAll()
        pendingOrderDao.deleteAll()
        menuDao.deleteAll()
        tableDao.deleteAll()
        settingsDao.deleteAll()

        // Import tables
        root.optJSONArray("tables")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                tableDao.insert(
                    Table(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        sortOrder = obj.optInt("sortOrder", 0)
                    )
                )
            }
        }

        // Import menu items
        root.optJSONArray("menuItems")?.let { arr ->
            val items = mutableListOf<MenuItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(
                    MenuItem(
                        id = obj.getString("id"),
                        category = obj.getString("category"),
                        extraCategories = obj.optString("extraCategories", ""),
                        code = obj.optString("code", ""),
                        price = obj.getDouble("price"),
                        marketPrice = obj.optBoolean("marketPrice", false),
                        available = obj.getBoolean("available"),
                        askMeDaily = obj.optBoolean("askMeDaily", false),
                        imageUrl = obj.optString("imageUrl", ""),
                        imagePath = obj.optString("imagePath", ""),
                        nameEn = obj.getString("nameEn"),
                        nameBm = obj.optString("nameBm", ""),
                        nameZh = obj.optString("nameZh", ""),
                        nameTa = obj.optString("nameTa", ""),
                        nameTh = obj.optString("nameTh", ""),
                        doNotTranslate = obj.optBoolean("doNotTranslate", false),
                        hasVariablePrice = obj.optBoolean("hasVariablePrice", false),
                        variablePriceDailyPrompt = obj.optBoolean("variablePriceDailyPrompt", false),
                        // `optDouble` returns NaN for a missing key, which would silently become a
                        // real price of NaN on the till. Absence has to stay null.
                        priceOption1 = if (obj.isNull("priceOption1")) null else obj.optDouble("priceOption1"),
                        priceOption2 = if (obj.isNull("priceOption2")) null else obj.optDouble("priceOption2"),
                        priceOption3 = if (obj.isNull("priceOption3")) null else obj.optDouble("priceOption3"),
                    )
                )
            }
            menuDao.upsertAll(items)
        }

        // Import orders
        root.optJSONArray("orders")?.let { arr ->
            val orders = mutableListOf<Order>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                orders.add(
                    Order(
                        id = obj.getString("id"),
                        tableId = obj.getString("tableId"),
                        source = obj.getString("source"),
                        status = OrderStatus.fromWire(obj.getString("status")),
                        paymentMethod = obj.optStringOrNull("paymentMethod"),
                        total = obj.getDouble("total"),
                        sentToKitchenAt = obj.optStringOrNull("sentToKitchenAt"),
                        cancelReason = obj.optStringOrNull("cancelReason"),
                        cancelledBy = obj.optStringOrNull("cancelledBy"),
                        createdAt = obj.getString("createdAt")
                    )
                )
            }
            orderDao.insertOrders(orders)
        }

        // Import order items
        root.optJSONArray("orderItems")?.let { arr ->
            val items = mutableListOf<OrderItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(
                    OrderItem(
                        id = obj.getString("id"),
                        orderId = obj.getString("orderId"),
                        menuItemId = obj.getString("menuItemId"),
                        nameSnapshot = obj.getString("nameSnapshot"),
                        unitPriceSnapshot = obj.getDouble("unitPriceSnapshot"),
                        categorySnapshot = obj.getString("categorySnapshot"),
                        quantity = obj.getInt("quantity"),
                        note = obj.optStringOrNull("note"),
                        sentToKitchen = obj.optBoolean("sentToKitchen", false)
                    )
                )
            }
            orderDao.insertOrderItems(items)
        }

        // Import settings
        if (!root.isNull("settings")) {
            val obj = root.getJSONObject("settings")
            settingsDao.upsert(
                SystemSettings(
                    id = obj.optInt("id", 1),
                    printLanguage = obj.optString("printLanguage", "EN"),
                    timezone = obj.optString("timezone", "Asia/Kuala_Lumpur"),
                    topN = obj.optInt("topN", 5),
                    staffCanSendKitchen = obj.optBoolean("staffCanSendKitchen", false),
                    staffCanTakePayment = obj.optBoolean("staffCanTakePayment", false)
                )
            )
        }

        // Import printer configs
        if (restoreHardwareConfig) {
        root.optJSONArray("printerConfigs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                printerConfigDao.insert(
                    PrinterConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        address = if (obj.isNull("address")) obj.optStringOrNull("macAddress") else obj.optStringOrNull("address"),
                        transport = PrinterTransport.valueOf(obj.optString("transport", "BLUETOOTH")),
                        drawerKick = DrawerKick.valueOf(obj.optString("drawerKick", "NONE")),
                        paperWidth = PaperWidth.valueOf(obj.getString("paperWidth")),
                        printerRole = PrinterRole.valueOf(obj.getString("printerRole")),
                        isActive = obj.optBoolean("isActive", true),
                        categoryFilter = obj.optStringOrNull("categoryFilter")
                    )
                )
            }
        }
        }

        // Import pending orders
        root.optJSONArray("pendingOrders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                pendingOrderDao.insert(
                    PendingOrder(
                        id = obj.getString("id"),
                        tableId = obj.getString("tableId"),
                        itemsJson = obj.getString("itemsJson"),
                        createdAt = obj.getString("createdAt"),
                        retryCount = obj.optInt("retryCount", 0)
                    )
                )
            }
        }

        // Import print jobs
        root.optJSONArray("printJobs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                printJobDao.insert(
                    PrintJob(
                        id = obj.getString("id"),
                        printerId = obj.getString("printerId"),
                        documentType = obj.getString("documentType"),
                        payload = obj.getString("payload"),
                        status = obj.getString("status"),
                        createdAt = obj.getString("createdAt"),
                        retryCount = obj.optInt("retryCount", 0),
                        lastError = obj.optStringOrNull("lastError")
                    )
                )
            }
        }
    }

    /**
     * Extension: returns null if key is missing or JSON null, otherwise the string.
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        return if (isNull(key)) null else optString(key, null)
    }
}

/**
 * Preview data for an import file, shown to user before confirming.
 */
data class BackupPreview(
    val version: Int,
    val exportedAt: String,
    val tableCount: Int,
    val menuItemCount: Int,
    val orderCount: Int,
    val orderItemCount: Int,
    val hasPrinterConfigs: Boolean,
    val hasSettings: Boolean
)
