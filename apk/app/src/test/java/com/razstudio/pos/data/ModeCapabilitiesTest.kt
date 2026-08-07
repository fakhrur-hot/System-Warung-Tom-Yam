package com.razstudio.pos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.4 — Property 1: mode is decided in one place and read everywhere
 * (Validates Requirements 1.3, 7.5).
 *
 * These are deliberately written over **reflection** rather than as a field-by-field checklist. A
 * hand-written list of eight assertions would still pass the day someone adds a ninth capability, so
 * the new field would escape the invariant entirely — exactly the drift this property exists to
 * prevent. Enumerating the fields means a new one is covered the moment it is declared, and
 * [allCapabilityFieldsAreBooleans] fails loudly if the type stops being a flat set of flags.
 *
 * Java reflection, not `kotlin.reflect.full`, so this needs no `kotlin-reflect` dependency on the test
 * classpath. No Robolectric either: pure Kotlin with no Android dependency.
 */
class ModeCapabilitiesTest {

    /**
     * The class's own declared fields, excluding anything the compiler added.
     *
     * The `$` filter is load-bearing and was discovered the hard way: the Compose compiler plugin adds
     * a `static int $stable` field to classes it processes, which is NOT flagged `isSynthetic`, so
     * [allCapabilityFieldsAreBooleans] failed with `found [${'$'}stable: int]` on the first run. Kotlin
     * never permits `$` in a source-declared property name, so excluding it cannot hide a real field.
     */
    private fun booleanFields() =
        ModeCapabilities::class.java.declaredFields
            .filterNot { it.isSynthetic || it.name.contains('$') }
            .onEach { it.isAccessible = true }

    private fun flagsOf(caps: ModeCapabilities): Map<String, Boolean> =
        booleanFields()
            .filter { it.type == java.lang.Boolean.TYPE }
            .associate { it.name to (it.get(caps) as Boolean) }

    /** Guards the reflection above: if a non-Boolean field appears, the other tests are lying. */
    @Test
    fun allCapabilityFieldsAreBooleans() {
        val nonBoolean = booleanFields()
            .filterNot { it.type == java.lang.Boolean.TYPE }
            .map { "${it.name}: ${it.type.simpleName}" }
        assertTrue(
            "ModeCapabilities must stay a flat set of Boolean flags; found $nonBoolean",
            nonBoolean.isEmpty(),
        )
    }

    /**
     * CLOUD is the mode with no *technical* limits, so every capability it does not enable has to be
     * a deliberate product decision — listed here by name so switching one off silently is not
     * possible. [gatewayPaymentsEnabled] is off product-wide because every acquirer evaluated for it
     * charges a standing subscription, which is incompatible with a zero-commitment POS; the
     * implementation stays wired so the flag alone can bring it back.
     */
    @Test
    fun cloudEnablesEveryCapabilityExceptThoseDisabledOnPurpose() {
        val deliberatelyOff = setOf("gatewayPaymentsEnabled")
        val off = flagsOf(OperatingMode.CLOUD.toCapabilities()).filterValues { !it }.keys
        assertEquals(
            "CLOUD disables exactly the capabilities retired on purpose",
            deliberatelyOff, off,
        )
    }

    @Test
    fun kioskEnablesNothing() {
        val on = flagsOf(OperatingMode.KIOSK.toCapabilities()).filterValues { it }.keys
        assertTrue(
            "KIOSK is one device, no peers, no tables, no internet — these were true: $on",
            on.isEmpty(),
        )
    }

    @Test
    fun lanEnablesOnlyTablesAndStaffDevices() {
        val on = flagsOf(OperatingMode.LAN.toCapabilities()).filterValues { it }.keys
        assertEquals(
            "LAN has peers and tables but no website, cloud storage, cloud realtime, or secondary admin",
            setOf("tables", "staffDevices"), on,
        )
    }

    /**
     * The Payment QR is available in all three modes (Requirement 14.7), so it must NOT be expressed as
     * a capability — a flag would invite switching it off for LAN/Kiosk alongside the genuinely
     * cloud-shaped image features. Its visibility depends only on whether an image is configured.
     */
    @Test
    fun paymentQrIsNotACapabilityFlag() {
        val offenders = booleanFields()
            .map { it.name.lowercase() }
            .filter { "paymentqr" in it || ("payment" in it && "qr" in it) }
        assertTrue(
            "Payment QR is mode-independent (Requirement 14.7); it must not become a capability: $offenders",
            offenders.isEmpty(),
        )
    }

    /** Every mode must be fully decided; a new enum value with no branch would throw here. */
    @Test
    fun everyOperatingModeResolvesAllCapabilities() {
        val expected = flagsOf(OperatingMode.CLOUD.toCapabilities()).size
        // Was 8 before gatewayPaymentsEnabled (task 6.4, PG-REQ-3) added a ninth.
        assertEquals("expected the nine documented capability flags", 9, expected)
        OperatingMode.entries.forEach { mode ->
            assertEquals(
                "every capability must be decided for $mode",
                expected, flagsOf(mode.toCapabilities()).size,
            )
        }
    }
}
