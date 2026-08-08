package com.razstudio.opsapp.data.api

import com.razstudio.opsapp.data.local.ConnectedCafeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Tests for [OperatorApiClient] — validates that the class exposes exactly the allowed
 * endpoint methods (menu, menu-image, tables, cafe-location, branding) and deliberately
 * excludes all other domains (orders, payments, devices, reports, settings, attendance,
 * aggregates, sessions, gateway, admin-recovery, admin-handshake).
 *
 * This is a compile-time-ish property: if the interface doesn't declare a method, the
 * shell UI can't call it. These reflection tests make that guarantee explicit and CI-visible.
 */
class OperatorApiClientTest {

    private val testCafe = ConnectedCafeEntity(
        id = "device-123",
        cafeName = "Test Cafe",
        cafeSlug = "test-cafe",
        supabaseUrl = "https://test.supabase.co",
        supabaseAnonKey = "anon-key-123",
        sessionToken = "session-token-456",
        connectedAt = "2024-01-01T00:00:00Z",
        lastConnectedAt = "2024-01-01T00:00:00Z",
    )

    private val client = OperatorApiClient(testCafe)

    // ── Allowed methods are present ─────────────────────────────────────────────

    private val publicMethods: Set<String> by lazy {
        client::class.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .toSet()
    }

    @Test
    fun `exposes getMenu`() {
        assertTrue("getMenu should be exposed", "getMenu" in publicMethods)
    }

    @Test
    fun `exposes upsertMenuItem`() {
        assertTrue("upsertMenuItem should be exposed", "upsertMenuItem" in publicMethods)
    }

    @Test
    fun `exposes deleteMenuItem`() {
        assertTrue("deleteMenuItem should be exposed", "deleteMenuItem" in publicMethods)
    }

    @Test
    fun `exposes uploadMenuImage`() {
        assertTrue("uploadMenuImage should be exposed", "uploadMenuImage" in publicMethods)
    }

    @Test
    fun `exposes deleteMenuImage`() {
        assertTrue("deleteMenuImage should be exposed", "deleteMenuImage" in publicMethods)
    }

    @Test
    fun `exposes getTables`() {
        assertTrue("getTables should be exposed", "getTables" in publicMethods)
    }

    @Test
    fun `exposes upsertTable`() {
        assertTrue("upsertTable should be exposed", "upsertTable" in publicMethods)
    }

    @Test
    fun `exposes deleteTable`() {
        assertTrue("deleteTable should be exposed", "deleteTable" in publicMethods)
    }

    @Test
    fun `exposes getCafeLocation`() {
        assertTrue("getCafeLocation should be exposed", "getCafeLocation" in publicMethods)
    }

    @Test
    fun `exposes updateCafeLocation`() {
        assertTrue("updateCafeLocation should be exposed", "updateCafeLocation" in publicMethods)
    }

    @Test
    fun `exposes getBranding`() {
        assertTrue("getBranding should be exposed", "getBranding" in publicMethods)
    }

    @Test
    fun `exposes updateBranding`() {
        assertTrue("updateBranding should be exposed", "updateBranding" in publicMethods)
    }

    // ── Excluded domains have no methods ────────────────────────────────────────

    @Test
    fun `no orders methods`() {
        val orderMethods = publicMethods.filter { it.contains("order", ignoreCase = true) }
        assertTrue("Should have no order methods but found: $orderMethods", orderMethods.isEmpty())
    }

    @Test
    fun `no payment methods`() {
        val paymentMethods = publicMethods.filter { it.contains("payment", ignoreCase = true) }
        assertTrue("Should have no payment methods but found: $paymentMethods", paymentMethods.isEmpty())
    }

    @Test
    fun `no devices methods`() {
        val deviceMethods = publicMethods.filter { it.contains("device", ignoreCase = true) }
        assertTrue("Should have no device methods but found: $deviceMethods", deviceMethods.isEmpty())
    }

    @Test
    fun `no reports methods`() {
        val reportMethods = publicMethods.filter { it.contains("report", ignoreCase = true) }
        assertTrue("Should have no report methods but found: $reportMethods", reportMethods.isEmpty())
    }

    @Test
    fun `no settings methods`() {
        val settingsMethods = publicMethods.filter { it.contains("setting", ignoreCase = true) }
        assertTrue("Should have no settings methods but found: $settingsMethods", settingsMethods.isEmpty())
    }

    @Test
    fun `no attendance methods`() {
        val attendanceMethods = publicMethods.filter { it.contains("attendance", ignoreCase = true) }
        assertTrue("Should have no attendance methods but found: $attendanceMethods", attendanceMethods.isEmpty())
    }

    @Test
    fun `no aggregate methods`() {
        val aggMethods = publicMethods.filter { it.contains("aggregate", ignoreCase = true) }
        assertTrue("Should have no aggregate methods but found: $aggMethods", aggMethods.isEmpty())
    }

    @Test
    fun `no session methods`() {
        val sessionMethods = publicMethods.filter { it.contains("session", ignoreCase = true) }
        assertTrue("Should have no session methods but found: $sessionMethods", sessionMethods.isEmpty())
    }

    @Test
    fun `no gateway methods`() {
        val gatewayMethods = publicMethods.filter { it.contains("gateway", ignoreCase = true) }
        assertTrue("Should have no gateway methods but found: $gatewayMethods", gatewayMethods.isEmpty())
    }

    @Test
    fun `no admin-recovery methods`() {
        val recoveryMethods = publicMethods.filter { it.contains("recovery", ignoreCase = true) }
        assertTrue("Should have no recovery methods but found: $recoveryMethods", recoveryMethods.isEmpty())
    }

    // ── Base URL derivation ─────────────────────────────────────────────────────

    @Test
    fun `exactly 12 public methods exposed`() {
        // getMenu, upsertMenuItem, deleteMenuItem, uploadMenuImage, deleteMenuImage,
        // getTables, upsertTable, deleteTable, getCafeLocation, updateCafeLocation,
        // getBranding, updateBranding
        assertEquals(
            "Should expose exactly 12 public methods (menu/tables/location/branding CRUD)",
            12,
            publicMethods.size
        )
    }
}
