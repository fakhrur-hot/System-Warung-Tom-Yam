package com.razstudio.pos.data.lan

import com.razstudio.pos.data.MenuItemDto
import com.razstudio.pos.data.OrderDto
import com.razstudio.pos.data.OrderItemDto
import org.json.JSONArray
import org.json.JSONObject

/**
 * DTO → JSON for [LanServer] responses (task 6.2).
 *
 * The mirror image of `data/json/OrderMapper`, which parses these same shapes on the way in. Every
 * key here is one `OrderMapper` reads with `reqString`/`reqDouble` — those throw on a missing field
 * rather than defaulting, so a key misspelled here fails the Client's whole sync rather than
 * silently producing an order with a blank name or a zero price. Kept beside [LanServer] rather than
 * next to the parser because only the LAN path ever serialises outward; Cloud responses are built by
 * the Edge Functions.
 *
 * `JSONObject.NULL` rather than Kotlin null throughout: `put(key, null)` **removes** the key, and a
 * missing `paymentMethod` is not the same to the parser as an explicitly null one.
 */
internal fun OrderDto.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("tableId", tableId)
    .put("source", source)
    .put("status", status)
    .put("paymentMethod", paymentMethod ?: JSONObject.NULL)
    .put("total", total)
    .put("sentToKitchenAt", sentToKitchenAt ?: JSONObject.NULL)
    .put("cancelReason", cancelReason ?: JSONObject.NULL)
    .put("cancelledBy", cancelledBy ?: JSONObject.NULL)
    .put("createdAt", createdAt)
    .put("items", JSONArray(items.map { it.toJson() }))

internal fun OrderItemDto.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("menuItemId", menuItemId)
    .put("nameSnapshot", nameSnapshot)
    .put("unitPriceSnapshot", unitPriceSnapshot)
    .put("categorySnapshot", categorySnapshot)
    .put("quantity", quantity)
    .put("note", note ?: JSONObject.NULL)
    .put("sentToKitchen", sentToKitchen)
    .put("sessionNumber", sessionNumber)

/**
 * Menu items go out in the wire shape `ApiClient.getMenu` parses — note the nested `name` object and
 * the `image` key, neither of which matches the DTO's own field names. This asymmetry is the
 * Supabase menu-snapshot format, and the Client parses it the same way whichever backend answered.
 */
internal fun MenuItemDto.toMenuJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("category", category)
    .put("code", code)
    .put("price", price)
    .put("marketPrice", marketPrice)
    .put("available", available)
    .put("askMeDaily", askMeDaily)
    .put("image", imageUrl)
    .put("hasVariablePrice", hasVariablePrice)
    .put("variablePriceDailyPrompt", variablePriceDailyPrompt)
    .put("priceOption1", priceOption1 ?: JSONObject.NULL)
    .put("priceOption2", priceOption2 ?: JSONObject.NULL)
    .put("priceOption3", priceOption3 ?: JSONObject.NULL)
    .put(
        "name",
        JSONObject()
            .put("en", nameEn)
            .put("bm", nameBm)
            .put("zh", nameZh)
            .put("ta", nameTa)
            .put("th", nameTh)
            .put("doNotTranslate", doNotTranslate),
    )
