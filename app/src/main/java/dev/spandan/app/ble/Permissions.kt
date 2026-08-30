package dev.spandan.app.ble

import android.Manifest
import android.os.Build

/**
 * Android 12+ (API 31+) uses dedicated Bluetooth runtime permissions.
 * Below that, BLE scanning required ACCESS_FINE_LOCATION at runtime even
 * though we never use it for location — that's a legacy OS requirement,
 * not something we can route around.
 */
object Permissions {
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
