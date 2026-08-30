package dev.spandan.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.spandan.app.ui.state.SosStatus

/**
 * Distinct haptic + audio cue per state change, for a user who may not be
 * able to see the screen. Both are independently toggleable (Settings), and
 * both no-op harmlessly if the underlying service isn't available.
 */
class Feedback(context: Context) {
    private val appContext = context.applicationContext
    var hapticsEnabled: Boolean = true
    var audioEnabled: Boolean = true

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun onStatusChanged(status: SosStatus) {
        if (hapticsEnabled) vibrate(status)
        if (audioEnabled) playTone(status)
    }

    private fun vibrate(status: SosStatus) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern: LongArray = when (status) {
            SosStatus.SENDING -> longArrayOf(0, 80)
            SosStatus.RELAYED -> longArrayOf(0, 60, 60, 60)
            SosStatus.ACKNOWLEDGED -> longArrayOf(0, 100, 80, 100, 80, 200) // distinct burst -- the moment
            SosStatus.STALE -> longArrayOf(0, 40)
            SosStatus.IDLE -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    private fun playTone(status: SosStatus) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            val (tone, durationMs) = when (status) {
                SosStatus.SENDING -> ToneGenerator.TONE_PROP_BEEP to 150
                SosStatus.RELAYED -> ToneGenerator.TONE_PROP_BEEP2 to 150
                SosStatus.ACKNOWLEDGED -> ToneGenerator.TONE_PROP_ACK to 400
                SosStatus.STALE -> ToneGenerator.TONE_PROP_NACK to 150
                SosStatus.IDLE -> return
            }
            tg.startTone(tone, durationMs)
        }
    }
}
