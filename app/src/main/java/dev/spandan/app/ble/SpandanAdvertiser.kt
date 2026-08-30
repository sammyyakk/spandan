package dev.spandan.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.util.Log

/**
 * Reserved-for-testing Bluetooth SIG company ID (0xFFFF). We are not a
 * registered manufacturer; this is a deliberate hackathon shortcut, not
 * spec-compliant, documented in CLAUDE.md.
 */
const val SPANDAN_MANUFACTURER_ID = 0xFFFF

private const val TAG = "SpandanAdvertiser"

class SpandanAdvertiser(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(payload: ByteArray, onResult: (success: Boolean, message: String) -> Unit) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            onResult(false, "no BLE advertiser on this device/adapter off")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(SPANDAN_MANUFACTURER_ID, payload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "advertise started")
                onResult(true, "advertising")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
                onResult(false, "advertise failed, error=$errorCode")
            }
        }
        callback = cb
        advertiser.startAdvertising(settings, data, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        callback?.let { advertiser.stopAdvertising(it) }
        callback = null
    }
}
