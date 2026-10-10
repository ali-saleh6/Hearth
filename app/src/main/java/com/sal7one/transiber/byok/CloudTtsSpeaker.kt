package com.sal7one.transiber.byok

import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import com.sal7one.transiber.caption.CaptionSpeaker
import com.sal7one.transiber.caption.CaptionSpeechQueue
import com.sal7one.transiber.caption.SpeakerGender
import com.sal7one.transiber.caption.toVoiceGender
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

/**
 * OpenAI-compatible /audio/speech client (BYOK TTS).
 *
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 */
class OpenAiSpeechClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "tts-1",
    private val voice: String = "alloy",
) {
    private val client = CloudVoiceClient()
    fun cancel() = client.cancel()
    fun speak(text: String): ByteArray = client.speak(
        CloudVoiceConfig(CloudVoiceProvider.CUSTOM, baseUrl, model, voice), apiKey, text).bytes
}

/**
 * Cloud TTS caption speaker: one utterance at a time, MP3/WAV via MediaPlayer.
 * NETWORK CODE — play distribution only; see [ByokPolicy].
 */
class CloudTtsSpeaker(
    private val apiKey: String,
    private val baseUrl: String = RemoteWhisperEngine.DEFAULT_BASE_URL,
    private val model: String = "tts-1",
    private val voice: String = "alloy",
    private val onError: (String) -> Unit = {},
    private val cacheDirectory: File? = null,
    private val audioManager: AudioManager? = null,
) : CaptionSpeaker {
    private var configuration: () -> CloudVoiceConfig = { CloudVoiceConfig(CloudVoiceProvider.CUSTOM, baseUrl, model, voice) }
    private var credential: (CloudVoiceConfig) -> String = { apiKey }
    private var onActivity: (Boolean) -> Unit = {}
    /** Set for captions over a video: the video is turned down while a line plays. */
    private var ducker: com.sal7one.transiber.voice.MediaDucker? = null
    private var restoreVolume: Job? = null
    internal constructor(configuration: () -> CloudVoiceConfig, credential: (CloudVoiceConfig) -> String,
        onError: (String) -> Unit, cacheDirectory: File?, audioManager: AudioManager?, onActivity: (Boolean) -> Unit = {},
        ducker: com.sal7one.transiber.voice.MediaDucker? = null) :
        this("", onError = onError, cacheDirectory = cacheDirectory, audioManager = audioManager) {
        this.configuration = configuration; this.credential = credential; this.onActivity = onActivity
        this.ducker = ducker?.also { it.recover() }
    }
    private data class Request(val text: String, val config: CloudVoiceConfig, val volume: Float)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = CaptionSpeechQueue<Request>(scope, onError, ::playOnce)
    @Volatile private var client: CloudVoiceClient? = null
    private var player: MediaPlayer? = null
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { change ->
            // A video player taking focus back is not a reason to cut a line off.
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()

    override fun speak(text: String, languageTag: String): Boolean =
        speak(text, languageTag, SpeakerGender.ANY, 100)

    override fun speak(text: String, languageTag: String, gender: SpeakerGender, volumePercent: Int): Boolean {
        if (text.isBlank()) return false
        if (text.length > 5000) {
            onError("Read aloud accepts at most 5000 characters")
            return false
        }
        val config = try { configuration().let { it.copy(voice = it.selectedVoice(gender.toVoiceGender())).validated() } }
        catch (e: Exception) { onError(e.message ?: e.toString()); return false }
        val request = Request(text, config, volumePercent.coerceIn(0, 100) / 100f)
        val accepted = if (ducker != null) queue.offerLatest(request) else queue.offer(request)
        if (!accepted) onError("Speech queue is full or closed. Skipped a new line to avoid falling behind.")
        return accepted
    }

    private suspend fun playOnce(request: Request) {
        val requestClient = CloudVoiceClient()
        client = requestClient
        onActivity(true)
        try {
            val audio = withContext(Dispatchers.IO) { requestClient.speak(request.config, credential(request.config), request.text) }
            currentCoroutineContext().ensureActive()
            withCloudVoiceFile(cacheDirectory, audio) { tmp ->
                var mp: MediaPlayer? = null
                var focusGranted = false
                try {
                    currentCoroutineContext().ensureActive()
                    audioManager?.let {
                        check(it.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            "Android could not grant audio focus for read aloud"
                        }
                        focusGranted = true
                    }
                    val output = MediaPlayer().also { mp = it; player = it }
                    // Read-aloud audio must not enter our USAGE_MEDIA playback capture;
                    // the ducked alarm stream is not captured either.
                    restoreVolume?.cancel()
                    output.setAudioAttributes(ducker?.takeIf { it.duck() }?.speechAttributes ?: audioAttributes)
                    output.setDataSource(tmp.absolutePath)
                    output.setVolume(request.volume, request.volume)
                    withTimeout(120_000) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            output.setOnPreparedListener {
                                if (continuation.isActive) try { it.start() }
                                catch (e: Exception) { continuation.resumeWithException(e) }
                            }
                            output.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                            output.setOnErrorListener { _, what, extra ->
                                if (continuation.isActive) continuation.resumeWithException(
                                    IllegalStateException("Android voice playback failed: $what / $extra"))
                                true
                            }
                            output.prepareAsync()
                        }
                    }
                } finally {
                    player = null
                    // Lines follow each other; wait before turning the video back up.
                    ducker?.let { d -> restoreVolume = scope.launch { delay(RESTORE_DELAY_MS); d.restore() } }
                    try {
                        mp?.release()
                    } finally {
                        if (focusGranted) audioManager?.abandonAudioFocusRequest(focus)
                    }
                }
            }
        } finally {
            if (client === requestClient) client = null
            requestClient.cancel()
            onActivity(false)
        }
    }

    override fun stop() {
        queue.stop()
        client?.cancel()
        restoreVolume?.cancel()
        ducker?.restore()
    }

    private companion object { const val RESTORE_DELAY_MS = 700L }

    override fun release() {
        stop()
        queue.close()
        scope.cancel()
    }
}
