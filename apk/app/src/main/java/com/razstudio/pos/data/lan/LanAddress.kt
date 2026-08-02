package com.razstudio.pos.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where this Server Device can be reached on the café's own network
 * (tasks 21.2, 21.3 — Requirements 4.3.2, 4.3.3, 4.3.4).
 *
 * A Client Device pairs by scanning a QR that carries an address. If that address is wrong, the
 * failure surfaces minutes later as "the staff phone can't connect" with nothing pointing at the
 * cause — so this resolves the address explicitly, and says so plainly when it cannot.
 *
 * ### Why two lookups
 *
 * [resolve] tries [ConnectivityManager] first: it reports the addresses Android has actually
 * assigned to a connected network, which is the authoritative answer when the device has *joined*
 * someone's Wi-Fi. But when the phone **is** the access point, its AP interface (`ap0`, `wlan1`,
 * `swlan0` depending on OEM) is not surfaced as a connected `Network` at all — see
 * [LanNetworkBinder]. Only the raw [NetworkInterface] list shows it, so that is the fallback rather
 * than the primary.
 *
 * ### Mobile data is deliberately not a candidate
 *
 * A cellular address is routable from the internet, not from the café's Wi-Fi, and a staff phone on
 * the hotspot cannot reach it. Interfaces are filtered to Wi-Fi/AP/Ethernet-shaped ones, and the
 * result is checked to be a private (RFC 1918) address — a public IP in a pairing QR would be both
 * unreachable and a small information leak.
 */
@Singleton
class LanAddress @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The outcome of looking for a usable address. */
    sealed interface Result {
        /** A private IPv4 address other devices on this network can reach. */
        data class Found(val ip: String, val interfaceName: String) : Result

        /**
         * No usable interface. [reason] is written for the café owner, not for a log — it is shown
         * on screen where the pairing QR would otherwise be (task 21.3).
         */
        data class Unavailable(val reason: String) : Result
    }

    /** Convenience for callers that only need the address. */
    fun currentIpOrNull(): String? = (resolve() as? Result.Found)?.ip

    /**
     * Find this device's address on the local network.
     *
     * Never throws: every lookup here can fail on some OEM build, and a pairing screen that crashes
     * is worse than one that explains itself.
     */
    fun resolve(): Result {
        fromConnectivityManager()?.let { return it }
        fromNetworkInterfaces()?.let { return it }

        return Result.Unavailable(
            "This device is not on a Wi-Fi network. Turn on the hotspot, or join the café's " +
                "Wi-Fi, then try again."
        )
    }

    /**
     * The address of a Wi-Fi (or Ethernet) network Android reports as connected.
     *
     * Scans every connected network rather than reading `activeNetwork`, for the same reason
     * [LanNetworkBinder] does: with mobile data on, the active network is cellular even while the
     * device sits on the café Wi-Fi, so the active-network shortcut returns the wrong answer exactly
     * when the café has a data SIM in the tablet.
     */
    private fun fromConnectivityManager(): Result.Found? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        for (network in networks) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            val usable = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!usable) continue

            val props = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
            val addr = props.linkAddresses.firstOrNull { it.isUsablePrivateV4() } ?: continue
            return Result.Found(
                ip = addr.address.hostAddress.orEmpty(),
                interfaceName = props.interfaceName ?: "wifi",
            )
        }
        return null
    }

    /**
     * The AP interface, for when this device is the hotspot.
     *
     * Android does not expose a tethering AP as a connected `Network`, so [fromConnectivityManager]
     * finds nothing in the topology the café is most likely to run — the tablet as the access point
     * with no router at all. The interface list still shows it.
     *
     * Names are matched by prefix because they are OEM-specific (`ap0`, `wlan1`, `swlan0`,
     * `softap0`). `rmnet*` (cellular) and `lo` are excluded rather than matched, so an unfamiliar
     * OEM name still has a chance of being found.
     */
    private fun fromNetworkInterfaces(): Result.Found? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrElse {
                Log.w(TAG, "NetworkInterface enumeration failed", it)
                null
            }
            .orEmpty()

        val candidates = interfaces.filter { nif ->
            runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false) &&
                EXCLUDED_PREFIXES.none { nif.name.startsWith(it) }
        }

        // Prefer an AP interface: if the device is both hotspotting and joined to a Wi-Fi, the
        // address staff phones can reach is the one on the AP they are attached to.
        val ordered = candidates.sortedByDescending { nif ->
            AP_PREFIXES.count { nif.name.startsWith(it) }
        }

        for (nif in ordered) {
            val addr = runCatching { nif.inetAddresses?.toList() }.getOrNull().orEmpty()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?: continue
            return Result.Found(ip = addr.hostAddress.orEmpty(), interfaceName = nif.name)
        }
        return null
    }

    /**
     * A link address usable for LAN pairing: IPv4, not loopback, and RFC 1918 private.
     *
     * `isSiteLocalAddress` is the private-range test (10/8, 172.16/12, 192.168/16). Requiring it
     * keeps a cellular or otherwise public address out of a pairing QR, where it would be
     * unreachable from the café Wi-Fi and would needlessly publish the device's public address.
     */
    private fun LinkAddress.isUsablePrivateV4(): Boolean {
        val a = address
        return a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress
    }

    private companion object {
        const val TAG = "LanAddress"

        /** Cellular and loopback are never the café's network. */
        // `usb` is excluded after seeing a Sunmi D3 Mini carry a permanently-up `usb0` at
        // 172.25.241.171 — a site-local RFC 1918 address, so it passes every other test here and
        // can be chosen ahead of the interface staff phones are actually on. A USB/RNDIS address is
        // never reachable from a phone on the café Wi-Fi, so it can never be the pairing address.
        val EXCLUDED_PREFIXES = listOf("rmnet", "lo", "dummy", "p2p", "usb")

        /** OEM-specific tethering AP interface names, preferred when present. */
        val AP_PREFIXES = listOf("ap", "swlan", "softap")
    }
}
