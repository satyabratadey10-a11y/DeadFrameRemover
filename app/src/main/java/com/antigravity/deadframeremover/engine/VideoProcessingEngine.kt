package com.antigravity.deadframeremover.engine

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

data class ProcessingProgress(
    val progress: Float = 0f,
    val totalScanned: Int = 0,
    val droppedFrames: Int = 0,
    val preservedFrames: Int = 0,
    val dropRatio: Float = 0f,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

class VideoProcessingEngine(private val context: Context) {

    private class SafeMediaMuxer(outputPath: String) {
        private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var isStarted = false
            private set
        private var videoTrackIndex = -1

        fun addTrackAndStart(format: MediaFormat) {
            if (!isStarted) {
                videoTrackIndex = muxer.addTrack(format)
                muxer.start()
                isStarted = true
            }
        }

        fun writeSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (isStarted && videoTrackIndex >= 0) {
                muxer.writeSampleData(videoTrackIndex, buffer, info)
            }
        }

        fun release() {
            if (isStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
                isStarted = false
            }
            try {
                muxer.release()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun processVideo(
        inputUri: Uri,
        outputFile: File,
        mseThreshold: Double,
        onProgressUpdate: (ProcessingProgress) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!NativeComparator.isLoaded) {
            throw IllegalStateException("Native frame_comparator C++ engine could not be loaded.")
        }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var safeMuxer: SafeMediaMuxer? = null

        var cachedPrevYBuffer: ByteBuffer? = null
        var hasPrevFrame = false

        var totalScanned = 0
        var droppedFrames = 0
        var preservedFrames = 0

        var lastOutputPtsUs = 0L
        var prevKeptInputPtsUs = -1L
        var firstKeptFrame = true

        fun calculateNextPts(currentInputPtsUs: Long): Long {
            if (firstKeptFrame) {
                firstKeptFrame = false
                prevKeptInputPtsUs = currentInputPtsUs
                lastOutputPtsUs = 0L
                return 0L
            }
            val deltaUs = currentInputPtsUs - prevKeptInputPtsUs
            val sanitizedDeltaUs = maxOf(1000L, deltaUs)
            val newPts = lastOutputPtsUs + sanitizedDeltaUs
            lastOutputPtsUs = newPts
            prevKeptInputPtsUs = currentInputPtsUs
            return newPts
        }

        try {
            extractor = MediaExtractor()
            val afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
                ?: throw IllegalArgumentException("Failed to open file descriptor for URI: $inputUri")
            afd.use { descriptor ->
                extractor.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )
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
                throw IllegalArgumentException("Input video does not contain a supported video track.")
            }

            extractor.selectTrack(videoTrackIndex)

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else 1L
            val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
            } else 30

            val bitRate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
            } else {
                (width.toLong() * height.toLong() * 3.5).toInt().coerceIn(2_000_000, 15_000_000)
            }

            val decoderMime = videoFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(decoderMime)
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder.configure(videoFormat, null, null, 0)
            decoder.start()

            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            safeMuxer = SafeMediaMuxer(outputFile.absolutePath)
            AppLogManager.log(
                LogLevel.INFO,
                "MediaCodecPipeline",
                "Pipeline initialized: ${width}x${height} @ ${frameRate}fps, threshold=$mseThreshold"
            )

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()

            var isExtractorEos = false
            var isDecoderEos = false
            var isEncoderEos = false

            fun drainEncoderOutput(timeoutUs: Long) {
                while (true) {
                    val encOutIndex = encoder.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                    when {
                        encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            safeMuxer.addTrackAndStart(encoder.outputFormat)
                        }
                        encOutIndex >= 0 -> {
                            val outBuffer = encoder.getOutputBuffer(encOutIndex)
                            if (outBuffer != null) {
                                if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    encBufferInfo.size = 0
                                }
                                if (encBufferInfo.size > 0 && safeMuxer.isStarted) {
                                    outBuffer.position(encBufferInfo.offset)
                                    outBuffer.limit(encBufferInfo.offset + encBufferInfo.size)
                                    safeMuxer.writeSample(outBuffer, encBufferInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIndex, false)
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isEncoderEos = true
                                return
                            }
                        }
                    }
                }
            }

            fun feedEncoderFrame(image: Image, ptsUs: Long) {
                var encInIndex = encoder.dequeueInputBuffer(2500L)
                while (encInIndex < 0 && coroutineContext.isActive) {
                    drainEncoderOutput(timeoutUs = 2500L)
                    encInIndex = encoder.dequeueInputBuffer(2500L)
                }
                if (encInIndex < 0) return

                val encInBuf = encoder.getInputBuffer(encInIndex)
                    ?: throw IllegalStateException("Encoder input buffer is null")
                encInBuf.clear()

                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]

                val yBuf = yPlane.buffer
                val uBuf = uPlane.buffer
                val vBuf = vPlane.buffer

                val nv12Size = width * height * 3 / 2
                val ret = NativeComparator.normalizeYUV420ToNV12(
                    yBuf, yBuf.position(), yPlane.rowStride, yPlane.pixelStride,
                    uBuf, uBuf.position(), uPlane.rowStride, uPlane.pixelStride,
                    vBuf, vBuf.position(), vPlane.rowStride, vPlane.pixelStride,
                    encInBuf, encInBuf.position(),
                    width, height
                )
                if (ret != 0) {
                    throw RuntimeException("normalizeYUV420ToNV12 failed with code: $ret")
                }

                encoder.queueInputBuffer(encInIndex, 0, nv12Size, ptsUs, 0)
            }

            fun signalEncoderEndOfStream() {
                var encInIndex = encoder.dequeueInputBuffer(5000L)
                while (encInIndex < 0 && coroutineContext.isActive) {
                    drainEncoderOutput(timeoutUs = 2500L)
                    encInIndex = encoder.dequeueInputBuffer(5000L)
                }
                if (encInIndex >= 0) {
                    encoder.queueInputBuffer(encInIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            while (!isEncoderEos && coroutineContext.isActive) {
                // Step A: Drain available output buffers from the encoder
                drainEncoderOutput(timeoutUs = 0L)

                // Step B: Dequeue output buffer from the decoder
                if (!isDecoderEos) {
                    val decOutIndex = decoder.dequeueOutputBuffer(decBufferInfo, 2500L)
                    when {
                        decOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        }
                        decOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        }
                        decOutIndex >= 0 -> {
                            val isEos = (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (isEos) {
                                isDecoderEos = true
                                decoder.releaseOutputBuffer(decOutIndex, false)
                                signalEncoderEndOfStream()
                            } else {
                                totalScanned++
                                val image = decoder.getOutputImage(decOutIndex)
                                if (image != null) {
                                    try {
                                        val yPlane = image.planes[0]
                                        val isDead = if (hasPrevFrame) {
                                            val mse = NativeComparator.compareYUVPlanes(
                                                cachedPrevYBuffer!!, 0,
                                                yPlane.buffer, yPlane.buffer.position(),
                                                width, height,
                                                yPlane.rowStride, yPlane.pixelStride,
                                                mseThreshold
                                            )
                                            mse <= mseThreshold
                                        } else {
                                            false
                                        }

                                        if (isDead) {
                                            droppedFrames++
                                        } else {
                                            preservedFrames++
                                            val retimedPts = calculateNextPts(decBufferInfo.presentationTimeUs)

                                            // Cache Y plane for next comparison
                                            val yBuf = yPlane.buffer
                                            val yCapacity = yBuf.capacity()
                                            if (cachedPrevYBuffer == null || cachedPrevYBuffer!!.capacity() < yCapacity) {
                                                cachedPrevYBuffer = ByteBuffer.allocateDirect(yCapacity)
                                            }
                                            cachedPrevYBuffer!!.clear()
                                            val curPos = yBuf.position()
                                            yBuf.position(0)
                                            cachedPrevYBuffer!!.put(yBuf)
                                            yBuf.position(curPos)
                                            cachedPrevYBuffer!!.flip()
                                            hasPrevFrame = true

                                            // Feed encoder with normalized NV12 data
                                            feedEncoderFrame(image, retimedPts)
                                        }
                                    } finally {
                                        image.close()
                                        decoder.releaseOutputBuffer(decOutIndex, false)
                                    }
                                } else {
                                    decoder.releaseOutputBuffer(decOutIndex, false)
                                }

                                val progressRatio = if (durationUs > 0) {
                                    (decBufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                } else 0f
                                val ratio = if (totalScanned > 0) droppedFrames.toFloat() / totalScanned else 0f
                                onProgressUpdate(
                                    ProcessingProgress(
                                        progress = progressRatio,
                                        totalScanned = totalScanned,
                                        droppedFrames = droppedFrames,
                                        preservedFrames = preservedFrames,
                                        dropRatio = ratio,
                                        isCompleted = false
                                    )
                                )
                            }
                        }
                    }
                }

                // Step C: Feed decoder input from MediaExtractor
                if (!isExtractorEos) {
                    val decInIndex = decoder.dequeueInputBuffer(0L)
                    if (decInIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(decInIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    decInIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isExtractorEos = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(decInIndex, 0, sampleSize, sampleTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
            }

            // Final drain of encoder
            while (!isEncoderEos && coroutineContext.isActive) {
                drainEncoderOutput(timeoutUs = 5000L)
            }

            val finalRatio = if (totalScanned > 0) droppedFrames.toFloat() / totalScanned else 0f
            onProgressUpdate(
                ProcessingProgress(
                    progress = 1.0f,
                    totalScanned = totalScanned,
                    droppedFrames = droppedFrames,
                    preservedFrames = preservedFrames,
                    dropRatio = finalRatio,
                    isCompleted = true
                )
            )
            AppLogManager.log(
                LogLevel.INFO,
                "MediaCodecPipeline",
                "MediaCodec transcode finished. Scanned: $totalScanned, Dropped: $droppedFrames, Preserved: $preservedFrames"
            )
        } catch (e: Exception) {
            AppLogManager.log(
                LogLevel.ERROR,
                "MediaCodecPipeline",
                "MediaCodec transcode error: ${e.message}"
            )
            onProgressUpdate(
                ProcessingProgress(
                    progress = 0f,
                    totalScanned = totalScanned,
                    droppedFrames = droppedFrames,
                    preservedFrames = preservedFrames,
                    dropRatio = 0f,
                    isCompleted = false,
                    errorMessage = e.localizedMessage ?: e.toString()
                )
            )
            throw e
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }

            try {
                encoder?.stop()
            } catch (_: Exception) {
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }

            try {
                extractor?.release()
            } catch (_: Exception) {
            }

            safeMuxer?.release()
        }
    }
}
