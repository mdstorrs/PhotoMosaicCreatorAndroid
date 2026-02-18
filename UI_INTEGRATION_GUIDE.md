# UI Integration Guide

## ViewModel Pattern Example

```kotlin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MosaicGenerationViewModel : ViewModel() {
    private val service = CoreMosaicGenerationService()
    
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState
    
    private val _progress = MutableStateFlow(MosaicGenerationProgress(0, ""))
    val progress: StateFlow<MosaicGenerationProgress> = _progress
    
    fun generateMosaic(project: PhotoMosaicProject) {
        viewModelScope.launch {
            _generationState.value = GenerationState.Loading
            try {
                val result = service.generateMosaic(
                    project,
                    onProgress = { _progress.value = it }
                )
                
                if (result.isSuccess) {
                    _generationState.value = GenerationState.Success(result)
                } else {
                    _generationState.value = GenerationState.Error(result.errorMessage ?: "Unknown error")
                }
            } catch (e: Exception) {
                _generationState.value = GenerationState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    fun cancelGeneration() {
        // Cancel the active coroutine job
        viewModelScope.coroutineContext.cancel()
    }
}

sealed class GenerationState {
    object Idle : GenerationState()
    object Loading : GenerationState()
    data class Success(val result: MosaicResult) : GenerationState()
    data class Error(val message: String) : GenerationState()
}
```

## Jetpack Compose UI Example

```kotlin
@Composable
fun MosaicGenerationScreen(viewModel: MosaicGenerationViewModel = hiltViewModel()) {
    val state by viewModel.generationState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
        when (state) {
            is GenerationState.Idle -> {
                Button(onClick = {
                    val project = buildProject() // Your project setup
                    viewModel.generateMosaic(project)
                }) {
                    Text("Generate Mosaic")
                }
            }
            
            is GenerationState.Loading -> {
                GenerationProgressUI(progress)
                Button(onClick = { viewModel.cancelGeneration() }) {
                    Text("Cancel")
                }
            }
            
            is GenerationState.Success -> {
                val result = (state as GenerationState.Success).result
                MosaicResultUI(result)
            }
            
            is GenerationState.Error -> {
                val error = (state as GenerationState.Error).message
                ErrorUI(error)
            }
        }
    }
}

@Composable
fun GenerationProgressUI(progress: MosaicGenerationProgress) {
    Column {
        Text("${progress.currentStage}")
        LinearProgressIndicator(
            progress = progress.percentComplete / 100f,
            modifier = Modifier.fillMaxWidth()
        )
        Text("${progress.percentComplete}%")
    }
}

@Composable
fun MosaicResultUI(result: MosaicResult) {
    Column {
        Text("Mosaic Generated!")
        Text("Grid: ${result.gridRows} x ${result.gridColumns}")
        Text("Output: ${result.outputWidth} x ${result.outputHeight}px")
        Text("Used ${result.usedCellPhotos} of ${result.totalCellPhotos} photos")
        Text("Generated in ${result.generationTimeMs}ms")
        
        result.temporaryFilePath?.let {
            Button(onClick = { shareOrSaveMosaic(it) }) {
                Text("Save Mosaic")
            }
        }
        
        result.usageReportPath?.let {
            Button(onClick = { openReport(it) }) {
                Text("View Report")
            }
        }
    }
}
```

## Handling Configuration Changes

```kotlin
// In your Activity/Fragment
viewModel.generationState.collect { state ->
    when (state) {
        is GenerationState.Loading -> {
            // Survives configuration change
            showProgressDialog()
        }
        is GenerationState.Success -> {
            dismissDialog()
            displayResult(state.result)
        }
        is GenerationState.Error -> {
            dismissDialog()
            showErrorMessage(state.message)
        }
        else -> {}
    }
}
```

## Memory Management Best Practices

```kotlin
// Good: Let service handle cleanup
val result = service.generateMosaic(project)
// Bitmaps are recycled automatically in buildCellCache.forEach { it.cleanup() }

// Good: Explicit cleanup if storing bitmaps
result.temporaryFilePath?.let {
    val mosaic = BitmapFactory.decodeFile(it)
    try {
        // Use mosaic
        displayImage(mosaic)
    } finally {
        mosaic.recycle()
    }
}

// Avoid: Storing bitmaps long-term
// Use file paths instead and load on demand
```

## Progress Reporting Best Practices

```kotlin
// Update UI at reasonable intervals (not every single percent)
private var lastUIUpdate = System.currentTimeMillis()

val progress = { percent: Int ->
    val now = System.currentTimeMillis()
    if (now - lastUIUpdate > 500) { // Update max every 500ms
        _progress.value = it
        lastUIUpdate = now
    }
}

val result = service.generateMosaic(project, onProgress = progress)
```

## File Storage Management

```kotlin
// Save mosaic to persistent storage
fun saveMosaicToGallery(mosaicPath: String, context: Context) {
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = "image/jpeg"
    }
    
    // Or save to app-specific directory
    val appDir = context.getExternalFilesDir("mosaics")
    File(appDir, "mosaic_${System.currentTimeMillis()}.jpg").apply {
        mosaicPath.let {
            File(it).copyTo(this, overwrite = true)
        }
    }
}

// Cleanup temp files after use
fun cleanupTempFiles(result: MosaicResult) {
    result.temporaryFilePath?.let { File(it).delete() }
    result.overlayImagePath?.let { File(it).delete() }
    result.usageReportPath?.let { File(it).delete() }
}
```

## Error Handling Strategy

```kotlin
enum class MosaicError {
    INVALID_CONFIGURATION,
    PRIMARY_IMAGE_NOT_FOUND,
    NO_CELL_PHOTOS,
    INSUFFICIENT_MEMORY,
    OPERATION_CANCELLED,
    UNKNOWN
}

fun getMosaicError(errorMessage: String?): MosaicError {
    return when {
        errorMessage?.contains("Primary image") == true -> MosaicError.PRIMARY_IMAGE_NOT_FOUND
        errorMessage?.contains("No cell photos") == true -> MosaicError.NO_CELL_PHOTOS
        errorMessage?.contains("cancelled") == true -> MosaicError.OPERATION_CANCELLED
        errorMessage?.contains("memory") == true -> MosaicError.INSUFFICIENT_MEMORY
        else -> MosaicError.UNKNOWN
    }
}

// Show user-friendly error messages
fun getUserMessage(error: MosaicError): String = when (error) {
    MosaicError.INVALID_CONFIGURATION -> "Please configure all required settings"
    MosaicError.PRIMARY_IMAGE_NOT_FOUND -> "Primary image file not found"
    MosaicError.NO_CELL_PHOTOS -> "Add at least one cell photo"
    MosaicError.INSUFFICIENT_MEMORY -> "Not enough memory - try fewer photos"
    MosaicError.OPERATION_CANCELLED -> "Generation cancelled"
    MosaicError.UNKNOWN -> "An error occurred"
}
```

## Performance Monitoring

```kotlin
// Track generation time and efficiency
fun logGenerationMetrics(result: MosaicResult) {
    val totalPixels = result.outputWidth * result.outputHeight
    val pixelsPerMs = totalPixels / result.generationTimeMs
    val efficiencyPercent = (result.usedCellPhotos * 100) / result.totalCellPhotos
    
    Log.d("Mosaic", """
        Generation Time: ${result.generationTimeMs}ms
        Grid: ${result.gridRows}x${result.gridColumns}
        Total Cells: ${result.gridRows * result.gridColumns}
        Used Photos: $efficiencyPercent%
        Pixels/ms: $pixelsPerMs
    """.trimIndent())
}
```

## Testing Utilities

```kotlin
// Mock data for testing
fun createTestProject(): PhotoMosaicProject {
    return PhotoMosaicProject(
        primaryImagePath = "/path/to/test/primary.jpg",
        cellPhotos = (1..100).map {
            CellPhoto(
                "/path/to/cells/photo_$it.jpg",
                PhotoOrientation.values().random()
            )
        },
        selectedPrintSize = PrintSize("8x10", 8.0, 10.0),
        selectedResolution = Resolution("300 DPI", 300),
        selectedCellSize = CellSize("0.5\"", 12.7),
        selectedCellPhotoPattern = CellPhotoPatternConfig("Square")
    )
}

// Test cancellation
fun testCancellation() = runTest {
    val job = launch {
        service.generateMosaic(createTestProject())
    }
    delay(100) // Let it start
    job.cancel()
    assertTrue(job.isCancelled)
}
```

## Accessibility Considerations

```kotlin
@Composable
fun AccessibleProgressScreen(progress: MosaicGenerationProgress) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                contentDescription = "Generating mosaic at ${progress.percentComplete}% - ${progress.currentStage}"
            }
    ) {
        LinearProgressIndicator(progress = progress.percentComplete / 100f)
        Text("${progress.percentComplete}% Complete")
        Text(progress.currentStage)
    }
}
```

## Resource Cleanup Checklist

- [ ] Bitmaps explicitly recycled
- [ ] Temp files deleted after use
- [ ] Coroutines cancelled on destroy
- [ ] File streams properly closed
- [ ] Memory pressure handled gracefully
- [ ] Configuration changes don't restart work
- [ ] Progress survives pause/resume

