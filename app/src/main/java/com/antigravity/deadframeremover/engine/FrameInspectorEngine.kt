package com.antigravity.deadframeremover.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class FrameItem(
    val index: Int,
    val ptsUs: Long,
    val formattedTime: String,
    val mse: Double,
    val isDead: Boolean,
    val bitmap: Bitmap?,
    var isSelected: Boolean = !isDead
)

class FrameInspectorEngine(private val context: Context) {

    suspend fun analyzeFrames(
        inputUri: Uri,
        mseThreshold: Double,
        maxFramesToSample: Int = 120,
        onProgress: (Float, Int, Int) -> Unit
    ): List<FrameItem> = withContext(Dispatchers.Default) {
        val frameList = mutableListOf<FrameItem>()
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var cachedPrevY: ByteBuffer? = null

        try {
            extractor = MediaExtractor()
            val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
                ?: throw IllegalArgumentException("Failed to open file descriptor for: $inputUri")
            afd.use { d ->
                extractor.setDataSource(d.fileDescriptor, d.startOffset, d.length)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                throw IllegalArgumentException("No video track found in input file.")
            }

            extractor.selectTrack(videoTrackIndex)

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val decoderMime = videoFormat.getString(MediaFormat.KEY_MIME)!!

            decoder = MediaCodec.createDecoderByType(decoderMime)
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            AppLogManager.log(
                LogLevel.INFO,
                "FrameInspector",
                "Starting hardware MediaCodec frame analysis: ${width}x${height}, maxFrames=$maxFramesToSample, threshold=$mseThreshold"
            )

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEos = false
            var isDecoderEos = false
            var frameIndex = 0

            while (!isDecoderEos && frameIndex < maxFramesToSample && coroutineContext.isActive) {
                // 1. Feed input from MediaExtractor into decoder
                if (!isExtractorEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEos = true
                            } else {
                                val pts = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Dequeue output from decoder
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Continue loop
                    }
                    outIndex >= 0 -> {
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (isEos) {
                            isDecoderEos = true
                            decoder.releaseOutputBuffer(outIndex, false)
                            break
                        }

                        val image = decoder.getOutputImage(outIndex)
                        if (image != null) {
                            try {
                                val yPlane = image.planes[0]
                                val uPlane = image.planes[1]
                                val vPlane = image.planes[2]

                                val yBuf = yPlane.buffer
                                val curYPos = yBuf.position()

                                val mse: Double
                                val isDead: Boolean

                                if (cachedPrevY != null && NativeComparator.isLoaded) {
                                    mse = NativeComparator.compareYUVPlanes(
                                        cachedPrevY!!, 0,
                                        yBuf, curYPos,
                                        width, height,
                                        yPlane.rowStride, yPlane.pixelStride,
                                        mseThreshold
                                    )
                                    isDead = mse <= mseThreshold
                                } else {
                                    mse = 999.0 // First frame is baseline
                                    isDead = false
                                }

                                // Create thumbnail Bitmap using fast native renderer
                                val thumbBitmap = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
                                if (NativeComparator.isLoaded) {
                                    NativeComparator.yuvToRgbBitmap(
                                        yBuf, curYPos, yPlane.rowStride, yPlane.pixelStride,
                                        uPlane.buffer, uPlane.buffer.position(), uPlane.rowStride, uPlane.pixelStride,
                                        vPlane.buffer, vPlane.buffer.position(), vPlane.rowStride, vPlane.pixelStride,
                                        width, height,
                                        thumbBitmap
                                    )
                                }

                                // Cache current Y plane for next consecutive frame comparison
                                val yCap = yBuf.capacity()
                                if (cachedPrevY == null || cachedPrevY!!.capacity() < yCap) {
                                    cachedPrevY = ByteBuffer.allocateDirect(yCap)
                                }
                                cachedPrevY!!.clear()
                                yBuf.position(0)
                                cachedPrevY!!.put(yBuf)
                                yBuf.position(curYPos)
                                cachedPrevY!!.flip()

                                val curPtsUs = bufferInfo.presentationTimeUs
                                val seconds = curPtsUs / 1_000_000f
                                val formatted = String.format(Locale.US, "%02d:%05.2f", (seconds / 60).toInt(), seconds % 60)

                                val item = FrameItem(
                                    index = frameIndex,
                                    ptsUs = curPtsUs,
                                    formattedTime = formatted,
                                    mse = mse,
                                    isDead = isDead,
                                    bitmap = thumbBitmap,
                                    isSelected = !isDead
                                )
                                frameList.add(item)
                                frameIndex++

                                val progressRatio = (frameIndex.toFloat() / maxFramesToSample).coerceIn(0f, 1f)
                                onProgress(progressRatio, frameIndex, maxFramesToSample)

                            } finally {
                                image.close()
                                decoder.releaseOutputBuffer(outIndex, false)
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            }

            AppLogManager.log(
                LogLevel.INFO,
                "FrameInspector",
                "Frame analysis complete. Scanned: ${frameList.size} consecutive frames. Dead: ${frameList.count { it.isDead }}, Good: ${frameList.count { !it.isDead }}"
            )
        } catch (e: Exception) {
            AppLogManager.log(LogLevel.ERROR, "FrameInspector", "Failed to analyze frames: ${e.message}")
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }

        frameList
    }
}
