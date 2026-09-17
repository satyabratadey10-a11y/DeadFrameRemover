package com.antigravity.deadframeremover.engine

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    private class SafeMediaMuxer(
        outputPath: String,
        private val expectedAudioFormat: MediaFormat?,
        private val rotationHint: Int = 0
    ) {
        private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var isStarted = false
            private set
        private var videoTrackIndex = -1
        private var audioTrackIndex = -1
        var videoSamplesWritten = 0
            private set
        var audioSamplesWritten = 0
            private set

        fun onVideoFormatChanged(format: MediaFormat) {
            if (!isStarted) {
                if (rotationHint != 0) {
                    try {
                        muxer.setOrientationHint(rotationHint)
                    } catch (e: Exception) {
                        AppLogManager.log(LogLevel.WARN, "SafeMediaMuxer", "Failed to set orientation hint: ${e.message}")
                    }
                }
                videoTrackIndex = muxer.addTrack(format)
                if (expectedAudioFormat != null) {
                    try {
                        audioTrackIndex = muxer.addTrack(expectedAudioFormat)
                        AppLogManager.log(
                            LogLevel.INFO,
                            "SafeMediaMuxer",
                            "Audio track registered in muxer: index $audioTrackIndex"
                        )
                    } catch (e: Exception) {
                        AppLogManager.log(
                            LogLevel.WARN,
                            "SafeMediaMuxer",
                            "Audio track rejected by container (${e.message}). Proceeding video-only."
                        )
                        audioTrackIndex = -1
                    }
                }
                muxer.start()
                isStarted = true
                AppLogManager.log(
                    LogLevel.INFO,
                    "SafeMediaMuxer",
                    "MediaMuxer started. videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex, rotation=$rotationHint"
                )
            }
        }

        @Synchronized
        fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (isStarted && videoTrackIndex >= 0) {
                muxer.writeSampleData(videoTrackIndex, buffer, info)
                videoSamplesWritten++
            }
        }

        @Synchronized
        fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (isStarted && audioTrackIndex >= 0) {
                muxer.writeSampleData(audioTrackIndex, buffer, info)
                audioSamplesWritten++
            }
        }

        fun release() {
            if (isStarted) {
                try {
                    if (videoSamplesWritten > 0 || audioSamplesWritten > 0) {
                        muxer.stop()
                    }
                } catch (e: Exception) {
                    AppLogManager.log(LogLevel.WARN, "SafeMediaMuxer", "Muxer stop warning: ${e.message}")
                }
                isStarted = false
            }
            try {
                muxer.release()
            } catch (e: Exception) {
                AppLogManager.log(LogLevel.WARN, "SafeMediaMuxer", "Muxer release warning: ${e.message}")
            }
        }
    }

    private data class VideoFrameRecord(
        val origPtsUs: Long,
        val isDead: Boolean,
        val retimedPtsUs: Long
    )

    suspend fun processVideo(
        inputUri: Uri,
        outputFile: File,
        mseThreshold: Double,
        selectedFrames: List<FrameItem>? = null,
        onProgressUpdate: (ProcessingProgress) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!NativeComparator.isLoaded) {
            throw IllegalStateException("Native frame_comparator C++ engine could not be loaded.")
        }

        val deselectedPts = if (!selectedFrames.isNullOrEmpty()) {
            selectedFrames.filter { !it.isSelected }.map { it.ptsUs }
        } else emptyList()

        val explicitlySelectedPts = if (!selectedFrames.isNullOrEmpty()) {
            selectedFrames.filter { it.isSelected }.map { it.ptsUs }
        } else emptyList()

        var afd: AssetFileDescriptor? = null
        var audioAfd: AssetFileDescriptor? = null
        var extractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var safeMuxer: SafeMediaMuxer? = null

        var packedPrevY: ByteBuffer? = null
        var hasPrevFrame = false

        var totalScanned = 0
        var droppedFrames = 0
        var preservedFrames = 0

        val frameRecords = ArrayList<VideoFrameRecord>()
        var currentRetimedVideoPts = 0L

        try {
            extractor = MediaExtractor()
            afd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
                ?: throw IllegalArgumentException("Failed to open file descriptor for URI: $inputUri")
            extractor.setDataSource(
                afd.fileDescriptor,
                afd.startOffset,
                afd.length
            )

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioFormat = format
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                throw IllegalArgumentException("Input video does not contain a supported video track.")
            }

            extractor.selectTrack(videoTrackIndex)

            if (audioTrackIndex >= 0 && audioFormat != null) {
                AppLogManager.log(
                    LogLevel.INFO,
                    "MediaCodecPipeline",
                    "Input audio track detected: ${audioFormat.getString(MediaFormat.KEY_MIME)}"
                )
                audioAfd = context.contentResolver.openAssetFileDescriptor(inputUri, "r")
                if (audioAfd != null) {
                    audioExtractor = MediaExtractor().apply {
                        setDataSource(audioAfd.fileDescriptor, audioAfd.startOffset, audioAfd.length)
                        selectTrack(audioTrackIndex)
                    }
                }
            } else {
                AppLogManager.log(
                    LogLevel.INFO,
                    "MediaCodecPipeline",
                    "No audio track present in input video. Proceeding with video-only export."
                )
            }

            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else 1L
            val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
            } else 30
            val frameDurationUs = if (frameRate > 0) 1_000_000L / frameRate else 33_333L

            val rotationHint = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0

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

            safeMuxer = SafeMediaMuxer(
                outputPath = outputFile.absolutePath,
                expectedAudioFormat = audioFormat,
                rotationHint = rotationHint
            )

            packedPrevY = ByteBuffer.allocateDirect(width * height)

            AppLogManager.log(
                LogLevel.INFO,
                "MediaCodecPipeline",
                "Pipeline initialized: ${width}x${height} @ ${frameRate}fps, threshold=$mseThreshold, hasAudio=${audioExtractor != null}"
            )

            val decBufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()

            val audioBuffer = ByteBuffer.allocateDirect(256 * 1024)
            val audioBufferInfo = MediaCodec.BufferInfo()
            var isAudioEos = (audioExtractor == null)
            var lastWrittenAudioPts = -1L

            fun mapAudioPts(audioOrigTimeUs: Long): Long {
                if (frameRecords.isEmpty()) return audioOrigTimeUs

                // Binary search for frame record closest to audioOrigTimeUs
                var low = 0
                var high = frameRecords.size - 1
                var bestIndex = 0
                var minDiff = Long.MAX_VALUE

                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val record = frameRecords[mid]
                    val diff = audioOrigTimeUs - record.origPtsUs
                    val absDiff = kotlin.math.abs(diff)

                    if (absDiff < minDiff) {
                        minDiff = absDiff
                        bestIndex = mid
                    }

                    if (diff < 0) {
                        high = mid - 1
                    } else if (diff > 0) {
                        low = mid + 1
                    } else {
                        break
                    }
                }

                val matched = frameRecords[bestIndex]
                if (matched.isDead) {
                    // This audio sample corresponds to a dropped dead frame interval
                    return -1L
                }

                val delta = audioOrigTimeUs - matched.origPtsUs
                return (matched.retimedPtsUs + delta).coerceAtLeast(0L)
            }

            fun pumpAudio(targetOrigPtsUs: Long) {
                val aExt = audioExtractor ?: return
                val mux = safeMuxer ?: return
                if (!mux.isStarted || isAudioEos) return

                while (!isAudioEos && coroutineContext.isActive) {
                    val sampleTimeUs = aExt.sampleTime
                    if (sampleTimeUs < 0) {
                        isAudioEos = true
                        break
                    }

                    if (targetOrigPtsUs >= 0 && sampleTimeUs > targetOrigPtsUs) {
                        break
                    }

                    audioBuffer.clear()
                    val sampleSize = aExt.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) {
                        isAudioEos = true
                        break
                    }

                    val mappedPts = mapAudioPts(sampleTimeUs)
                    if (mappedPts >= 0) {
                        val finalPts = if (mappedPts <= lastWrittenAudioPts) {
                            lastWrittenAudioPts + 1000L
                        } else {
                            mappedPts
                        }
                        lastWrittenAudioPts = finalPts

                        audioBuffer.position(0)
                        audioBuffer.limit(sampleSize)
                        audioBufferInfo.set(0, sampleSize, finalPts, aExt.sampleFlags)
                        mux.writeAudioSample(audioBuffer, audioBufferInfo)
                    }

                    aExt.advance()
                }
            }

            var isExtractorEos = false
            var isDecoderEos = false
            var isEncoderEos = false

            fun drainEncoderOutput(timeoutUs: Long) {
                val enc = encoder ?: return
                val mux = safeMuxer ?: return
                while (true) {
                    val encOutIndex = enc.dequeueOutputBuffer(encBufferInfo, timeoutUs)
                    when {
                        encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            mux.onVideoFormatChanged(enc.outputFormat)
                        }
                        encOutIndex >= 0 -> {
                            val outBuffer = enc.getOutputBuffer(encOutIndex)
                            if (outBuffer != null) {
                                if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    encBufferInfo.size = 0
                                }
                                if (encBufferInfo.size > 0 && mux.isStarted) {
                                    outBuffer.position(encBufferInfo.offset)
                                    outBuffer.limit(encBufferInfo.offset + encBufferInfo.size)
                                    mux.writeVideoSample(outBuffer, encBufferInfo)
                                }
                            }
                            enc.releaseOutputBuffer(encOutIndex, false)
                            if ((encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                isEncoderEos = true
                                return
                            }
                        }
                    }
                }
            }

            fun feedEncoderFrame(image: Image, ptsUs: Long) {
                val enc = encoder ?: return
                var encInIndex = enc.dequeueInputBuffer(2500L)
                while (encInIndex < 0 && coroutineContext.isActive) {
                    drainEncoderOutput(timeoutUs = 2500L)
                    encInIndex = enc.dequeueInputBuffer(2500L)
                }
                if (encInIndex < 0) return

                val encInBuf = enc.getInputBuffer(encInIndex)
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

                enc.queueInputBuffer(encInIndex, 0, nv12Size, ptsUs, 0)
            }

            fun signalEncoderEndOfStream() {
                val enc = encoder ?: return
                var encInIndex = enc.dequeueInputBuffer(5000L)
                while (encInIndex < 0 && coroutineContext.isActive) {
                    drainEncoderOutput(timeoutUs = 2500L)
                    encInIndex = enc.dequeueInputBuffer(5000L)
                }
                if (encInIndex >= 0) {
                    enc.queueInputBuffer(encInIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                                        val curPtsUs = decBufferInfo.presentationTimeUs
                                        val curYPos = yPlane.buffer.position()

                                        val mse = if (hasPrevFrame && packedPrevY != null) {
                                            NativeComparator.comparePackedWithYPlane(
                                                packedPrevY,
                                                yPlane.buffer, curYPos,
                                                yPlane.rowStride, yPlane.pixelStride,
                                                width, height
                                            )
                                        } else {
                                            999.0
                                        }

                                        val isExplicitlyDeselected = deselectedPts.any { kotlin.math.abs(curPtsUs - it) < (frameDurationUs * 3 / 4) }
                                        val isExplicitlySelected = explicitlySelectedPts.any { kotlin.math.abs(curPtsUs - it) < (frameDurationUs * 3 / 4) }

                                        val isDead = when {
                                            isExplicitlyDeselected -> true
                                            isExplicitlySelected -> false
                                            hasPrevFrame -> mse <= mseThreshold
                                            else -> false
                                        }

                                        if (isDead) {
                                            droppedFrames++
                                            frameRecords.add(
                                                VideoFrameRecord(
                                                    origPtsUs = curPtsUs,
                                                    isDead = true,
                                                    retimedPtsUs = currentRetimedVideoPts
                                                )
                                            )
                                        } else {
                                            val retimedPts = preservedFrames.toLong() * frameDurationUs
                                            currentRetimedVideoPts = retimedPts
                                            preservedFrames++

                                            frameRecords.add(
                                                VideoFrameRecord(
                                                    origPtsUs = curPtsUs,
                                                    isDead = false,
                                                    retimedPtsUs = retimedPts
                                                )
                                            )

                                            // Update baseline packed Y plane for next comparison
                                            if (packedPrevY != null) {
                                                NativeComparator.packYPlane(
                                                    yPlane.buffer, curYPos,
                                                    yPlane.rowStride, yPlane.pixelStride,
                                                    width, height,
                                                    packedPrevY
                                                )
                                                hasPrevFrame = true
                                            }

                                            // Feed encoder with normalized NV12 data
                                            feedEncoderFrame(image, retimedPts)
                                        }

                                        // Pump audio interleaved up to current decoded video timestamp
                                        pumpAudio(curPtsUs)

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

            // Final drain of remaining audio samples to end of video
            pumpAudio(targetOrigPtsUs = -1L)

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
                "MediaCodec transcode finished. Scanned: $totalScanned, Dropped: $droppedFrames, Preserved: $preservedFrames. Video samples: ${safeMuxer?.videoSamplesWritten}, Audio samples: ${safeMuxer?.audioSamplesWritten}"
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
            try {
                afd?.close()
            } catch (_: Exception) {
            }

            try {
                audioExtractor?.release()
            } catch (_: Exception) {
            }
            try {
                audioAfd?.close()
            } catch (_: Exception) {
            }

            safeMuxer?.release()
        }
    }
}
