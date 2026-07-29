package com.warungtomyam.pos.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database holding local menu items, orders, tables, settings, pending orders,
 * printer configurations, and print jobs.
 * Version 5 adds PrinterConfig and PrintJob entities for multi-printer support.
 * Version 6 adds MenuItem.imagePath (Storage object path, for deleting superseded images).
 * Version 7 adds MenuItem variable-price fields (hasVariablePrice, variablePriceDailyPrompt,
 * priceOption1/2/3) for "special" items with admin-editable, day-selectable price presets.
 * Version 8 adds SystemSettings.nextTableNumber (auto-generated T0001-T9999 table IDs) and
 * OrderItem.sessionNumber (groups items by which order-placement round they belong to).
 * Version 9 adds MenuItem.code (optional short item code) and MenuItem.marketPrice
 * (market-price items whose price is decided at the counter) for the dynamic-menu revamp.
 * Uses destructive migration fallback (acceptable for local cache), but a proper
 * MIGRATION_8_9 is registered so existing rows survive the upgrade.
 */
@Database(
    entities = [
        MenuItem::class,
        Order::class,
        OrderItem::class,
        SystemSettings::class,
        Table::class,
        PendingOrder::class,
        PrinterConfig::class,
        PrintJob::class
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun orderDao(): OrderDao
    abstract fun settingsDao(): SettingsDao
    abstract fun tableDao(): TableDao
    abstract fun pendingOrderDao(): PendingOrderDao
    abstract fun printerConfigDao(): PrinterConfigDao
    abstract fun printJobDao(): PrintJobDao
}
