package dev.spandan.mesh

enum class Role { RELAY, BEACON, DEEP_BEACON }

/**
 * Inputs a node re-evaluates periodically to pick its role. All are already
 * bucketed/coarse — role election doesn't need finer precision than the packet
 * format itself carries.
 */
data class RoleInputs(
    val batteryBucket: Int,       // 0..7, same buckets as the wire format
    val neighbourCount: Int,
    val motionlessMillis: Long,
)

class RoleElectionConfig(
    val lowBatteryBucket: Int = 2,          // <= this bucket (~25%) is "low"
    val deepBeaconMotionlessMillis: Long = 15 * 60_000L, // 15 min stationary
    val criticalBatteryBucket: Int = 0,     // <= this is "critical", forces DEEP_BEACON
)

/**
 * An entrapped person's phone should stop burning battery relaying for others —
 * long motionlessness or low battery demotes a node away from RELAY. Density
 * doesn't affect role directly (that's [dutyCycleIntervalMs]'s job), only
 * battery and stillness do.
 */
object RoleElection {
    fun evaluate(inputs: RoleInputs, config: RoleElectionConfig = RoleElectionConfig()): Role {
        val stationaryTooLong = inputs.motionlessMillis >= config.deepBeaconMotionlessMillis
        val batteryCritical = inputs.batteryBucket <= config.criticalBatteryBucket
        if (stationaryTooLong || batteryCritical) return Role.DEEP_BEACON
        if (inputs.batteryBucket <= config.lowBatteryBucket) return Role.BEACON
        return Role.RELAY
    }
}

/**
 * Density-adaptive duty cycling: as observed neighbour count rises, widen the
 * advertising interval to avoid a broadcast storm. Independent of [Role] —
 * even a RELAY node backs off its own beacon cadence in a crowded mesh.
 */
object DutyCycle {
    private const val BASE_INTERVAL_MS = 1_000L
    private const val MAX_INTERVAL_MS = 15_000L

    fun advertiseIntervalMs(neighbourCount: Int): Long =
        (BASE_INTERVAL_MS * (1 + neighbourCount)).coerceAtMost(MAX_INTERVAL_MS)
}
