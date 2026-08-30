package dev.spandan.mesh

/**
 * Everything the mesh protocol needs from the outside world, abstracted so the
 * exact same [MeshNode] logic runs against real Android BLE plumbing or an
 * in-memory simulated network. No Android imports allowed in this module.
 */
interface Transport {
    /** Broadcasts encoded packet bytes to whatever's listening. Fire-and-forget. */
    fun send(bytes: ByteArray)

    /** Registers the single listener for inbound bytes. Called once, at node setup. */
    fun onReceive(listener: (bytes: ByteArray) -> Unit)
}

interface Clock {
    fun nowMillis(): Long
}

interface Scheduler {
    /** Runs [action] after [delayMillis]. Returns a handle to cancel it before it fires. */
    fun schedule(delayMillis: Long, action: () -> Unit): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}
