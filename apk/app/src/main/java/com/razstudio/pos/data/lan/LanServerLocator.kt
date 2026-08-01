package com.razstudio.pos.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.razstudio.pos.data.AppConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the LAN Server again after its address changes (task 7.3, Requirement 5.5).
 *
 * The café's server is a phone acting as an access point. Its address is not stable: rebooting the
 * tablet, toggling the hotspot, or joining a different router all move it — and a staff phone that
 * paired last week is still holding the old one. Without recovery the only fix is to re-pair every
 * device, and re-pairing means the admin approving each one again during service.
 *
 * ### The credential survives every branch
 *
 * That is the point of Requirement 5.5. The credential was issued to the *device*, not to an
 * address, so moving the server does not invalidate it — only the URL is relearned. Nothing here
 * touches [AppConfigStore]'s stored credential or asks for re-approval.
 *
 * ### Four rungs, cheapest first (task 21.9 — supersedes the mDNS-first ladder in 7.3)
 *
 * 1. **Last known address.** Usually still right — the server moved only if something changed.
 * 2. **The DHCP gateway.** In the topology this café actually runs — Server Device hosting the
 *    hotspot — the gateway **is** the Server. So one cheap, synchronous, permission-free lookup
 *    recovers the single most common failure (hotspot restarted on a different subnet) without
 *    touching multicast at all. This rung is why 21.9 supersedes 7.3: mDNS-first meant paying a
 *    5 s discovery timeout for a case a `getDhcpInfo()` call answers instantly.
 * 3. **mDNS.** [NsdManager] discovery for [SERVICE_TYPE]. Covers the shared-router case, where the
 *    gateway is the router rather than the Server.
 * 4. **Give up and say so.** Reported to the caller so the UI can offer a re-scan. Deliberately not
 *    a silent retry loop: a staff phone quietly failing to find the server looks identical to one
 *    with nothing to send, and the shift discovers it when orders never reach the kitchen.
 */
@Singleton
class LanServerLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfig: AppConfigStore,
) {

    sealed interface Result {
        /** [url] is confirmed reachable and has been persisted as the new last-known address. */
        data class Reachable(val url: String, val viaDiscovery: Boolean) : Result

        /** Nothing answered. The caller should prompt for a re-scan; the credential is untouched. */
        data object NotFound : Result
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Re-establish the Server's address.
     *
     * Call after a connection failure, not before every request — the happy path must stay a plain
     * HTTP call with no discovery overhead.
     */
    suspend fun locate(): Result = withContext(Dispatchers.IO) {
        appConfig.lanServerUrl().takeIf { it.isNotBlank() }?.let { last ->
            if (probe(last)) {
                Log.i(TAG, "Last known address still reachable")
                return@withContext Result.Reachable(last, viaDiscovery = false)
            }
            Log.i(TAG, "Last known address unreachable — trying the DHCP gateway")
        }

        gatewayCandidate()?.let { url ->
            if (probe(url)) {
                appConfig.setLanServerUrl(url)
                Log.i(TAG, "Recovered server address from the DHCP gateway: $url")
                return@withContext Result.Reachable(url, viaDiscovery = true)
            }
            Log.i(TAG, "Gateway is not our server — falling through to mDNS")
        }

        discoverViaMdns()?.let { url ->
            if (probe(url)) {
                appConfig.setLanServerUrl(url)
                Log.i(TAG, "Recovered server address via mDNS: $url")
                return@withContext Result.Reachable(url, viaDiscovery = true)
            }
        }

        Log.w(TAG, "Server not found — a re-scan is needed")
        Result.NotFound
    }

    /**
     * The default gateway as a candidate server address (task 21.9, Requirement 5.5.1).
     *
     * When the Server Device hosts the hotspot, every Client's gateway **is** the Server — so this
     * one lookup recovers a hotspot restart that moved the subnet, which is the failure this ladder
     * exists for. It is synchronous, needs no permission, and costs nothing when it guesses wrong,
     * because [probe] rejects a gateway that is merely a router.
     *
     * `getDhcpInfo` is deprecated and returns 0 on modern Android for some configurations; treated
     * as "no candidate" rather than special-cased, since the mDNS rung below covers it.
     */
    @Suppress("DEPRECATION")
    private fun gatewayCandidate(): String? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return null
        val gateway = runCatching { wifi.dhcpInfo?.gateway ?: 0 }.getOrDefault(0)
        if (gateway == 0) return null

        // DhcpInfo packs IPv4 little-endian on all supported devices.
        val ip = listOf(
            gateway and 0xFF,
            gateway shr 8 and 0xFF,
            gateway shr 16 and 0xFF,
            gateway shr 24 and 0xFF,
        ).joinToString(".")
        return "http://$ip:$LAN_PORT"
    }

    /**
     * Is something answering as our server at [baseUrl]?
     *
     * Probes an **unauthenticated** endpoint on purpose. Using an authenticated one would conflate
     * two different failures — "the server moved" and "this device was revoked" — and the recovery
     * for those is opposite: relearn an address versus stop and re-pair. `devices-status` answers
     * without a credential, so a reply means the server is there whatever this device's standing.
     */
    private fun probe(baseUrl: String): Boolean = runCatching {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/functions/v1/devices-status?deviceId=probe")
            .get()
            .build()
        probeClient.newCall(request).execute().use { response ->
            // Any HTTP reply proves something is listening and routing. 404 for an unknown device id
            // is a perfectly good answer to "are you there?".
            response.code in 200..499
        }
    }.getOrElse { false }

    /**
     * One-shot mDNS lookup, bounded by [DISCOVERY_TIMEOUT_MS].
     *
     * [NsdManager] is callback-based and has no cancellation guarantees, so discovery is always
     * stopped in a `finally` — leaking a discovery listener keeps the Wi-Fi multicast lock alive and
     * drains a phone that is meant to last a shift.
     */
    private suspend fun discoverViaMdns(): String? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val found = CompletableDeferred<String?>()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: $errorCode")
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                found.complete("http://$host:${serviceInfo.port}")
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                found.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains(SERVICE_TYPE_BARE)) {
                    @Suppress("DEPRECATION")
                    runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { found.await() }
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS discovery failed", t)
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }

    private companion object {
        const val TAG = "LanServerLocator"
        const val SERVICE_TYPE = "_warungpos._tcp."
        const val SERVICE_TYPE_BARE = "_warungpos._tcp"
        const val PROBE_TIMEOUT_MS = 2_500L

        /** Must match LanServer.PORT. */
        const val LAN_PORT = 8765
        const val DISCOVERY_TIMEOUT_MS = 5_000L
    }
}
