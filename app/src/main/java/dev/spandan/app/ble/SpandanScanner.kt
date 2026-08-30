package dev.spandan.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

private const val TAG = "SpandanScanner"

data class SpandanScanEvent(
    val rawBytes: ByteArray,
    val rssi: Int,
    val timestampMillis: Long,
)

class SpandanScanner(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onEvent: (SpandanScanEvent) -> Unit, onResult: (success: Boolean, message: String) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onResult(false, "no BLE scanner on this device/adapter off")
            return
        }

        // Mask of all-zero bytes means "don't care about payload content" —
        // this filters purely on manufacturer ID so we never process other
        // BLE traffic in the room.
        val dontCareData = ByteArray(dev.spandan.mesh.PACKET_SIZE_BYTES)
        val dontCareMask = ByteArray(dev.spandan.mesh.PACKET_SIZE_BYTES)
        val filter = ScanFilter.Builder()
            .setManufacturerData(SPANDAN_MANUFACTURER_ID, dontCareData, dontCareMask)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bytes = result.scanRecord?.getManufacturerSpecificData(SPANDAN_MANUFACTURER_ID)
                if (bytes != null) {
                    onEvent(SpandanScanEvent(bytes, result.rssi, System.currentTimeMillis()))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                onResult(false, "scan failed, error=$errorCode")
            }
        }
        callback = cb
        scanner.startScan(listOf(filter), settings, cb)
        onResult(true, "scanning")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        callback?.let { scanner.stopScan(it) }
        callback = null
    }
}
