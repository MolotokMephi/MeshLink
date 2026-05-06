package team.hex.meshlink.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.core.net.toUri
import java.io.File
import java.util.UUID

/**
 * Tiny voice-note recorder built on [MediaRecorder]. Tap-and-hold the
 * mic button to start; release to stop. Output is AAC-in-MP4 at 24 kbps
 * mono, which gets handed to [team.hex.meshlink.files.FileTransfer.offer]
 * exactly like any other file (same chunk pipeline, same encryption).
 *
 * The recorder lives on the foreground service so a quick tap-and-hold
 * doesn't lose the recording when the chat screen recomposes.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    @Volatile var isRecording: Boolean = false
        private set

    /** Begin recording. Returns false if already running or hardware refused. */
    fun start(): Boolean {
        if (isRecording) return false
        val cacheDir = File(context.cacheDir, "voice").apply { mkdirs() }
        val out = File(cacheDir, "vn-${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(24_000)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioChannels(1)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            output = out
            isRecording = true
            true
        }.getOrElse {
            runCatching { rec.release() }
            false
        }
    }

    /** Stop recording. Returns the captured audio file, or null on failure. */
    fun stop(): File? {
        if (!isRecording) return null
        val rec = recorder
        val out = output
        recorder = null
        output = null
        isRecording = false
        return runCatching {
            rec?.stop()
            rec?.release()
            out
        }.getOrNull()
    }

    fun cancel() {
        val rec = recorder
        val out = output
        recorder = null
        output = null
        isRecording = false
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { out?.delete() }
    }
}

/** Convert a recorded [File] into the content URI expected by FileTransfer. */
fun File.asVoiceNoteUri() = toUri()
