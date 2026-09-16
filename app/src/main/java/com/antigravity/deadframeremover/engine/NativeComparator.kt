package com.antigravity.deadframeremover.engine

import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import java.nio.ByteBuffer

object NativeComparator {
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("frame_comparator")
            isLoaded = true
            AppLogManager.log(LogLevel.INFO, "NativeComparator", "libframe_comparator.so loaded successfully.")
        } catch (t: Throwable) {
            isLoaded = false
            AppLogManager.log(LogLevel.ERROR, "NativeComparator", "Failed to load libframe_comparator.so: ${t.message}")
        }
    }

    external fun compareYUVPlanes(
        bufferPrev: ByteBuffer,
        prevOffset: Int,
        bufferCurr: ByteBuffer,
        currOffset: Int,
        width: Int,
        height: Int,
        yRowStride: Int,
        yPixelStride: Int,
        threshold: Double
    ): Double

    external fun normalizeYUV420ToNV12(
        yBuffer: ByteBuffer, yOffset: Int, yRowStride: Int, yPixelStride: Int,
        uBuffer: ByteBuffer, uOffset: Int, uRowStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vOffset: Int, vRowStride: Int, vPixelStride: Int,
        dstBuffer: ByteBuffer, dstOffset: Int,
        width: Int, height: Int
    ): Int

    external fun yuvToRgbBitmap(
        yBuffer: ByteBuffer, yOffset: Int, yRowStride: Int, yPixelStride: Int,
        uBuffer: ByteBuffer, uOffset: Int, uRowStride: Int, uPixelStride: Int,
        vBuffer: ByteBuffer, vOffset: Int, vRowStride: Int, vPixelStride: Int,
        srcWidth: Int, srcHeight: Int,
        dstBitmap: android.graphics.Bitmap
    ): Int
}
