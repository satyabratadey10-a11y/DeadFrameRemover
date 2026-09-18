package com.antigravity.deadframeremover

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
    val mseThreshold: Float = 1.5f,
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
                    maxFramesToSample = 240
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

    fun startExport(context: android.content.Context, engine: VideoProcessingEngine, useSelection: Boolean = false) {
        val currentUri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isProcessing) return

        val tempFile = File(context.cacheDir, "temp_export_${System.currentTimeMillis()}.mp4")
        val finalFileName = "cleaned_video_${System.currentTimeMillis()}.mp4"

        _uiState.update {
            it.copy(
                isProcessing = true,
                exportedFile = null,
                progress = ProcessingProgress()
            )
        }
        val frames = _uiState.value.frames
        val exportType = if (useSelection && frames.isNotEmpty()) "Selective Frames" else "Threshold-Based"
        AppLogManager.log(LogLevel.INFO, "Pipeline", "Starting H.264 MediaCodec export ($exportType) to Download folder: $finalFileName")

        processingJob = viewModelScope.launch {
            try {
                engine.processVideo(
                    inputUri = currentUri,
                    outputFile = tempFile,
                    mseThreshold = _uiState.value.mseThreshold.toDouble(),
                    selectedFrames = if (useSelection && frames.isNotEmpty()) frames else null
                ) { progressUpdate ->
                    _uiState.update { it.copy(progress = progressUpdate) }
                }

                // Move/Save the exported file directly into /storage/emulated/0/Download/
                val exportedFile = saveToDownloadsFolder(context, tempFile, finalFileName)

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        exportedFile = exportedFile
                    )
                }
                AppLogManager.log(LogLevel.INFO, "Pipeline", "H.264 MediaCodec export completed successfully and saved to: ${exportedFile.absolutePath}")
            } catch (e: Exception) {
                AppLogManager.log(LogLevel.ERROR, "Pipeline", "MediaCodec export failed: ${e.message}")
                try { tempFile.delete() } catch (_: Exception) {}
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progress = it.progress.copy(errorMessage = e.localizedMessage ?: "Export error")
                    )
                }
            }
        }
    }

    private fun saveToDownloadsFolder(context: android.content.Context, tempFile: File, fileName: String): File {
        // Target public download folder: /storage/emulated/0/Download/
        val downloadFolder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "")
        if (!downloadFolder.exists()) {
            downloadFolder.mkdirs()
        }

        var destFile = File(downloadFolder, fileName)
        var copySucceeded = false

        try {
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()
            copySucceeded = true
            AppLogManager.log(LogLevel.INFO, "FileSave", "Saved directly via File API to: ${destFile.absolutePath}")
        } catch (e: Exception) {
            AppLogManager.log(LogLevel.WARN, "FileSave", "Direct copy to Download directory failed (${e.message}). Falling back to MediaStore...")
        }

        if (!copySucceeded) {
            destFile = saveViaMediaStore(context, tempFile, fileName)
            try { tempFile.delete() } catch (_: Exception) {}
        }

        // Notify Android MediaScanner so file is immediately indexed in Download list and Gallery
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                AppLogManager.log(LogLevel.INFO, "MediaScanner", "Scanned & indexed to media store: $path ($uri)")
            }
        } catch (e: Exception) {
            AppLogManager.log(LogLevel.WARN, "MediaScanner", "MediaScanner scan failed: ${e.message}")
        }

        return destFile
    }

    private fun saveViaMediaStore(context: android.content.Context, tempFile: File, fileName: String): File {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create MediaStore entry in Downloads")

        context.contentResolver.openOutputStream(itemUri)?.use { outStream ->
            tempFile.inputStream().use { inStream ->
                inStream.copyTo(outStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(itemUri, values, null, null)
        }

        return File("/storage/emulated/0/Download", fileName)
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
    private var processingEngine: VideoProcessingEngine? = null
    private var frameInspectorEngine: FrameInspectorEngine? = null

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

        try {
            processingEngine = VideoProcessingEngine(applicationContext)
        } catch (t: Throwable) {
            AppLogManager.log(LogLevel.ERROR, "MainActivity", "VideoProcessingEngine init error: ${t.message}")
        }

        try {
            frameInspectorEngine = FrameInspectorEngine(applicationContext)
        } catch (t: Throwable) {
            AppLogManager.log(LogLevel.ERROR, "MainActivity", "FrameInspectorEngine init error: ${t.message}")
        }

        try {
            viewModel.checkCrashLog(this)
        } catch (t: Throwable) {
            AppLogManager.log(LogLevel.ERROR, "MainActivity", "checkCrashLog error: ${t.message}")
        }

        try {
            checkAndRequestPermissions()
        } catch (t: Throwable) {
            AppLogManager.log(LogLevel.ERROR, "MainActivity", "checkAndRequestPermissions error: ${t.message}")
        }

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
                        val engine = frameInspectorEngine ?: FrameInspectorEngine(applicationContext).also { frameInspectorEngine = it }
                        viewModel.analyzeFrames(engine)
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
                    onStartExport = { useSelection ->
                        val engine = processingEngine ?: VideoProcessingEngine(applicationContext).also { processingEngine = it }
                        viewModel.startExport(applicationContext, engine, useSelection)
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
