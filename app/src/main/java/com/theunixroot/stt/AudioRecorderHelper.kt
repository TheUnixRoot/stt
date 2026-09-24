package com.theunixroot.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderHelper {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)

    @Volatile
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private val pcmDataStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }

            pcmDataStream.reset()
            isRecording = true
            audioRecord?.startRecording()

            Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(pcmDataStream) {
                            pcmDataStream.write(buffer, 0, read)
                        }
                    }
                }
            }.start()

            true
        } catch (e: Exception) {
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            false
        }
    }

    suspend fun stopAndGetWav(): ByteArray = withContext(Dispatchers.IO) {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        val pcmBytes: ByteArray
        synchronized(pcmDataStream) {
            pcmBytes = pcmDataStream.toByteArray()
            pcmDataStream.reset()
        }

        buildWav(pcmBytes, sampleRate, 1, 16)
    }

    private fun buildWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size for PCM
            putShort(1) // AudioFormat 1 = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(totalAudioLen)
        }.array()

        val wavOutput = ByteArrayOutputStream(header.size + pcmData.size)
        wavOutput.write(header)
        wavOutput.write(pcmData)
        return wavOutput.toByteArray()
    }
}
