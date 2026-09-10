package com.antigravity.deadframeremover.engine

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FFmpegVideoExporter(private val context: Context) {

    init {
        FFmpegKitConfig.enableLogCallback { log ->
            val msg = log.message
            if (!msg.isNullOrBlank()) {
                AppLogManager.log(LogLevel.FFMPEG, "FFmpeg", msg.trimEnd())
            }
        }
    }

    suspend fun exportWithSelection(
        inputUri: Uri,
        outputFile: File,
        selectedIndices: List<Int>,
        totalFrames: Int,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
        val outputPath = outputFile.absolutePath

        AppLogManager.log(
            LogLevel.INFO,
            "FFmpegExporter",
            "Exporting ${selectedIndices.size}/$totalFrames selected frames via FFmpeg to: $outputPath"
        )

        val ffmpegCommand = if (selectedIndices.isNotEmpty() && selectedIndices.size < totalFrames) {
            // Expression: select='eq(n\,0)+eq(n\,2)+eq(n\,5)...',setpts=N/FRAME_RATE/TB
            val selectExpr = selectedIndices.joinToString("+") { "eq(n\\,$it)" }
            "-y -i \"$inputPath\" -vf \"select='$selectExpr',setpts=N/FRAME_RATE/TB\" -c:v libx264 -preset veryfast -crf 20 -an \"$outputPath\""
        } else {
            // Standard fallback using mpdecimate if no specific subset or all frames selected
            "-y -i \"$inputPath\" -vf \"mpdecimate=hi=64*8:lo=64*3:frac=0.33,setpts=N/FRAME_RATE/TB\" -c:v libx264 -preset veryfast -crf 20 -an \"$outputPath\""
        }

        executeCommand(ffmpegCommand, outputFile, onProgress)
    }

    suspend fun exportWithMseThreshold(
        inputUri: Uri,
        outputFile: File,
        mseThreshold: Double,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
        val outputPath = outputFile.absolutePath

        AppLogManager.log(
            LogLevel.INFO,
            "FFmpegExporter",
            "Starting FFmpeg mpdecimate export (MSE Threshold $mseThreshold) -> $outputPath"
        )

        val hiValue = (mseThreshold * 64).toInt().coerceIn(32, 512)
        val loValue = (hiValue / 2).coerceIn(16, 256)

        // mpdecimate drops identical frames, setpts retimes to continuous stream
        val ffmpegCommand = "-y -i \"$inputPath\" -vf \"mpdecimate=hi=$hiValue:lo=$loValue:frac=0.33,setpts=N/FRAME_RATE/TB\" -c:v libx264 -preset veryfast -crf 20 -an \"$outputPath\""

        executeCommand(ffmpegCommand, outputFile, onProgress)
    }

    private fun executeCommand(command: String, outputFile: File, onProgress: (Float) -> Unit): Boolean {
        AppLogManager.log(LogLevel.INFO, "FFmpegCommand", "Executing: $command")

        FFmpegKitConfig.enableStatisticsCallback { stats ->
            // Progress estimation based on time/frames
            val timeMs = stats.time
            if (timeMs > 0.0) {
                onProgress(((timeMs / 1000.0 % 100.0) / 100.0).toFloat())
            }
        }

        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode

        return if (ReturnCode.isSuccess(returnCode)) {
            AppLogManager.log(
                LogLevel.INFO,
                "FFmpegExporter",
                "FFmpeg execution completed successfully! File size: ${outputFile.length() / 1024} KB"
            )
            onProgress(1.0f)
            true
        } else {
            val failStackTrace = session.failStackTrace ?: "Unknown error"
            AppLogManager.log(
                LogLevel.ERROR,
                "FFmpegExporter",
                "FFmpeg failed with state ${session.state}, returnCode: $returnCode. Error: $failStackTrace"
            )
            false
        }
    }
}
