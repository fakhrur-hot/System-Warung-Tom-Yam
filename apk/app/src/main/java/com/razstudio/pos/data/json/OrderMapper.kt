package com.razstudio.pos.data.json

import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.OrderItemDto
import com.razstudio.pos.data.local.Order
import com.razstudio.pos.data.local.OrderItem
import com.razstudio.pos.data.local.OrderStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single canonical parser for order payloads.
 *
 * Every JSON → order conversion goes through here — REST catch-up sync
 * ([com.razstudio.pos.data.ApiClient]), the admin Realtime `NEW_ORDER`
 * broadcast ([com.razstudio.pos.realtime.RealtimeService]), and the ordering
 * foreground service ([com.razstudio.pos.realtime.OrderingForegroundService]).
 * Keeping the field contract in one place means a backend change or a null-handling
 * fix is made once, not in three hand-written copies.
 *
 * Nullable fields use [optStringOrNull] (never the `optString(name, null)` trap);
 * required fields use the `req*` helpers, which throw [ParseException] naming the
 * offending field so callers can log precisely what broke.
 *
 * The wire DTO keeps `status` as a String; [toEntity] maps it to the typed
 * [OrderStatus] enum (Seam 2), so an unrecognized status becomes [OrderStatus.UNKNOWN]
 * rather than a silently-accepted arbitrary string.
 */
object OrderMapper {

    fun orderDto(json: JSONObject): OrderDto = OrderDto(
        id = json.reqString("id"),
        tableId = json.reqString("tableId"),
        source = json.optStringOrNull("source") ?: "QR",
        status = json.reqString("status"),
        paymentMethod = json.optStringOrNull("paymentMethod"),
        total = json.reqDouble("total"),
        sentToKitchenAt = json.optStringOrNull("sentToKitchenAt"),
        cancelReason = json.optStringOrNull("cancelReason"),
        cancelledBy = json.optStringOrNull("cancelledBy"),
        createdAt = json.reqString("createdAt"),
        items = orderItemDtos(json.optJSONArray("items")),
    )

    fun orderItemDto(json: JSONObject): OrderItemDto = OrderItemDto(
        id = json.reqString("id"),
        menuItemId = json.reqString("menuItemId"),
        nameSnapshot = json.reqString("nameSnapshot"),
        unitPriceSnapshot = json.reqDouble("unitPriceSnapshot"),
        categorySnapshot = json.reqString("categorySnapshot"),
        quantity = json.reqInt("quantity"),
        note = json.optStringOrNull("note"),
        sentToKitchen = json.optBoolean("sentToKitchen", false),
        sessionNumber = json.optInt("sessionNumber", 1),
    )

    fun orderItemDtos(array: JSONArray?): List<OrderItemDto> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) add(orderItemDto(array.getJSONObject(i)))
    }
}

/** Map a parsed [OrderDto] to its Room entity. */
fun OrderDto.toEntity(): Order = Order(
    id = id,
    tableId = tableId,
    source = source,
    status = OrderStatus.fromWire(status),
    paymentMethod = paymentMethod,
    total = total,
    sentToKitchenAt = sentToKitchenAt,
    cancelReason = cancelReason,
    cancelledBy = cancelledBy,
    createdAt = createdAt,
)

/** Map a parsed [OrderItemDto] to its Room entity under [orderId]. */
fun OrderItemDto.toEntity(orderId: String): OrderItem = OrderItem(
    id = id,
    orderId = orderId,
    menuItemId = menuItemId,
    nameSnapshot = nameSnapshot,
    unitPriceSnapshot = unitPriceSnapshot,
    categorySnapshot = categorySnapshot,
    quantity = quantity,
    note = note,
    sentToKitchen = sentToKitchen,
    sessionNumber = sessionNumber,
)
