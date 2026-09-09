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
import com.antigravity.deadframeremover.engine.ProcessingProgress
import com.antigravity.deadframeremover.engine.VideoProcessingEngine
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
    val progress: ProcessingProgress = ProcessingProgress(),
    val exportedFile: File? = null
)

class VideoProcessingViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()

    private var processingJob: Job? = null

    fun selectVideo(uri: Uri, fileName: String?) {
        _uiState.update {
            it.copy(
                selectedUri = uri,
                selectedFileName = fileName,
                progress = ProcessingProgress(),
                exportedFile = null
            )
        }
    }

    fun setThreshold(threshold: Float) {
        _uiState.update { it.copy(mseThreshold = threshold) }
    }

    fun startProcessing(engine: VideoProcessingEngine, outputDir: File) {
        val currentUri = _uiState.value.selectedUri ?: return
        if (_uiState.value.isProcessing) return

        val outputFile = File(outputDir, "cleaned_${System.currentTimeMillis()}.mp4")

        _uiState.update {
            it.copy(
                isProcessing = true,
                exportedFile = null,
                progress = ProcessingProgress()
            )
        }

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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        progress = it.progress.copy(errorMessage = e.localizedMessage ?: "Processing error")
                    )
                }
            }
        }
    }

    fun cancelProcessing() {
        processingJob?.cancel()
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

                MainScreen(
                    selectedUri = state.selectedUri,
                    selectedFileName = state.selectedFileName,
                    mseThreshold = state.mseThreshold,
                    isProcessing = state.isProcessing,
                    progress = state.progress,
                    exportedFile = state.exportedFile,
                    onSelectVideo = { uri ->
                        val fileName = queryFileName(uri)
                        viewModel.selectVideo(uri, fileName)
                    },
                    onThresholdChange = { threshold ->
                        viewModel.setThreshold(threshold)
                    },
                    onStartExport = {
                        val outputDir = getExternalFilesDir(null) ?: cacheDir
                        viewModel.startProcessing(processingEngine, outputDir)
                    },
                    onCancelExport = {
                        viewModel.cancelProcessing()
                    },
                    onOpenExportedVideo = { file ->
                        openExportedVideo(file)
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
