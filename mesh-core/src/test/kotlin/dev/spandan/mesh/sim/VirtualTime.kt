package dev.spandan.mesh.sim

import dev.spandan.mesh.Clock
import dev.spandan.mesh.Scheduler
import java.util.PriorityQueue

/** A clock that only moves when [FakeScheduler.advanceTimeBy] tells it to. */
class VirtualClock : Clock {
    var now: Long = 0L
        internal set

    override fun nowMillis(): Long = now
}

/**
 * Deterministic discrete-event scheduler backing the simulator: no real sleeping,
 * no flakiness, thousands of simulated nodes run at the speed of a for-loop.
 */
class FakeScheduler(private val clock: VirtualClock) : Scheduler {
    private class Task(val fireAt: Long, val seq: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val queue = PriorityQueue<Task>(compareBy({ it.fireAt }, { it.seq }))
    private var seq = 0L

    override fun schedule(delayMillis: Long, action: () -> Unit): Scheduler.Cancellable {
        val task = Task(clock.now + delayMillis, seq++, action)
        queue.add(task)
        return Scheduler.Cancellable { task.cancelled = true }
    }

    /** Runs every task due within the next [deltaMillis], advancing the clock as it goes. */
    fun advanceTimeBy(deltaMillis: Long) {
        val target = clock.now + deltaMillis
        while (queue.isNotEmpty() && queue.peek().fireAt <= target) {
            val task = queue.poll()
            clock.now = task.fireAt
            if (!task.cancelled) task.action()
        }
        clock.now = target
    }
}
