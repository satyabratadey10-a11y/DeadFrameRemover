package com.antigravity.deadframeremover.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

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
        maxFramesToSample: Int = 80,
        onProgress: (Float, Int, Int) -> Unit
    ): List<FrameItem> = withContext(Dispatchers.Default) {
        val frameList = mutableListOf<FrameItem>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, inputUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 5000L
            val durationUs = durationMs * 1000L

            val stepUs = (durationUs / maxFramesToSample.coerceAtLeast(1)).coerceAtLeast(33_333L)
            var prevBitmap: Bitmap? = null

            AppLogManager.log(
                LogLevel.INFO,
                "FrameInspector",
                "Analyzing frames for visual inspection: duration=${durationMs}ms, sampling up to $maxFramesToSample frames."
            )

            var frameIndex = 0
            var currentUs = 0L

            while (currentUs < durationUs && frameIndex < maxFramesToSample) {
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        currentUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        180,
                        120
                    )
                } else {
                    retriever.getFrameAtTime(currentUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }

                if (rawBitmap != null) {
                    val scaled = if (rawBitmap.width > 200 || rawBitmap.height > 150) {
                        Bitmap.createScaledBitmap(rawBitmap, 180, 120, true)
                    } else {
                        rawBitmap
                    }

                    val mse: Double
                    val isDead: Boolean

                    if (prevBitmap != null) {
                        mse = computeBitmapMSE(prevBitmap, scaled)
                        isDead = mse <= mseThreshold
                    } else {
                        mse = 99.0 // First frame is baseline
                        isDead = false
                    }

                    val seconds = currentUs / 1_000_000f
                    val formatted = String.format(Locale.US, "%02d:%05.2f", (seconds / 60).toInt(), seconds % 60)

                    val item = FrameItem(
                        index = frameIndex,
                        ptsUs = currentUs,
                        formattedTime = formatted,
                        mse = mse,
                        isDead = isDead,
                        bitmap = scaled,
                        isSelected = !isDead
                    )
                    frameList.add(item)
                    prevBitmap = scaled

                    frameIndex++
                    onProgress(currentUs.toFloat() / durationUs, frameIndex, maxFramesToSample)
                }

                currentUs += stepUs
            }

            AppLogManager.log(
                LogLevel.INFO,
                "FrameInspector",
                "Frame analysis complete. Total: ${frameList.size}, Good: ${frameList.count { !it.isDead }}, Dead: ${frameList.count { it.isDead }}"
            )
        } catch (e: Exception) {
            AppLogManager.log(LogLevel.ERROR, "FrameInspector", "Failed to extract frames: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        frameList
    }

    private fun computeBitmapMSE(prev: Bitmap, curr: Bitmap): Double {
        val width = minOf(prev.width, curr.width)
        val height = minOf(prev.height, curr.height)
        if (width <= 0 || height <= 0) return 0.0

        var sumSq = 0L
        var samples = 0L

        // Subsampled 2x2 stride for speed
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)

                // Fast integer luminance formula: (77*R + 150*G + 29*B) >> 8
                val y1 = (77 * ((p1 shr 16) and 0xFF) + 150 * ((p1 shr 8) and 0xFF) + 29 * (p1 and 0xFF)) shr 8
                val y2 = (77 * ((p2 shr 16) and 0xFF) + 150 * ((p2 shr 8) and 0xFF) + 29 * (p2 and 0xFF)) shr 8

                val diff = y1 - y2
                sumSq += (diff * diff).toLong()
                samples++
            }
        }

        return if (samples > 0) sumSq.toDouble() / samples else 0.0
    }
}
