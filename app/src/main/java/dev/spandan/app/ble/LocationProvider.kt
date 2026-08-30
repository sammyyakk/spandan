package dev.spandan.app.ble

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import dev.spandan.mesh.QuantizedLocation

/**
 * Plain [LocationManager], not Play Services FusedLocationProvider -- the
 * project's non-negotiable is no Play Services dependency for core function.
 * No fix (permission denied, no provider, no cached location yet) yields
 * [QuantizedLocation.noFix] rather than an error; the brief requires a
 * manual pin-drop path for that case, never a blocking failure.
 */
class LocationProvider(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var manualOverride: QuantizedLocation? = null

    /** Lets the UI's manual pin-drop path stand in when there's no real fix. */
    fun setManualLocation(lat: Double, lon: Double) {
        manualOverride = QuantizedLocation.fromDegrees(lat, lon)
    }

    fun clearManualLocation() {
        manualOverride = null
    }

    @SuppressLint("MissingPermission")
    fun currentLocation(): QuantizedLocation {
        manualOverride?.let { return it }
        val mgr = manager ?: return QuantizedLocation.noFix()
        val best = runCatching {
            mgr.getProviders(true)
                .mapNotNull { provider -> mgr.getLastKnownLocation(provider) }
                .maxByOrNull(Location::getTime)
        }.getOrNull()
        return best?.let { QuantizedLocation.fromDegrees(it.latitude, it.longitude) } ?: QuantizedLocation.noFix()
    }
}
