package dev.spandan.app.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.spandan.mesh.Transport

/**
 * Bridges [dev.spandan.mesh.MeshNode] to real BLE. A legacy advertiser can only
 * carry one payload at a time, but MeshNode calls send() once per event (our own
 * SOS repeats, foreign relays, acks) and those can overlap — so sends are queued
 * and advertised as short back-to-back bursts rather than dropped or clobbered.
 * Burst duration is comfortably shorter than any severity's rebroadcast interval,
 * so this never becomes the bottleneck.
 */
class BleTransport(context: Context, private val burstDurationMs: Long = 1_500L) : Transport {
    private val advertiser = SpandanAdvertiser(context)
    private val scanner = SpandanScanner(context)
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<ByteArray>()
    private var burstActive = false
    private var started = false

    var onStatus: (advertising: Boolean, scanning: Boolean, message: String) -> Unit = { _, _, _ -> }

    override fun send(bytes: ByteArray) {
        handler.post {
            queue.addLast(bytes)
            if (!burstActive) processQueue()
        }
    }

    override fun onReceive(listener: (bytes: ByteArray) -> Unit) {
        started = true
        scanner.start(
            onEvent = { event -> listener(event.rawBytes) },
            onResult = { success, message -> onStatus(burstActive, success, message) },
        )
    }

    fun stop() {
        started = false
        queue.clear()
        handler.removeCallbacksAndMessages(null)
        advertiser.stop()
        scanner.stop()
        burstActive = false
    }

    private fun processQueue() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            burstActive = false
            return
        }
        burstActive = true
        advertiser.start(next) { success, message -> onStatus(success, started, message) }
        handler.postDelayed({
            advertiser.stop()
            processQueue()
        }, burstDurationMs)
    }
}
