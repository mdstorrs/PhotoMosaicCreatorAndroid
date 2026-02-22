package com.storrs.photomosaiccreatorandroid.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storrs.photomosaiccreatorandroid.models.*
import com.storrs.photomosaiccreatorandroid.persistence.StateRepository
import com.storrs.photomosaiccreatorandroid.services.CoreMosaicGenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor

/**
 * ViewModel for managing mosaic generation state and user interactions.
 * Uses AndroidViewModel for Application context to persist state.
 */
class MosaicViewModel(application: Application) : AndroidViewModel(application) {

    private val service = CoreMosaicGenerationService()
    private val repo = StateRepository(application)

    /** Dedicated subdirectory inside app cache for all mosaic temp files. */
    private val mosaicCacheDir: File = File(application.cacheDir, "mosaic_output").also {
        it.mkdirs()
        service.outputDir = it
    }

    // State management
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState

    private val _progress = MutableStateFlow(MosaicGenerationProgress(0, ""))
    val progress: StateFlow<MosaicGenerationProgress> = _progress

    private val _primaryImagePath = MutableStateFlow<String?>(null)
    val primaryImagePath: StateFlow<String?> = _primaryImagePath

    private val _cellPhotoPaths = MutableStateFlow<List<String>>(emptyList())
    val cellPhotoPaths: StateFlow<List<String>> = _cellPhotoPaths

    private val _settings = MutableStateFlow(createDefaultSettings())
    val settings: StateFlow<MosaicSettingsState> = _settings

    private val orientationCache = mutableMapOf<String, PhotoOrientation>()

    /** Tracks the active generation coroutine so it can be cancelled. */
    private var generationJob: Job? = null

    init {
        restoreState()
        recomputeSettings()
    }

    /**
     * Restores persisted state from SharedPreferences.
     */
    private fun restoreState() {
        // Restore primary image (only if the file still exists)
        repo.loadPrimaryImagePath()?.let { path ->
            if (File(path).exists()) {
                _primaryImagePath.value = path
            }
        }

        // Restore cell photos (filter out any that no longer exist)
        val savedCells = repo.loadCellPhotoPaths().filter { File(it).exists() }
        if (savedCells.isNotEmpty()) {
            _cellPhotoPaths.value = savedCells
        }

        // Restore settings
        val defaults = createDefaultSettings()
        var restored = defaults

        repo.loadPrintSizeLabel()?.let { label ->
            defaults.printSizes.firstOrNull { it.label == label }?.let {
                restored = restored.copy(selectedPrintSize = it)
            }
        }

        repo.loadCellSizeLabel()?.let { label ->
            defaults.cellSizes.firstOrNull { it.label == label }?.let {
                restored = restored.copy(selectedCellSize = it)
            }
        }

        val savedColorChange = repo.loadColorChangePercent()
        if (savedColorChange >= 0) {
            restored = restored.copy(colorChangePercent = savedColorChange)
        }

        repo.loadPattern()?.let {
            restored = restored.copy(pattern = it)
        }

        repo.loadUseAllImages()?.let {
            restored = restored.copy(useAllImages = it)
        }

        repo.loadMirrorImages()?.let {
            restored = restored.copy(mirrorImages = it)
        }

        _settings.value = restored
    }

    /**
     * Set the primary image path.
     */
    fun setPrimaryImagePath(path: String?) {
        _primaryImagePath.value = path
        repo.savePrimaryImagePath(path)
    }

    /**
     * Set the cell photo paths.
     */
    fun setCellPhotoPaths(paths: List<String>) {
        _cellPhotoPaths.value = paths.distinct()
        repo.saveCellPhotoPaths(_cellPhotoPaths.value)
        recomputeSettings()
    }

    /**
     * Add cell photos to the existing list.
     */
    fun addCellPhotos(paths: List<String>) {
        val merged = LinkedHashSet(_cellPhotoPaths.value)
        merged.addAll(paths)
        _cellPhotoPaths.value = merged.toList()
        repo.saveCellPhotoPaths(_cellPhotoPaths.value)
        recomputeSettings()
    }

    /**
     * Clear cell photos.
     */
    fun clearCellPhotos() {
        _cellPhotoPaths.value = emptyList()
        repo.saveCellPhotoPaths(emptyList())
        recomputeSettings()
    }

    /**
     * Remove selected cell photos.
     */
    fun removeCellPhotos(paths: List<String>) {
        if (paths.isEmpty()) return
        _cellPhotoPaths.value = _cellPhotoPaths.value.filterNot { it in paths }
        repo.saveCellPhotoPaths(_cellPhotoPaths.value)
        recomputeSettings()
    }

    fun updatePrintSize(option: PrintSizeOption) {
        _settings.value = _settings.value.copy(selectedPrintSize = option)
        repo.savePrintSizeLabel(option.label)
        recomputeSettings()
    }

    fun updateCellSize(option: CellSizeOption) {
        _settings.value = _settings.value.copy(selectedCellSize = option)
        repo.saveCellSizeLabel(option.label)
        recomputeSettings()
    }

    fun updateColorChange(percent: Int) {
        _settings.value = _settings.value.copy(colorChangePercent = percent.coerceIn(0, 100))
        repo.saveColorChangePercent(percent.coerceIn(0, 100))
        recomputeSettings()
    }

    fun updatePattern(pattern: PatternKind) {
        _settings.value = _settings.value.copy(pattern = pattern)
        repo.savePattern(pattern)
        recomputeSettings()
    }

    fun updateUseAllImages(enabled: Boolean) {
        _settings.value = _settings.value.copy(useAllImages = enabled)
        repo.saveUseAllImages(enabled)
    }

    fun updateMirrorImages(enabled: Boolean) {
        _settings.value = _settings.value.copy(mirrorImages = enabled)
        repo.saveMirrorImages(enabled)
    }

    fun buildDebugReport(primaryPath: String?, cellPaths: List<String>): String {
        if (primaryPath.isNullOrBlank()) {
            return "Primary image not selected."
        }
        if (cellPaths.isEmpty()) {
            return "No cell photos selected."
        }

        val project = createDefaultProject(primaryPath, cellPaths)
        val report = StringBuilder()
        val settings = _settings.value

        report.appendLine("Primary Image:")
        report.appendLine("  Path: ${project.primaryImagePath}")
        report.appendLine()

        val landscapeCount = project.cellPhotos.count { it.orientation == PhotoOrientation.Landscape }
        val portraitCount = project.cellPhotos.count { it.orientation == PhotoOrientation.Portrait }

        report.appendLine("Cell Photos:")
        report.appendLine("  Count: ${project.cellPhotos.size}")
        report.appendLine("  Landscape: $landscapeCount")
        report.appendLine("  Portrait: $portraitCount")
        report.appendLine()

        report.appendLine("Print Size:")
        report.appendLine("  Name: ${project.selectedPrintSize?.name}")
        report.appendLine("  Width: ${project.selectedPrintSize?.width}")
        report.appendLine("  Height: ${project.selectedPrintSize?.height}")
        report.appendLine()

        report.appendLine("Resolution:")
        report.appendLine("  Name: ${project.selectedResolution?.name}")
        report.appendLine("  DPI: ${project.selectedResolution?.ppi}")
        report.appendLine()

        report.appendLine("Cell Size:")
        report.appendLine("  Name: ${project.selectedCellSize?.name}")
        report.appendLine("  Size (mm): ${project.selectedCellSize?.sizeMm}")
        report.appendLine()

        report.appendLine("Pattern:")
        report.appendLine("  Name: ${project.selectedCellPhotoPattern?.name}")
        report.appendLine()

        report.appendLine("Color Change:")
        report.appendLine("  Name: ${project.selectedColorChange?.name}")
        report.appendLine("  Percentage: ${project.selectedColorChange?.percentageChange}")
        report.appendLine()

        report.appendLine("Duplicate Spacing:")
        report.appendLine("  Name: ${project.selectedDuplicateSpacing?.name}")
        report.appendLine("  Min Spacing: ${project.selectedDuplicateSpacing?.minSpacing}")
        report.appendLine()

        report.appendLine("Max Duplicates:")
        report.appendLine("  Value: ${settings.maxDuplicates}")
        report.appendLine()

        report.appendLine("Custom Sizing:")
        report.appendLine("  Custom Width: ${project.customWidth}")
        report.appendLine("  Custom Height: ${project.customHeight}")
        report.appendLine("  Custom Cell Size: ${project.customCellSize}")
        report.appendLine("  Custom Color Change: ${project.customColorChange}")
        report.appendLine()

        report.appendLine("Placement Options:")
        report.appendLine("  Cell Shape: ${project.cellShape}")
        report.appendLine("  Cell Image Fit Mode: ${project.cellImageFitMode}")
        report.appendLine("  Primary Image Sizing: ${project.primaryImageSizingMode}")
        report.appendLine("  Random Cell Candidates: ${project.randomCellCandidates}")
        report.appendLine("  Use All Images: ${project.useAllImages}")
        report.appendLine("  Create Report: ${project.createReport}")

        return report.toString()
    }

    /**
     * Generate mosaic with default settings.
     */
    fun generateMosaicWithDefaults() {
        // Clean up previous mosaic temp files before creating new ones
        cleanupPreviousMosaicData()

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

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _generationState.value = GenerationState.Loading
            try {
                // Create project with default settings
                val project = createDefaultProject(primaryPath, cellPaths)

                // Generate mosaic
                val result = service.generateMosaic(
                    project,
                    maxUsesOverride = null, // Let the service calculate based on actual cell counts
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
            } catch (e: CancellationException) {
                // Coroutine was cancelled — state already set to Idle by cancelGeneration()
                throw e
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
        generationJob?.cancel()
        generationJob = null
        _generationState.value = GenerationState.Idle
        _progress.value = MosaicGenerationProgress(0, "")
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        cleanupPreviousMosaicData()
        _generationState.value = GenerationState.Idle
        _progress.value = MosaicGenerationProgress(0, "")
    }

    /**
     * Deletes all temporary mosaic files from previous runs so local
     * storage doesn't keep growing with every generation.
     *
     * Targets:
     *  - mosaic_output/ subfolder (mosaic JPEGs, overlay JPEGs, usage CSVs)
     *  - mosaic_composite_* files in main cache dir (preview composites)
     *  - mosaic_overlay_* files in main cache dir (blurred overlay cache)
     */
    fun cleanupPreviousMosaicData() {
        // 1. Wipe everything inside the dedicated mosaic output directory
        mosaicCacheDir.listFiles()?.forEach { file ->
            try { file.delete() } catch (_: Exception) {}
        }

        // 2. Clean composite & overlay caches from the main app cache dir
        val cacheDir = getApplication<Application>().cacheDir
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && (
                        file.name.startsWith("mosaic_composite_") ||
                        file.name.startsWith("mosaic_overlay_")
                    )
            ) {
                try { file.delete() } catch (_: Exception) {}
            }
        }

        // 3. Also sweep the system temp dir in case older runs left files there
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: return)
        if (tmpDir.exists() && tmpDir.canRead()) {
            tmpDir.listFiles()?.forEach { file ->
                if (file.isFile && (
                            file.name.startsWith("mosaic_") ||
                            file.name.startsWith("mosaic_overlay_") ||
                            file.name.startsWith("mosaic_usage_")
                        )
                ) {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    /** Convenience: delete without throwing. */
    private fun File.deleteQuietly() {
        try {
            if (exists()) delete()
        } catch (_: Exception) { /* best-effort */ }
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
        val settings = _settings.value
        val patternName = when (settings.pattern) {
            PatternKind.Parquet -> "Parquet ${settings.parquetRatio}"
            else -> "Square"
        }

        return PhotoMosaicProject(
            primaryImagePath = primaryImagePath,
            cellPhotos = cellPhotoPaths.map { path ->
                CellPhoto(
                    path = path,
                    orientation = orientationCache[path] ?: PhotoOrientation.Square
                )
            },
            selectedPrintSize = PrintSize(
                settings.selectedPrintSize.label,
                settings.selectedPrintSize.widthInches,
                settings.selectedPrintSize.heightInches
            ),
            selectedResolution = Resolution("300 DPI", 300),
            selectedCellSize = CellSize(
                settings.selectedCellSize.label,
                settings.selectedCellSize.sizeMm
            ),
            selectedCellPhotoPattern = CellPhotoPatternConfig(patternName),
            selectedColorChange = ColorChange(
                "${settings.colorChangePercent}%",
                settings.colorChangePercent
            ),
            selectedDuplicateSpacing = DuplicateSpacing(
                settings.duplicateSpacing.toString(),
                settings.duplicateSpacing
            ),
            cellShape = CellShape.Square,
            cellImageFitMode = CellImageFitMode.CropCenter,
            primaryImageSizingMode = PrimaryImageSizingMode.KeepAspectRatio,
            randomCellCandidates = 5,
            useAllImages = settings.useAllImages,
            createReport = true
        )
    }

    private fun recomputeSettings() {
        viewModelScope.launch {
            val current = _settings.value
            val paths = _cellPhotoPaths.value
            val counts = computePhotoCounts(paths)
            val totalCells = calculateTotalCells(
                current.selectedPrintSize,
                current.selectedCellSize
            )
            val maxDuplicates = if (paths.isEmpty()) {
                0
            } else {
                ceil((totalCells / paths.size.toDouble()) * 2).toInt()
            }
            val smallestCount = minOf(counts.landscape, counts.portrait)
            val duplicateSpacing = ceil(smallestCount / 10.0).toInt()
            val parquetRatio = computeParquetRatio(counts.landscape, counts.portrait)

            _settings.value = current.copy(
                landscapeCount = counts.landscape,
                portraitCount = counts.portrait,
                totalCells = totalCells,
                maxDuplicates = maxDuplicates,
                duplicateSpacing = duplicateSpacing,
                parquetRatio = parquetRatio
            )
        }
    }

    private suspend fun computePhotoCounts(paths: List<String>): PhotoCounts {
        return withContext(Dispatchers.Default) {
            var landscape = 0
            var portrait = 0

            for (path in paths) {
                val orientation = orientationCache[path] ?: detectOrientation(path)
                orientationCache[path] = orientation
                when (orientation) {
                    PhotoOrientation.Landscape -> landscape++
                    PhotoOrientation.Portrait -> portrait++
                    else -> Unit
                }
            }

            PhotoCounts(
                total = paths.size,
                landscape = landscape,
                portrait = portrait
            )
        }
    }

    private fun detectOrientation(path: String): PhotoOrientation {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            val width = options.outWidth
            val height = options.outHeight
            when {
                width > height -> PhotoOrientation.Landscape
                height > width -> PhotoOrientation.Portrait
                else -> PhotoOrientation.Square
            }
        } catch (_: Exception) {
            PhotoOrientation.Square
        }
    }

    private fun calculateTotalCells(
        printSize: PrintSizeOption,
        cellSize: CellSizeOption
    ): Int {
        val widthMm = printSize.widthInches * 25.4
        val heightMm = printSize.heightInches * 25.4
        val columns = floor(widthMm / cellSize.sizeMm).toInt().coerceAtLeast(1)
        val rows = floor(heightMm / cellSize.sizeMm).toInt().coerceAtLeast(1)
        return rows * columns
    }

    private fun computeParquetRatio(landscape: Int, portrait: Int): String {
        if (landscape == 0 && portrait == 0) return "N/A"
        if (landscape == 0) return "0:${maxOf(portrait, 1)}"
        if (portrait == 0) return "${maxOf(landscape, 1)}:0"

        val gcd = gcd(landscape, portrait)
        val l = (landscape / gcd).coerceAtLeast(1)
        val p = (portrait / gcd).coerceAtLeast(1)
        return "$l:$p"
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val temp = x % y
            x = y
            y = temp
        }
        return x.coerceAtLeast(1)
    }
}

private fun createDefaultSettings(): MosaicSettingsState {
    val printSizes = listOf(
        PrintSizeOption("X-Small", 8.0, 12.0),
        PrintSizeOption("Small", 12.0, 18.0),
        PrintSizeOption("Medium", 16.0, 24.0),
        PrintSizeOption("Large", 20.0, 30.0),
        PrintSizeOption("X-Large", 26.67, 40.0),
        PrintSizeOption("XX-Large", 40.0, 60.0)
    )
    val cellSizes = listOf(
        CellSizeOption("9mm", 9.0),
        CellSizeOption("12mm", 12.0),
        CellSizeOption("15mm", 15.0),
        CellSizeOption("18mm", 18.0),
        CellSizeOption("21mm", 21.0),
        CellSizeOption("24mm", 24.0)
    )
    val defaultPrint = printSizes.first { it.label == "Large" }
    val defaultCell = cellSizes.first { it.label == "15mm" }

    return MosaicSettingsState(
        printSizes = printSizes,
        cellSizes = cellSizes,
        selectedPrintSize = defaultPrint,
        selectedCellSize = defaultCell,
        colorChangePercent = 10,
        pattern = PatternKind.Square,
        parquetRatio = "N/A",
        useAllImages = true,
        mirrorImages = true,
        dpi = 300,
        maxDuplicates = 0,
        duplicateSpacing = 0,
        landscapeCount = 0,
        portraitCount = 0,
        totalCells = 0
    )
}

data class PrintSizeOption(
    val label: String,
    val widthInches: Double,
    val heightInches: Double
)

data class CellSizeOption(
    val label: String,
    val sizeMm: Double
)

data class MosaicSettingsState(
    val printSizes: List<PrintSizeOption>,
    val cellSizes: List<CellSizeOption>,
    val selectedPrintSize: PrintSizeOption,
    val selectedCellSize: CellSizeOption,
    val colorChangePercent: Int,
    val pattern: PatternKind,
    val parquetRatio: String,
    val useAllImages: Boolean,
    val mirrorImages: Boolean,
    val dpi: Int,
    val maxDuplicates: Int,
    val duplicateSpacing: Int,
    val landscapeCount: Int,
    val portraitCount: Int,
    val totalCells: Int
)

/**
 * Represents the state of mosaic generation.
 */
sealed class GenerationState {
    object Idle : GenerationState()
    object Loading : GenerationState()
    data class Success(val result: MosaicResult) : GenerationState()
    data class Error(val message: String) : GenerationState()
}
