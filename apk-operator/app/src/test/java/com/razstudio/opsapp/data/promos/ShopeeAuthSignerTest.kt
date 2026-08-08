package com.razstudio.opsapp.data.promos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `apk/app`'s `com.razstudio.pos.data.promos.ShopeeAuthSignerTest` (post-bugfix
 * version). The expected hex digests were computed independently of [ShopeeAuthSigner] (via
 * `sha256sum`/`openssl dgst -sha256`, cross-checked against each other) — a test that only checked
 * the signer against its own output would pass even if the algorithm itself were wrong, which is
 * exactly what happened in `apk/app` before that bugfix.
 */
class ShopeeAuthSignerTest {

    private val signer = ShopeeAuthSigner()

    @Test
    fun `sign produces a 64-char lowercase hex string`() {
        val result = signer.sign("app123", 1700000000L, "hello", "secret456")
        assertEquals(64, result.length)
        assertTrue(result.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sign matches an independently computed SHA-256 digest for a simple base string`() {
        val result = signer.sign(appId = "a", timestamp = 1L, payload = "b", secret = "c")
        assertEquals(
            "191d417b52a417992ef9bea91d489aa22791f346d4f7fb58c018b09792551ca9",
            result,
        )
    }

    @Test
    fun `sign matches an independently computed SHA-256 digest for a realistic payload`() {
        val result = signer.sign(
            appId = "app123",
            timestamp = 1700000000L,
            payload = """{"query":"test"}""",
            secret = "secret456",
        )
        assertEquals(
            "a93c78ce67d88754baf3f2fea3f37cd8b3602735dae5ea8f36f328eb2a5100e3",
            result,
        )
    }

    @Test
    fun `sign is deterministic`() {
        val a = signer.sign("appId", 123L, "payload123", "mySecret")
        val b = signer.sign("appId", 123L, "payload123", "mySecret")
        assertEquals(a, b)
    }

    @Test
    fun `different secrets produce different signatures`() {
        val a = signer.sign("appId", 1L, "same-payload", "secret1")
        val b = signer.sign("appId", 1L, "same-payload", "secret2")
        assertNotEquals(a, b)
    }

    @Test
    fun `different payloads produce different signatures`() {
        val a = signer.sign("appId", 1L, "payload-a", "same-secret")
        val b = signer.sign("appId", 1L, "payload-b", "same-secret")
        assertNotEquals(a, b)
    }

    @Test
    fun `different timestamps produce different signatures`() {
        val a = signer.sign("appId", 1L, "payload", "secret")
        val b = signer.sign("appId", 2L, "payload", "secret")
        assertNotEquals(a, b)
    }

    @Test
    fun `buildHeaders contains exactly Authorization and Content-Type`() {
        val headers = signer.buildHeaders(
            appId = "app123",
            secret = "secret456",
            payload = """{"query":"test"}""",
            timestamp = 1700000000L,
        )
        assertEquals(setOf("Authorization", "Content-Type"), headers.keys)
    }

    @Test
    fun `buildHeaders formats Authorization as SHA256 Credential,Timestamp,Signature`() {
        val appId = "app123"
        val secret = "secret456"
        val payload = """{"query":"test"}"""
        val timestamp = 1700000000L

        val headers = signer.buildHeaders(appId, secret, payload, timestamp)
        val expectedSignature = signer.sign(appId, timestamp, payload, secret)

        assertEquals(
            "SHA256 Credential=$appId,Timestamp=$timestamp,Signature=$expectedSignature",
            headers["Authorization"],
        )
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `companion object has correct affiliate ID`() {
        assertEquals("12352980181", ShopeeAuthSigner.AFFILIATE_ID)
    }

    @Test
    fun `sign handles empty payload`() {
        val result = signer.sign("appId", 1L, "", "secret")
        assertEquals(64, result.length)
        assertTrue(result.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
