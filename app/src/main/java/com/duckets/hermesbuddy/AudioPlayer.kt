package com.duckets.hermesbuddy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * Plays server audio streams (audio.start -> PCM16 binary frames -> audio.end)
 * through AudioTrack. A new audio.start replaces whatever is playing.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var track: AudioTrack? = null

    @Volatile var activeStream: Int = -1
        private set

    val isPlaying: Boolean get() = activeStream >= 0

    @Synchronized
    fun startStream(stream: Int, rate: Int) {
        stopStream()
        try {
            val safeRate = if (rate in 8000..48000) rate else 16000
            val minBuf = AudioTrack.getMinBufferSize(
                safeRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(safeRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, safeRate * 2)) // ~1s jitter buffer
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.play()
            activeStream = stream
        } catch (e: Exception) {
            Log.w(TAG, "startStream failed", e)
            activeStream = -1
        }
    }

    @Synchronized
    fun write(stream: Int, payload: ByteArray) {
        if (stream != activeStream) return
        try {
            track?.write(payload, 0, payload.size)
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
        }
    }

    @Synchronized
    fun stopStream() {
        val t = track
        track = null
        activeStream = -1
        t?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
    }
}

/** Android TextToSpeech fallback: speaks `reply` text when the server sends no audio stream. */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                try {
                    tts?.language = Locale.US
                } catch (_: Exception) {
                }
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-reply")
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
