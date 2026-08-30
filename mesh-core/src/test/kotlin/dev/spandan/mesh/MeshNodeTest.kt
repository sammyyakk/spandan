package dev.spandan.mesh

import dev.spandan.mesh.sim.FakeMeshNetwork
import dev.spandan.mesh.sim.FakeScheduler
import dev.spandan.mesh.sim.VirtualClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun symmetric(vararg links: Pair<Int, Int>): Map<Int, Set<Int>> {
    val map = HashMap<Int, MutableSet<Int>>()
    for ((a, b) in links) {
        map.getOrPut(a) { mutableSetOf() }.add(b)
        map.getOrPut(b) { mutableSetOf() }.add(a)
    }
    return map
}

private class Harness(adjacency: Map<Int, Set<Int>>, lossRate: Double = 0.0, seed: Int = 1) {
    val clock = VirtualClock()
    val scheduler = FakeScheduler(clock)
    val network = FakeMeshNetwork(adjacency, lossRate, Random(seed))
    val events = HashMap<Int, MutableList<MeshEvent>>()

    fun node(index: Int, originId: Int, random: Random = Random(index + 100)): MeshNode {
        val log = events.getOrPut(index) { mutableListOf() }
        return MeshNode(
            originId = originId,
            transport = network.transportFor(index),
            clock = clock,
            scheduler = scheduler,
            random = random,
            onEvent = { log += it },
        )
    }

    fun advance(ms: Long) = scheduler.advanceTimeBy(ms)

    fun countOf(index: Int, predicate: (MeshEvent) -> Boolean): Int =
        events[index]?.count(predicate) ?: 0
}

private fun testSos(originId: Int = 0xABCD, severity: Int = 4) = SpandanPacket(
    msgType = MsgType.SOS,
    protocolVersion = 0,
    hazardCategory = HazardCategory.TRAPPED,
    severity = severity,
    originId = originId,
    msgId = 0x1111,
    location = QuantizedLocation.noFix(),
    baroValid = false,
    baroDeltaDeciHpa = 0,
    batteryBucket = 5,
    livenessBucket = 0,
    originTs = 0,
    hopCount = 0,
)

class MeshNodeChainRelayTest {

    // A <-> B <-> C, A and C out of range of each other. A's packet must reach
    // C via exactly one relay from B (Stage 3 success criteria).
    @Test
    fun `A originates, B relays exactly once, C receives with hop count 1`() {
        val h = Harness(symmetric(0 to 1, 1 to 2))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB000)
        val c = h.node(2, originId = 0xC000)

        a.originateSos(HazardCategory.TRAPPED, 5, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(30_000)

        val received = h.events[2]!!.filterIsInstance<MeshEvent.Received>().filter { it.packet.msgType == MsgType.SOS }
        assertEquals(1, received.size)
        assertEquals(1, received.first().packet.hopCount)

        val bRelays = h.countOf(1) { it is MeshEvent.Relayed }
        assertEquals(1, bRelays, "B must relay the packet exactly once")
    }

    @Test
    fun `never rebroadcasts a packet it already relayed, even after repeated hearings`() {
        // Two paths from A to C via both B1 and B2 means C (and B1,B2) each hear
        // the packet more than once — dedup must still cap each node at one relay.
        val h = Harness(symmetric(0 to 1, 0 to 2, 1 to 3, 2 to 3))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB100)
        h.node(2, originId = 0xB200)
        h.node(3, originId = 0xC000)

        a.originateSos(HazardCategory.TRAPPED, 5, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(30_000)

        assertEquals(1, h.countOf(1) { it is MeshEvent.Relayed })
        assertEquals(1, h.countOf(2) { it is MeshEvent.Relayed })
        assertTrue(h.countOf(3) { it is MeshEvent.Dropped && (it as MeshEvent.Dropped).reason == DropReason.DUPLICATE } >= 1)
    }

    @Test
    fun `packet dies past its severity's hop TTL`() {
        // Severity 0 under WEIGHTED config has hopTtl = 3. A 5-hop line topology
        // means the far end must never see it.
        val links = (0 until 5).map { it to it + 1 }.toTypedArray()
        val h = Harness(symmetric(*links))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB1); h.node(2, originId = 0xB2)
        h.node(3, originId = 0xB3); h.node(4, originId = 0xB4)
        val end = h.node(5, originId = 0xEE00)

        a.originateSos(HazardCategory.OTHER, severity = 0, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(60_000)

        assertTrue(
            h.events[5]!!.filterIsInstance<MeshEvent.Received>().none { it.packet.msgType == MsgType.SOS },
            "packet should not survive 5 hops at severity 0",
        )
        assertTrue(h.countOf(4) { it is MeshEvent.Dropped && (it as MeshEvent.Dropped).reason == DropReason.TTL_EXCEEDED } >= 1)
        assertEquals(0, h.countOf(5) { it is MeshEvent.Relayed }, "end node never even attempts a relay")
    }

    @Test
    fun `higher severity travels further under the same topology`() {
        val links = (0 until 5).map { it to it + 1 }.toTypedArray()
        val h = Harness(symmetric(*links))
        val a = h.node(0, originId = 0xA000)
        for (i in 1..4) h.node(i, originId = 0xB000 + i)
        val end = h.node(5, originId = 0xEE00)

        // severity 7 -> hopTtl = 10 under WEIGHTED, comfortably covers 5 hops.
        a.originateSos(HazardCategory.MEDICAL, severity = 7, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(60_000)

        assertTrue(
            h.events[5]!!.filterIsInstance<MeshEvent.Received>().any { it.packet.msgType == MsgType.SOS },
            "severity 7 packet should reach the far end",
        )
    }

    @Test
    fun `BEACON role never relays a foreign packet`() {
        val h = Harness(symmetric(0 to 1, 1 to 2))
        val a = h.node(0, originId = 0xA000)
        val b = h.node(1, originId = 0xB000)
        h.node(2, originId = 0xC000)
        b.updateRole(RoleInputs(batteryBucket = 1, neighbourCount = 0, motionlessMillis = 0))
        assertEquals(Role.BEACON, b.role)

        a.originateSos(HazardCategory.TRAPPED, 5, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(30_000)

        assertEquals(0, h.countOf(1) { it is MeshEvent.Relayed })
        assertTrue(h.countOf(1) { it is MeshEvent.Dropped && (it as MeshEvent.Dropped).reason == DropReason.ROLE_NO_RELAY } >= 1)
    }
}

class MeshNodeAckPathTest {
    @Test
    fun `ack from gateway propagates back to origin and flips acknowledged state`() {
        val h = Harness(symmetric(0 to 1, 1 to 2))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB000)
        val gateway = h.node(2, originId = 0xC000)
        gateway.isGateway = true

        val msgId = a.originateSos(HazardCategory.TRAPPED, 6, QuantizedLocation.noFix(), false, 0, 3, 0)
        assertFalse(a.isAcknowledged(msgId))

        h.advance(60_000)

        assertTrue(a.isAcknowledged(msgId), "origin should see its SOS acknowledged once the gateway's ack propagates back")
        assertTrue(h.events[0]!!.any { it is MeshEvent.Acknowledged && it.msgId == msgId })
    }

    @Test
    fun `updateActivePhrase changes content of subsequent resends without changing msgId`() {
        val h = Harness(symmetric(0 to 1))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB000)

        val msgId = a.originateSos(HazardCategory.MEDICAL, 6, QuantizedLocation.noFix(), false, 0, 3, 0)
        a.updateActivePhrase(msgId, CannedPhrase.NEED_MEDICAL_EVAC)
        h.advance(30_000)

        val sends = h.events[0]!!.filterIsInstance<MeshEvent.Sent>().filter { it.packet.msgType == MsgType.SOS }
        assertTrue(sends.isNotEmpty())
        assertTrue(sends.all { it.packet.msgId == msgId }, "msgId must not change when phrase updates")
        assertTrue(sends.any { it.packet.phrase == CannedPhrase.NEED_MEDICAL_EVAC }, "later resends should carry the updated phrase")
    }

    @Test
    fun `updateActivePhrase on an unknown or already-acked msgId is a no-op`() {
        val h = Harness(symmetric(0 to 1))
        val a = h.node(0, originId = 0xA000)
        a.updateActivePhrase(0x9999, CannedPhrase.PLEASE_HURRY) // never originated; must not throw
    }

    @Test
    fun `cancelSos stops further resends immediately`() {
        val h = Harness(symmetric(0 to 1))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB000)

        val msgId = a.originateSos(HazardCategory.TRAPPED, 5, QuantizedLocation.noFix(), false, 0, 7, 0)
        h.advance(5_000)
        a.cancelSos(msgId)

        val sendsAtCancel = h.countOf(0) { it is MeshEvent.Sent && it.packet.msgType == MsgType.SOS }
        h.advance(120_000)
        val sendsLater = h.countOf(0) { it is MeshEvent.Sent && it.packet.msgType == MsgType.SOS }
        assertEquals(sendsAtCancel, sendsLater, "cancelled SOS must not keep resending")
    }

    @Test
    fun `ack and its originating SOS do not collide on dedup key`() {
        val sos = testSos()
        val ack = sos.copy(msgType = MsgType.ACK, hopCount = 0)
        assertTrue(sos.dedupKey() != ack.dedupKey(), "SOS and its ACK must not share a dedup key or the ACK is dropped as a duplicate")
    }

    @Test
    fun `origin stops repeating its SOS once acknowledged`() {
        val h = Harness(symmetric(0 to 1, 1 to 2))
        val a = h.node(0, originId = 0xA000)
        h.node(1, originId = 0xB000)
        val gateway = h.node(2, originId = 0xC000)
        gateway.isGateway = true

        a.originateSos(HazardCategory.TRAPPED, 6, QuantizedLocation.noFix(), false, 0, 3, 0)
        h.advance(120_000)

        fun sosSends() = h.countOf(0) { it is MeshEvent.Sent && it.packet.msgType == MsgType.SOS }
        val sentCountAtAck = sosSends()
        h.advance(120_000)
        val sentCountLater = sosSends()
        assertEquals(sentCountAtAck, sentCountLater, "no further SOS repeats after acknowledgement (heartbeats aside)")
    }
}

class SeenPacketCacheTest {
    @Test
    fun `eviction under pressure drops lowest severity first, then oldest`() {
        val clock = VirtualClock()
        val cache = SeenPacketCache(capacity = 2, clock = clock) { SeverityConfig.WEIGHTED }

        val low = testSos(originId = 1, severity = 1).copy(msgId = 1)
        val mid = testSos(originId = 2, severity = 4).copy(msgId = 2)
        val high = testSos(originId = 3, severity = 7).copy(msgId = 3)

        cache.insert(low.dedupKey(), low)
        clock.now = 100
        cache.insert(mid.dedupKey(), mid)
        clock.now = 200
        cache.insert(high.dedupKey(), high) // forces eviction: capacity 2

        assertFalse(cache.contains(low.dedupKey()), "lowest severity should be evicted first")
        assertTrue(cache.contains(mid.dedupKey()))
        assertTrue(cache.contains(high.dedupKey()))
    }

    @Test
    fun `entries expire past their severity's cache TTL`() {
        val clock = VirtualClock()
        val cache = SeenPacketCache(capacity = 10, clock = clock) { SeverityConfig.WEIGHTED }
        val packet = testSos(severity = 0) // WEIGHTED cacheTtl(0) = 30_000ms
        cache.insert(packet.dedupKey(), packet)
        assertTrue(cache.contains(packet.dedupKey()))

        clock.now = 30_001
        assertFalse(cache.contains(packet.dedupKey()), "entry should have expired past its severity's TTL")
    }
}

class RoleElectionTest {
    @Test
    fun `healthy battery and moving stays RELAY`() {
        val role = RoleElection.evaluate(RoleInputs(batteryBucket = 6, neighbourCount = 3, motionlessMillis = 1_000))
        assertEquals(Role.RELAY, role)
    }

    @Test
    fun `low battery demotes to BEACON`() {
        val role = RoleElection.evaluate(RoleInputs(batteryBucket = 2, neighbourCount = 3, motionlessMillis = 1_000))
        assertEquals(Role.BEACON, role)
    }

    @Test
    fun `long motionless period forces DEEP_BEACON regardless of battery`() {
        val role = RoleElection.evaluate(RoleInputs(batteryBucket = 7, neighbourCount = 3, motionlessMillis = 20 * 60_000L))
        assertEquals(Role.DEEP_BEACON, role)
    }

    @Test
    fun `critical battery forces DEEP_BEACON regardless of motion`() {
        val role = RoleElection.evaluate(RoleInputs(batteryBucket = 0, neighbourCount = 3, motionlessMillis = 0))
        assertEquals(Role.DEEP_BEACON, role)
    }

    @Test
    fun `duty cycle interval widens as neighbour count rises, capped`() {
        val low = DutyCycle.advertiseIntervalMs(0)
        val mid = DutyCycle.advertiseIntervalMs(5)
        val high = DutyCycle.advertiseIntervalMs(100)
        assertTrue(low < mid)
        assertTrue(mid < high)
        assertEquals(15_000L, high) // capped
    }
}

class NeighbourTrackerTest {
    @Test
    fun `counts distinct recent origins and forgets stale ones`() {
        val clock = VirtualClock()
        val tracker = NeighbourTracker(clock, windowMillis = 1_000)
        tracker.record(testSos(originId = 0xAAA), rssi = -50)
        tracker.record(testSos(originId = 0xBBB), rssi = -60)
        tracker.record(testSos(originId = 0xAAA), rssi = -55) // duplicate, shouldn't double count
        assertEquals(2, tracker.count())

        clock.now = 2_000
        assertEquals(0, tracker.count(), "stale sightings should age out of the window")
    }

    @Test
    fun `snapshot exposes rssi and last-seen packet metadata, most recent first`() {
        val clock = VirtualClock()
        val tracker = NeighbourTracker(clock)
        tracker.record(testSos(originId = 0xAAA, severity = 3), rssi = -50)
        clock.now = 100
        tracker.record(testSos(originId = 0xBBB, severity = 6), rssi = -40)

        val snapshot = tracker.snapshot()
        assertEquals(listOf(0xBBB, 0xAAA), snapshot.map { it.originId })
        assertEquals(-40, snapshot.first { it.originId == 0xBBB }.lastRssi)
        assertEquals(6, snapshot.first { it.originId == 0xBBB }.lastSeverity)
    }
}
