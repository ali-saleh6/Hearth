package com.sal7one.transiber.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * Turns the video down while a caption is spoken, then back up.
 *
 * Audio focus alone is not enough: Android cannot duck some players for the
 * app (a browser playing through AAudio, for one), and the spoken line shares
 * the media volume with the video, so lowering media would quiet both. The
 * line therefore plays on the alarm stream at the video's own loudness while
 * the media stream drops. Levels are saved before they change, so a crash or
 * kill mid-line is undone by [recover] on the next start.
 */
internal class MediaDucker(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.applicationContext.getSharedPreferences("media-ducking", Context.MODE_PRIVATE)

    /** Attributes for a line spoken while [duck] holds the video down. */
    val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    val ducked: Boolean get() = prefs.contains(MUSIC_DUCKED)

    /** True when the video was turned down and the line should use [speechAttributes]. */
    fun duck(): Boolean {
        if (ducked) return true
        // A fixed level (HDMI-CEC soundbar, some TVs) cannot be changed from here;
        // muted media leaves nothing to talk over.
        if (audio == null || audio.isVolumeFixed) return false
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
        return runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, voice, 0)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, lowered, 0)
        }.onFailure { restore() }.isSuccess
    }

    /** Put levels back, unless the user changed one in the meantime: their choice wins. */
    fun restore() {
        if (!ducked || audio == null) return
        runCatching {
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == prefs.getInt(MUSIC_DUCKED, -1))
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(MUSIC_BEFORE, 0), 0)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == prefs.getInt(ALARM_SET, -1))
                audio.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt(ALARM_BEFORE, 0), 0)
        }
        prefs.edit().clear().commit()
    }

    /** Undo a duck left behind by a process that died mid-line. */
    fun recover() = restore()

    private companion object {
        /** Share of the video's volume step kept while a line is spoken. */
        const val DUCK_FRACTION = 0.35f
        const val MUSIC_BEFORE = "music-before"
        const val MUSIC_DUCKED = "music-ducked"
        const val ALARM_BEFORE = "alarm-before"
        const val ALARM_SET = "alarm-set"
    }
}
