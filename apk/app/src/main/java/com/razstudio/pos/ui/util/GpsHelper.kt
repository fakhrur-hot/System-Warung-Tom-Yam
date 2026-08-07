package com.razstudio.pos.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Locating the device well enough to answer a geofence question.
 *
 * ## The bug this was rewritten for
 *
 * This used to call `getLastKnownLocation()` on the GPS and network providers, take whichever was
 * newer, and return it — never once asking the device where it actually is. On a phone that has not
 * held a GPS lock recently that returns whatever is cached, and on a real staff phone standing in a
 * real café that was a **three-hour-old cell-tower estimate with ±100 m stated accuracy, 8.6 km
 * from the premises**. The app compared it against a 100 m radius and told the member of staff they
 * were eight kilometres away while they stood at the counter.
 *
 * Permissions were never the cause — fine and coarse were both declared and both granted. The two
 * code faults were:
 *
 *  - **No active request.** [locate] now asks for a current fix and falls back to the cache only
 *    when that fails.
 *  - **No quality check.** A fix that is stale, or whose own margin of error is wider than the
 *    radius being tested, cannot answer the question. Reporting a distance derived from one is
 *    worse than reporting nothing, because the number looks authoritative. [assess] refuses those.
 */
object GpsHelper {

    private const val TAG = "GpsHelper"
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** How long to wait for a live fix before falling back to whatever is cached. */
    private const val ACTIVE_FIX_TIMEOUT_MS = 8_000L

    /**
     * Oldest fix still treated as describing where the device is *now*.
     *
     * Two minutes is generous for somebody standing at a counter, and short enough that a position
     * from earlier in the shift — the actual failure this replaces — cannot pass.
     */
    private const val MAX_FIX_AGE_MS = 2 * 60 * 1000L

    /** What the caller gets back, and what it is entitled to conclude from it. */
    sealed class Fix {
        /** Recent, and precise enough for the radius it was requested for. */
        data class Usable(val location: Location) : Fix()

        /** A real position, but too old to describe where the device is now. */
        data class Stale(val location: Location, val ageMs: Long) : Fix()

        /** Current, but its own margin of error is wider than the radius being tested. */
        data class TooImprecise(val location: Location, val accuracyMeters: Float) : Fix()

        /** No provider produced anything. */
        object Unavailable : Fix()

        /** Location permission is not granted. */
        object NoPermission : Fix()
    }

    /**
     * Ask the device where it is now, and say whether the answer can be trusted for [radiusMeters].
     *
     * Tries a live fix first, then falls back to the newest cached one — which is then subject to
     * exactly the same age and accuracy tests. A cached fix is not automatically wrong; it is
     * simply not automatically right.
     */
    suspend fun locate(context: Context, radiusMeters: Int): Fix {
        if (!hasPermission(context)) return Fix.NoPermission
        val location = awaitCurrentLocation(context)
            ?: getLastLocation(context)
            ?: return Fix.Unavailable
        return assess(location, radiusMeters)
    }

    /** Age and precision, judged against the radius the caller cares about. */
    fun assess(location: Location, radiusMeters: Int): Fix {
        val age = System.currentTimeMillis() - location.time
        if (age > MAX_FIX_AGE_MS) return Fix.Stale(location, age)

        // A fix that admits it could be 100 m out cannot decide a 100 m radius — inside and outside
        // are both consistent with it. Judged against the radius rather than a fixed threshold,
        // because a café with a 500 m radius can tolerate what a 50 m one cannot.
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        if (accuracy > radiusMeters) return Fix.TooImprecise(location, accuracy)

        return Fix.Usable(location)
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Request a genuinely current fix, or null if none arrives within [ACTIVE_FIX_TIMEOUT_MS].
     *
     * GPS is asked first because it is the only provider that can beat a café-sized radius. The
     * network provider is the fallback for indoors, where GPS frequently never returns at all —
     * which is precisely where a till sits.
     */
    private suspend fun awaitCurrentLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            if (!enabled) continue
            val fix = withTimeoutOrNull(ACTIVE_FIX_TIMEOUT_MS) { requestSingleFix(manager, provider) }
            if (fix != null) return fix
        }
        return null
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { runCatching { signal.cancel() } }
                    manager.getCurrentLocation(provider, signal, { it.run() }) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    // requestSingleUpdate is deprecated but is the only single-shot API below R,
                    // and minSdk is 26. The listener is removed on cancellation so a timed-out
                    // request cannot leave the radio running.
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) = Unit

                        override fun onProviderEnabled(p: String) = Unit

                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not request a fix from $provider", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Newest cached fix from GPS or network, or null.
     *
     * Kept as the fallback behind [locate] rather than as a primary source — see the class note for
     * what happens when this is trusted on its own.
     */
    fun getLastLocation(context: Context): Location? {
        if (!hasPermission(context)) {
            Log.w(TAG, "Fine location permission not granted")
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        @Suppress("MissingPermission")
        val gpsLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        @Suppress("MissingPermission")
        val networkLocation = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        return when {
            gpsLocation != null && networkLocation != null ->
                if (gpsLocation.time >= networkLocation.time) gpsLocation else networkLocation
            gpsLocation != null -> gpsLocation
            networkLocation != null -> networkLocation
            else -> {
                Log.w(TAG, "No location fix available from any provider")
                null
            }
        }
    }

    /** Distance in metres between two coordinates (Haversine). */
    fun computeDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** Whether a staff position falls inside the café radius. */
    fun isWithinRadius(
        staffLat: Double,
        staffLng: Double,
        cafeLat: Double,
        cafeLng: Double,
        radiusMeters: Int,
    ): Boolean = computeDistance(staffLat, staffLng, cafeLat, cafeLng) <= radiusMeters
}
