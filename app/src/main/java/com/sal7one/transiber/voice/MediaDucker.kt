package com.sal7one.transiber.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Turns the video down while a caption is spoken, then fades it back up.
 *
 * Audio focus alone is not enough: Android cannot duck some players for the
 * app (a browser playing through AAudio, for one), and the spoken line shares
 * the media volume with the video, so lowering media would quiet both. The
 * line therefore plays on the alarm stream at the video's own loudness while
 * the media stream drops. Levels are saved before they change, so a crash or
 * kill mid-line is undone by [recover] on the next start.
 *
 * Volume moves one step at a time: a jump back to full level after every line
 * sounds like a burst of noise.
 */
internal class MediaDucker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.applicationContext.getSharedPreferences("media-ducking", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    /** The media level this ducker last set; anything else means the user moved it. */
    private var expected = -1
    private var fade: Runnable? = null

    /** Attributes for a line spoken while [duck] holds the video down. */
    val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    val ducked: Boolean get() = prefs.contains(MUSIC_DUCKED)

    /** True when the video was turned down and the line should use [speechAttributes]. */
    fun duck(): Boolean = synchronized(lock) {
        if (audio == null) return false
        if (ducked) {
            // A new line arrived while the video was fading back up: go down again.
            if (fade == null) return true
            val lowered = prefs.getInt(MUSIC_DUCKED, 1)
            return runCatching { fadeTo(lowered, DOWN_STEP_MS) {} }.isSuccess
        }
        // A fixed level (HDMI-CEC soundbar, some TVs) cannot be changed from here;
        // muted media leaves nothing to talk over.
        if (audio.isVolumeFixed) return false
        val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (music <= 0) return false
        val musicMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val alarmMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val voice = (music.toFloat() / musicMax * alarmMax).roundToInt()
            .coerceIn(audio.getStreamMinVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1), alarmMax)
        val lowered = (music * DUCK_FRACTION).roundToInt().coerceIn(1, music)
        // Save first: whatever happens next, the original levels can be put back.
        check(prefs.edit().putInt(MUSIC_BEFORE, music).putInt(MUSIC_DUCKED, lowered)
            .putInt(ALARM_BEFORE, alarm).putInt(ALARM_SET, voice).commit()) { "Could not save volume before ducking" }
        expected = music
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, voice, 0)
            fadeTo(lowered, DOWN_STEP_MS) {}
        }.onFailure { restoreNow() }.isSuccess
    }

    /** Fade the video back up, unless the user changed the level meanwhile: their choice wins. */
    fun restore() = synchronized(lock) {
        if (!ducked || audio == null) return
        fadeTo(prefs.getInt(MUSIC_BEFORE, 0), UP_STEP_MS) { finish() }
    }

    /** Undo a duck left behind by a process that died mid-line, without a fade. */
    fun recover() = synchronized(lock) { restoreNow() }

    private fun restoreNow() {
        cancelFade()
        if (!ducked || audio == null) return
        runCatching {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == prefs.getInt(MUSIC_DUCKED, -1) || current == expected)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(MUSIC_BEFORE, 0), 0)
        }
        finish()
    }

    private fun finish() {
        runCatching {
            if (audio?.getStreamVolume(AudioManager.STREAM_ALARM) == prefs.getInt(ALARM_SET, -1))
                audio.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt(ALARM_BEFORE, 0), 0)
        }
        prefs.edit().clear().commit()
        expected = -1
    }

    /** Step the media level toward [target], one index per [stepMs]. */
    private fun fadeTo(target: Int, stepMs: Long, done: () -> Unit) {
        val manager = audio ?: return
        cancelFade()
        val step = object : Runnable {
            override fun run() { synchronized(lock) {
                if (fade !== this) return
                val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (expected >= 0 && current != expected) {
                    // The remote changed the volume mid-fade: keep it, forget ours.
                    fade = null; finish(); return
                }
                if (current == target) { fade = null; done(); return }
                val next = if (current < target) current + 1 else current - 1
                runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
                expected = next
                main.postDelayed(this, stepMs)
            } }
        }
        fade = step
        if (expected < 0) expected = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        main.post(step)
    }

    private fun cancelFade() {
        fade?.let(main::removeCallbacks)
        fade = null
    }

    private companion object {
        /** Share of the video's volume step kept while a line is spoken. */
        const val DUCK_FRACTION = 0.20f
        /** Quick on the way down so the line starts clear, gentle on the way back. */
        const val DOWN_STEP_MS = 40L
        const val UP_STEP_MS = 160L
        const val MUSIC_BEFORE = "music-before"
        const val MUSIC_DUCKED = "music-ducked"
        const val ALARM_BEFORE = "alarm-before"
        const val ALARM_SET = "alarm-set"
    }
}
