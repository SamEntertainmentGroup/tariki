package com.dztrafic.carte

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Alarme courte, forte et stridente : trois salves d'une sirène à deux tons, en son carré. */
object AlertSound {

    private const val SAMPLE_RATE = 22050
    private const val HIGH_HZ = 2600.0
    private const val LOW_HZ = 1700.0
    private const val SWITCH_MS = 60
    private const val BURST_MS = 240
    private const val GAP_MS = 70
    private const val BURSTS = 3
    private const val AMPLITUDE = 0.95

    private val samples: ShortArray by lazy { buildSamples() }

    private fun buildSamples(): ShortArray {
        val burstSamples = SAMPLE_RATE * BURST_MS / 1000
        val gapSamples = SAMPLE_RATE * GAP_MS / 1000
        val switchSamples = SAMPLE_RATE * SWITCH_MS / 1000
        val out = ShortArray((burstSamples + gapSamples) * BURSTS)
        var index = 0
        repeat(BURSTS) {
            for (i in 0 until burstSamples) {
                val frequency = if ((i / switchSamples) % 2 == 0) HIGH_HZ else LOW_HZ
                val wave = sin(2.0 * PI * frequency * i / SAMPLE_RATE)
                val square = if (wave >= 0.0) 1.0 else -1.0
                out[index++] = (square * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
            }
            index += gapSamples
        }
        return out
    }

    fun play() {
        Thread {
            val data = samples
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(data.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            } catch (ignored: Exception) {
                return@Thread
            }
            try {
                track.write(data, 0, data.size)
                track.setVolume(1f)
                track.play()
                Thread.sleep(data.size * 1000L / SAMPLE_RATE + 150L)
            } catch (ignored: Exception) {
                // Lecture impossible : pas de son, mais l'alerte visuelle reste affichée.
            } finally {
                track.release()
            }
        }.start()
    }
}
