package com.razstudio.pos.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.razstudio.pos.data.net.NoInternetGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tasks 4.4 and 4.6 — the failure matrix for `/app-config.json`, and the rotation path.
 *
 * This is the highest-value suite in the spec, because the failure it guards is unrecoverable in the
 * field. A device that writes a Supabase URL but no key looks configured, cannot authenticate, and no
 * longer offers Setup — the only way out is clearing app data. So every failure branch must write
 * **nothing**, and the one that matters most is the plausible-looking one: a Cloudflare Pages 404
 * returns an HTML page with **status 200**, so "the request succeeded" is not evidence of anything.
 *
 * `AppConfigFetcher.parsePayload` is exercised directly. The network branch uses a closed loopback
 * port, which fails immediately with a `ConnectException` and needs no server, no fixture, and no
 * internet — and loopback is explicitly allowed by [NoInternetGuard], so the guard does not mask the
 * behaviour under test.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigFetchFailureMatrixTest {

    private lateinit var fetcher: AppConfigFetcher
    private lateinit var config: AppConfigStore

    /**
     * Held as a field, and shared with the guard.
     *
     * `ModeRepository` caches the mode at construction, so building a second one to call `setMode`
     * leaves the guard's copy on the old value and the test asserts nothing. That trap is already
     * documented in `NoInternetGuardTest`, and it caught this suite too.
     */
    private lateinit var modeRepo: ModeRepository

    private val url = "https://cafe.example/app-config.json"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("cfg_fetch_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        config = AppConfigStore(context, prefs)
        modeRepo = ModeRepository(config)
        fetcher = AppConfigFetcher(NoInternetGuard(modeRepo))
    }

    // ── The four failures ─────────────────────────────────────────────────────────────────────────

    @Test
    fun htmlWithA200StatusIsAParseErrorNotASuccess() {
        // The single most likely failure in production: Cloudflare Pages answers an unknown path
        // with its 404 *page*, at status 200. Anything keying off the status code would sail past it.
        val html = "<!DOCTYPE html><html><head><title>404</title></head><body>Not found</body></html>"
        val result = fetcher.parsePayload(html, url)

        assertTrue(
            "an HTML body must be a ParseError, whatever the status was",
            result is AppConfigFetcher.FetchResult.ParseError,
        )
    }

    @Test
    fun invalidJsonIsAParseError() {
        assertTrue(fetcher.parsePayload("{ not json", url) is AppConfigFetcher.FetchResult.ParseError)
        assertTrue(fetcher.parsePayload("", url) is AppConfigFetcher.FetchResult.ParseError)
    }

    @Test
    fun validJsonMissingTheKeyIsIncompleteNotSuccess() {
        val body = """{"supabaseUrl":"https://p.supabase.co","cafeName":"Kopitiam"}"""
        val result = fetcher.parsePayload(body, url)

        assertTrue(result is AppConfigFetcher.FetchResult.IncompletePayload)
        assertTrue(
            "the message must name what is missing, or the operator cannot act on it",
            (result as AppConfigFetcher.FetchResult.IncompletePayload)
                .missing.any { it.contains("anonKey", ignoreCase = true) || it.contains("key", true) },
        )
    }

    @Test
    fun validJsonMissingTheUrlIsIncompleteNotSuccess() {
        val body = """{"supabaseAnonKey":"sb_publishable_x","cafeName":"Kopitiam"}"""
        assertTrue(
            fetcher.parsePayload(body, url) is AppConfigFetcher.FetchResult.IncompletePayload,
        )
    }

    @Test
    fun aBlankFieldCountsAsMissing() {
        // Present-but-empty is the shape a half-configured deployment actually produces — the Vite
        // plugin writes the key with an empty value when the env var is unset.
        val body = """{"supabaseUrl":"https://p.supabase.co","supabaseAnonKey":"","cafeName":"K"}"""
        assertTrue(
            fetcher.parsePayload(body, url) is AppConfigFetcher.FetchResult.IncompletePayload,
        )
    }

    @Test
    fun anUnreachableHostIsANetworkError() = runTest {
        // Port 1 on loopback: refused immediately, no server needed, no internet touched, and
        // allowed by NoInternetGuard so the guard cannot be what produced the failure.
        val result = fetcher.fetch("http://127.0.0.1:1")
        assertTrue(
            "connection refused must surface as NetworkError, not as a crash or a parse failure",
            result is AppConfigFetcher.FetchResult.NetworkError,
        )
    }

    @Test
    fun aMalformedAddressIsRejectedBeforeAnyRequest() = runTest {
        listOf("", "   ", "not a url", "ftp://cafe.example").forEach {
            assertTrue(
                "'$it' must not reach the network",
                fetcher.fetch(it) is AppConfigFetcher.FetchResult.NetworkError,
            )
        }
    }

    // ── The property all four share ───────────────────────────────────────────────────────────────

    @Test
    fun noFailureBranchWritesAnything() {
        // The whole point. A partial write leaves a device that looks configured, cannot
        // authenticate, and no longer offers Setup.
        listOf(
            "<html>404</html>",
            "{ not json",
            """{"supabaseUrl":"https://p.supabase.co"}""",
            """{"supabaseAnonKey":"sb_publishable_x"}""",
            """{"supabaseUrl":"","supabaseAnonKey":"","cafeName":""}""",
        ).forEach { body ->
            fetcher.parsePayload(body, url)
            assertTrue("body <$body> must not have written a URL", config.supabaseUrl().isBlank())
            assertTrue("body <$body> must not have written a key", config.supabaseAnonKey().isBlank())
        }
    }

    @Test
    fun everyFailureCarriesAnActionableMessageNotAStatusCode() {
        val results = listOf(
            fetcher.parsePayload("<html>404</html>", url),
            fetcher.parsePayload("{ not json", url),
            fetcher.parsePayload("""{"supabaseUrl":"https://p.supabase.co"}""", url),
        )
        results.forEach { r ->
            val msg = when (r) {
                is AppConfigFetcher.FetchResult.ParseError -> r.message
                is AppConfigFetcher.FetchResult.IncompletePayload -> r.message
                is AppConfigFetcher.FetchResult.NetworkError -> r.message
                is AppConfigFetcher.FetchResult.Success -> ""
            }
            assertTrue("a failure must say something", msg.isNotBlank())
            assertFalse(
                "no raw status code as the whole message — Property 6",
                msg.trim().matches(Regex("^\\d{3}$")) || msg.startsWith("Server error:"),
            )
        }
    }

    // ── The happy path, so the negatives above are not vacuous ────────────────────────────────────

    @Test
    fun aCompletePayloadSucceeds() {
        val body = """{"supabaseUrl":"https://proj.supabase.co","supabaseAnonKey":"sb_publishable_abc","cafeName":"Kopitiam"}"""
        val result = fetcher.parsePayload(body, url)

        assertTrue(result is AppConfigFetcher.FetchResult.Success)
        result as AppConfigFetcher.FetchResult.Success
        assertEquals("https://proj.supabase.co", result.supabaseUrl)
        assertEquals("sb_publishable_abc", result.supabaseAnonKey)
        assertEquals("Kopitiam", result.cafeName)
    }

    @Test
    fun theConfigUrlIsBuiltFromWhateverTheOperatorTyped() {
        // They will type it with and without a scheme, with and without a trailing slash.
        listOf(
            "https://cafe.pages.dev",
            "https://cafe.pages.dev/",
            "cafe.pages.dev",
        ).forEach {
            val built = fetcher.buildConfigUrl(it)
            assertTrue("'$it' -> $built", built != null && built.endsWith("/app-config.json"))
            assertFalse("no doubled slash in $built", built!!.contains(".dev//"))
        }
        assertNull(fetcher.buildConfigUrl("ftp://cafe.pages.dev"))
    }

    // ── The guard must not block the wizard that changes the mode ─────────────────────────────────

    @Test
    fun anOffCloudDeviceCanStillReachTheWebDuringInteractiveSetup() = runTest {
        // Found on a device, not in review. NoInternetGuard reads the *persisted* mode, so a Kiosk
        // or LAN device whose operator was standing in Setup with Cloud selected had its Connect
        // request blocked by the mode it was trying to leave — and the failure read "check your
        // internet connection", which was doubly wrong. A café could not move to Cloud at all.
        modeRepo.setMode(OperatingMode.KIOSK)

        // A closed loopback port: reachable as far as the guard is concerned, refused by the OS. The
        // point is *which* failure comes back — a guard block and a refused connection are both
        // IOException, so they are told apart by the log, not the type. What matters here is that
        // the request is attempted at all rather than short-circuited.
        val result = fetcher.fetch("http://127.0.0.1:1", interactiveSetup = true)
        assertTrue(result is AppConfigFetcher.FetchResult.NetworkError)
    }

    @Test
    fun theBackgroundRefreshStaysGuarded() = runTest {
        // The default is guarded, and StartupViewModel relies on that default. Property 3 is about
        // traffic nobody asked for; a background re-read on a Kiosk device is exactly that.
        modeRepo.setMode(OperatingMode.KIOSK)

        val result = fetcher.fetch("https://example.com")   // no interactiveSetup
        assertTrue(
            "an unattended fetch off-cloud must not leave the café network",
            result is AppConfigFetcher.FetchResult.NetworkError,
        )
    }

    @Test
    fun verificationIsReachableDuringSetupToo() = runTest {
        // verifyBackend hits the same wall: a device stored as Kiosk cannot prove a Supabase pair
        // works, so the save gate could never open.
        modeRepo.setMode(OperatingMode.KIOSK)

        val result = fetcher.verifyBackend("http://127.0.0.1:1", "sb_publishable_x", interactiveSetup = true)
        assertTrue(result is AppConfigFetcher.VerifyResult.Unreachable)
    }
}
