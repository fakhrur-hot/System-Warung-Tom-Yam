package com.warungtomyam.pos.data.local

import androidx.room.TypeConverter

/**
 * Room type converters for custom enums used in printer entities.
 */
class Converters {

    @TypeConverter
    fun fromPaperWidth(value: PaperWidth): String = value.name

    @TypeConverter
    fun toPaperWidth(value: String): PaperWidth = PaperWidth.valueOf(value)

    @TypeConverter
    fun fromPrinterRole(value: PrinterRole): String = value.name

    @TypeConverter
    fun toPrinterRole(value: String): PrinterRole = PrinterRole.valueOf(value)

    @TypeConverter
    fun fromOrderStatus(value: OrderStatus): String = value.name

    @TypeConverter
    fun toOrderStatus(value: String): OrderStatus = OrderStatus.fromWire(value)
}
