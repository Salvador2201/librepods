package me.kavishdevar.librepods.jimena

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000

/** Writes raw 16-bit mono PCM samples as a playable WAV file. */
fun writeWavFile(pcm: ByteArray, outFile: File, sampleRate: Int = SAMPLE_RATE) {
    FileOutputStream(outFile).use { out ->
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        header[20] = 1; header[21] = 0 // PCM
        header[22] = 1; header[23] = 0 // mono
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        header[32] = 2; header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, pcm.size)
        out.write(header)
        out.write(pcm)
    }
}

private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = (value and 0xff).toByte()
    buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
    buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
    buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
}

/** RMS of a 16-bit little-endian PCM chunk, roughly 0..~1.0 for typical mic levels. */
fun pcmRms(buffer: ByteArray, length: Int): Double {
    if (length <= 0) return 0.0
    var sum = 0.0
    var i = 0
    var count = 0
    while (i + 1 < length) {
        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
        sum += (sample.toDouble() * sample.toDouble())
        i += 2
        count++
    }
    if (count == 0) return 0.0
    return sqrt(sum / count) / 32768.0
}

/**
 * Accumulates PCM chunks for one utterance, ending after [silenceMs] of quiet
 * following at least one loud chunk, or after [maxMs] total.
 */
class UtteranceRecorder(
    private val silenceThreshold: Double = 0.02,
    private val silenceMs: Int = 1100,
    private val maxMs: Int = 15000,
    private val chunkMs: Int = 100,
) {
    private val buffer = ByteArrayOutputStream()
    private var silentFor = 0
    private var heardVoice = false
    private var elapsed = 0

    /** Returns true when the utterance is considered complete. */
    fun feed(chunk: ByteArray, length: Int): Boolean {
        buffer.write(chunk, 0, length)
        elapsed += chunkMs
        val loud = pcmRms(chunk, length) > silenceThreshold
        if (loud) {
            heardVoice = true
            silentFor = 0
        } else if (heardVoice) {
            silentFor += chunkMs
        }
        return (heardVoice && silentFor >= silenceMs) || elapsed >= maxMs
    }

    fun hasVoice() = heardVoice
    fun pcm(): ByteArray = buffer.toByteArray()
}
