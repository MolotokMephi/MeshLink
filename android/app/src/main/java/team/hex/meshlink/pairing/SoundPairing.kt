package team.hex.meshlink.pairing

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Acoustic pairing fallback for environments with no camera, no NFC and
 * no shared LAN — emit the [PairingPayload] string as a sequence of
 * audible tones, decode on the receiver.
 *
 * Encoding: 4-FSK at 16 kHz mono PCM. Two bits per symbol mapped to the
 * frequencies {F0=1500Hz, F1=2100Hz, F2=2700Hz, F3=3300Hz}; one symbol
 * = 100ms of tone. A 50ms pre-amble of F0+F3 marks the start of frame.
 *
 * The wire is wrapped with a length byte and a 2-byte CRC-16 so a
 * partial recording either decodes cleanly or fails fast. Bandwidth is
 * ~20 chars/sec — roughly 6 seconds for an 80-char pairing payload.
 *
 * Detection uses the Goertzel single-frequency filter, which is several
 * times cheaper than a full FFT and lets us run the decoder on phones
 * down to API 26 without breaking a sweat.
 */
object SoundPairing {

    private const val SAMPLE_RATE = 16_000
    private const val SYMBOL_MS = 100
    private val SAMPLES_PER_SYMBOL = SAMPLE_RATE * SYMBOL_MS / 1000
    private val TONES = floatArrayOf(1500f, 2100f, 2700f, 3300f)
    private const val PREAMBLE_MS = 50
    private const val POSTAMBLE_MS = 50

    /** Emit [text] as audible tones via the speaker. Blocks until done. */
    fun emit(text: String) {
        val frame = framePayload(text.toByteArray(Charsets.UTF_8))
        val track = newTrack() ?: return
        try {
            track.play()
            // Pre-amble: alternate F0/F3 for 100ms each.
            track.write(tone(TONES[0], PREAMBLE_MS), 0, samples(PREAMBLE_MS))
            track.write(tone(TONES[3], PREAMBLE_MS), 0, samples(PREAMBLE_MS))
            for (b in frame) {
                val symbols = byteToSymbols(b)
                for (s in symbols) {
                    track.write(tone(TONES[s], SYMBOL_MS), 0, SAMPLES_PER_SYMBOL)
                }
            }
            // Post-amble: silence so the AudioTrack drains cleanly.
            track.write(ShortArray(samples(POSTAMBLE_MS)), 0, samples(POSTAMBLE_MS))
            track.stop()
        } finally {
            runCatching { track.release() }
        }
    }

    /**
     * Listen for a single pairing frame. Returns the decoded UTF-8 text
     * or null on timeout / CRC mismatch. [timeoutMs] caps how long we
     * spend trying.
     */
    @Suppress("MissingPermission")
    fun listen(timeoutMs: Long = 30_000L): String? {
        val rec = newRecord() ?: return null
        try {
            rec.startRecording()
            val deadline = System.currentTimeMillis() + timeoutMs
            val pre = ShortArray(samples(PREAMBLE_MS))
            val sym = ShortArray(SAMPLES_PER_SYMBOL)
            // Hunt for the F0→F3 preamble.
            var locked = false
            while (!locked && System.currentTimeMillis() < deadline) {
                read(rec, pre)
                if (closestTone(pre) == 0) {
                    read(rec, pre)
                    if (closestTone(pre) == 3) locked = true
                }
            }
            if (!locked) return null
            // Read length byte (4 symbols), then payload+CRC.
            val symbolStream = ArrayList<Int>(1024)
            val deadline2 = System.currentTimeMillis() + (timeoutMs / 2).coerceAtLeast(2_000L)
            // First read 4 symbols for length.
            for (i in 0 until 4) {
                read(rec, sym); symbolStream.add(closestTone(sym))
            }
            val length = bytesFromSymbols(symbolStream).getOrNull(0)?.toInt()?.and(0xFF) ?: return null
            // Then length bytes + 2-byte CRC = (length + 2) * 4 symbols.
            val remainingSymbols = (length + 2) * 4
            for (i in 0 until remainingSymbols) {
                if (System.currentTimeMillis() >= deadline2) return null
                read(rec, sym); symbolStream.add(closestTone(sym))
            }
            val bytes = bytesFromSymbols(symbolStream)
            return decodeFrame(bytes)
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    // ------- framing -------

    private fun framePayload(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) { "sound-pairing payload limited to 255 bytes" }
        val frame = ByteArray(1 + payload.size + 2)
        frame[0] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 1, payload.size)
        val crc = crc16(payload)
        frame[1 + payload.size] = (crc ushr 8 and 0xFF).toByte()
        frame[1 + payload.size + 1] = (crc and 0xFF).toByte()
        return frame
    }

    private fun decodeFrame(bytes: List<Byte>): String? {
        if (bytes.size < 3) return null
        val length = bytes[0].toInt() and 0xFF
        if (bytes.size < length + 3) return null
        val payload = bytes.subList(1, 1 + length).toByteArray()
        val expected = ((bytes[1 + length].toInt() and 0xFF) shl 8) or
            (bytes[1 + length + 1].toInt() and 0xFF)
        val actual = crc16(payload)
        if (expected != actual) return null
        return String(payload, Charsets.UTF_8)
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun byteToSymbols(byte: Byte): IntArray {
        val v = byte.toInt() and 0xFF
        return intArrayOf((v shr 6) and 3, (v shr 4) and 3, (v shr 2) and 3, v and 3)
    }

    private fun bytesFromSymbols(symbols: List<Int>): List<Byte> {
        val out = ArrayList<Byte>(symbols.size / 4)
        var i = 0
        while (i + 4 <= symbols.size) {
            val v = (symbols[i] shl 6) or (symbols[i + 1] shl 4) or
                (symbols[i + 2] shl 2) or symbols[i + 3]
            out.add(v.toByte())
            i += 4
        }
        return out
    }

    // ------- DSP -------

    private fun tone(freq: Float, durationMs: Int): ShortArray {
        val n = samples(durationMs)
        val out = ShortArray(n)
        val twoPiF = 2.0 * PI * freq / SAMPLE_RATE
        for (i in 0 until n) {
            // 12% Hann window over the first/last 8ms to avoid clicks.
            val edge = (SAMPLE_RATE * 0.008).toInt()
            val gain = when {
                i < edge -> 0.5 * (1 - cos(PI * i / edge))
                i > n - edge -> 0.5 * (1 - cos(PI * (n - i) / edge))
                else -> 1.0
            }
            out[i] = ((sin(twoPiF * i) * gain) * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        return out
    }

    /** Returns the index of the strongest tone in [TONES] (Goertzel). */
    private fun closestTone(samples: ShortArray): Int {
        var best = 0; var bestPower = -1.0
        for (i in TONES.indices) {
            val p = goertzel(samples, TONES[i])
            if (p > bestPower) { bestPower = p; best = i }
        }
        return best
    }

    private fun goertzel(samples: ShortArray, freq: Float): Double {
        val n = samples.size
        val k = (0.5 + n * freq / SAMPLE_RATE).toInt()
        val w = 2.0 * PI * k / n
        val cosine = cos(w)
        val coeff = 2.0 * cosine
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (s in samples) {
            s0 = (s.toDouble() / Short.MAX_VALUE) + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun samples(ms: Int): Int = SAMPLE_RATE * ms / 1000

    private fun newTrack(): AudioTrack? = runCatching {
        val bufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLES_PER_SYMBOL * 2 * 4)
        AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }.getOrNull()

    @Suppress("MissingPermission")
    private fun newRecord(): AudioRecord? = runCatching {
        val bufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLES_PER_SYMBOL * 2 * 4)
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
    }.getOrNull()

    private fun read(rec: AudioRecord, into: ShortArray): Int {
        var off = 0
        while (off < into.size) {
            val r = rec.read(into, off, into.size - off)
            if (r <= 0) return off
            off += r
        }
        return off
    }
}
