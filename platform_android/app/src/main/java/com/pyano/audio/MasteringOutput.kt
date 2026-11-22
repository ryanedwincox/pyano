// Concern: sends preview PCM to Android audio | Non-concern: mastering decisions | IO: (stereo samples) -> AudioTrack
package com.pyano.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

internal interface MasteringOutput {
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun write(samples: ShortArray, count: Int)
}
internal class AndroidMasteringOutput(sampleRate: Int) : MasteringOutput {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    override fun play() { track.play() }
    override fun pause() { track.pause() }
    override fun flush() { track.flush() }
    override fun stop() { track.stop() }
    override fun release() { track.release() }
    override fun write(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val written = track.write(samples, offset, count - offset)
            check(written > 0) { "Audio preview output stopped. Try playing again." }
            offset += written
        }
    }
}
