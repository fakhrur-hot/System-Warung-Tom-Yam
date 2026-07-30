package com.razstudio.pos.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS utility for staff attendance check-in radius validation.
 * Uses Android LocationManager (no Google Play dependency).
 */
object GpsHelper {

    private const val TAG = "GpsHelper"
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Get the last known location from GPS or Network provider.
     * Returns null if location permission is not granted or no fix is available.
     */
    fun getLastLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Fine location permission not granted")
            return null
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // Try GPS provider first (most accurate), then network
        val gpsLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        val networkLocation = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }

        // Return the most recent fix
        return when {
            gpsLocation != null && networkLocation != null -> {
                if (gpsLocation.time >= networkLocation.time) gpsLocation else networkLocation
            }
            gpsLocation != null -> gpsLocation
            networkLocation != null -> networkLocation
            else -> {
                Log.w(TAG, "No location fix available from any provider")
                null
            }
        }
    }

    /**
     * Compute the distance in meters between two GPS coordinates using the Haversine formula.
     */
    fun computeDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Check if a staff location is within the café radius.
     */
    fun isWithinRadius(
        staffLat: Double,
        staffLng: Double,
        cafeLat: Double,
        cafeLng: Double,
        radiusMeters: Int
    ): Boolean {
        val distance = computeDistance(staffLat, staffLng, cafeLat, cafeLng)
        return distance <= radiusMeters
    }
}
