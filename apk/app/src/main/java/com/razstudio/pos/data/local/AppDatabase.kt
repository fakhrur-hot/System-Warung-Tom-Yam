package com.razstudio.pos.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.razstudio.pos.data.promos.AffiliateProductDao
import com.razstudio.pos.data.promos.AffiliateProductEntity
import com.razstudio.pos.data.promos.CampaignEntity
import com.razstudio.pos.notification.CapturedPayment
import com.razstudio.pos.notification.CapturedPaymentDao

/**
 * Room database holding local menu items, orders, tables, settings, pending orders,
 * printer configurations, print jobs, and (from v11) the operating-mode entities.
 * Version 5 adds PrinterConfig and PrintJob entities for multi-printer support.
 * Version 6 adds MenuItem.imagePath (Storage object path, for deleting superseded images).
 * Version 7 adds MenuItem variable-price fields (hasVariablePrice, variablePriceDailyPrompt,
 * priceOption1/2/3) for "special" items with admin-editable, day-selectable price presets.
 * Version 8 adds SystemSettings.nextTableNumber (auto-generated T0001-T9999 table IDs) and
 * OrderItem.sessionNumber (groups items by which order-placement round they belong to).
 * Version 9 adds MenuItem.code (optional short item code) and MenuItem.marketPrice
 * (market-price items whose price is decided at the counter) for the dynamic-menu revamp.
 * Version 10 adds MenuItem.extraCategories (comma-separated secondary category pages).
 * Version 11 adds OrderNumberSequence (Kiosk Mode running order numbers, keyed by business day).
 * Version 12 adds PairedDevice (LAN Mode paired Client Device registry, keyed by device id).
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
        PrintJob::class,
        OrderNumberSequence::class,
        PairedDevice::class,
        PairingToken::class,
        CafeSession::class,
        DailyAggregate::class,
        PaymentTransaction::class,
        CashDrawerEvent::class,
        CapturedPayment::class,
        AffiliateProductEntity::class,
        CampaignEntity::class,
    ],
    version = 23,
    // exportSchema = true so MigrationTestHelper can validate the schema after migration.
    // Schema JSON files are written to app/schemas/ and committed to source control so that
    // future migration tests can verify against a stable baseline (Requirement 8.1, 12.6).
    exportSchema = true
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
    abstract fun orderNumberSequenceDao(): OrderNumberSequenceDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun pairingTokenDao(): PairingTokenDao
    abstract fun cafeSessionDao(): CafeSessionDao
    abstract fun dailyAggregateDao(): DailyAggregateDao
    abstract fun paymentTransactionDao(): PaymentTransactionDao
    abstract fun cashDrawerEventDao(): CashDrawerEventDao
    abstract fun capturedPaymentDao(): CapturedPaymentDao
    abstract fun affiliateProductDao(): AffiliateProductDao
}
