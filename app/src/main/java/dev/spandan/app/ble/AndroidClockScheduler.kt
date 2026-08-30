package dev.spandan.app.ble

import android.os.Handler
import android.os.Looper
import dev.spandan.mesh.Clock
import dev.spandan.mesh.Scheduler

class AndroidClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class AndroidScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): Scheduler.Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return Scheduler.Cancellable { handler.removeCallbacks(runnable) }
    }
}
