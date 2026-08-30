package dev.spandan.mesh.sim

import dev.spandan.mesh.Transport
import kotlin.random.Random

/**
 * N virtual nodes over a configurable connectivity graph with configurable
 * packet loss — the harness the brief asks for so relay/dedup/TTL/election
 * logic can be tested without five phones on the desk.
 *
 * [adjacency] is directed: `adjacency[from]` is the set of node indices that
 * hear `from`'s transmissions (mirror both directions for a symmetric link).
 */
class FakeMeshNetwork(
    private val adjacency: Map<Int, Set<Int>>,
    private val lossRate: Double = 0.0,
    private val random: Random = Random(0),
) {
    private val transports = HashMap<Int, FakeTransport>()

    fun transportFor(nodeIndex: Int): Transport =
        transports.getOrPut(nodeIndex) { FakeTransport(nodeIndex) }

    private inner class FakeTransport(private val nodeIndex: Int) : Transport {
        private var listener: ((ByteArray) -> Unit)? = null

        override fun send(bytes: ByteArray) {
            for (neighbour in adjacency[nodeIndex].orEmpty()) {
                if (random.nextDouble() >= lossRate) {
                    transports[neighbour]?.listener?.invoke(bytes)
                }
            }
        }

        override fun onReceive(listener: (bytes: ByteArray) -> Unit) {
            this.listener = listener
        }
    }
}
