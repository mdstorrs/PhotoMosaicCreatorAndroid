package com.storrs.photomosaiccreatorandroid.ui.viewmodel

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storrs.photomosaiccreatorandroid.models.*
import com.storrs.photomosaiccreatorandroid.services.CoreMosaicGenerationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel for managing mosaic generation state and user interactions.
 */
class MosaicViewModel : ViewModel() {

    private val service = CoreMosaicGenerationService()

    // State management
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState

    private val _progress = MutableStateFlow(MosaicGenerationProgress(0, ""))
    val progress: StateFlow<MosaicGenerationProgress> = _progress

    private val _primaryImagePath = MutableStateFlow<String?>(null)
    val primaryImagePath: StateFlow<String?> = _primaryImagePath

    private val _cellPhotoPaths = MutableStateFlow<List<String>>(emptyList())
    val cellPhotoPaths: StateFlow<List<String>> = _cellPhotoPaths

    /**
     * Set the primary image path.
     */
    fun setPrimaryImagePath(path: String?) {
        _primaryImagePath.value = path
    }

    /**
     * Set the cell photo paths.
     */
    fun setCellPhotoPaths(paths: List<String>) {
        _cellPhotoPaths.value = paths.distinct()
    }

    /**
     * Add cell photos to the existing list.
     */
    fun addCellPhotos(paths: List<String>) {
        val merged = LinkedHashSet(_cellPhotoPaths.value)
        merged.addAll(paths)
        _cellPhotoPaths.value = merged.toList()
    }

    /**
     * Clear cell photos.
     */
    fun clearCellPhotos() {
        _cellPhotoPaths.value = emptyList()
    }

    /**
     * Remove selected cell photos.
     */
    fun removeCellPhotos(paths: List<String>) {
        if (paths.isEmpty()) return
        _cellPhotoPaths.value = _cellPhotoPaths.value.filterNot { it in paths }
    }

    /**
     * Generate mosaic with default settings.
     */
    fun generateMosaicWithDefaults() {
        val primaryPath = _primaryImagePath.value
        val cellPaths = _cellPhotoPaths.value

        if (primaryPath == null || primaryPath.isBlank()) {
            _generationState.value = GenerationState.Error("Please select a primary image")
            return
        }

        if (cellPaths.isEmpty()) {
            _generationState.value = GenerationState.Error("Please select at least one cell photo")
            return
        }

        viewModelScope.launch {
            _generationState.value = GenerationState.Loading
            try {
                // Create project with default settings
                val project = createDefaultProject(primaryPath, cellPaths)

                // Generate mosaic
                val result = service.generateMosaic(
                    project,
                    onProgress = { progress ->
                        _progress.value = progress
                    }
                )

                if (result.isSuccess) {
                    _generationState.value = GenerationState.Success(result)
                } else {
                    _generationState.value = GenerationState.Error(
                        result.errorMessage ?: "Unknown error occurred"
                    )
                }
            } catch (e: Exception) {
                _generationState.value = GenerationState.Error(
                    e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    /**
     * Cancel the current generation.
     */
    fun cancelGeneration() {
        _generationState.value = GenerationState.Idle
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        _generationState.value = GenerationState.Idle
        _progress.value = MosaicGenerationProgress(0, "")
    }

    /**
     * Save the mosaic result to the device's Pictures folder (Gallery).
     */
    fun saveToGallery(context: Context, result: MosaicResult): String? {
        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val mosaicDir = File(picturesDir, "PhotoMosaics")

            if (!mosaicDir.exists()) {
                mosaicDir.mkdirs()
            }

            // Create filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "mosaic_$timestamp.jpg"
            val savedFile = File(mosaicDir, fileName)

            // Copy the temporary file to the Pictures directory
            result.temporaryFilePath?.let { tempPath ->
                val tempFile = File(tempPath)
                if (tempFile.exists()) {
                    tempFile.copyTo(savedFile, overwrite = true)

                    // Notify the media scanner so the file appears in Gallery
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(savedFile.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )

                    return savedFile.absolutePath
                }
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("MosaicViewModel", "Error saving to gallery", e)
            null
        }
    }

    /**
     * Create project with default settings.
     */
    private fun createDefaultProject(
        primaryImagePath: String,
        cellPhotoPaths: List<String>
    ): PhotoMosaicProject {
        return PhotoMosaicProject(
            primaryImagePath = primaryImagePath,
            cellPhotos = cellPhotoPaths.map { path ->
                CellPhoto(
                    path = path,
                    // Try to determine orientation from filename or default to Square
                    orientation = PhotoOrientation.Square
                )
            },
            // Default print size: 20" x 30"
            selectedPrintSize = PrintSize("20x30", 20.0, 30.0),
            // Default resolution: 300 DPI (high quality print)
            selectedResolution = Resolution("300 DPI", 300),
            // Default cell size: 15mm
            selectedCellSize = CellSize("15mm", 15.0),
            // Default pattern: Square
            selectedCellPhotoPattern = CellPhotoPatternConfig("Square"),
            // Default color change: 10%
            selectedColorChange = ColorChange("20%", 20),
            // Default duplicate spacing: 3
            selectedDuplicateSpacing = DuplicateSpacing("3", 3),
            // Other defaults
            cellShape = CellShape.Square,
            cellImageFitMode = CellImageFitMode.CropCenter,
            primaryImageSizingMode = PrimaryImageSizingMode.KeepAspectRatio,
            randomCellCandidates = 5,
            useAllImages = false,
            createReport = true
        )
    }
}

/**
 * Represents the state of mosaic generation.
 */
sealed class GenerationState {
    object Idle : GenerationState()
    object Loading : GenerationState()
    data class Success(val result: MosaicResult) : GenerationState()
    data class Error(val message: String) : GenerationState()
}
