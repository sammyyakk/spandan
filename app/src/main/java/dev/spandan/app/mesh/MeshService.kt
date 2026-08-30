package dev.spandan.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.spandan.app.ble.AndroidClock
import dev.spandan.app.ble.AndroidScheduler
import dev.spandan.app.ble.BleTransport
import dev.spandan.mesh.CannedPhrase
import dev.spandan.mesh.HazardCategory
import dev.spandan.mesh.MeshEvent
import dev.spandan.mesh.MeshNode
import dev.spandan.mesh.QuantizedLocation
import dev.spandan.mesh.Role
import dev.spandan.mesh.RoleInputs
import dev.spandan.mesh.SeverityConfig
import kotlin.random.Random

data class MeshSnapshot(
    val originId: Int,
    val role: Role,
    val batteryBucket: Int,
    val neighbourCount: Int,
    val isGateway: Boolean,
    val weightedPropagation: Boolean,
    val pendingSosMsgId: Int?,
    val acknowledged: Boolean,
    val activeCategory: HazardCategory?,
    val activePhrase: CannedPhrase,
    val sentAtMillis: Long?,
)

/**
 * Foreground service hosting the mesh: owns the [MeshNode], the real BLE
 * [BleTransport], role re-evaluation off battery/motion/neighbour count, and
 * the persistent notification that keeps advertise/scan alive through
 * screen-off and doze (Stage 5).
 */
class MeshService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

    private lateinit var transport: BleTransport
    private lateinit var meshNode: MeshNode
    private var sensorManager: SensorManager? = null
    private var significantMotionSensor: Sensor? = null
    private var lastMotionAtMillis: Long = System.currentTimeMillis()
    private var lastPendingSosMsgId: Int? = null
    private var lastPendingCategory: HazardCategory? = null
    private var lastPendingPhrase: CannedPhrase = CannedPhrase.NONE
    private var lastSentAtMillis: Long? = null

    var listener: ((MeshEvent) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun service(): MeshService = this@MeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("starting"))

        transport = BleTransport(this)
        meshNode = MeshNode(
            originId = Random.nextInt(0, 0x10000),
            transport = transport,
            clock = AndroidClock(),
            scheduler = AndroidScheduler(mainHandler),
            onEvent = { event ->
                mainHandler.post {
                    if (event is MeshEvent.Acknowledged) updateNotification("acknowledged")
                    listener?.invoke(event)
                }
            },
        )

        setupMotionSensing()
        mainHandler.post(roleTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(roleTick)
        sensorManager?.unregisterListener(motionListener)
        transport.stop()
    }

    // --- public API for the bound Activity -------------------------------------------------

    fun sendSos(hazard: HazardCategory, severity: Int, phrase: CannedPhrase = CannedPhrase.NONE): Int {
        val batteryBucket = readBatteryBucket()
        val msgId = meshNode.originateSos(
            hazardCategory = hazard,
            severity = severity,
            location = QuantizedLocation.noFix(), // Stage 1-7 demo scope: no GPS wiring yet
            baroValid = false,
            baroDeltaDeciHpa = 0,
            batteryBucket = batteryBucket,
            livenessBucket = livenessBucket(),
            phrase = phrase,
        )
        lastPendingSosMsgId = msgId
        lastPendingCategory = hazard
        lastPendingPhrase = phrase
        lastSentAtMillis = System.currentTimeMillis()
        updateNotification("broadcasting")
        return msgId
    }

    fun attachPhrase(msgId: Int, phrase: CannedPhrase) {
        meshNode.updateActivePhrase(msgId, phrase)
        if (msgId == lastPendingSosMsgId) lastPendingPhrase = phrase
    }

    fun cancelSos() {
        val msgId = lastPendingSosMsgId ?: return
        meshNode.cancelSos(msgId)
        lastPendingSosMsgId = null
        lastPendingCategory = null
        lastPendingPhrase = CannedPhrase.NONE
        lastSentAtMillis = null
        updateNotification("idle")
    }

    fun setGateway(enabled: Boolean) {
        meshNode.isGateway = enabled
    }

    /** Gateway-only, per MeshNode.originateCommandMessage's own contract -- throws otherwise. */
    fun sendCommandMessage(message: dev.spandan.mesh.CommandMessage) {
        meshNode.originateCommandMessage(message)
    }

    fun setWeightedPropagation(enabled: Boolean) {
        meshNode.severityConfig = if (enabled) SeverityConfig.WEIGHTED else SeverityConfig.NAIVE_FLOODING
    }

    /** Every nearby node heard recently — automatic the moment both sides are running. */
    fun nearbyDevices() = meshNode.nearbyDevices()

    fun snapshot(): MeshSnapshot = MeshSnapshot(
        originId = meshNode.originId,
        role = meshNode.role,
        batteryBucket = readBatteryBucket(),
        neighbourCount = meshNode.neighbourCount(),
        isGateway = meshNode.isGateway,
        weightedPropagation = meshNode.severityConfig === SeverityConfig.WEIGHTED,
        pendingSosMsgId = lastPendingSosMsgId,
        acknowledged = lastPendingSosMsgId?.let { meshNode.isAcknowledged(it) } ?: false,
        activeCategory = lastPendingCategory,
        activePhrase = lastPendingPhrase,
        sentAtMillis = lastSentAtMillis,
    )

    // --- battery / motion sampling for role election ----------------------------------------

    private fun readBatteryBucket(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = registerReceiver(null, filter) ?: return 7
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return 7
        val fraction = level.toFloat() / scale.toFloat()
        return (fraction * 8).toInt().coerceIn(0, 7)
    }

    private fun setupMotionSensing() {
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        significantMotionSensor = sensor
        if (sensor != null) {
            sm.registerListener(motionListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        // No significant-motion sensor on this device: we cannot detect stillness,
        // so we deliberately do not fake a reading — motionlessMillis stays 0
        // (never contributes to a false DEEP_BEACON demotion). Flagged in CLAUDE.md.
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            lastMotionAtMillis = System.currentTimeMillis()
            // TYPE_SIGNIFICANT_MOTION is a one-shot trigger; re-arm it.
            significantMotionSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun livenessBucket(): Int {
        if (significantMotionSensor == null) return 0
        val motionlessMs = System.currentTimeMillis() - lastMotionAtMillis
        return bucketiseMotionless(motionlessMs)
    }

    private fun bucketiseMotionless(ms: Long): Int = when {
        ms < 60_000 -> 0
        ms < 5 * 60_000 -> 1
        ms < 15 * 60_000 -> 2
        ms < 60 * 60_000 -> 3
        ms < 4 * 3_600_000 -> 4
        ms < 12 * 3_600_000 -> 5
        ms < 24 * 3_600_000 -> 6
        else -> 7
    }

    private val roleTick = object : Runnable {
        override fun run() {
            val motionlessMs = if (significantMotionSensor == null) 0L else System.currentTimeMillis() - lastMotionAtMillis
            meshNode.updateRole(
                RoleInputs(
                    batteryBucket = readBatteryBucket(),
                    neighbourCount = meshNode.neighbourCount(),
                    motionlessMillis = motionlessMs,
                )
            )
            mainHandler.postDelayed(this, ROLE_TICK_INTERVAL_MS)
        }
    }

    // --- notification ------------------------------------------------------------------------

    private fun buildNotification(status: String): Notification {
        val channelId = "spandan_mesh"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Spandan mesh", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Spandan mesh active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ROLE_TICK_INTERVAL_MS = 10_000L
    }
}
