package com.warungtomyam.pos.data.demo

import com.warungtomyam.pos.data.local.AppDatabase
import com.warungtomyam.pos.data.local.MenuItem
import com.warungtomyam.pos.data.local.Order
import com.warungtomyam.pos.data.local.OrderItem
import com.warungtomyam.pos.data.local.OrderStatus
import com.warungtomyam.pos.data.local.Table

/**
 * Pure data object containing deterministic seed data for Demo Mode.
 * Populates the in-memory demo database on every session start.
 */
object DemoSeedData {

    val tables = listOf(
        Table(id = "T1", label = "Table 1", sortOrder = 1),
        Table(id = "T2", label = "Table 2", sortOrder = 2),
        Table(id = "T3", label = "Table 3", sortOrder = 3),
    )

    val menuItems = listOf(
        // Food (4 items)
        MenuItem(id = "m1", category = "FOOD", price = 8.0,
            available = true, askMeDaily = false,
            nameEn = "Coconut milk rice", nameBm = "Nasi Lemak",
            nameZh = "椰浆饭", nameTa = "நாசி லெமாக்", nameTh = "ข้าวมันกะทิ"),
        MenuItem(id = "m2", category = "FOOD", price = 7.0,
            available = true, askMeDaily = false,
            nameEn = "Fried noodles", nameBm = "Mee Goreng",
            nameZh = "炒面", nameTa = "மீ கோரெங்", nameTh = "หมี่กอเร็ง"),
        MenuItem(id = "m3", category = "FOOD", price = 8.0,
            available = true, askMeDaily = false,
            nameEn = "Village-style fried rice", nameBm = "Nasi Goreng Kampung",
            nameZh = "马来乡村炒饭", nameTa = "நாசி கோரெங் கம்பூங்", nameTh = "ข้าวผัดแบบบ้านๆ"),
        MenuItem(id = "m4", category = "FOOD", price = 10.0,
            available = true, askMeDaily = true,
            nameEn = "Spiced fried chicken", nameBm = "Ayam Goreng Berempah",
            nameZh = "香料炸鸡", nameTa = "மசாலா கோழி வறுவல்", nameTh = "ไก่ทอดเครื่องเทศ"),
        // Beverages (3 items)
        MenuItem(id = "m5", category = "BEVERAGES", price = 3.0,
            available = true, askMeDaily = false,
            nameEn = "Pulled milk tea", nameBm = "Teh Tarik",
            nameZh = "拉茶", nameTa = "தேநீர் தாரிக்", nameTh = "ชาดึง"),
        MenuItem(id = "m6", category = "BEVERAGES", price = 3.0,
            available = true, askMeDaily = false,
            nameEn = "Black coffee", nameBm = "Kopi O",
            nameZh = "咖啡乌", nameTa = "கோப்பி ஓ", nameTh = "กาแฟดำ"),
        MenuItem(id = "m7", category = "BEVERAGES", price = 4.0,
            available = true, askMeDaily = false,
            nameEn = "Rose syrup milk drink", nameBm = "Sirap Bandung",
            nameZh = "玫瑰糖浆奶水", nameTa = "சிராப் பாண்டூங்", nameTh = "น้ำแดงบันดุง"),
        // Side Dishes (2 items)
        MenuItem(id = "m8", category = "SIDE_DISHES", price = 5.0,
            available = true, askMeDaily = false,
            nameEn = "Fish crackers", nameBm = "Keropok Lekor",
            nameZh = "鱼饼", nameTa = "கெரோப்போக் லெகோர்", nameTh = "ข้าวเกรียบปลา"),
        MenuItem(id = "m9", category = "SIDE_DISHES", price = 3.0,
            available = true, askMeDaily = false,
            nameEn = "Fried egg (sunny side up)", nameBm = "Telur Mata",
            nameZh = "煎蛋", nameTa = "பொரித்த முட்டை", nameTh = "ไข่ดาว"),
        // Others (1 item)
        MenuItem(id = "m10", category = "OTHERS", price = 9.0,
            available = true, askMeDaily = false,
            nameEn = "Mixed rice", nameBm = "Nasi Campur",
            nameZh = "杂菜饭", nameTa = "கலவை சாதம்", nameTh = "ข้าวราดแกง"),
    )

    // Two pre-existing orders in different statuses
    val orders = listOf(
        Order(id = "demo-order-1", tableId = "T2", source = "QR",
            status = OrderStatus.SENT_TO_KITCHEN, total = 14.0,
            sentToKitchenAt = "2025-01-01T10:00:00Z", createdAt = "2025-01-01T09:55:00Z"),
        Order(id = "demo-order-2", tableId = "T3", source = "STAFF",
            status = OrderStatus.RECEIVED, total = 13.0,
            createdAt = "2025-01-01T10:05:00Z"),
    )

    val orderItems = listOf(
        // Order 1 items (T2, sent to kitchen)
        OrderItem(id = "oi1", orderId = "demo-order-1", menuItemId = "m1",
            nameSnapshot = "Nasi Lemak", unitPriceSnapshot = 8.0,
            categorySnapshot = "FOOD", quantity = 1, sentToKitchen = true),
        OrderItem(id = "oi2", orderId = "demo-order-1", menuItemId = "m5",
            nameSnapshot = "Teh Tarik", unitPriceSnapshot = 3.0,
            categorySnapshot = "BEVERAGES", quantity = 2, sentToKitchen = true),
        // Order 2 items (T4, received but not yet sent)
        OrderItem(id = "oi3", orderId = "demo-order-2", menuItemId = "m2",
            nameSnapshot = "Mee Goreng", unitPriceSnapshot = 7.0,
            categorySnapshot = "FOOD", quantity = 1, sentToKitchen = false),
        OrderItem(id = "oi4", orderId = "demo-order-2", menuItemId = "m6",
            nameSnapshot = "Kopi O", unitPriceSnapshot = 3.0,
            categorySnapshot = "BEVERAGES", quantity = 1, sentToKitchen = false),
        OrderItem(id = "oi5", orderId = "demo-order-2", menuItemId = "m9",
            nameSnapshot = "Telur Mata", unitPriceSnapshot = 3.0,
            categorySnapshot = "SIDE_DISHES", quantity = 1, sentToKitchen = false),
    )

    /**
     * Inserts all deterministic seed data into the provided database.
     * Called at the start of every demo session.
     */
    suspend fun seed(db: AppDatabase) {
        db.tableDao().let { dao -> tables.forEach { dao.insert(it) } }
        db.menuDao().upsertAll(menuItems)
        db.orderDao().insertOrders(orders)
        db.orderDao().insertOrderItems(orderItems)
    }
}
