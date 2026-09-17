package com.antigravity.deadframeremover.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.deadframeremover.engine.FrameItem
import com.antigravity.deadframeremover.engine.ProcessingProgress
import com.antigravity.deadframeremover.logging.AppLogManager
import com.antigravity.deadframeremover.logging.LogEntry
import com.antigravity.deadframeremover.logging.LogLevel
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    selectedUri: Uri?,
    selectedFileName: String?,
    mseThreshold: Float,
    isProcessing: Boolean,
    isAnalyzingFrames: Boolean,
    frames: List<FrameItem>,
    progress: ProcessingProgress,
    exportedFile: File?,
    savedCrashLog: String?,
    logs: List<LogEntry>,
    onSelectVideo: (Uri) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onAnalyzeFrames: () -> Unit,
    onToggleFrameSelection: (Int) -> Unit,
    onSelectAllGoodFrames: () -> Unit,
    onSelectAllFrames: () -> Unit,
    onInvertSelection: () -> Unit,
    onStartExport: (useSelection: Boolean) -> Unit,
    onCancelExport: () -> Unit,
    onOpenExportedVideo: (File) -> Unit,
    onClearCrashLog: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showLogDialog by remember { mutableStateOf(false) }
    var selectedLogTab by remember { mutableIntStateOf(if (savedCrashLog != null) 1 else 0) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onSelectVideo(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "DeadFrameRemover",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }
                },
                actions = {
                    // Logs & Crash Viewer Action Button
                    IconButton(onClick = {
                        if (savedCrashLog != null) selectedLogTab = 1
                        showLogDialog = true
                    }) {
                        BadgedBox(
                            badge = {
                                if (savedCrashLog != null) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                                        Text("!")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Open Logs & Crash Viewer"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Crash Warning Banner (if a previous crash occurred)
            if (savedCrashLog != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "App Crash Log Detected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "A previous fatal crash was captured by the crash catcher. You can inspect the stack trace and copy the log.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    selectedLogTab = 1
                                    showLogDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("View Crash Log")
                            }
                            OutlinedButton(onClick = onClearCrashLog) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            // 2. Video Source Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Input Video Source",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = selectedFileName ?: "No MP4 video selected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedFileName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { videoPickerLauncher.launch("video/mp4") },
                        enabled = !isProcessing && !isAnalyzingFrames,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedUri == null) "Select MP4 Video" else "Change Video")
                    }
                }
            }

            // 3. Duplicate MSE Threshold Slider
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Duplicate Threshold (MSE)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f", mseThreshold),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = "Frames with Mean Squared Error ≤ threshold are dropped as duplicates/freezes. For screen recordings & games, 0.5–2.0 is ideal to drop exact duplicate freezes. For camera videos, 2.0–5.0 catches subtle compression duplicates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Slider(
                        value = mseThreshold,
                        onValueChange = onThresholdChange,
                        valueRange = 0.1f..25.0f,
                        steps = 248,
                        enabled = !isProcessing && !isAnalyzingFrames
                    )
                }
            }

            // 4. Exact Frame Shower and Selector Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Exact Frame Shower & Selector",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Red = Dead/Duplicate | Green = Good Frame",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Action buttons: Analyze Frames & Selection presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onAnalyzeFrames,
                            enabled = selectedUri != null && !isProcessing && !isAnalyzingFrames,
                            modifier = Modifier.weight(1.2f)
                        ) {
                            if (isAnalyzingFrames) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analyzing…")
                            } else {
                                Icon(imageVector = Icons.Default.Search, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analyze Frames")
                            }
                        }

                        if (frames.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onSelectAllGoodFrames,
                                enabled = !isProcessing && !isAnalyzingFrames,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Select Good")
                            }
                            OutlinedButton(
                                onClick = onInvertSelection,
                                enabled = !isProcessing && !isAnalyzingFrames,
                                modifier = Modifier.weight(0.8f)
                            ) {
                                Text("Invert")
                            }
                        }
                    }

                    // Frame Statistics Counters
                    if (frames.isNotEmpty()) {
                        val goodCount = frames.count { !it.isDead }
                        val deadCount = frames.count { it.isDead }
                        val selectedCount = frames.count { it.isSelected }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FrameBadge(
                                modifier = Modifier.weight(1f),
                                title = "Total Frames",
                                count = frames.size.toString(),
                                borderColor = MaterialTheme.colorScheme.outline
                            )
                            FrameBadge(
                                modifier = Modifier.weight(1f),
                                title = "Good (Keep)",
                                count = goodCount.toString(),
                                borderColor = Color(0xFF43A047),
                                textColor = Color(0xFF43A047)
                            )
                            FrameBadge(
                                modifier = Modifier.weight(1f),
                                title = "Dead (Drop)",
                                count = deadCount.toString(),
                                borderColor = Color(0xFFE53935),
                                textColor = Color(0xFFE53935)
                            )
                            FrameBadge(
                                modifier = Modifier.weight(1f),
                                title = "Selected",
                                count = selectedCount.toString(),
                                borderColor = MaterialTheme.colorScheme.primary,
                                textColor = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Horizontal Frame Shower Strip
                        val listState = rememberLazyListState()
                        LazyRow(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp)
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(frames, key = { it.index }) { frame ->
                                FrameCardItem(
                                    frame = frame,
                                    onToggle = { onToggleFrameSelection(frame.index) }
                                )
                            }
                        }
                    } else if (!isAnalyzingFrames) {
                        Text(
                            text = "Tap 'Analyze Frames' to inspect exact video frames, identify freeze frames in RED, and select frames to export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // 5. Diagnostics & Pipeline Counters
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Transcode Progress & Metrics",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DiagnosticTile(
                            modifier = Modifier.weight(1f),
                            title = "Scanned",
                            value = progress.totalScanned.toString(),
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                        DiagnosticTile(
                            modifier = Modifier.weight(1f),
                            title = "Dropped",
                            value = progress.droppedFrames.toString(),
                            accentColor = Color(0xFFE53935)
                        )
                        DiagnosticTile(
                            modifier = Modifier.weight(1f),
                            title = "Preserved",
                            value = progress.preservedFrames.toString(),
                            accentColor = Color(0xFF43A047)
                        )
                    }

                    if (isProcessing || progress.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = String.format(Locale.US, "Progress: %.1f%%", progress.progress * 100f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.End),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 6. Error Notice
            if (progress.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = "Error: ${progress.errorMessage}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 7. Export Success Card
            if (progress.isCompleted && exportedFile != null && exportedFile.exists()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF81C784)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Video Export Completed!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF81C784)
                            )
                        }
                        Text(
                            text = "Output file: ${exportedFile.name} (${exportedFile.length() / 1024} KB)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { onOpenExportedVideo(exportedFile) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play Exported MP4 Video")
                        }
                    }
                }
            }

            // 8. Dual Exporter Action Buttons (FFmpeg & MediaCodec)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isProcessing) {
                    OutlinedButton(
                        onClick = onCancelExport,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel Processing")
                    }
                } else {
                    if (frames.isNotEmpty()) {
                        Button(
                            onClick = { onStartExport(true) },
                            enabled = selectedUri != null && !isAnalyzingFrames,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Selected Frames (H.264 MediaCodec)")
                        }

                        FilledTonalButton(
                            onClick = { onStartExport(false) },
                            enabled = selectedUri != null && !isAnalyzingFrames,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Auto-Drop Dead Frames (Threshold MediaCodec)")
                        }
                    } else {
                        Button(
                            onClick = { onStartExport(false) },
                            enabled = selectedUri != null && !isAnalyzingFrames,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Clean MP4 (Hardware MediaCodec)")
                        }
                    }
                }
            }
        }
    }

    // 9. Full Diagnostic Logcat & Crash Reporter Dialog
    if (showLogDialog) {
        LogcatAndCrashDialog(
            logs = logs,
            savedCrashLog = savedCrashLog,
            selectedTab = selectedLogTab,
            onTabSelected = { selectedLogTab = it },
            onDismiss = { showLogDialog = false },
            onCopyLogs = {
                val success = AppLogManager.copyEntireLogToClipboard(context)
                if (success) {
                    Toast.makeText(context, "Entire log copied to clipboard!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to copy logs.", Toast.LENGTH_SHORT).show()
                }
            },
            onClearLogs = onClearLogs,
            onClearCrashLog = onClearCrashLog
        )
    }
}

/**
 * Exact Frame Card:
 * Outlined with RED border if Dead / Duplicate.
 * Outlined with GREEN border if Good.
 */
@Composable
private fun FrameCardItem(
    frame: FrameItem,
    onToggle: () -> Unit
) {
    val borderColor = if (frame.isSelected) Color(0xFF43A047) else Color(0xFFE53935)
    val badgeBg = if (frame.isSelected) Color(0xFF43A047).copy(alpha = 0.15f) else Color(0xFFE53935).copy(alpha = 0.15f)
    val badgeText = if (frame.isSelected) "KEEP" else "DROP"
    val detectTag = if (frame.isDead) "Duplicate" else "Motion"

    Card(
        modifier = Modifier
            .width(160.dp)
            .fillMaxHeight()
            .border(BorderStroke(2.5.dp, borderColor), RoundedCornerShape(12.dp))
            .clickable { onToggle() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Frame index and Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${frame.index}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = "$badgeText ($detectTag)",
                        color = borderColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Frame Image Thumbnail
            if (frame.bitmap != null) {
                Image(
                    bitmap = frame.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(95.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(95.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Preview", fontSize = 11.sp, color = Color.Gray)
                }
            }

            // Footer: Timestamp, MSE, and Toggle Checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = frame.formattedTime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (frame.index == 0) "Base" else String.format(Locale.US, "MSE: %.1f", frame.mse),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (frame.isDead) Color(0xFFE53935) else Color(0xFF43A047)
                    )
                }

                Checkbox(
                    checked = frame.isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF43A047),
                        uncheckedColor = Color(0xFFE53935)
                    )
                )
            }
        }
    }
}

@Composable
private fun FrameBadge(
    modifier: Modifier = Modifier,
    title: String,
    count: String,
    borderColor: Color,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = title,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DiagnosticTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LogcatAndCrashDialog(
    logs: List<LogEntry>,
    savedCrashLog: String?,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onClearCrashLog: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Dialog Title Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Diagnostics & Crash Logs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Tabs: Live Logs vs Crash Report
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF2A2A2A),
                    contentColor = Color.White
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        text = { Text("App & Logcat (${logs.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Crash Report")
                                if (savedCrashLog != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color.Red, CircleShape)
                                    )
                                }
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Log Text Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTab == 0) {
                        if (logs.isEmpty()) {
                            Text(
                                text = "No log messages captured yet.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                logs.forEach { entry ->
                                    val textColor = when (entry.level) {
                                        LogLevel.ERROR -> Color(0xFFEF5350)
                                        LogLevel.WARN -> Color(0xFFFFB74D)
                                        LogLevel.INFO -> Color(0xFF81C784)
                                        LogLevel.FFMPEG -> Color(0xFF4FC3F7)
                                        LogLevel.DEBUG -> Color(0xFFB0BEC5)
                                    }
                                    Text(
                                        text = "[${entry.timestamp}] [${entry.level.name}] [${entry.tag}]: ${entry.message}",
                                        color = textColor,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    } else {
                        if (savedCrashLog != null) {
                            Text(
                                text = savedCrashLog,
                                color = Color(0xFFEF5350),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                text = "No crash report recorded. The application has not experienced unhandled crashes.",
                                color = Color(0xFF81C784),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Buttons with "Copy Entire Log to Clipboard"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onCopyLogs,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Entire Log to Clipboard", fontWeight = FontWeight.Bold)
                    }

                    if (selectedTab == 0) {
                        OutlinedButton(
                            onClick = onClearLogs,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Clear")
                        }
                    } else if (savedCrashLog != null) {
                        OutlinedButton(
                            onClick = onClearCrashLog,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350))
                        ) {
                            Text("Clear Crash")
                        }
                    }
                }
            }
        }
    }
}
