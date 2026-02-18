package com.storrs.photomosaiccreatorandroid

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.storrs.photomosaiccreatorandroid.ui.theme.PhotoMosaicCreatorAndroidTheme
import com.storrs.photomosaiccreatorandroid.ui.viewmodel.GenerationState
import com.storrs.photomosaiccreatorandroid.ui.viewmodel.MosaicViewModel
import java.io.File
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModel = ViewModelProvider(this)[MosaicViewModel::class.java]
        setContent {
            PhotoMosaicCreatorAndroidTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MosaicGeneratorScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun MosaicGeneratorScreen(viewModel: MosaicViewModel) {
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val primaryImagePath by viewModel.primaryImagePath.collectAsStateWithLifecycle()
    val cellPhotoPaths by viewModel.cellPhotoPaths.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCellPhotoGrid by rememberSaveable { mutableStateOf(false) }

    // File picker launchers
    val primaryImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = getRealPathFromURI(context, it)
            if (path != null) {
                viewModel.setPrimaryImagePath(path)
            }
        }
    }

    val multipleImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val paths = uris.mapNotNull { getRealPathFromURI(context, it) }
        if (paths.isNotEmpty()) {
            viewModel.addCellPhotos(paths)
        }
    }

    if (showCellPhotoGrid) {
        CellPhotoGridScreen(
            cellPhotoPaths = cellPhotoPaths,
            onClose = { showCellPhotoGrid = false },
            onAddMore = { multipleImagesLauncher.launch("image/*") },
            onClearAll = { viewModel.clearCellPhotos() },
            onDeleteSelected = { selected ->
                viewModel.removeCellPhotos(selected)
            }
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Text(
                text = "Photo Mosaic Creator",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Image Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Primary Image",
                        style = MaterialTheme.typography.titleMedium
                    )

                    val hasPrimaryImage = primaryImagePath != null
                    if (hasPrimaryImage) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            AsyncImage(
                                model = File(primaryImagePath!!),
                                contentDescription = "Primary image preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    Button(
                        onClick = { primaryImageLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select Primary Image")
                    }
                }
            }

            // Cell Photos Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Cell Photos",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Button(
                        onClick = { multipleImagesLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Cell Photos")
                    }

                    if (cellPhotoPaths.isNotEmpty()) {
                        Button(
                            onClick = { showCellPhotoGrid = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("${cellPhotoPaths.size} photo(s) selected")
                        }
                    } else {
                        Text(
                            text = "No photos selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Default Settings Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Default Settings",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "• Print Size: 20\" × 30\"\n• Resolution: 300 DPI\n• Cell Size: 15mm\n• Color Change: 10%\n• Duplicate Spacing: 3\n• Pattern: Square",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Generate Button
            if (generationState !is GenerationState.Loading) {
                Button(
                    onClick = { viewModel.generateMosaicWithDefaults() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = primaryImagePath != null && cellPhotoPaths.isNotEmpty()
                ) {
                    Text(
                        text = "Generate Mosaic",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Generation State UI
            when (generationState) {
                is GenerationState.Idle -> {
                    // Idle state, no UI needed
                }
                is GenerationState.Loading -> {
                    GenerationProgressUI(progress, viewModel)
                }
                is GenerationState.Success -> {
                    val result = (generationState as GenerationState.Success).result
                    GenerationSuccessUI(result, viewModel)
                }
                is GenerationState.Error -> {
                    val message = (generationState as GenerationState.Error).message
                    GenerationErrorUI(message, viewModel)
                }
            }
        }
    }
}

@Composable
fun GenerationProgressUI(
    progress: com.storrs.photomosaiccreatorandroid.models.MosaicGenerationProgress,
    viewModel: MosaicViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Generating Mosaic...",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = progress.currentStage,
                style = MaterialTheme.typography.bodySmall
            )

            LinearProgressIndicator(
                progress = { progress.percentComplete / 100f },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "${progress.percentComplete}% Complete",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.cancelGeneration() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun GenerationSuccessUI(
    result: com.storrs.photomosaiccreatorandroid.models.MosaicResult,
    viewModel: MosaicViewModel
) {
    val context = LocalContext.current
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "✓ Mosaic Generated Successfully!",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Grid: ${result.gridRows} × ${result.gridColumns}\nSize: ${result.outputWidth} × ${result.outputHeight}px\nPhotos: ${result.usedCellPhotos}/${result.totalCellPhotos}\nTime: ${result.generationTimeMs}ms",
                style = MaterialTheme.typography.bodySmall
            )

            if (result.temporaryFilePath != null) {
                Text(
                    text = "Saved to: ${result.temporaryFilePath?.substringAfterLast("/")}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.background,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(8.dp)
                )
            }

            // Show save status message if available
            if (saveMessage != null) {
                Text(
                    text = saveMessage!!,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (saveMessage!!.startsWith("✓"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(8.dp)
                )
            }

            // Button row
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isSaving) {
                            isSaving = true
                            val savedPath = viewModel.saveToGallery(context, result)
                            if (savedPath != null) {
                                saveMessage = "✓ Saved to Gallery: ${File(savedPath).name}"
                            } else {
                                saveMessage = "✗ Failed to save to Gallery"
                            }
                            isSaving = false
                        }
                    },
                    modifier = Modifier
                        .weight(1f),
                    enabled = !isSaving
                ) {
                    Text(if (isSaving) "Saving..." else "Save to Gallery")
                }

                Button(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Create Another")
                }
            }
        }
    }
}

@Composable
fun GenerationErrorUI(message: String, viewModel: MosaicViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "✗ Error",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { viewModel.reset() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try Again")
            }
        }
    }
}

@Composable
fun CellPhotoGridScreen(
    cellPhotoPaths: List<String>,
    onClose: () -> Unit,
    onAddMore: () -> Unit,
    onClearAll: () -> Unit,
    onDeleteSelected: (List<String>) -> Unit
) {
    val selectedPaths = remember { mutableStateListOf<String>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Cell Photos",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text("Back")
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (cellPhotoPaths.isEmpty()) {
                Text(
                    text = "No photos selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        bottom = 120.dp
                    )
                ) {
                    items(cellPhotoPaths, key = { it }) { path ->
                        val isSelected = selectedPaths.contains(path)
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        Color.Transparent,
                                    shape = MaterialTheme.shapes.small
                                )
                                .clickable {
                                    if (isSelected) {
                                        selectedPaths.remove(path)
                                    } else {
                                        selectedPaths.add(path)
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Cell photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedPaths.size} selected",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                onDeleteSelected(selectedPaths.toList())
                                selectedPaths.clear()
                            },
                            enabled = selectedPaths.isNotEmpty()
                        ) {
                            Text("Delete")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearAll,
                            modifier = Modifier.weight(1f),
                            enabled = cellPhotoPaths.isNotEmpty()
                        ) {
                            Text("Clear All")
                        }
                        Button(
                            onClick = onAddMore,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Add More")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Get the real file path from a URI.
 * This converts content URIs to actual file paths by querying the content provider.
 */
fun getRealPathFromURI(context: android.content.Context, uri: Uri): String? {
    return when (uri.scheme) {
        "content" -> {
            // Query the content provider to get the actual file path
            try {
                val cursor = context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Images.Media.DATA),
                    null,
                    null,
                    null
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        return it.getString(index)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e("MainActivity", "Error getting path from content URI", e)
                null
            }
        }
        "file" -> {
            uri.path
        }
        else -> null
    }
}