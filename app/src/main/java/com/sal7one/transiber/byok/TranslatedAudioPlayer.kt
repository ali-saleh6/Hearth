package com.sal7one.transiber.byok

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import com.sal7one.transiber.voice.MediaDucker

/**
 * Plays the translated speech a live translation session streams back, with
 * the video turned down underneath it.
 *
 * The voice must not reach playback capture: capture records media and game
 * audio, so a voice on the media stream would be sent back to the translator
 * as more speech. The alarm stream used while ducking is never captured; when
 * the volume cannot be changed it falls back to navigation guidance, which is
 * not captured either.
 */
internal class TranslatedAudioPlayer(context: Context, private val enabled: () -> Boolean) : AutoCloseable {
    private val ducker = MediaDucker(context).also { it.recover() }
    private val thread = HandlerThread("hearth-translated-audio").apply { start() }
    private val handler = Handler(thread.looper)
    private var track: AudioTrack? = null
    private val restore = Runnable { stopTrack(); ducker.restore() }
    @Volatile private var closed = false

    /** PCM16 mono little-endian at [SAMPLE_RATE], as the translation session sends it. */
    fun write(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        handler.post {
            if (closed) return@post
            if (!enabled()) { silence(); return@post }
            handler.removeCallbacks(restore)
            val output = track ?: open().also { track = it }
            output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            // The session streams speech in bursts; once it stops, let the
            // buffered tail play out, then bring the video back.
            handler.postDelayed(restore, RESTORE_AFTER_MS)
        }
    }

    /** Drop what is buffered and bring the video back now. */
    fun silence() {
        handler.post { handler.removeCallbacks(restore); stopTrack(); ducker.restore() }
    }

    private fun open(): AudioTrack {
        val attributes = if (ducker.duck()) ducker.speechAttributes else android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE)).build()
            .also { check(it.state == AudioTrack.STATE_INITIALIZED) { "Translated audio output failed to start" }; it.play() }
    }

    private fun stopTrack() {
        track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        track = null
    }

    override fun close() {
        closed = true
        handler.post { handler.removeCallbacks(restore); stopTrack(); ducker.restore(); thread.quitSafely() }
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        /** Silence after the last chunk before the video comes back up. */
        const val RESTORE_AFTER_MS = 1_200L
    }
}
