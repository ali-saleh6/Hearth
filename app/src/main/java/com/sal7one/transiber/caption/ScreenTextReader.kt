package com.sal7one.transiber.caption

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.util.Log
import com.sal7one.transiber.BuildConfig
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrModels
import com.sal7one.transiber.ocr.OcrStability
import com.sal7one.transiber.ocr.OcrText
import java.io.File
import kotlin.math.abs

/**
 * Reads the subtitle strip off the screen instead of listening to audio.
 *
 * The player's own captions beat on-device recognition on a TV-class CPU, they
 * arrive already punctuated and sentence-segmented, which translates better
 * than raw recognizer output, and this path does not depend on playback
 * capture, which an app can refuse.
 *
 * Cost control matters on a 32-bit panel, so a frame is only recognized when
 * the strip actually changed, and never more often than [minIntervalMs].
 */
internal class ScreenTextReader(
    private val context: Context,
    private val projection: MediaProjection,
    private val profileId: String,
    /** Share of screen height, measured from the bottom, that holds subtitles. */
    private val stripFraction: Float = 0.4f,
    private val minIntervalMs: Long = 400,
    /** Text and its best-guess language tag, or null when it cannot be told. */
    private val onText: (String, String?) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("hearth-screen-text").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var engine: OcrEngine? = null
    private val stability = OcrStability()
    private val arabic = profileId == "arabic"
    @Volatile private var closed = false
    private var busy = false
    private var lastRunMs = 0L
    private var lastSignature = 0
    /** Last reading passed on; the next one only adds what scrolled in after it. */
    private var lastSpoken: String? = null
    private val audio = context.getSystemService(android.media.AudioManager::class.java)

    fun start() {
        handler.post {
            if (closed) return@post
            try {
                val profile = OcrCatalog.profile(profileId)
                engine = OcrModels(File(context.filesDir, "ocr-models")).open(context, profile)
                val metrics = context.resources.displayMetrics
                // Capture below panel resolution: subtitles stay legible well
                // under 4K and every extra pixel is paid for twice, in the
                // copy and in detection.
                val scale = minOf(1f, CAPTURE_WIDTH / metrics.widthPixels.toFloat())
                val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
                val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 1.inv()
                val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                images.setOnImageAvailableListener({ source -> drain(source) }, handler)
                reader = images
                display = projection.createVirtualDisplay("Hearth captions", width, height,
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.surface, null, handler)
                Log.i(TAG, "Reading $profileId captions at ${width}x$height")
            } catch (e: Exception) { onError(e.message ?: e.toString()) }
            catch (e: LinkageError) { onError(e.message ?: e.toString()) }
        }
    }

    private fun drain(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            // Captions belong to a playing video; menus and the home screen
            // have text too, and silence means nothing worth reading.
            if (busy || now - lastRunMs < minIntervalMs) return
            if (hearthOnScreen() || audio?.isMusicActive == false) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipped frame: hearth=${hearthOnScreen()} music=${audio?.isMusicActive}")
                return
            }
            val plane = image.planes[0]
            if (plane.pixelStride != 4) { onError("Unsupported screen pixel stride ${plane.pixelStride}"); return }
            // rowStride can exceed the visible width; copy the padded row and
            // read back only the pixels that are really on screen.
            val padded = plane.rowStride / plane.pixelStride
            val frame = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
            frame.copyPixelsFromBuffer(plane.buffer)
            val top = ((1f - stripFraction) * image.height).toInt().coerceIn(0, image.height - 1)
            val inset = (image.width * EDGE_INSET).toInt()
            val cropWidth = (image.width - inset * 2).coerceAtLeast(1)
            val cropHeight = (image.height - top).coerceAtLeast(1)
            val pixels = IntArray(cropWidth * cropHeight)
            frame.getPixels(pixels, 0, cropWidth, inset, top, cropWidth, cropHeight)
            frame.recycle()
            val signature = signature(pixels, cropWidth, cropHeight)
            if (signature == lastSignature) return
            if (lastSignature == 0) Log.i(TAG, "First screen frame ${image.width}x${image.height}")
            lastSignature = signature
            lastRunMs = now
            busy = true
            recognize(pixels, cropWidth, cropHeight)
        } finally { image.close() }
    }

    private fun recognize(pixels: IntArray, width: Int, height: Int) {
        try {
            val began = SystemClock.elapsedRealtime()
            val lines = engine?.recognize(pixels, width, height) ?: return
            val confident = lines.filter { it.confidence >= MIN_CONFIDENCE && it.text.isNotBlank() }
            // The player labels ads in the same strip as the subtitles; an ad's
            // own captions are not the video, so nothing is read until it ends.
            confident.firstOrNull { AD_LABEL.containsMatchIn(it.text.trim()) }?.let { badge ->
                if (BuildConfig.DEBUG) Log.d(TAG, "Ad on screen (${badge.text}); not reading")
                return
            }
            // Reading order: top band first, then left to right within a band.
            val band = (height / 8).coerceAtLeast(1)
            val text = confident.asSequence()
                // Player subtitles are centred; titles, time labels and side
                // panels that share the bottom of the screen are not.
                .filter { abs(it.x + it.width / 2 - width / 2) <= width * CENTRE_TOLERANCE }
                // Stray glyphs from the picture ("2", "O") are not speech.
                .filter { line -> line.text.count(Char::isLetter) >= MIN_LETTERS }
                // Logos and thumbnails share the frame with Arabic lines too.
                .filter { line -> !isArtwork(line.text) }
                // Players draw subtitles as light text on a dark box; text in
                // thumbnails and artwork under the player rarely looks like that.
                .filter { line -> looksLikeSubtitle(pixels, width, height, line) }
                .sortedWith(compareBy({ it.y / band }, { it.x }))
                .joinToString(" ") { OcrText.logical(it.text.trim(), arabic) }
                .trim()
            if (BuildConfig.DEBUG) Log.d(TAG, "OCR ${SystemClock.elapsedRealtime() - began}ms, ${lines.size} lines: $text")
            if (text.isEmpty() || isSoundLabel(text) || !stability.observe(text, captured = false)) return
            val fresh = CaptionDelta.newPart(lastSpoken, text) ?: return
            lastSpoken = text
            collect(fresh)
        } catch (e: Exception) { onError(e.message ?: e.toString()) }
        catch (e: LinkageError) { onError(e.message ?: e.toString()) }
        finally { busy = false }
    }

    /**
     * Captions carry no language metadata, and the translator and voice both
     * need one. Script settles most languages outright; Latin text needs the
     * platform's on-device classifier to tell English from French.
     */
    private fun language(text: String): String? {
        val scripts = text.codePoints().toArray().asSequence().filter(Character::isLetter)
            .map(Character.UnicodeScript::of).groupingBy { it }.eachCount()
        val script = scripts.maxByOrNull { it.value }?.key ?: return null
        SCRIPT_LANGUAGES[script]?.let { return it }
        if (script != Character.UnicodeScript.LATIN || android.os.Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
            val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
            (0 until result.localeHypothesisCount).map(result::getLocale)
                .maxByOrNull(result::getConfidenceScore)
                ?.takeIf { result.getConfidenceScore(it) >= MIN_LANGUAGE_CONFIDENCE }
                ?.toLanguageTag()?.substringBefore('-')
        }.getOrNull()
    }

    /**
     * Subtitles split sentences across screens ("I just wanted to enjoy that
     * one moment" / "when all of you thought,"). A whole sentence translates
     * and sounds better, and costs one cloud request instead of two.
     */
    private val sentence = StringBuilder()
    private var sentenceParts = 0
    private val flushSentence = Runnable { flush() }

    private fun collect(fragment: String) {
        if (sentence.isNotEmpty()) sentence.append(' ')
        sentence.append(fragment)
        sentenceParts++
        handler.removeCallbacks(flushSentence)
        if (SENTENCE_END.containsMatchIn(fragment) || sentenceParts >= MAX_SENTENCE_PARTS || sentence.length >= MAX_SENTENCE_CHARS) flush()
        else handler.postDelayed(flushSentence, SENTENCE_WAIT_MS)
    }

    private fun flush() {
        handler.removeCallbacks(flushSentence)
        val text = sentence.toString().trim()
        sentence.clear(); sentenceParts = 0
        if (text.isNotEmpty() && !closed) onText(text, language(text))
    }

    private fun isSoundLabel(text: String) = text.replace(BRACKETED, "").count(Char::isLetter) < MIN_SPEECH_LETTERS

    /** Short all-capitals Latin text is a title or thumbnail ("THE LAST OF US"), not a subtitle. */
    private fun isArtwork(text: String): Boolean {
        val latin = text.filter { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        return latin.length >= MIN_SPEECH_LETTERS && latin.none(Char::isLowerCase) && text.length < MAX_ARTWORK_CHARS
    }

    private fun looksLikeSubtitle(pixels: IntArray, width: Int, height: Int, line: com.sal7one.common_jni.ocr.OcrLine): Boolean {
        val left = line.x.coerceIn(0, width - 1); val right = (line.x + line.width).coerceIn(left + 1, width)
        val top = line.y.coerceIn(0, height - 1); val bottom = (line.y + line.height).coerceIn(top + 1, height)
        var dark = 0; var light = 0; var total = 0
        val step = maxOf(1, (right - left) * (bottom - top) / 4000)
        var i = 0
        for (y in top until bottom) for (x in left until right) {
            if (i++ % step != 0) continue
            val pixel = pixels[y * width + x]
            val grey = ((pixel shr 16 and 0xFF) * 3 + (pixel shr 8 and 0xFF) * 6 + (pixel and 0xFF)) / 10
            if (grey < DARK) dark++ else if (grey > LIGHT) light++
            total++
        }
        return total > 0 && dark * 10 >= total * 3 && light * 20 >= total
    }

    /** Hearth's own screens are not the player; never read them as captions. */
    private fun hearthOnScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * Coarse greyscale fingerprint of the strip. Subtitles hold still for
     * seconds at a time, so this skips recognition on unchanged frames, and
     * dropping the low bits keeps video noise behind the text from looking
     * like a new caption.
     */
    private fun signature(pixels: IntArray, width: Int, height: Int): Int {
        var hash = 17
        val stepX = (width / 48).coerceAtLeast(1)
        val stepY = (height / 16).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = pixels[y * width + x]
                val grey = ((pixel shr 16 and 0xFF) * 3 + (pixel shr 8 and 0xFF) * 6 + (pixel and 0xFF)) / 10
                hash = hash * 31 + (grey shr 4)
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    override fun close() {
        closed = true
        handler.post {
            runCatching { engine?.cancel() }
            runCatching { engine?.close() }
            engine = null
            runCatching { display?.release() }
            display = null
            runCatching { reader?.close() }
            reader = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "HearthScreenText"
        const val CAPTURE_WIDTH = 1280f
        const val EDGE_INSET = 0.04f
        const val MIN_CONFIDENCE = 0.5f
        const val CENTRE_TOLERANCE = 0.15f
        const val MIN_LETTERS = 2
        /** Greyscale bounds of a subtitle box's background and its text. */
        const val DARK = 70
        const val LIGHT = 180
        const val MIN_LANGUAGE_CONFIDENCE = 0.5f
        /** YouTube's ad badges and buttons, in English and Arabic interfaces. */
        val AD_LABEL = Regex("(?i)^sponsored\\b|^(skip|skip ads?|visit advertiser)$|^ad(\\s*[·•]|\\s+\\d+\\s+of\\s+\\d+)|^إعلان|^مُ?مو[َّ]*ل")
        /** "(Applause)", "[Music]" and OCR crumbs around them leave fewer letters than this. */
        const val MIN_SPEECH_LETTERS = 3
        const val MAX_ARTWORK_CHARS = 24
        val BRACKETED = Regex("[(\\[][^)\\]]*[)\\]]")
        /** A fragment ending a sentence is spoken now; others wait briefly for the rest. */
        val SENTENCE_END = Regex("[.!?…。؟]['\"”’)]*$")
        const val SENTENCE_WAIT_MS = 2_500L
        const val MAX_SENTENCE_PARTS = 3
        const val MAX_SENTENCE_CHARS = 220
        /** Scripts used by essentially one language each. */
        val SCRIPT_LANGUAGES = mapOf(
            Character.UnicodeScript.ARABIC to "ar", Character.UnicodeScript.HEBREW to "he",
            Character.UnicodeScript.GREEK to "el", Character.UnicodeScript.THAI to "th",
            Character.UnicodeScript.HANGUL to "ko", Character.UnicodeScript.HIRAGANA to "ja",
            Character.UnicodeScript.KATAKANA to "ja", Character.UnicodeScript.HAN to "zh",
            Character.UnicodeScript.DEVANAGARI to "hi", Character.UnicodeScript.CYRILLIC to "ru",
        )
    }
}
