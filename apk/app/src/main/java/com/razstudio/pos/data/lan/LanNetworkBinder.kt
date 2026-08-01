package com.razstudio.pos.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes this app's LAN traffic out of the Wi-Fi interface (tasks 21.4, 21.7, 21.8 — Requirements
 * 4.3.5, 4.3.8, 4.3.9).
 *
 * Ported from StudioRoom's canon-sync `NetworkBinder`, which was device-verified against Canon
 * Camera Connect via `dumpsys connectivity`. The problem there and here is identical: reach a
 * device on a local Wi-Fi that has **no internet**, from a phone that may also have mobile data on.
 * Only the transport differs — raw PTP/IP sockets there, OkHttp here — so the socket wiring is
 * adapted and the network-selection logic is kept.
 *
 * ### The bug this exists to prevent (task 21.4)
 *
 * With mobile data on, `ConnectivityManager.activeNetwork` is **cellular**, even while the phone is
 * sitting on the café's Wi-Fi. Android keeps it that way deliberately, because the café AP has no
 * validated internet. So any code that reaches for `activeNetwork` sends its request out of the SIM,
 * where `192.168.x.x` goes nowhere — and the staff phone reports "cannot reach the server" while
 * visibly connected to the right Wi-Fi. The fix is to scan *all* connected networks for a
 * `TRANSPORT_WIFI` one instead of trusting the default.
 *
 * ### What this deliberately does NOT do (task 21.7)
 *
 * No `WifiNetworkSpecifier`, no `requestNetwork` with an SSID matcher, no system Wi-Fi picker. That
 * API forces a disconnect-and-re-associate, and it demands `ACCESS_FINE_LOCATION` at runtime even on
 * Android 13+ — a permission prompt that café staff routinely refuse, for a POS that has no business
 * asking for their location. The operator joins the network in Android settings; this class only
 * *selects* what is already there.
 *
 * ### Server vs Client (task 21.8)
 *
 * The Server Device is usually the hotspot itself. Android does not surface a tethering AP as a
 * connected `Network`, so there is nothing to bind and [bindToLanWifi] would fail looking for one —
 * but the kernel already routes `192.168.x.x` out of the AP interface, so binding is unnecessary
 * anyway. [isActingAsHotspot] detects that case and short-circuits.
 */
@Singleton
class LanNetworkBinder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** What [bindToLanWifi] did, so a caller can report it and undo it. */
    sealed interface Binding {
        /** The process is now pinned to [network]; call [release] to restore the previous default. */
        data class Bound(val network: Network, val release: () -> Unit) : Binding

        /** Nothing to do — this device is the AP and the kernel already routes correctly. */
        data object HotspotHost : Binding

        /** No Wi-Fi to bind to. [reason] is written to be shown, not just logged. */
        data class NoWifi(val reason: String) : Binding
    }

    /**
     * Detect the phone-is-the-AP topology (task 21.8).
     *
     * `WifiManager.isWifiApEnabled` was public until API 26 and hidden after; AOSP still implements
     * it on the system service, it is just absent from the SDK. Reflection is the standard workaround
     * (Termux, KDE Connect, LocalSend all do this).
     *
     * **Fails closed to false.** If the method is gone on some OEM build, or reflection is blocked,
     * the honest answer is "I don't know", and treating that as "not hotspotting" merely falls
     * through to the normal Wi-Fi search — which either finds a network or reports that it did not.
     * Failing open would skip binding on a device that genuinely needed it, producing the silent
     * wrong-interface bug this class exists to prevent.
     */
    fun isActingAsHotspot(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        return runCatching {
            val method = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wm) as? Boolean ?: false
        }.getOrElse {
            Log.d(TAG, "isWifiApEnabled unavailable (${it.javaClass.simpleName}) — assuming not hotspotting")
            false
        }
    }

    /**
     * Pin this process to the café's Wi-Fi so LAN requests leave by the right interface.
     *
     * Binds the **process**, not just one socket, so that everything the app does — including DNS and
     * any request that forgets to use the bound client — follows the same route. [Binding.Bound.release]
     * restores whatever was bound before, so leaving LAN Mode does not strand the device off mobile
     * data.
     */
    fun bindToLanWifi(): Binding {
        if (isActingAsHotspot()) {
            Log.i(TAG, "This device is the access point — no bind needed, kernel routes via the AP interface")
            return Binding.HotspotHost
        }

        val cm = connectivityManager
            ?: return Binding.NoWifi("Network services are unavailable on this device.")

        val wifi = findWifiNetwork()
            ?: return Binding.NoWifi(
                "This device is not on a Wi-Fi network. Join the café's Wi-Fi in Android settings, " +
                    "then try again."
            )

        val previous = runCatching { cm.boundNetworkForProcess }.getOrNull()
        val bound = runCatching { cm.bindProcessToNetwork(wifi) }.getOrElse {
            Log.w(TAG, "bindProcessToNetwork failed", it)
            false
        }
        if (!bound) {
            return Binding.NoWifi("Could not route this app over Wi-Fi. Reconnect and try again.")
        }

        Log.i(TAG, "Bound process to Wi-Fi network $wifi (was $previous)")
        return Binding.Bound(
            network = wifi,
            release = {
                runCatching { cm.bindProcessToNetwork(previous) }
                    .onFailure { Log.w(TAG, "Failed to restore previous process network", it) }
            },
        )
    }

    /**
     * An OkHttp client whose sockets are pinned to [network].
     *
     * The adaptation from the canon-sync original: it bound raw sockets with `network.bindSocket`,
     * whereas OkHttp opens its own, so the network's [Network.getSocketFactory] is handed to the
     * builder instead. Belt and braces alongside [bindToLanWifi] — the process binding covers
     * everything, this covers the LAN client specifically even if the process binding is lost or was
     * never applied because the device is the hotspot host.
     */
    fun OkHttpClient.pinnedTo(network: Network): OkHttpClient =
        newBuilder().socketFactory(network.socketFactory).build()

    /**
     * The connected Wi-Fi network, whether or not it is the system default.
     *
     * Prefers the active network when it is already Wi-Fi (the common, cheap case), then falls back
     * to scanning every connected network. That fallback is the whole point — see the class KDoc.
     */
    private fun findWifiNetwork(): Network? {
        val cm = connectivityManager ?: return null

        cm.activeNetwork?.let { active ->
            val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
        }

        @Suppress("DEPRECATION")
        val all = runCatching { cm.allNetworks }.getOrNull().orEmpty()
        return all.firstOrNull { network ->
            runCatching { cm.getNetworkCapabilities(network) }
                .getOrNull()
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private companion object {
        const val TAG = "LanNetworkBinder"
    }
}
