package com.razstudio.pos.data.net

import android.util.Log
import com.razstudio.pos.data.ModeRepository
import com.razstudio.pos.data.OperatingMode
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops any request leaving the café's network when the café is off-cloud
 * (task 18.1 — Requirements 11.1, 11.2, 11.2.1; **Property 3: no internet traffic originates in LAN
 * or Kiosk Mode**).
 *
 * ### Why a `Dns` and not only an `Interceptor`
 *
 * An interceptor can read the request's host, but a *hostname* tells you nothing about where it goes
 * — `menu-images.example.com` could resolve anywhere, and `pos.local` legitimately resolves to a
 * device on the café LAN. The only honest test is the resolved address. So this wraps [Dns.SYSTEM],
 * resolves normally, and then refuses to hand back any address that is not on a local network.
 *
 * Failing at resolution also gives the right exception type for free: [UnknownHostException] is an
 * `IOException`, which every caller here already handles as a network failure. A guard that threw
 * something exotic would turn "blocked" into a crash.
 *
 * ### What counts as local
 *
 * Loopback, RFC 1918 site-local (`10/8`, `172.16/12`, `192.168/16`), and link-local (`169.254/16`,
 * plus IPv6 `fe80::/10`). Everything else is the internet.
 *
 * ### Cloud Mode is untouched
 *
 * [OperatingMode.CLOUD] short-circuits before any check, so an existing café's behaviour is
 * byte-identical. This guard only has opinions off-cloud.
 *
 * ### It must be on *every* client
 *
 * The point of the requirement is that one unguarded client defeats the whole property. This app has
 * five OkHttp clients plus Coil's image loader, and the easiest one to forget is the one that does
 * not look like an API call: a leftover `https://…/menu-images/x.jpg` on a menu row, fetched by Coil,
 * is a real request to a real server carrying the café's IP. [applyTo] exists so wiring one up is a
 * single line, and [PROTECTED_CLIENTS] documents where they are.
 */
@Singleton
class NoInternetGuard @Inject constructor(
    private val modeRepository: ModeRepository,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)

        // Cloud is the untouched path — no inspection, no behaviour change.
        if (modeRepository.currentMode() == OperatingMode.CLOUD) return resolved

        val offending = resolved.filterNot { it.isLocal() }
        if (offending.isEmpty()) return resolved

        val mode = modeRepository.currentMode()
        Log.e(
            TAG,
            "BLOCKED: $hostname resolved to ${offending.joinToString { it.hostAddress ?: "?" }} " +
                "which is off-LAN, and this device is in $mode",
        )
        throw UnknownHostException(
            "Blocked by NoInternetGuard: $hostname is not on the local network, and this café is " +
                "running in $mode. See Property 3.",
        )
    }

    /**
     * True when [this] is an address on a local network rather than the internet.
     *
     * `isAnyLocalAddress` is included because `0.0.0.0` is what a server binds to, and a probe
     * against it must not be mistaken for an internet call.
     */
    private fun InetAddress.isLocal(): Boolean =
        isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress

    companion object {
        private const val TAG = "NoInternetGuard"

        /**
         * Every HTTP client in the app, so a new one is an obvious omission rather than an invisible
         * hole. Kept as documentation because there is no way to enforce it at compile time — the
         * whole failure mode of this task is a client somebody forgot.
         *
         *  1. `ApiClient` — the REST/gateway client.
         *  2. `RealtimeService` — the admin device's Supabase WebSocket client.
         *  3. `OrderingForegroundService` — the staff device's separate WebSocket client.
         *  4. `PaymentQrResolver` — downloads the payment QR image. **Not listed in task 18.1**, and
         *     the most dangerous omission of the five: it fetches over HTTPS from cloud storage and
         *     does not look like an API call.
         *  5. `LanServerLocator` — the LAN reachability probe. Always targets a local address, so the
         *     guard is a no-op for it, but it is wired anyway so the rule has no exceptions.
         *  6. Coil's `ImageLoader` — menu photos. A leftover `https://` URL on a menu row is a real
         *     request to a real server, carrying the café's IP, triggered by simply opening the menu.
         */
        val PROTECTED_CLIENTS = listOf(
            "ApiClient",
            "RealtimeService",
            "OrderingForegroundService",
            "PaymentQrResolver",
            "LanServerLocator",
            "Coil ImageLoader",
        )
    }
}

/** Wire the guard into a client. One line, so there is no excuse for a client without it. */
fun OkHttpClient.Builder.guardedAgainstInternet(guard: NoInternetGuard): OkHttpClient.Builder =
    dns(guard)
