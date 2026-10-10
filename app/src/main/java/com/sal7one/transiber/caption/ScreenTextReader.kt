package com.sal7one.transiber.caption

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
import com.sal7one.common_jni.ocr.OcrEngine
import com.sal7one.transiber.ocr.OcrCatalog
import com.sal7one.transiber.ocr.OcrModels
import com.sal7one.transiber.ocr.OcrStability
import com.sal7one.transiber.ocr.OcrText
import java.io.File

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
    private val stripFraction: Float = 0.3f,
    private val minIntervalMs: Long = 400,
    private val onText: (String) -> Unit,
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
            } catch (e: Exception) { onError(e.message ?: e.toString()) }
            catch (e: LinkageError) { onError(e.message ?: e.toString()) }
        }
    }

    private fun drain(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        try {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            if (busy || now - lastRunMs < minIntervalMs) return
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
            lastSignature = signature
            lastRunMs = now
            busy = true
            recognize(pixels, cropWidth, cropHeight)
        } finally { image.close() }
    }

    private fun recognize(pixels: IntArray, width: Int, height: Int) {
        try {
            val lines = engine?.recognize(pixels, width, height) ?: return
            // Reading order: top band first, then left to right within a band.
            val band = (height / 8).coerceAtLeast(1)
            val text = lines.asSequence()
                .filter { it.confidence >= MIN_CONFIDENCE && it.text.isNotBlank() }
                .sortedWith(compareBy({ it.y / band }, { it.x }))
                .joinToString(" ") { OcrText.logical(it.text.trim(), arabic) }
                .trim()
            if (text.isNotEmpty() && stability.observe(text, captured = false)) onText(text)
        } catch (e: Exception) { onError(e.message ?: e.toString()) }
        catch (e: LinkageError) { onError(e.message ?: e.toString()) }
        finally { busy = false }
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
        const val CAPTURE_WIDTH = 1280f
        const val EDGE_INSET = 0.04f
        const val MIN_CONFIDENCE = 0.5f
    }
}
