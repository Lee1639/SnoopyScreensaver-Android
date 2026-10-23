package com.snoopysaver.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Handler
import android.util.Log
import android.util.LruCache
import com.radzivon.bartoshyk.avif.coder.Coder
import com.radzivon.bartoshyk.avif.coder.PreferredColorConfig
import java.io.File
import kotlin.math.max

object Heic {
    private val cache = object : LruCache<String, Bitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val coderLock = Any()
    private val coder = try {
        Coder()
    } catch (error: Throwable) {
        Log.w(TAG, "heif coder unavailable: ${error.message}")
        null
    }

    fun decode(file: File, maxPx: Int, asMask: Boolean = false): Bitmap? {
        val webp = File(file.parentFile, file.nameWithoutExtension + ".webp")
        val source = if (webp.isFile) webp else file
        val key = source.path + "@" + maxPx + if (asMask) "m" else ""
        cache.get(key)?.let { return it }
        return try {
            var bitmap = if (isHeif(source)) {
                decodeHeic(source, maxPx) ?: decodePlatform(source, maxPx)
            } else {
                decodePlatform(source, maxPx)
            } ?: return null
            if (asMask) bitmap = maskify(bitmap)
            cache.put(key, bitmap)
            bitmap
        } catch (error: Exception) {
            Log.w(TAG, "heic ${file.name}: ${error.message}")
            null
        }
    }

    /** Apple HEIC keeps transparency in a separate gray image. The platform decoder drops it. */
    private fun decodeHeic(file: File, maxPx: Int): Bitmap? {
        val decoder = coder ?: return null
        return try {
            val bytes = file.readBytes()
            val edge = maxPx.coerceAtLeast(1)
            val bitmap = synchronized(coderLock) {
                decoder.decodeSampled(bytes, edge, edge, PreferredColorConfig.RGBA_8888)
            }
            if (!bitmap.hasAlpha()) Log.w(TAG, "decoded ${file.name} without alpha")
            bitmap
        } catch (error: Exception) {
            Log.w(TAG, "heif ${file.name}: ${error.message}")
            null
        }
    }

    /** Some idle props are PNG files saved with a .heic name. Those are not HEIF. */
    private fun isHeif(file: File): Boolean {
        if (!file.extension.equals("heic", true) && !file.extension.equals("heif", true)) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val count = input.read(header)
                count >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodePlatform(file: File, maxPx: Int): Bitmap? {
        val bytes = file.readBytes()
        if (isPng(bytes) || isJpeg(bytes) || isWebp(bytes)) {
            return decodeRaster(bytes, maxPx)
        }
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val edge = max(width, height).coerceAtLeast(1)
            if (edge > maxPx) {
                val scale = maxPx.toFloat() / edge
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun decodeRaster(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (edge / sample > maxPx * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun isPng(bytes: ByteArray): Boolean {
        return bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte()
    }

    private fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private fun isWebp(bytes: ByteArray): Boolean {
        return bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
    }

    /**
     * A grayscale matte with a flat alpha channel stores the hole in its color.
     * A flat color already stores the hole in alpha. The fully open plate is solid
     * green, and green's luma (182) would veil the screensaver over the video.
     */
    private fun maskify(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        var minAlpha = 255
        var maxAlpha = 0
        var minLuma = 255
        var maxLuma = 0
        val stepX = (width / 12).coerceAtLeast(1)
        val stepY = (height / 12).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = source.getPixel(x, y)
                val alpha = color ushr 24
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                val luma = (0.2126 * red + 0.7152 * green + 0.0722 * blue).toInt()
                if (alpha < minAlpha) minAlpha = alpha
                if (alpha > maxAlpha) maxAlpha = alpha
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
                x += stepX
            }
            y += stepY
        }
        if (maxAlpha - minAlpha > 12 || maxLuma - minLuma <= 12) return source
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            val luma = (0.2126 * red + 0.7152 * green + 0.0722 * blue).toInt().coerceIn(0, 255)
            pixels[index] = (luma shl 24) or (luma shl 16) or (luma shl 8) or luma
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}

class HeicPlayer(private val main: Handler) {
    private var generation = 0

    fun cancel() {
        generation++
    }

    fun play(frames: List<File>, loops: Int, maxPx: Int, onFrame: (Bitmap) -> Unit, onDone: () -> Unit) {
        val token = ++generation
        if (frames.isEmpty()) {
            main.post { if (token == generation) onDone() }
            return
        }
        Thread {
            val interval = 1_000_000_000L / 24
            var next = System.nanoTime()
            var misses = 0
            repeat(loops.coerceAtLeast(1)) {
                for (file in frames) {
                    if (token != generation) return@Thread
                    val bitmap = Heic.decode(file, maxPx)
                    if (bitmap == null) {
                        misses++
                        if (misses >= 3) {
                            main.post { if (token == generation) onDone() }
                            return@Thread
                        }
                        continue
                    }
                    misses = 0
                    val wait = next - System.nanoTime()
                    if (wait > 0) sleepQuiet(wait)
                    if (token != generation) return@Thread
                    next += interval
                    if (System.nanoTime() - next > interval * 3) next = System.nanoTime()
                    main.post { if (token == generation) onFrame(bitmap) }
                }
            }
            main.post { if (token == generation) onDone() }
        }.start()
    }
}

class MaskPlayer(private val main: Handler) {
    private var generation = 0

    fun cancel() {
        generation++
    }

    fun play(
        masks: List<File>,
        outlines: List<File>,
        onFrame: (Bitmap?, Bitmap?) -> Unit,
        onDone: () -> Unit,
    ) {
        val token = ++generation
        val count = maxOf(masks.size, outlines.size)
        if (count == 0) {
            main.post { if (token == generation) onDone() }
            return
        }
        Thread {
            val interval = 1_000_000_000L / 24
            var next = System.nanoTime()
            for (index in 0 until count) {
                if (token != generation) return@Thread
                val mask = masks.getOrNull(index)?.let { Heic.decode(it, 800, asMask = true) }
                    ?: masks.lastOrNull()?.let { Heic.decode(it, 800, asMask = true) }
                val outline = outlines.getOrNull(index)?.let { Heic.decode(it, 960) }
                val wait = next - System.nanoTime()
                if (wait > 0) sleepQuiet(wait)
                if (token != generation) return@Thread
                next += interval
                main.post { if (token == generation) onFrame(mask, outline) }
            }
            main.post { if (token == generation) onDone() }
        }.start()
    }
}

private fun sleepQuiet(nanos: Long) {
    try {
        Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
    } catch (_: InterruptedException) {
    }
}

private const val TAG = "SnoopySaver"
