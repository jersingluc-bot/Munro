package uk.munromap.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Where the phone thinks it is. Null until the first fix arrives. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val elapsedMillis: Long,
)

object LocationSource {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Emits fixes from GPS and the network provider.
     *
     * Uses android.location rather than Play Services so the app carries no
     * Google dependency and works on a phone with no data connection.
     */
    @SuppressLint("MissingPermission")
    fun fixes(context: Context): Flow<Fix> = callbackFlow {
        if (!hasPermission(context)) {
            close()
            return@callbackFlow
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        fun emit(location: Location) {
            trySend(
                Fix(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = location.accuracy,
                    elapsedMillis = System.currentTimeMillis(),
                )
            )
        }

        // Show the last known position immediately so the map isn't blank
        // while the GPS is still getting a fix.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.asSequence()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { emit(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = emit(location)

            @Deprecated("Required on older API levels")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        providers.forEach { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 2_000L, 5f, listener)
                }
            }
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }
}
