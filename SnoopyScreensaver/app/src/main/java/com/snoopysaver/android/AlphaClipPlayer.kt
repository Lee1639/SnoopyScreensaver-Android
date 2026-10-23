package com.snoopysaver.android

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

enum class VideoKind { OPAQUE, ALPHA, UNSUPPORTED }

object VideoProbe {
    fun kind(file: File): VideoKind {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = videoTrack(extractor) ?: return VideoKind.UNSUPPORTED
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return VideoKind.UNSUPPORTED
            if (mime != "video/hevc") return if (mime.startsWith("video/")) VideoKind.OPAQUE else VideoKind.UNSUPPORTED
            extractor.selectTrack(track)
            val lengthSize = lengthSize(format)
            val sampleSize = extractor.sampleSize
            if (sampleSize <= 0 || sampleSize > 12_000_000) return VideoKind.OPAQUE
            val buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0) return VideoKind.OPAQUE
            val bytes = readBytes(buffer, read)
            split(bytes, lengthSize).second.isNotEmpty().let { hasAlpha ->
                if (hasAlpha) VideoKind.ALPHA else VideoKind.OPAQUE
            }
        } catch (error: Exception) {
            Log.w(TAG, "probe ${file.name}: ${error.message}")
            VideoKind.UNSUPPORTED
        } finally {
            extractor.release()
        }
    }
}

/**
 * Decodes Apple's HEVC-with-alpha (base layer + auxiliary alpha layer in one sample)
 * and composites a premultiplied bitmap. Hardware decoders ignore the alpha layer,
 * so each layer is fed to its own decoder.
 */
class AlphaClipPlayer(private val main: Handler) {
    @Volatile private var stopped = false
    private var worker: Thread? = null

    fun stop() {
        stopped = true
        worker?.join(800)
        worker = null
    }

    fun play(
        files: List<File>,
        onFrame: (Bitmap) -> Unit,
        onEnded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        stop()
        if (files.isEmpty()) {
            onFailed("empty")
            return
        }
        stopped = false
        val thread = Thread {
            var failed: String? = null
            try {
                playAll(files, onFrame)
            } catch (error: Exception) {
                failed = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "alpha playback failed: $failed")
            }
            if (!stopped) {
                val reason = failed
                main.post {
                    if (reason == null) onEnded() else onFailed(reason)
                }
            }
        }
        worker = thread
        thread.name = "snoopy-alpha"
        thread.start()
    }

    private fun playAll(files: List<File>, onFrame: (Bitmap) -> Unit) {
        var index = 0
        while (index < files.size && !stopped) {
            val file = files[index]
            var repeats = 1
            while (index + repeats < files.size && files[index + repeats] == file) repeats++
            index += repeats
            openAndPlay(file, repeats, onFrame)
        }
    }

    private fun openAndPlay(file: File, repeats: Int, onFrame: (Bitmap) -> Unit) {
        val extractor = MediaExtractor()
        var baseCodec: MediaCodec? = null
        var alphaCodec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = videoTrack(extractor) ?: error("no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("no mime")
            val lengthSize = lengthSize(format)
            baseCodec = MediaCodec.createDecoderByType(mime)
            alphaCodec = MediaCodec.createDecoderByType(mime)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoColor.YUV420_FLEXIBLE)
            baseCodec.configure(format, null, null, 0)
            alphaCodec.configure(format, null, null, 0)
            baseCodec.start()
            alphaCodec.start()
            var originPts = -1L
            var originNano = 0L
            repeat(repeats) { cycle ->
                if (stopped) return
                if (cycle > 0) {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    baseCodec.flush()
                    alphaCodec.flush()
                    originPts = -1L
                }
                decodeOnce(extractor, baseCodec, alphaCodec, lengthSize, onFrame) { pts ->
                    if (originPts < 0) {
                        originPts = pts
                        originNano = System.nanoTime()
                    } else {
                        val due = originNano + (pts - originPts) * 1_000
                        val delay = due - System.nanoTime()
                        if (delay > 2_000_000) sleepQuiet(delay)
                    }
                    delayFrom(originNano, originPts, pts) > -120_000_000
                }
            }
        } finally {
            try { baseCodec?.stop() } catch (_: Exception) {}
            try { alphaCodec?.stop() } catch (_: Exception) {}
            try { baseCodec?.release() } catch (_: Exception) {}
            try { alphaCodec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun delayFrom(originNano: Long, originPts: Long, pts: Long): Long {
        if (originPts < 0) return 0
        val due = originNano + (pts - originPts) * 1_000
        return due - System.nanoTime()
    }

    private fun decodeOnce(
        extractor: MediaExtractor,
        baseCodec: MediaCodec,
        alphaCodec: MediaCodec,
        lengthSize: Int,
        onFrame: (Bitmap) -> Unit,
        shouldShow: (Long) -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        val alphaInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var baseDone = false
        var alphaDone = false
        var produced = 0
        var sawImage = false
        val baseReady = HashMap<Long, Bitmap>()
        val alphaReady = HashMap<Long, Bitmap>()
        val started = System.nanoTime()
        while ((!baseDone || !alphaDone) && !stopped) {
            if (!inputDone) {
                if (!queueSample(extractor, baseCodec, alphaCodec, lengthSize)) inputDone = true
            }
            baseDone = baseDone || drain(baseCodec, info, baseReady)
            alphaDone = alphaDone || drain(alphaCodec, alphaInfo, alphaReady)
            val shared = baseReady.keys.intersect(alphaReady.keys).sorted()
            for (pts in shared) {
                val base = baseReady.remove(pts) ?: continue
                val alpha = alphaReady.remove(pts) ?: continue
                sawImage = true
                if (shouldShow(pts)) {
                    val frame = composite(base, alpha)
                    produced++
                    main.post { onFrame(frame) }
                }
            }
            if (!sawImage && System.nanoTime() - started > 2_000_000_000L) error("no alpha frame")
            if (inputDone && baseReady.isEmpty() && alphaReady.isEmpty() && !baseDone) {
                // Keep spinning until both decoders report end or time out.
                if (System.nanoTime() - started > 8_000_000_000L) break
            }
        }
        if (!sawImage) error("decoder produced no image")
        if (produced == 0) error("all frames dropped")
    }

    private fun queueSample(
        extractor: MediaExtractor,
        baseCodec: MediaCodec,
        alphaCodec: MediaCodec,
        lengthSize: Int,
    ): Boolean {
        val sampleSize = extractor.sampleSize
        if (sampleSize < 0) {
            queueEos(baseCodec)
            queueEos(alphaCodec)
            return false
        }
        val packet = ByteBuffer.allocateDirect(sampleSize.toInt())
        val read = extractor.readSampleData(packet, 0)
        if (read < 0) {
            queueEos(baseCodec)
            queueEos(alphaCodec)
            return false
        }
        val bytes = readBytes(packet, read)
        val (base, alpha) = split(bytes, lengthSize)
        val pts = extractor.sampleTime
        if (base.isNotEmpty()) queue(baseCodec, base, pts, 0)
        if (alpha.isNotEmpty()) queue(alphaCodec, alpha, pts, 0)
        extractor.advance()
        return true
    }

    private fun queue(codec: MediaCodec, bytes: ByteArray, pts: Long, flags: Int) {
        var index = -1
        repeat(8) {
            if (index >= 0) return@repeat
            index = codec.dequeueInputBuffer(15_000)
        }
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(bytes)
        codec.queueInputBuffer(index, 0, bytes.size, pts, flags)
    }

    private fun queueEos(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(20_000)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, into: HashMap<Long, Bitmap>): Boolean {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 8_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                else -> {
                    if (index < 0) continue
                    val image = codec.getOutputImage(index)
                    if (image != null && info.size > 0) {
                        into[info.presentationTimeUs] = imageToBitmap(image)
                        image.close()
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return true
                }
            }
        }
    }
}

private object MediaCodecInfoColor {
    const val YUV420_FLEXIBLE = 0x7F420888
}

private fun videoTrack(extractor: MediaExtractor): Int? {
    for (index in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith("video/")) return index
    }
    return null
}

private fun lengthSize(format: MediaFormat): Int {
    val csd = format.getByteBuffer("csd-0") ?: return 4
    val bytes = ByteArray(csd.remaining())
    csd.duplicate().get(bytes)
    if (bytes.size <= 21) return 4
    return (bytes[21].toInt() and 3) + 1
}

private fun readBytes(buffer: ByteBuffer, size: Int): ByteArray {
    val out = ByteArray(size)
    val start = if (buffer.position() >= size) buffer.position() - size else 0
    buffer.position(start)
    buffer.get(out, 0, size)
    return out
}

private fun split(sample: ByteArray, lengthSize: Int): Pair<ByteArray, ByteArray> {
    val base = ByteArrayOutputStream()
    val alpha = ByteArrayOutputStream()
    var cursor = 0
    var parsed = 0
    while (cursor + lengthSize <= sample.size) {
        var length = 0
        for (offset in 0 until lengthSize) {
            length = (length shl 8) or (sample[cursor + offset].toInt() and 0xFF)
        }
        cursor += lengthSize
        if (length <= 1 || cursor + length > sample.size) break
        val nal = sample.copyOfRange(cursor, cursor + length)
        cursor += length
        parsed++
        val layer = layerId(nal)
        if (layer == 0) writeNal(base, nal, lengthSize) else writeNal(alpha, forceLayerZero(nal), lengthSize)
    }
    if (parsed == 0) return splitAnnexB(sample)
    return base.toByteArray() to alpha.toByteArray()
}

private fun splitAnnexB(sample: ByteArray): Pair<ByteArray, ByteArray> {
    val base = ByteArrayOutputStream()
    val alpha = ByteArrayOutputStream()
    val starts = ArrayList<Int>()
    var index = 0
    while (index + 3 < sample.size) {
        if (sample[index] == 0.toByte() && sample[index + 1] == 0.toByte()) {
            if (sample[index + 2] == 1.toByte()) starts += index + 3
            else if (index + 4 < sample.size && sample[index + 2] == 0.toByte() && sample[index + 3] == 1.toByte()) {
                starts += index + 4
            }
        }
        index++
    }
    for (position in starts.indices) {
        val from = starts[position]
        val to = if (position + 1 < starts.size) {
            var end = starts[position + 1]
            while (end > from && sample[end - 1] == 0.toByte()) end--
            end
        } else sample.size
        if (to - from < 2) continue
        val nal = sample.copyOfRange(from, to)
        if (layerId(nal) == 0) writeNal(base, nal, 4) else writeNal(alpha, forceLayerZero(nal), 4)
    }
    return base.toByteArray() to alpha.toByteArray()
}

private fun layerId(nal: ByteArray): Int {
    if (nal.size < 2) return 0
    val b0 = nal[0].toInt() and 0xFF
    val b1 = nal[1].toInt() and 0xFF
    return ((b0 and 1) shl 5) or (b1 ushr 3)
}

private fun forceLayerZero(nal: ByteArray): ByteArray {
    val copy = nal.copyOf()
    copy[0] = (copy[0].toInt() and 0xFE).toByte()
    copy[1] = (copy[1].toInt() and 0x07).toByte()
    return copy
}

private fun writeNal(out: ByteArrayOutputStream, nal: ByteArray, lengthSize: Int) {
    var length = nal.size
    val prefix = ByteArray(lengthSize)
    for (index in lengthSize - 1 downTo 0) {
        prefix[index] = (length and 0xFF).toByte()
        length = length ushr 8
    }
    out.write(prefix)
    out.write(nal)
}

private fun imageToBitmap(image: android.media.Image): Bitmap {
    val crop = image.cropRect
    val sourceWidth = crop.width()
    val sourceHeight = crop.height()
    val edge = max(sourceWidth, sourceHeight).coerceAtLeast(1)
    val scale = minOf(1f, 720f / edge)
    val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val pixels = IntArray(width * height)
    for (dy in 0 until height) {
        val sy = crop.top + dy * sourceHeight / height
        for (dx in 0 until width) {
            val sx = crop.left + dx * sourceWidth / width
            val y = byteAt(yBuffer, sy * yPlane.rowStride + sx * yPlane.pixelStride)
            val u = byteAt(uBuffer, (sy / 2) * uPlane.rowStride + (sx / 2) * uPlane.pixelStride) - 128
            val v = byteAt(vBuffer, (sy / 2) * vPlane.rowStride + (sx / 2) * vPlane.pixelStride) - 128
            val red = (y + 1.402f * v).toInt().coerceIn(0, 255)
            val green = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
            val blue = (y + 1.772f * u).toInt().coerceIn(0, 255)
            pixels[dy * width + dx] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun composite(base: Bitmap, alphaImage: Bitmap): Bitmap {
    val width = base.width
    val height = base.height
    val pixels = IntArray(width * height)
    base.getPixels(pixels, 0, width, 0, 0, width, height)
    val alphaPixels = IntArray(width * height)
    val scaled = if (alphaImage.width == width && alphaImage.height == height) {
        alphaImage
    } else {
        Bitmap.createScaledBitmap(alphaImage, width, height, true)
    }
    scaled.getPixels(alphaPixels, 0, width, 0, 0, width, height)
    for (index in pixels.indices) {
        val color = pixels[index]
        val luma = alphaPixels[index] and 0xFF
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        pixels[index] = (luma shl 24) or (red shl 16) or (green shl 8) or blue
    }
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    out.isPremultiplied = true
    return out
}

private fun byteAt(buffer: ByteBuffer, index: Int): Int {
    val absolute = buffer.position() + index
    if (absolute < 0 || absolute >= buffer.limit()) return 0
    return buffer.get(absolute).toInt() and 0xFF
}

private fun sleepQuiet(nanos: Long) {
    try {
        Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
    } catch (_: InterruptedException) {
    }
}

private const val TAG = "SnoopySaver"
