package dev.spandan.app.ble

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records a short voicenote to a local file. Bulk-payload tier only (Wi-Fi
 * Direct, same-room) -- never fits in a BLE advertisement, same reasoning as
 * canned phrases for the core mesh (see CLAUDE.md).
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File {
        val file = File(context.cacheDir, "voicenote_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        outputFile = file
        return file
    }

    /** Returns the recorded file, or null if nothing was recording. */
    fun stop(): File? {
        val r = recorder ?: return null
        runCatching { r.stop() }
        r.release()
        recorder = null
        return outputFile
    }

    fun cancel() {
        val r = recorder ?: return
        runCatching { r.stop() }
        r.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
