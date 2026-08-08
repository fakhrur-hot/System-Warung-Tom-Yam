package com.razstudio.opsapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for the pure, network-free pieces of the ported provisioning module. `apk/app` had no
 * existing tests for `ProvisionerClient` to carry over (bugfix/design.md task 5.2 notes this), so
 * these are new — scoped to what's testable without a live Wizard endpoint or WorkManager
 * instrumentation: URL derivation and the result/step status helpers.
 */
class ProvisionerClientTest {

    // ─── functionsEndpoint() ───────────────────────────────────────────────────────

    @Test
    fun `functionsEndpoint converts a run URL to its functions sibling`() {
        val result = functionsEndpoint("https://wizard.example.com/api/provision/run")
        assertEquals("https://wizard.example.com/api/provision/functions", result)
    }

    @Test
    fun `functionsEndpoint leaves an already-functions URL unchanged`() {
        val result = functionsEndpoint("https://wizard.example.com/api/provision/functions")
        assertEquals("https://wizard.example.com/api/provision/functions", result)
    }

    @Test
    fun `functionsEndpoint assumes the standard route for a bare origin`() {
        val result = functionsEndpoint("https://wizard.example.com")
        assertEquals("https://wizard.example.com/api/provision/functions", result)
    }

    @Test
    fun `functionsEndpoint strips a trailing slash before deriving the sibling`() {
        val result = functionsEndpoint("https://wizard.example.com/api/provision/run/")
        assertEquals("https://wizard.example.com/api/provision/functions", result)
    }

    @Test
    fun `functionsEndpoint returns null for a blank URL`() {
        assertNull(functionsEndpoint(""))
        assertNull(functionsEndpoint("   "))
    }

    @Test
    fun `functionsEndpoint returns null for a non-https URL`() {
        assertNull(functionsEndpoint("http://wizard.example.com/api/provision/run"))
    }

    // ─── StepResult ─────────────────────────────────────────────────────────────────

    @Test
    fun `StepResult isOk is true only for status ok`() {
        assertTrue(StepResult(step = "supabase", status = "ok").isOk)
        assertFalse(StepResult(step = "supabase", status = "error").isOk)
        assertFalse(StepResult(step = "supabase", status = "pending").isOk)
    }

    @Test
    fun `StepResult isError is true only for status error`() {
        assertTrue(StepResult(step = "cloudflare", status = "error").isError)
        assertFalse(StepResult(step = "cloudflare", status = "ok").isError)
    }

    // ─── ProvisionResult.success ────────────────────────────────────────────────────

    @Test
    fun `ProvisionResult success is false when results are empty`() {
        val result = ProvisionResult(results = emptyList())
        assertFalse(result.success)
    }

    @Test
    fun `ProvisionResult success is true when every step is ok`() {
        val result = ProvisionResult(
            results = listOf(
                StepResult(step = "supabase", status = "ok"),
                StepResult(step = "cloudflare", status = "ok"),
            )
        )
        assertTrue(result.success)
    }

    @Test
    fun `ProvisionResult success is false when any step errored`() {
        val result = ProvisionResult(
            results = listOf(
                StepResult(step = "supabase", status = "ok"),
                StepResult(step = "cloudflare", status = "error", detail = "invalid token"),
            )
        )
        assertFalse(result.success)
    }
}
