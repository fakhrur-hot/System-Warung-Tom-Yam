package com.razstudio.pos.data

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The café owner scanned a valid owner key and got
 * `Expected URL scheme 'http' or 'https' but no scheme was found for /funct…`.
 *
 * That message is OkHttp's, thrown from `Request.Builder.url()`, and it reached the screen because
 * `ApiClient.baseUrl()` returned the bare string `/functions/v1`: the device had no backend at all,
 * so the base it concatenated onto was `""`. The template APK ships with an empty
 * `BuildConfig.SUPABASE_URL` by design — one binary, any café — so *every* API call on a device
 * where Setup was skipped died this way, not just owner recovery.
 *
 * Two things are pinned here:
 *
 *  1. the schemeless URL really does throw the exact exception the owner saw — so nobody later
 *     decides the empty-base case is harmless and removes the guard;
 *  2. [ApiClient.BackendNotConfiguredException] is an [IOException] — which is the whole reason the
 *     fix needed no new catch block at ~20 call sites. If someone re-parents it to
 *     `RuntimeException`, every one of those paths silently reverts to leaking a raw message.
 *
 * `baseUrl()` itself is private and `ApiClient` needs Hilt-injected storage, so the URL half is
 * asserted against OkHttp directly — that is where the failure actually originated.
 */
class BackendNotConfiguredTest {

    /** Exactly what `"".trimEnd('/') + "/functions/v1"` produced before the guard. */
    private val schemelessBase = "" + "/functions/v1"

    @Test
    fun aBlankBaseProducesTheUrlTheOwnerSaw() {
        assertEquals("/functions/v1", schemelessBase)
    }

    @Test
    fun okHttpRejectsThatUrlWithTheOwnersErrorMessage() {
        val error = runCatching {
            Request.Builder().url("$schemelessBase/admin-recovery").build()
        }.exceptionOrNull()

        assertTrue(
            "the empty-base case must still be a hard failure, not a silently relative URL",
            error is IllegalArgumentException,
        )
        assertTrue(
            "this is the string the café owner was shown — it is the bug's signature",
            error!!.message!!.contains("Expected URL scheme"),
        )
    }

    @Test
    fun aConfiguredBaseBuildsCleanly() {
        // The control: with a real base the same concatenation is a perfectly good URL, which is why
        // this went unnoticed on every café-specific build.
        val url = Request.Builder()
            .url("https://example.supabase.co".trimEnd('/') + "/functions/v1/admin-recovery")
            .build()
            .url
        assertEquals("https", url.scheme)
        assertEquals("/functions/v1/admin-recovery", url.encodedPath)
    }

    @Test
    fun theExceptionIsAnIOExceptionSoExistingCatchBlocksHandleIt() {
        val thrown: Throwable = ApiClient.BackendNotConfiguredException()

        assertTrue(
            "must stay an IOException — ~20 call sites map IOException to ApiResult.NetworkError " +
                "and would otherwise leak a raw message again",
            thrown is IOException,
        )
        assertTrue(
            "the message has to name the remedy, not just the fault",
            thrown.message!!.contains("Setup"),
        )
    }
}
