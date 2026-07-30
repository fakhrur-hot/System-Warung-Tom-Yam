package com.razstudio.pos.data.json

import com.razstudio.pos.data.local.OrderStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the JSON parsing bug (apk-refactor Seam 1): JSON-null
 * values must parse to Kotlin `null`, never the literal String "null".
 */
class OrderMapperTest {

    @Test
    fun nullableFieldsWithJsonNullParseToKotlinNull() {
        val json = JSONObject(
            """
            {
              "id": "o1", "tableId": "T1", "status": "RECEIVED", "total": 12.5,
              "createdAt": "2026-07-22T00:00:00Z",
              "source": null, "paymentMethod": null, "sentToKitchenAt": null,
              "cancelReason": null, "cancelledBy": null,
              "items": [
                {
                  "id": "i1", "menuItemId": "m1", "nameSnapshot": "Nasi Lemak",
                  "unitPriceSnapshot": 12.5, "categorySnapshot": "FOOD",
                  "quantity": 1, "note": null
                }
              ]
            }
            """.trimIndent()
        )

        val dto = OrderMapper.orderDto(json)

        assertNull("paymentMethod should be null, not \"null\"", dto.paymentMethod)
        assertNull(dto.sentToKitchenAt)
        assertNull(dto.cancelReason)
        assertNull(dto.cancelledBy)
        // source is nullable-with-default: JSON null falls back to "QR"
        assertEquals("QR", dto.source)

        assertEquals(1, dto.items.size)
        assertNull("item note should be null, not \"null\"", dto.items[0].note)

        // Entity mapping must preserve the real null (this is what reaches Room / the printer).
        val item = dto.items[0].toEntity("o1")
        assertNull(item.note)
        assertTrue(item.note != "null")
    }

    @Test
    fun missingNullableKeysAlsoParseToNull() {
        // Keys entirely absent (not JSON null) must also yield null.
        val json = JSONObject(
            """{"id":"o2","tableId":"T2","status":"READY","total":3.0,"createdAt":"x"}"""
        )
        val dto = OrderMapper.orderDto(json)
        assertNull(dto.paymentMethod)
        assertNull(dto.cancelReason)
        assertEquals(0, dto.items.size)
    }

    @Test
    fun presentValuesParseNormally() {
        val json = JSONObject(
            """
            {"id":"o3","tableId":"T3","status":"COMPLETED","total":20.0,
             "createdAt":"x","paymentMethod":"CASH","source":"STAFF"}
            """.trimIndent()
        )
        val dto = OrderMapper.orderDto(json)
        assertEquals("CASH", dto.paymentMethod)
        assertEquals("STAFF", dto.source)
    }

    @Test(expected = ParseException::class)
    fun missingRequiredFieldThrowsNamedParseException() {
        // "id" is required and absent → ParseException naming the field.
        val json = JSONObject(
            """{"tableId":"T1","status":"RECEIVED","total":1.0,"createdAt":"x"}"""
        )
        OrderMapper.orderDto(json)
    }

    @Test
    fun toEntityMapsKnownStatusToEnum() {
        val json = JSONObject(
            """{"id":"o4","tableId":"T4","status":"SENT_TO_KITCHEN","total":5.0,"createdAt":"x"}"""
        )
        val order = OrderMapper.orderDto(json).toEntity()
        assertEquals(OrderStatus.SENT_TO_KITCHEN, order.status)
    }

    @Test
    fun toEntityMapsUnknownStatusToUnknownNotCrash() {
        val json = JSONObject(
            """{"id":"o5","tableId":"T5","status":"WAT_IS_THIS","total":5.0,"createdAt":"x"}"""
        )
        val order = OrderMapper.orderDto(json).toEntity()
        assertEquals(OrderStatus.UNKNOWN, order.status)
    }
}
