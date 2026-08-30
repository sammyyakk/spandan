package dev.spandan.mesh

import kotlin.random.Random

/**
 * The whole protocol brain of one mesh participant: dedup, TTL, severity-weighted
 * rebroadcast with jitter, role-gated relay, and ack origination/recognition.
 * Pure Kotlin — driven entirely through [Transport]/[Clock]/[Scheduler], so the
 * exact same instance runs against real BLE or the fake-transport simulator.
 */
class MeshNode(
    val originId: Int,
    private val transport: Transport,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val random: Random = Random.Default,
    cacheCapacity: Int = 256,
    private val repeatCount: Int = 3,
    private val onEvent: (MeshEvent) -> Unit = {},
) {
    /** Swappable at runtime — this is the "flip one flag" demo knob (Stage 4). */
    var severityConfig: SeverityConfig = SeverityConfig.WEIGHTED

    var role: Role = Role.RELAY
        private set

    var isGateway: Boolean = false

    private val cache = SeenPacketCache(cacheCapacity, clock) { severityConfig }
    private val neighbours = NeighbourTracker(clock)
    private val pendingAcks = HashSet<Int>() // msgIds of our own SOS packets awaiting ack
    private val ackedMsgIds = HashSet<Int>()
    private val activeSosPackets = HashMap<Int, SpandanPacket>() // msgId -> current (mutable-by-phrase) content
    private val cancelledSosMsgIds = HashSet<Int>()
    private var lastKnownBatteryBucket = 7

    init {
        transport.onReceive { bytes, rssi -> handleIncoming(bytes, rssi) }
        sendHeartbeat()
        scheduleNextHeartbeat()
    }

    /**
     * Every node continuously broadcasts a lightweight presence beacon
     * (`msgType = RELAY_META`), independent of any SOS — this is what makes
     * "who's nearby" automatic the instant two nodes are both running, with no
     * pairing or discovery step. Single-hop only: never flood-relayed (that
     * would turn idle presence into a broadcast storm). Interval widens as
     * neighbour count rises (density-adaptive duty cycling, Stage 6).
     */
    private fun sendHeartbeat() {
        val packet = SpandanPacket(
            msgType = MsgType.RELAY_META,
            protocolVersion = 0,
            hazardCategory = HazardCategory.OTHER,
            severity = 0,
            originId = originId,
            msgId = 0,
            location = QuantizedLocation.noFix(),
            baroValid = false,
            baroDeltaDeciHpa = 0,
            batteryBucket = lastKnownBatteryBucket,
            livenessBucket = 0,
            originTs = (clock.nowMillis() / 1000 % 256).toInt(),
            hopCount = 0,
        )
        sendNow(packet)
    }

    private fun scheduleNextHeartbeat() {
        scheduler.schedule(DutyCycle.advertiseIntervalMs(neighbourCount())) {
            sendHeartbeat()
            scheduleNextHeartbeat()
        }
    }

    fun neighbourCount(): Int = neighbours.count()

    /** Every nearby node heard recently — automatic the moment both sides are running; no pairing/discovery step. */
    fun nearbyDevices(): List<NeighbourInfo> = neighbours.snapshot()

    fun isAcknowledged(msgId: Int): Boolean = msgId in ackedMsgIds

    /** Re-evaluates [role] from freshly sampled inputs. Call this periodically from the host. */
    fun updateRole(inputs: RoleInputs, config: RoleElectionConfig = RoleElectionConfig()) {
        lastKnownBatteryBucket = inputs.batteryBucket
        val newRole = RoleElection.evaluate(inputs, config)
        if (newRole != role) {
            role = newRole
            onEvent(MeshEvent.RoleChanged(newRole))
        }
    }

    /** Originates a new SOS from this node. Returns the msgId so the caller can track ack state. */
    fun originateSos(
        hazardCategory: HazardCategory,
        severity: Int,
        location: QuantizedLocation,
        baroValid: Boolean,
        baroDeltaDeciHpa: Int,
        batteryBucket: Int,
        livenessBucket: Int,
        phrase: CannedPhrase = CannedPhrase.NONE,
    ): Int {
        val msgId = random.nextInt(0, 0x10000)
        val packet = SpandanPacket(
            msgType = MsgType.SOS,
            protocolVersion = 0,
            hazardCategory = hazardCategory,
            severity = severity,
            originId = originId,
            msgId = msgId,
            location = location,
            baroValid = baroValid,
            baroDeltaDeciHpa = baroDeltaDeciHpa,
            batteryBucket = batteryBucket,
            livenessBucket = livenessBucket,
            originTs = (clock.nowMillis() / 1000 % 256).toInt(),
            hopCount = 0,
            phrase = phrase,
        )
        pendingAcks += msgId
        activeSosPackets[msgId] = packet
        originate(packet)
        return msgId
    }

    /**
     * Updates the phrase on an SOS we originated that's still actively
     * repeating (not yet acknowledged). Takes effect on the *next* scheduled
     * resend — msgId/dedupKey are unchanged, so this is not a new SOS.
     *
     * Known limitation, inherent to flood dedup rather than a bug: a neighbour
     * that already relayed the original packet will drop this later resend as
     * a duplicate (dedupKey doesn't include phrase, by design — phrase isn't
     * part of packet identity). So an attached phrase only reaches neighbours
     * encountered *after* the update, not nodes that already relayed the
     * pre-update version. No-op if [msgId] isn't an active SOS of ours.
     */
    fun updateActivePhrase(msgId: Int, phrase: CannedPhrase) {
        val current = activeSosPackets[msgId] ?: return
        activeSosPackets[msgId] = current.copy(phrase = phrase)
    }

    /**
     * "I'm safe now": stops resending this SOS locally. Cannot recall copies
     * already relayed elsewhere in the mesh — that's inherent to a flood
     * broadcast (no central authority to tell "undo that"), not a bug. A
     * cancelled msgId also can't later be "acknowledged" into resuming.
     */
    fun cancelSos(msgId: Int) {
        cancelledSosMsgIds += msgId
        activeSosPackets.remove(msgId)
        pendingAcks.remove(msgId)
    }

    /**
     * Sends [packet] now and keeps it alive afterward:
     *  - an SOS re-sends indefinitely, on jittered severity-weighted intervals,
     *    until acknowledged — "broadcasting" is meant to persist until someone
     *    answers, not stop after an arbitrary count. [isAcknowledged] gates it.
     *  - an ACK (or anything else we originate) only needs enough redundancy to
     *    get picked up by the flood once, so it uses a small finite repeat count.
     */
    private fun originate(packet: SpandanPacket) {
        val key = packet.dedupKey()
        cache.insert(key, packet)
        cache.markRelayed(key) // we originated it; never "relay" our own packet via the foreign-packet path
        sendNow(packet)
        if (packet.msgType == MsgType.SOS) {
            scheduleUntilAcked(packet)
        } else {
            scheduleFiniteRepeats(packet, repeatsLeft = repeatCount)
        }
    }

    private fun scheduleUntilAcked(packet: SpandanPacket) {
        val delay = jitteredDelay(severityConfig.rebroadcastIntervalMs(packet.severity))
        scheduler.schedule(delay) {
            if (isAcknowledged(packet.msgId) || packet.msgId in cancelledSosMsgIds) {
                activeSosPackets.remove(packet.msgId)
                return@schedule
            }
            // Re-read from activeSosPackets each time so updateActivePhrase() takes effect;
            // absence here (shouldn't normally happen outside ack/cancel) just stops the chain.
            val current = activeSosPackets[packet.msgId] ?: return@schedule
            sendNow(current)
            scheduleUntilAcked(current)
        }
    }

    private fun scheduleFiniteRepeats(packet: SpandanPacket, repeatsLeft: Int) {
        if (repeatsLeft <= 0) return
        val delay = jitteredDelay(severityConfig.rebroadcastIntervalMs(packet.severity))
        scheduler.schedule(delay) {
            sendNow(packet)
            scheduleFiniteRepeats(packet, repeatsLeft - 1)
        }
    }

    private fun sendNow(packet: SpandanPacket) {
        transport.send(packet.encode())
        onEvent(MeshEvent.Sent(packet))
    }

    private fun jitteredDelay(baseMs: Long): Long {
        val jitter = random.nextLong(0, (baseMs / 2).coerceAtLeast(1))
        return baseMs + jitter
    }

    private fun handleIncoming(bytes: ByteArray, rssi: Int?) {
        val packet = runCatching { SpandanPacket.decode(bytes) }.getOrElse {
            onEvent(MeshEvent.Dropped(DropReason.DECODE_FAILED, null))
            return
        }

        neighbours.record(packet, rssi)

        if (packet.msgType == MsgType.RELAY_META) {
            // Presence-only: never flood-relayed, never cached for dedup/ack —
            // just proves a neighbour is here. Relaying these would turn idle
            // presence into a broadcast storm.
            onEvent(MeshEvent.Received(packet))
            return
        }

        // Recognize an ack addressed to us before dedup — it always carries our own originId.
        if (packet.msgType == MsgType.ACK && packet.originId == originId) {
            if (packet.msgId in pendingAcks && packet.msgId !in ackedMsgIds) {
                ackedMsgIds += packet.msgId
                activeSosPackets.remove(packet.msgId)
                onEvent(MeshEvent.Acknowledged(packet.msgId))
            }
        }

        val key = packet.dedupKey()
        if (cache.contains(key)) {
            onEvent(MeshEvent.Dropped(DropReason.DUPLICATE, packet))
            return
        }

        val hopTtl = severityConfig.hopTtl(packet.severity)
        if (packet.hopCount >= hopTtl) {
            onEvent(MeshEvent.Dropped(DropReason.TTL_EXCEEDED, packet))
            return
        }

        cache.insert(key, packet)
        onEvent(MeshEvent.Received(packet))

        if (isGateway && packet.msgType == MsgType.SOS) {
            originateAck(packet)
        }

        if (role == Role.BEACON || role == Role.DEEP_BEACON) {
            onEvent(MeshEvent.Dropped(DropReason.ROLE_NO_RELAY, packet))
            return
        }

        scheduleRelay(packet, key)
    }

    private fun originateAck(sos: SpandanPacket) {
        val ack = SpandanPacket(
            msgType = MsgType.ACK,
            protocolVersion = sos.protocolVersion,
            hazardCategory = sos.hazardCategory,
            severity = sos.severity,
            originId = sos.originId, // "this ack is for that origin"
            msgId = sos.msgId,       // echoes the SOS's msgId
            location = QuantizedLocation.noFix(),
            baroValid = false,
            baroDeltaDeciHpa = 0,
            batteryBucket = 0,
            livenessBucket = 0,
            originTs = (clock.nowMillis() / 1000 % 256).toInt(),
            hopCount = 0,
        )
        originate(ack)
    }

    private fun scheduleRelay(packet: SpandanPacket, key: Long) {
        val relayPacket = packet.copy(hopCount = packet.hopCount + 1)
        val delay = jitteredDelay(severityConfig.rebroadcastIntervalMs(packet.severity))
        scheduler.schedule(delay) {
            if (cache.wasRelayed(key)) return@schedule // never rebroadcast a packet we've already relayed
            cache.markRelayed(key)
            transport.send(relayPacket.encode())
            onEvent(MeshEvent.Relayed(relayPacket))
        }
    }
}
