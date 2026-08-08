package com.razstudio.opsapp.data.api

/**
 * DTOs for the five Edge Functions the Operator APK is allowed to call.
 * Mirrors the response shapes from `apk/app`'s `ApiClient` but keeps only what the
 * operator profile shell needs — no order, payment, or device-management shapes.
 */

// ── Menu ────────────────────────────────────────────────────────────────────────

data class MenuItemDto(
    val id: String,
    val category: String,
    val extraCategories: String = "",
    val code: String = "",
    val price: Double,
    val marketPrice: Boolean = false,
    val available: Boolean,
    val askMeDaily: Boolean = false,
    val imageUrl: String = "",
    val hasVariablePrice: Boolean = false,
    val variablePriceDailyPrompt: Boolean = false,
    val priceOption1: Double? = null,
    val priceOption2: Double? = null,
    val priceOption3: Double? = null,
    val nameEn: String,
    val nameBm: String = "",
    val nameZh: String = "",
    val nameTa: String = "",
    val nameTh: String = "",
    val doNotTranslate: Boolean = false,
)

data class MenuCategoryDto(
    val name: String,
    val sortOrder: Int,
    val nameI18n: Map<String, String> = emptyMap(),
)

data class MenuResponse(
    val configured: Boolean,
    val items: List<MenuItemDto>,
    val categories: List<MenuCategoryDto> = emptyList(),
)

// ── Tables ──────────────────────────────────────────────────────────────────────

data class TableDto(
    val id: String,
    val displayName: String,
    val qrToken: String? = null,
)

// ── Café Location ───────────────────────────────────────────────────────────────

data class CafeLocationDto(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 100,
)

// ── Branding ────────────────────────────────────────────────────────────────────

data class BrandingDto(
    val cafeName: String,
    val logoUrl: String = "",
    val logoBase64: String? = null,       // set only when uploading a new logo
    val paymentQrHash: String? = null,
    val paymentQrUrl: String? = null,
)
