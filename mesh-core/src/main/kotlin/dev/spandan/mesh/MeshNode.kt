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

    init {
        transport.onReceive { bytes -> handleIncoming(bytes) }
    }

    fun neighbourCount(): Int = neighbours.count()

    fun isAcknowledged(msgId: Int): Boolean = msgId in ackedMsgIds

    /** Re-evaluates [role] from freshly sampled inputs. Call this periodically from the host. */
    fun updateRole(inputs: RoleInputs, config: RoleElectionConfig = RoleElectionConfig()) {
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
        )
        pendingAcks += msgId
        originate(packet)
        return msgId
    }

    /** Sends [packet] now and schedules [repeatCount] more jittered re-sends — used for both self-originated SOS and gateway-originated ACKs. */
    private fun originate(packet: SpandanPacket) {
        val key = packet.dedupKey()
        cache.insert(key, packet)
        cache.markRelayed(key) // we originated it; never "relay" our own packet via the foreign-packet path
        sendNow(packet)
        scheduleRepeats(packet, repeatsLeft = repeatCount)
    }

    private fun scheduleRepeats(packet: SpandanPacket, repeatsLeft: Int) {
        if (repeatsLeft <= 0) return
        val delay = jitteredDelay(severityConfig.rebroadcastIntervalMs(packet.severity))
        scheduler.schedule(delay) {
            // Stop re-sending our own SOS once it's been acknowledged.
            if (packet.msgType == MsgType.SOS && packet.originId == originId && isAcknowledged(packet.msgId)) return@schedule
            sendNow(packet)
            scheduleRepeats(packet, repeatsLeft - 1)
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

    private fun handleIncoming(bytes: ByteArray) {
        val packet = runCatching { SpandanPacket.decode(bytes) }.getOrElse {
            onEvent(MeshEvent.Dropped(DropReason.DECODE_FAILED, null))
            return
        }

        neighbours.record(packet.originId)

        // Recognize an ack addressed to us before dedup — it always carries our own originId.
        if (packet.msgType == MsgType.ACK && packet.originId == originId) {
            if (packet.msgId in pendingAcks && packet.msgId !in ackedMsgIds) {
                ackedMsgIds += packet.msgId
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
