package com.antigravity.deadframeremover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.deadframeremover.engine.FFmpegVideoExporter
import com.antigravity.deadframeremover.engine.FrameInspectorEngine
import com.antigravity.deadframeremover.engine.FrameItem
import com.antigravity.deadframeremover.engine.ProcessingProgress
import com.antigravity.deadframeremover.engine.VideoProcessingEngine
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.CrashHandler
import com.antigravity.deadframeremover.logging.LogLevel
import com.antigravity.deadframeremover.ui.MainScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class MainUiState(
    val selectedUri: Uri? = null,
    val selectedFileName: String? = null,
    val mseThreshold: Float = 2.0f,
    val isProcessing: Boolean = false,
    val isAnalyzingFrames: Boolean = false,
    val frames: List<FrameItem> = emptyList(),
    val progress: ProcessingProgress = ProcessingProgress(),
    val exportedFile: File? = null,
    val savedCrashLog: String? = null
)

class VideoProcessingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private var processingJob: Job? = null

    fun checkCrashLog(context: android.content.Context) {
        val crashDump = CrashHandler.getSavedCrashLog(context)
        if (!crashDump.isNullOrBlank()) {
            _uiState.update { it.copy(savedCrashLog = crashDump) }
        }
    }

    fun clearCrashLog(context: android.content.Context) {
        CrashHandler.clearCrashLog(context)
        _uiState.update { it.copy(savedCrashLog = null) }
        AppLogManager.log(LogLevel.INFO, "CrashHandler", "Crash report cleared by user.")
    }

    fun selectVideo(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                selectedUri = uri,
                selectedFileName = fileName,
                frames = emptyList(),
                progress = ProcessingProgress(),
                exportedFile = null
            )
        }
        AppLogManager.log(LogLevel.INFO, "MainActivity", "Selected video file: $fileName")
    }

    fun setThreshold(threshold: Float) {
        _uiState.update { it.copy(mseThreshold = threshold) }
    }

    fun analyzeFrames(engine: FrameInspectorEngine) {
        val currentUri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isAnalyzingFrames || _uiState.value.isProcessing) return

        _uiState.update { it.copy(isAnalyzingFrames = true) }

        viewModelScope.launch {
            try {
                val extracted = engine.analyzeFrames(
                    inputUri = currentUri,
                    mseThreshold = _uiState.value.mseThreshold.toDouble(),
                    maxFramesToSample = 80
                ) { _, _, _ -> }

                _uiState.update {
                    it.copy(
                        frames = extracted,
                        isAnalyzingFrames = false
                    )
                }
            } catch (e: Exception) {
                AppLogManager.log(LogLevel.ERROR, "FrameAnalyzer", "Error analyzing frames: ${e.message}")
                _uiState.update { it.copy(isAnalyzingFrames = false) }
            }
        }
    }

    fun toggleFrameSelection(index: Int) {
        val currentFrames = _uiState.value.frames.toMutableList()
        val target = currentFrames.find { it.index == index } ?: return
        val updated = target.copy(isSelected = !target.isSelected)
        val pos = currentFrames.indexOf(target)
        currentFrames[pos] = updated
        _uiState.update { it.copy(frames = currentFrames) }
    }

    fun selectAllGoodFrames() {
        val updated = _uiState.value.frames.map { it.copy(isSelected = !it.isDead) }
        _uiState.update { it.copy(frames = updated) }
    }

    fun selectAllFrames() {
        val updated = _uiState.value.frames.map { it.copy(isSelected = true) }
        _uiState.update { it.copy(frames = updated) }
    }

    fun invertSelection() {
        val updated = _uiState.value.frames.map { it.copy(isSelected = !it.isSelected) }
        _uiState.update { it.copy(frames = updated) }
    }

    fun startMediaCodecExport(engine: VideoProcessingEngine, outputDir: File) {
        val currentUri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isProcessing) return

        val outputFile = File(outputDir, "cleaned_mediacodec_${System.currentTimeMillis()}.mp4")

        _uiState.update {
            it.copy(
                isProcessing = true,
                exportedFile = null,
                progress = ProcessingProgress()
            )
        }
        AppLogManager.log(LogLevel.INFO, "Pipeline", "Starting MediaCodec NDK export to: ${outputFile.name}")

        processingJob = viewModelScope.launch {
            try {
                engine.processVideo(
                    inputUri = currentUri,
                    outputFile = outputFile,
                    mseThreshold = _uiState.value.mseThreshold.toDouble()
                ) { progressUpdate ->
                    _uiState.update { it.copy(progress = progressUpdate) }
                }
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        exportedFile = outputFile
                    )
                }
                AppLogManager.log(LogLevel.INFO, "Pipeline", "MediaCodec NDK export finished successfully.")
            } catch (e: Exception) {
                AppLogManager.log(LogLevel.ERROR, "Pipeline", "MediaCodec export failed: ${e.message}")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progress = it.progress.copy(errorMessage = e.localizedMessage ?: "Processing error")
                    )
                }
            }
        }
    }

    fun startFFmpegExport(exporter: FFmpegVideoExporter, outputDir: File) {
        val currentUri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isProcessing) return

        val outputFile = File(outputDir, "cleaned_ffmpeg_${System.currentTimeMillis()}.mp4")

        _uiState.update {
            it.copy(
                isProcessing = true,
                exportedFile = null,
                progress = ProcessingProgress()
            )
        }
        AppLogManager.log(LogLevel.INFO, "Pipeline", "Starting FFmpeg export to: ${outputFile.name}")

        processingJob = viewModelScope.launch {
            try {
                val frames = _uiState.value.frames
                val success = if (frames.isNotEmpty()) {
                    val selectedIndices = frames.filter { it.isSelected }.map { it.index }
                    exporter.exportWithSelection(
                        inputUri = currentUri,
                        outputFile = outputFile,
                        selectedIndices = selectedIndices,
                        totalFrames = frames.size
                    ) { ratio ->
                        _uiState.update {
                            it.copy(
                                progress = it.progress.copy(
                                    progress = ratio,
                                    totalScanned = frames.size,
                                    preservedFrames = selectedIndices.size,
                                    droppedFrames = frames.size - selectedIndices.size
                                )
                            )
                        }
                    }
                } else {
                    exporter.exportWithMseThreshold(
                        inputUri = currentUri,
                        outputFile = outputFile,
                        mseThreshold = _uiState.value.mseThreshold.toDouble()
                    ) { ratio ->
                        _uiState.update { it.copy(progress = it.progress.copy(progress = ratio)) }
                    }
                }

                if (success) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            exportedFile = outputFile,
                            progress = it.progress.copy(progress = 1.0f, isCompleted = true)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            progress = it.progress.copy(errorMessage = "FFmpeg export failed. Check diagnostics log.")
                        )
                    }
                }
            } catch (e: Exception) {
                AppLogManager.log(LogLevel.ERROR, "Pipeline", "FFmpeg export exception: ${e.message}")
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progress = it.progress.copy(errorMessage = e.localizedMessage ?: "FFmpeg export error")
                    )
                }
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        AppLogManager.log(LogLevel.WARN, "Pipeline", "Video processing cancelled by user.")
        _uiState.update {
            it.copy(
                isProcessing = false,
                progress = it.progress.copy(errorMessage = "Processing cancelled by user.")
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel: VideoProcessingViewModel by viewModels()
    private lateinit var processingEngine: VideoProcessingEngine
    private lateinit var ffmpegExporter: FFmpegVideoExporter
    private lateinit var frameInspectorEngine: FrameInspectorEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Storage permissions are required to access videos.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processingEngine = VideoProcessingEngine(applicationContext)
        ffmpegExporter = FFmpegVideoExporter(applicationContext)
        frameInspectorEngine = FrameInspectorEngine(applicationContext)

        viewModel.checkCrashLog(this)
        checkAndRequestPermissions()

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF64B5F6),
                    surface = Color(0xFF1E1E1E),
                    background = Color(0xFF121212)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1976D2),
                    surface = Color(0xFFFFFFFF),
                    background = Color(0xFFF5F5F5)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                val state by viewModel.uiState.collectAsState()
                val logs by AppLogManager.logsFlow.collectAsState()

                MainScreen(
                    selectedUri = state.selectedUri,
                    selectedFileName = state.selectedFileName,
                    mseThreshold = state.mseThreshold,
                    isProcessing = state.isProcessing,
                    isAnalyzingFrames = state.isAnalyzingFrames,
                    frames = state.frames,
                    progress = state.progress,
                    exportedFile = state.exportedFile,
                    savedCrashLog = state.savedCrashLog,
                    logs = logs,
                    onSelectVideo = { uri ->
                        val fileName = queryFileName(uri)
                        viewModel.selectVideo(uri, fileName)
                    },
                    onThresholdChange = { threshold ->
                        viewModel.setThreshold(threshold)
                    },
                    onAnalyzeFrames = {
                        viewModel.analyzeFrames(frameInspectorEngine)
                    },
                    onToggleFrameSelection = { index ->
                        viewModel.toggleFrameSelection(index)
                    },
                    onSelectAllGoodFrames = {
                        viewModel.selectAllGoodFrames()
                    },
                    onSelectAllFrames = {
                        viewModel.selectAllFrames()
                    },
                    onInvertSelection = {
                        viewModel.invertSelection()
                    },
                    onStartMediaCodecExport = {
                        val outputDir = getExternalFilesDir(null) ?: cacheDir
                        viewModel.startMediaCodecExport(processingEngine, outputDir)
                    },
                    onStartFFmpegExport = {
                        val outputDir = getExternalFilesDir(null) ?: cacheDir
                        viewModel.startFFmpegExport(ffmpegExporter, outputDir)
                    },
                    onCancelExport = {
                        viewModel.cancelProcessing()
                    },
                    onOpenExportedVideo = { file ->
                        openExportedVideo(file)
                    },
                    onClearCrashLog = {
                        viewModel.clearCrashLog(this)
                    },
                    onClearLogs = {
                        AppLogManager.clearLogs()
                    }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "video.mp4"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: name
            }
        }
        return name
    }

    private fun openExportedVideo(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Clean MP4 saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
