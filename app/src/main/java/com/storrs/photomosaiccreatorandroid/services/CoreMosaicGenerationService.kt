package com.storrs.photomosaiccreatorandroid.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.storrs.photomosaiccreatorandroid.models.*
import kotlinx.coroutines.*
import java.io.File
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

/**
 * Core service for generating photo mosaics efficiently.
 *
 * Key design decisions for efficiency:
 * 1. All images are cached after resizing to avoid repeated processing
 * 2. Cell images use RGB_565 format to reduce memory (16-bit instead of 32-bit)
 * 3. Bitmap recycling is explicit and mandatory to prevent memory leaks
 * 4. Color analysis uses fast sampling (every ~10-50th pixel)
 * 5. Coroutines allow cancellation and proper resource cleanup
 * 6. LRU cache could be added for very large projects (hundreds of images)
 */
class CoreMosaicGenerationService {

    /**
     * Directory where temporary mosaic output files are written.
     * Must be set before calling generateMosaic so cleanup can target it.
     */
    var outputDir: File? = null

    companion object {
        private const val TAG = "MosaicGeneration"
        private const val MAX_MOSAIC_DIMENSION = 2048
    }

    /**
     * Generates a complete photo mosaic from a project configuration.
     *
     * @param project The mosaic project configuration
     * @param maxUsesOverride Optional override for maximum uses per photo
     * @param onProgress Callback for generation progress
     * @param scope Coroutine scope for cancellation and threading
     * @return MosaicResult with file paths or error message
     */
    suspend fun generateMosaic(
        project: PhotoMosaicProject,
        maxUsesOverride: Int? = null,
        onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
        scope: CoroutineScope = GlobalScope
    ): MosaicResult {
        val result = MosaicResult()
        val startTime = System.currentTimeMillis()

        try {
            reportProgress(onProgress, 0, "Validating")
            validateProject(project)

            reportProgress(onProgress, 1, "Get Pattern")
            val pattern = resolvePatternInfo(getPatternInfo(project.selectedCellPhotoPattern), project.cellPhotos)

            reportProgress(onProgress, 2, "Verify Primary Image")
            val primaryPath = project.primaryImagePath ?: throw IllegalStateException("Primary image not selected")
            val primaryFile = File(primaryPath)
            if (!primaryFile.exists()) {
                throw IllegalStateException("Primary image file not found: $primaryPath")
            }

            reportProgress(onProgress, 3, "Calculating Grid")
            val (primaryWidth, primaryHeight) = getBitmapDimensions(primaryPath)
            val grid = calculateGrid(project, primaryWidth, primaryHeight, pattern)

            val targetWidth = maxOf(1, grid.width)
            val targetHeight = maxOf(1, grid.height)

            val maxTarget = maxOf(targetWidth, targetHeight)
            var scaledWidth = targetWidth
            var scaledHeight = targetHeight
            if (maxTarget > MAX_MOSAIC_DIMENSION) {
                val scale = MAX_MOSAIC_DIMENSION / maxTarget.toFloat()
                scaledWidth = maxOf(1, (targetWidth * scale).toInt())
                scaledHeight = maxOf(1, (targetHeight * scale).toInt())
            }

            scaledWidth = minOf(scaledWidth, primaryWidth)
            scaledHeight = minOf(scaledHeight, primaryHeight)

            reportProgress(onProgress, 4, "Loading Primary Image")
            val primaryBitmap = loadBitmap(primaryPath, scaledWidth, scaledHeight)
                ?: throw IllegalStateException("Unable to load primary image")

            result.gridRows = grid.rows
            result.gridColumns = grid.columns
            result.outputWidth = grid.width
            result.outputHeight = grid.height

            // Pre-processing phase (0-10%)
            val preProcessPercent = 10
            var lastPreprocessReported = -1
            val preprocessProgress = { percent: Int ->
                val mapped = (percent * preProcessPercent / 100)
                if (mapped != lastPreprocessReported) {
                    reportProgress(onProgress, mapped, "Loading Cells Images")
                    lastPreprocessReported = mapped
                }
            }

            reportProgress(onProgress, 5, "Building Cell Cache")
            val cellCache = buildCellCache(
                project.cellPhotos,
                grid,
                project.cellImageFitMode,
                pattern,
                onProgress,
                scope
            )

            if (cellCache.isEmpty()) {
                throw IllegalStateException("No valid cell photos were loaded")
            }

            reportProgress(onProgress, preProcessPercent, "Building Mosaic Plan")
            val plan = buildMosaicPlan(grid, pattern, cellCache)
            val maxUses = maxUsesOverride ?: plan.maxPhotoUses

            try {
                reportProgress(onProgress, preProcessPercent, "Preparing Primary Image")
                val preparedPrimary = ImageProcessingUtils.preparePrimaryImage(
                    primaryBitmap,
                    grid.width,
                    grid.height,
                    project.primaryImageSizingMode == PrimaryImageSizingMode.KeepAspectRatio
                )

                val usageEntries = mutableListOf<CellUsage>()
                val mosaicProgress: (MosaicGenerationProgress) -> Unit = { progress ->
                    onProgress?.invoke(progress)
                }

                reportProgress(onProgress, preProcessPercent, "Creating Mosaic")
                val mosaicBitmap = createMosaic(
                    preparedPrimary,
                    cellCache,
                    grid,
                    project,
                    pattern,
                    maxUses,
                    plan.totalCells,
                    project.useAllImages,
                    usageEntries,
                    mosaicProgress,
                    scope
                )

                reportProgress(onProgress, 95, "Saving Results")
                val tempPath = saveBitmapAsJpeg(mosaicBitmap, "mosaic_")
                val overlayPath = saveBitmapAsJpeg(preparedPrimary, "mosaic_overlay_")

                reportProgress(onProgress, 98, "Writing Report")
                val reportPath = if (project.createReport) {
                    writeUsageReport(usageEntries, cellCache)
                } else {
                    null
                }

                val usedPhotoCount = usageEntries
                    .map { it.path }
                    .distinct()
                    .size

                result.temporaryFilePath = tempPath
                result.overlayImagePath = overlayPath
                result.usageReportPath = reportPath
                result.overlayOpacityPercent = 0
                result.totalCellPhotos = cellCache.size
                result.usedCellPhotos = usedPhotoCount

                mosaicBitmap.recycle()
                preparedPrimary.recycle()

                reportProgress(onProgress, 100, "Complete")
            } finally {
                cellCache.forEach { it.cleanup() }
            }

            primaryBitmap.recycle()
        } catch (e: CancellationException) {
            result.errorMessage = "Mosaic generation cancelled"
            Log.w(TAG, "Generation cancelled", e)
            throw e
        } catch (e: Exception) {
            result.errorMessage = "Mosaic generation failed: ${e.message}"
            Log.e(TAG, "Mosaic generation error", e)
        } finally {
            val endTime = System.currentTimeMillis()
            result.generationTimeMs = endTime - startTime
        }

        return result
    }

    /**
     * Builds a plan for mosaic generation without actually creating the mosaic.
     * Useful for UI feedback before generation.
     */
    suspend fun buildMosaicPlan(
        project: PhotoMosaicProject,
        scope: CoroutineScope = GlobalScope
    ): MosaicPlan {
        validateProject(project)

        val pattern = resolvePatternInfo(getPatternInfo(project.selectedCellPhotoPattern), project.cellPhotos)
        val primaryPath = project.primaryImagePath ?: throw IllegalStateException("Primary image not selected")
        val primaryFile = File(primaryPath)

        if (!primaryFile.exists()) {
            throw IllegalStateException("Primary image not found: $primaryPath")
        }

        val (primaryWidth, primaryHeight) = getBitmapDimensions(primaryPath)
        val grid = calculateGrid(project, primaryWidth, primaryHeight, pattern)

        return buildMosaicPlan(project, grid, pattern)
    }

    /**
     * Loads a cell photo's bitmap with optional sampling for faster loading.
     * Uses RGB_565 format to save memory.
     */
    fun loadBitmap(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(path, maxWidth, maxHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            android.graphics.BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: $path", e)
            null
        }
    }

    /**
     * Gets bitmap dimensions without loading the full bitmap.
     */
    private fun getBitmapDimensions(path: String): Pair<Int, Int> {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, options)
        return Pair(options.outWidth, options.outHeight)
    }

    /**
     * Calculates appropriate sample size for efficient bitmap loading.
     */
    private fun calculateSampleSize(path: String, maxWidth: Int, maxHeight: Int): Int {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, options)

        var sampleSize = 1
        while (options.outWidth / sampleSize > maxWidth || options.outHeight / sampleSize > maxHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Pre-processes and caches all cell photos.
     * This is the most important step for efficiency - done once, used many times.
     */
    private suspend fun buildCellCache(
        cellPhotos: List<CellPhoto>,
        grid: GridDimensions,
        fitMode: CellImageFitMode,
        pattern: PatternInfo,
        onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
        scope: CoroutineScope
    ): List<CellPhotoCache> = withContext(Dispatchers.Default) {
        val cache = mutableListOf<CellPhotoCache>()

        val requiredOrientation = when (pattern.kind) {
            PatternKind.Landscape -> PhotoOrientation.Landscape
            PatternKind.Portrait -> PhotoOrientation.Portrait
            else -> null
        }

        val total = cellPhotos.size
        var processed = 0
        var lastReported = -1

        for (photo in cellPhotos) {
            ensureActive()
            // Skip photos that don't match required orientation
            if (requiredOrientation != null &&
                photo.orientation != requiredOrientation &&
                photo.orientation != PhotoOrientation.Square
            ) {
                processed++
                if (total > 0) {
                    val percent = (processed * 100) / total
                    if (percent != lastReported) {
                        reportProgress(onProgress, percent, "Loading Cell Images: ${processed}/${total}")
                        lastReported = percent
                    }
                }
                continue
            }

            try {
                val targetWidth = maxOf(grid.landscapeCellWidth, grid.portraitCellWidth)
                val targetHeight = maxOf(grid.landscapeCellHeight, grid.portraitCellHeight)

                val image = loadBitmap(photo.path, targetWidth, targetHeight)
                if (image == null) {
                    processed++
                    if (total > 0) {
                        val percent = (processed * 100) / total
                        if (percent != lastReported) {
                            reportProgress(onProgress, percent, "Loading Cell Images: ${processed}/${total}")
                            lastReported = percent
                        }
                    }
                } else {
                    val avgColor = ImageProcessingUtils.getAverageColorFast(image)

                    val (resizedLandscape, landscapeQuadrants) = if (photo.orientation == PhotoOrientation.Landscape) {
                        val resized = prepareCellImage(image, grid.landscapeCellWidth, grid.landscapeCellHeight, fitMode)
                        val quads = ImageProcessingUtils.getQuadrantColors(
                            resized, 0, 0, resized.width, resized.height, clamp = false
                        )
                        Pair(resized, quads)
                    } else if (photo.orientation == PhotoOrientation.Square) {
                        val resized = prepareCellImage(image, grid.landscapeCellWidth, grid.landscapeCellHeight, fitMode)
                        val quads = ImageProcessingUtils.getQuadrantColors(
                            resized, 0, 0, resized.width, resized.height, clamp = false
                        )
                        Pair(resized, quads)
                    } else {
                        Pair(null, CellQuadrantColors(RgbColor.Gray, RgbColor.Gray, RgbColor.Gray, RgbColor.Gray))
                    }

                    val (resizedPortrait, portraitQuadrants) = if (photo.orientation == PhotoOrientation.Portrait) {
                        val resized = prepareCellImage(image, grid.portraitCellWidth, grid.portraitCellHeight, fitMode)
                        val quads = ImageProcessingUtils.getQuadrantColors(
                            resized, 0, 0, resized.width, resized.height, clamp = false
                        )
                        Pair(resized, quads)
                    } else if (photo.orientation == PhotoOrientation.Square) {
                        val resized = prepareCellImage(image, grid.portraitCellWidth, grid.portraitCellHeight, fitMode)
                        val quads = ImageProcessingUtils.getQuadrantColors(
                            resized, 0, 0, resized.width, resized.height, clamp = false
                        )
                        Pair(resized, quads)
                    } else {
                        Pair(null, CellQuadrantColors(RgbColor.Gray, RgbColor.Gray, RgbColor.Gray, RgbColor.Gray))
                    }

                    cache.add(
                        CellPhotoCache(
                            path = photo.path,
                            orientation = photo.orientation,
                            averageColor = avgColor,
                            resizedLandscapeBitmap = resizedLandscape,
                            resizedPortraitBitmap = resizedPortrait,
                            landscapeQuadrants = landscapeQuadrants,
                            portraitQuadrants = portraitQuadrants
                        )
                    )

                    image.recycle()

                    processed++
                    if (total > 0) {
                        val percent = (processed * 100) / total
                        if (percent != lastReported) {
                            reportProgress(onProgress, percent, "Loading Cell Images: ${processed}/${total}")
                            lastReported = percent
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache cell photo ${photo.path}: ${e.message}")
                processed++
                if (total > 0) {
                    val percent = (processed * 100) / total
                    if (percent != lastReported) {
                        reportProgress(onProgress, percent, "Loading Cell Images: ${processed}/${total}")
                        lastReported = percent
                    }
                }
            }
        }

        cache
    }

    /**
     * Prepares a cell image by resizing and optionally cropping.
     */
    private fun prepareCellImage(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        fitMode: CellImageFitMode
    ): Bitmap {
        return if (fitMode == CellImageFitMode.StretchToFit) {
            ImageProcessingUtils.resizeBitmap(source, targetWidth, targetHeight)
        } else {
            // Crop center
            ImageProcessingUtils.resizeBitmap(source, targetWidth, targetHeight).let {
                ImageProcessingUtils.cropBitmap(it, targetWidth, targetHeight).apply {
                    if (this !== it) it.recycle()
                }
            }
        }
    }

    /**
     * Validates that the project has all required settings.
     */
    private fun validateProject(project: PhotoMosaicProject) {
        require(!project.primaryImagePath.isNullOrBlank()) { "Primary image is not selected" }
        require(project.cellPhotos.isNotEmpty()) { "No cell photos have been added" }
        require(project.selectedPrintSize != null) { "Print size is not selected" }
        require(project.selectedResolution != null) { "Resolution is not selected" }
        require(project.selectedCellSize != null) { "Cell size is not selected" }
    }

    /**
     * Calculates grid dimensions based on project settings and primary image size.
     */
    private fun calculateGrid(
        project: PhotoMosaicProject,
        primaryWidth: Int,
        primaryHeight: Int,
        pattern: PatternInfo
    ): GridDimensions {
        val printSize = project.selectedPrintSize!!
        val resolution = project.selectedResolution!!
        val cellSize = project.selectedCellSize!!

        var printWidthIn = if (printSize.isCustom) project.customWidth else printSize.width
        var printHeightIn = if (printSize.isCustom) project.customHeight else printSize.height

        val longSide = maxOf(printWidthIn, printHeightIn)
        val shortSide = minOf(printWidthIn, printHeightIn)

        val orientation = getPhotoOrientation(primaryWidth, primaryHeight)
        when (orientation) {
            PhotoOrientation.Landscape -> {
                printWidthIn = longSide
                printHeightIn = shortSide
            }
            PhotoOrientation.Portrait -> {
                printWidthIn = shortSide
                printHeightIn = longSide
            }
            PhotoOrientation.Square -> {}
        }

        if (project.primaryImageSizingMode == PrimaryImageSizingMode.KeepAspectRatio) {
            val primaryAspect = primaryWidth / primaryHeight.toFloat()
            val printAspect = printWidthIn / printHeightIn

            if (primaryAspect >= printAspect) {
                printHeightIn = printWidthIn / primaryAspect
            } else {
                printWidthIn = printHeightIn * primaryAspect
            }
        }

        val pixelWidth = maxOf(1, (printWidthIn * resolution.ppi).toInt())
        val pixelHeight = maxOf(1, (printHeightIn * resolution.ppi).toInt())

        val cellSizeMm = if (cellSize.isCustom) project.customCellSize else cellSize.sizeMm
        val cellSizeIn = cellSizeMm / 25.4
        val baseCellPixels = maxOf(1, (cellSizeIn * resolution.ppi).toInt())

        val (shapeWidth, shapeHeight) = getCellDimensions(baseCellPixels, project.cellShape)

        var landscapeWidth = shapeWidth
        var landscapeHeight = shapeHeight
        var portraitWidth = shapeHeight
        var portraitHeight = shapeWidth

        // For parquet with square cells, force 4:3 so landscape/portrait differ
        if (pattern.kind == PatternKind.Parquet && landscapeWidth == landscapeHeight) {
            val (pw, ph) = getCellDimensions(baseCellPixels, CellShape.Rectangle4x3)
            landscapeWidth = pw
            landscapeHeight = ph
            portraitWidth = ph
            portraitHeight = pw
        }

        if (pattern.kind == PatternKind.Square) {
            landscapeWidth = baseCellPixels
            landscapeHeight = baseCellPixels
            portraitWidth = baseCellPixels
            portraitHeight = baseCellPixels
        }

        val cellWidth = if (pattern.kind == PatternKind.Portrait) portraitWidth else landscapeWidth
        val cellHeight = if (pattern.kind == PatternKind.Portrait) portraitHeight else landscapeHeight

        val unitSize = gcd(landscapeWidth, landscapeHeight)
        val unitColumns = maxOf(1, pixelWidth / unitSize)
        val unitRows = maxOf(1, pixelHeight / unitSize)

        val columns = maxOf(1, pixelWidth / cellWidth)
        val rows = maxOf(1, pixelHeight / cellHeight)

        val outputWidth = if (pattern.kind == PatternKind.Parquet) unitColumns * unitSize else columns * cellWidth
        val outputHeight = if (pattern.kind == PatternKind.Parquet) unitRows * unitSize else rows * cellHeight

        return GridDimensions(
            width = outputWidth,
            height = outputHeight,
            baseCellPixels = unitSize,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            landscapeCellWidth = landscapeWidth,
            landscapeCellHeight = landscapeHeight,
            portraitCellWidth = portraitWidth,
            portraitCellHeight = portraitHeight,
            rows = if (pattern.kind == PatternKind.Parquet) unitRows else rows,
            columns = if (pattern.kind == PatternKind.Parquet) unitColumns else columns,
            unitRows = unitRows,
            unitColumns = unitColumns
        )
    }

    /**
     * Gets cell dimensions based on shape.
     */
    private fun getCellDimensions(baseCellPixels: Int, shape: CellShape): Pair<Int, Int> {
        return when (shape) {
            CellShape.Rectangle4x3 -> {
                Pair(baseCellPixels, maxOf(1, (baseCellPixels * 3.0 / 4.0).toInt()))
            }
            CellShape.Rectangle3x2 -> {
                Pair(baseCellPixels, maxOf(1, (baseCellPixels * 2.0 / 3.0).toInt()))
            }
            else -> Pair(baseCellPixels, baseCellPixels)
        }
    }

    /**
     * Greatest common divisor (Euclidean algorithm).
     */
    private fun gcd(a: Int, b: Int): Int {
        var x = maxOf(1, a)
        var y = maxOf(1, b)
        while (y != 0) {
            val temp = y
            y = x % y
            x = temp
        }
        return maxOf(1, x)
    }

    /**
     * Determines photo orientation from dimensions.
     */
    private fun getPhotoOrientation(width: Int, height: Int): PhotoOrientation {
        return when {
            width > height -> PhotoOrientation.Landscape
            height > width -> PhotoOrientation.Portrait
            else -> PhotoOrientation.Square
        }
    }

    /**
     * Builds a mosaic plan from grid and pattern information.
     */
    private suspend fun buildMosaicPlan(
        project: PhotoMosaicProject,
        grid: GridDimensions,
        pattern: PatternInfo
    ): MosaicPlan {
        val cellCounts = calculateCellCounts(grid, pattern)
        val photoCounts = countAvailablePhotos(project.cellPhotos, pattern)
        val maxUses = calculateRecommendedMaxUses(cellCounts, photoCounts, pattern)

        return MosaicPlan(
            totalCells = cellCounts.total,
            availablePhotos = photoCounts.total,
            maxPhotoUses = maxUses,
            landscapeCells = cellCounts.landscape,
            portraitCells = cellCounts.portrait,
            availableLandscapePhotos = photoCounts.landscape,
            availablePortraitPhotos = photoCounts.portrait
        )
    }

    /**
     * Builds a mosaic plan from cache.
     */
    private suspend fun buildMosaicPlan(
        grid: GridDimensions,
        pattern: PatternInfo,
        cache: List<CellPhotoCache>
    ): MosaicPlan {
        val cellCounts = calculateCellCounts(grid, pattern)
        val photoCounts = countAvailablePhotosFromCache(cache, pattern)
        val maxUses = calculateRecommendedMaxUses(cellCounts, photoCounts, pattern)

        return MosaicPlan(
            totalCells = cellCounts.total,
            availablePhotos = photoCounts.total,
            maxPhotoUses = maxUses,
            landscapeCells = cellCounts.landscape,
            portraitCells = cellCounts.portrait,
            availableLandscapePhotos = photoCounts.landscape,
            availablePortraitPhotos = photoCounts.portrait
        )
    }

    /**
     * Calculates how many landscape and portrait cells are needed.
     */
    private fun calculateCellCounts(grid: GridDimensions, pattern: PatternInfo): CellCounts {
        val total = maxOf(0, grid.rows * grid.columns)
        return when (pattern.kind) {
            PatternKind.Landscape -> CellCounts(total, total, 0)
            PatternKind.Portrait -> CellCounts(total, 0, total)
            PatternKind.Parquet -> countParquetCells(grid, pattern)
            else -> CellCounts(total, 0, 0)
        }
    }

    /**
     * Counts available photos by orientation.
     */
    private fun countAvailablePhotos(photos: List<CellPhoto>, pattern: PatternInfo): PhotoCounts {
        val landscape = photos.count {
            it.orientation == PhotoOrientation.Landscape || it.orientation == PhotoOrientation.Square
        }
        val portrait = photos.count {
            it.orientation == PhotoOrientation.Portrait || it.orientation == PhotoOrientation.Square
        }

        return when (pattern.kind) {
            PatternKind.Landscape -> PhotoCounts(landscape, landscape, portrait)
            PatternKind.Portrait -> PhotoCounts(portrait, landscape, portrait)
            else -> PhotoCounts(photos.size, landscape, portrait)
        }
    }

    /**
     * Counts available photos in cache by orientation.
     */
    private fun countAvailablePhotosFromCache(cache: List<CellPhotoCache>, pattern: PatternInfo): PhotoCounts {
        val landscape = cache.count {
            it.orientation == PhotoOrientation.Landscape || it.orientation == PhotoOrientation.Square
        }
        val portrait = cache.count {
            it.orientation == PhotoOrientation.Portrait || it.orientation == PhotoOrientation.Square
        }
        val total = cache.size

        return when (pattern.kind) {
            PatternKind.Landscape -> PhotoCounts(total, landscape, portrait)
            PatternKind.Portrait -> PhotoCounts(total, landscape, portrait)
            else -> PhotoCounts(total, landscape, portrait)
        }
    }

    /**
     * Calculates recommended maximum uses per photo.
     */
    private fun calculateRecommendedMaxUses(
        cells: CellCounts,
        photos: PhotoCounts,
        pattern: PatternInfo
    ): Int {
        if (cells.total <= 0 || photos.total <= 0) return Int.MAX_VALUE

        val requiredUses = when (pattern.kind) {
            PatternKind.Parquet -> calculateParquetRequiredUses(cells, photos)
            else -> (cells.total + photos.total - 1) / photos.total // Ceiling division
        }

        return maxOf(1, requiredUses * 2)
    }

    /**
     * Calculates required uses for parquet pattern.
     */
    private fun calculateParquetRequiredUses(cells: CellCounts, photos: PhotoCounts): Int {
        if (cells.landscape > 0 && photos.landscape == 0) return Int.MAX_VALUE
        if (cells.portrait > 0 && photos.portrait == 0) return Int.MAX_VALUE

        val landscapeUses = if (cells.landscape > 0) {
            (cells.landscape + maxOf(1, photos.landscape) - 1) / maxOf(1, photos.landscape)
        } else {
            0
        }

        val portraitUses = if (cells.portrait > 0) {
            (cells.portrait + maxOf(1, photos.portrait) - 1) / maxOf(1, photos.portrait)
        } else {
            0
        }

        return maxOf(landscapeUses, portraitUses)
    }

    /**
     * Counts cells in a parquet pattern using unit-grid occupancy.
     * Exact translation of C# CountParquetCells.
     */
    private fun countParquetCells(grid: GridDimensions, pattern: PatternInfo): CellCounts {
        val unitSize = grid.baseCellPixels
        val unitColumns = grid.unitColumns
        val unitRows = grid.unitRows

        val landscapeWidthUnits = maxOf(1, grid.landscapeCellWidth / unitSize)
        val landscapeHeightUnits = maxOf(1, grid.landscapeCellHeight / unitSize)
        val portraitWidthUnits = maxOf(1, grid.portraitCellWidth / unitSize)
        val portraitHeightUnits = maxOf(1, grid.portraitCellHeight / unitSize)

        val sequence = buildParquetSequence(pattern)
        val deltaUnits = maxOf(0, portraitHeightUnits - landscapeHeightUnits)
        val cycleWidthUnits = maxOf(1, (pattern.landscapeCount * landscapeWidthUnits) + (pattern.portraitCount * portraitWidthUnits))
        val cyclesAcross = maxOf(1, (unitColumns + cycleWidthUnits - 1) / cycleWidthUnits) + 1
        val maxPortraitsPerRow = maxOf(0, pattern.portraitCount * cyclesAcross)
        val topPaddingUnits = deltaUnits * maxPortraitsPerRow
        val rowCount = maxOf(1, (unitRows + topPaddingUnits + landscapeHeightUnits - 1) / landscapeHeightUnits)
        val leftPaddingUnits = portraitWidthUnits * rowCount
        val totalColumns = unitColumns + leftPaddingUnits + cycleWidthUnits
        val totalRows = unitRows + topPaddingUnits + portraitHeightUnits

        val occupied = Array(totalRows) { BooleanArray(totalColumns) }

        var totalCells = 0
        var landscapeCells = 0
        var portraitCells = 0

        var rowIndex = 0
        while (rowIndex * landscapeHeightUnits < totalRows) {
            val baseYUnit = (rowIndex * landscapeHeightUnits) - topPaddingUnits
            val rowOffsetUnits = -rowIndex * portraitWidthUnits
            var xUnit = leftPaddingUnits + rowOffsetUnits
            var patternIndex = 0
            var currentYUnit = baseYUnit

            while (xUnit < totalColumns) {
                val yUnitForOccupancy = currentYUnit + topPaddingUnits
                if (yUnitForOccupancy >= totalRows || xUnit < 0) {
                    xUnit++
                    continue
                }

                if (occupied[yUnitForOccupancy][xUnit]) {
                    xUnit++
                    continue
                }

                val orientation = sequence[patternIndex]
                val widthUnits: Int
                val heightUnits: Int
                if (orientation == PhotoOrientation.Portrait) {
                    widthUnits = portraitWidthUnits
                    heightUnits = portraitHeightUnits
                } else {
                    widthUnits = landscapeWidthUnits
                    heightUnits = landscapeHeightUnits
                }

                if (!canPlace(occupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits, totalColumns, totalRows)) {
                    xUnit++
                    continue
                }

                markOccupied(occupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits)

                // Only count cells that are at least partially visible on the output canvas
                val px = (xUnit - leftPaddingUnits) * unitSize
                val py = currentYUnit * unitSize
                val pw = widthUnits * unitSize
                val ph = heightUnits * unitSize
                val isVisible = px + pw > 0 && py + ph > 0 && px < grid.width && py < grid.height

                if (isVisible) {
                    totalCells++
                    if (orientation == PhotoOrientation.Portrait) {
                        portraitCells++
                    } else {
                        landscapeCells++
                    }
                }

                if (orientation == PhotoOrientation.Portrait && deltaUnits > 0) {
                    currentYUnit += deltaUnits
                }

                patternIndex = (patternIndex + 1) % sequence.size
                xUnit += widthUnits
            }

            rowIndex++
        }

        return CellCounts(totalCells, landscapeCells, portraitCells)
    }

    /**
     * Creates the mosaic by compositing cell images.
     */
    private suspend fun createMosaic(
        primary: Bitmap,
        cache: List<CellPhotoCache>,
        grid: GridDimensions,
        project: PhotoMosaicProject,
        pattern: PatternInfo,
        maxUses: Int,
        totalCells: Int,
        useAllImages: Boolean,
        usage: MutableList<CellUsage>,
        onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
        scope: CoroutineScope
    ): Bitmap = withContext(Dispatchers.Default) {
        if (pattern.kind == PatternKind.Parquet) {
            createParquetMosaic(
                primary, cache, grid, project, pattern, maxUses,
                totalCells, useAllImages, usage, onProgress, scope
            )
        } else {
            createStandardMosaic(
                primary, cache, grid, project, pattern, maxUses,
                totalCells, useAllImages, usage, onProgress, scope
            )
        }
    }

    /**
     * Creates a standard (non-parquet) mosaic.
     */
    private suspend fun createStandardMosaic(
        primary: Bitmap,
        cache: List<CellPhotoCache>,
        grid: GridDimensions,
        project: PhotoMosaicProject,
        pattern: PatternInfo,
        maxUses: Int,
        totalCells: Int,
        useAllImages: Boolean,
        usage: MutableList<CellUsage>,
        onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
        scope: CoroutineScope
    ): Bitmap = withContext(Dispatchers.Default) {
        val mosaic = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(mosaic)

        reportProgress(onProgress, 10, "Analyzing primary image colors")

        val colorAdjustPercent = getColorAdjustPercent(project)
        val minSpacing = project.selectedDuplicateSpacing?.minSpacing ?: 0
        val lastUsedPositions = mutableMapOf<String, MutableList<Pair<Int, Int>>>()

        val requiredOrientation = when (pattern.kind) {
            PatternKind.Landscape -> PhotoOrientation.Landscape
            PatternKind.Portrait -> PhotoOrientation.Portrait
            else -> null
        }

        val randomCellCandidates = project.randomCellCandidates.coerceIn(1, 20)
        val placements = mutableListOf<MosaicPlacement>()

        reportProgress(onProgress, 15, "Building cell placement map (${grid.rows}x${grid.columns} grid)")

        val primaryPixels = IntArray(primary.width * primary.height)
        primary.getPixels(primaryPixels, 0, primary.width, 0, 0, primary.width, primary.height)

        for (row in 0 until grid.rows) {
            ensureActive()
            for (col in 0 until grid.columns) {
                val x = col * grid.cellWidth
                val y = row * grid.cellHeight
                val targetColor = ImageProcessingUtils.getAverageColorRegionFastFromPixels(
                    primaryPixels, primary.width, primary.height, x, y, grid.cellWidth, grid.cellHeight
                )
                val targetQuadrants = ImageProcessingUtils.getQuadrantColorsFromPixels(
                    primaryPixels, primary.width, primary.height, x, y, grid.cellWidth, grid.cellHeight, clamp = false
                )
                placements.add(
                    MosaicPlacement(
                        row, col, x, y, grid.cellWidth, grid.cellHeight,
                        requiredOrientation ?: PhotoOrientation.Landscape,
                        targetColor, targetQuadrants
                    )
                )
            }

            val buildPercent = 15 + ((row + 1) * 5 / maxOf(1, grid.rows))
            reportProgress(onProgress, buildPercent, "Building placement map: ${row + 1}/${grid.rows}")
        }

        var availablePlacements = placements.toMutableList()

        if (useAllImages) {
            reportProgress(onProgress, 20, "Placing all cell images (${cache.size} photos)")
            for (item in cache) {
                ensureActive()
                if (availablePlacements.isEmpty()) break
                if (item.useCount >= maxUses) continue

                var bestIndex = -1
                var bestDistance = Double.MAX_VALUE

                for (i in availablePlacements.indices) {
                    val placement = availablePlacements[i]
                    val distance = ImageProcessingUtils.quadrantDistance(
                        getQuadrants(item, placement.orientation),
                        placement.targetQuadrants
                    )
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = i
                    }
                }

                if (bestIndex >= 0) {
                    val selectedPlacement = availablePlacements[bestIndex]
                    placeCell(canvas, item, selectedPlacement, colorAdjustPercent)
                    trackUse(item, lastUsedPositions, usage, selectedPlacement.row, selectedPlacement.col, selectedPlacement.x, selectedPlacement.y)
                    availablePlacements.removeAt(bestIndex)
                }
            }
        }

        availablePlacements.shuffle()

        reportProgress(onProgress, 25, "Matching images to cells (${availablePlacements.size} cells remaining)")
        var processed = 0
        var lastReported = 25

        for (placement in availablePlacements) {
            ensureActive()
            val match = findBestMatch(
                cache, placement.targetQuadrants, maxUses, minSpacing, minSpacing,
                placement.row, placement.col, randomCellCandidates,
                lastUsedPositions, requiredOrientation
            )

            if (match != null) {
                placeCell(canvas, match, placement, colorAdjustPercent)
                trackUse(match, lastUsedPositions, usage, placement.row, placement.col, placement.x, placement.y)
            }

            processed++
            if (totalCells > 0) {
                val percent = 25 + ((processed * 70) / totalCells)
                if (percent != lastReported) {
                    reportProgress(onProgress, percent, "Placing cells: ${processed}/${availablePlacements.size}")
                    lastReported = percent
                }
            }
        }

        reportProgress(onProgress, 95, "Rendering final mosaic")

        mosaic
    }

    /**
     * Creates a parquet pattern mosaic using unit-grid occupancy.
     * Exact translation of C# CreateParquetMosaic.
     */
    private suspend fun createParquetMosaic(
        primary: Bitmap,
        cache: List<CellPhotoCache>,
        grid: GridDimensions,
        project: PhotoMosaicProject,
        pattern: PatternInfo,
        maxUses: Int,
        totalCells: Int,
        useAllImages: Boolean,
        usage: MutableList<CellUsage>,
        onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
        scope: CoroutineScope
    ): Bitmap = withContext(Dispatchers.Default) {
        val mosaic = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(mosaic)

        reportProgress(onProgress, 10, "Analyzing primary image colors")

        val colorAdjustPercent = getColorAdjustPercent(project)
        val minSpacing = project.selectedDuplicateSpacing?.minSpacing ?: 0
        val lastUsedPixelPositions = mutableMapOf<String, MutableList<Pair<Int, Int>>>()

        val unitSize = grid.baseCellPixels
        val unitColumns = grid.unitColumns
        val unitRows = grid.unitRows

        val landscapeWidthUnits = maxOf(1, grid.landscapeCellWidth / unitSize)
        val landscapeHeightUnits = maxOf(1, grid.landscapeCellHeight / unitSize)
        val portraitWidthUnits = maxOf(1, grid.portraitCellWidth / unitSize)
        val portraitHeightUnits = maxOf(1, grid.portraitCellHeight / unitSize)

        val sequence = buildParquetSequence(pattern)
        val deltaUnits = maxOf(0, portraitHeightUnits - landscapeHeightUnits)
        val cycleWidthUnits = maxOf(1, (pattern.landscapeCount * landscapeWidthUnits) + (pattern.portraitCount * portraitWidthUnits))
        val cyclesAcross = maxOf(1, (unitColumns + cycleWidthUnits - 1) / cycleWidthUnits) + 1
        val maxPortraitsPerRow = maxOf(0, pattern.portraitCount * cyclesAcross)
        val topPaddingUnits = deltaUnits * maxPortraitsPerRow
        val rowCount = maxOf(1, (unitRows + topPaddingUnits + landscapeHeightUnits - 1) / landscapeHeightUnits)
        val leftPaddingUnits = portraitWidthUnits * rowCount
        val totalColumns = unitColumns + leftPaddingUnits + cycleWidthUnits
        val tRows = unitRows + topPaddingUnits + portraitHeightUnits

        val occupied = Array(tRows) { BooleanArray(totalColumns) }
        val plannedOccupied = Array(tRows) { BooleanArray(totalColumns) }
        val randomCellCandidates = project.randomCellCandidates.coerceIn(1, 20)
        val placements = mutableListOf<MosaicPlacement>()

        val primaryPixels = IntArray(primary.width * primary.height)
        primary.getPixels(primaryPixels, 0, primary.width, 0, 0, primary.width, primary.height)

        reportProgress(onProgress, 15, "Building placement map")

        var rowIndex = 0
        while (rowIndex * landscapeHeightUnits < tRows) {
            ensureActive()
            val baseYUnit = (rowIndex * landscapeHeightUnits) - topPaddingUnits
            val rowOffsetUnits = -rowIndex * portraitWidthUnits
            var xUnit = leftPaddingUnits + rowOffsetUnits
            var patternIndex = 0
            var currentYUnit = baseYUnit

            while (xUnit < totalColumns) {
                val yUnitForOccupancy = currentYUnit + topPaddingUnits
                if (yUnitForOccupancy >= tRows || xUnit < 0) {
                    xUnit++
                    continue
                }

                val orientation = sequence[patternIndex]
                val widthUnits: Int
                val heightUnits: Int
                if (orientation == PhotoOrientation.Portrait) {
                    widthUnits = portraitWidthUnits
                    heightUnits = portraitHeightUnits
                } else {
                    widthUnits = landscapeWidthUnits
                    heightUnits = landscapeHeightUnits
                }

                if (!canPlace(plannedOccupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits, totalColumns, tRows)) {
                    xUnit++
                    continue
                }

                markOccupied(plannedOccupied, xUnit, yUnitForOccupancy, widthUnits, heightUnits)

                val x = (xUnit - leftPaddingUnits) * unitSize
                val y = currentYUnit * unitSize
                val w = widthUnits * unitSize
                val h = heightUnits * unitSize

                // Only add placements that are at least partially visible on the output canvas.
                // Completely off-screen cells must not consume photo uses.
                val isVisible = x + w > 0 && y + h > 0 && x < grid.width && y < grid.height

                if (isVisible) {
                    val targetColor = ImageProcessingUtils.getAverageColorRegionFastFromPixels(
                        primaryPixels, primary.width, primary.height, x, y, w, h
                    )
                    val targetQuadrants = ImageProcessingUtils.getQuadrantColorsFromPixels(
                        primaryPixels, primary.width, primary.height, x, y, w, h, clamp = true
                    )

                    placements.add(
                        MosaicPlacement(
                            yUnitForOccupancy, xUnit, x, y, w, h,
                            orientation, targetColor, targetQuadrants
                        )
                    )
                }

                if (orientation == PhotoOrientation.Portrait && deltaUnits > 0) {
                    currentYUnit += deltaUnits
                }

                patternIndex = (patternIndex + 1) % sequence.size
                xUnit += widthUnits
            }

            val buildPercent = 15 + ((rowIndex + 1) * 5 / maxOf(1, rowCount))
            reportProgress(onProgress, buildPercent, "Building placement map: row ${rowIndex + 1}")

            rowIndex++
        }

        reportProgress(onProgress, 20, "Placement map complete (${placements.size} cells)")

        var availablePlacements = placements.toMutableList()

        if (useAllImages) {
            reportProgress(onProgress, 20, "Placing all cell images (${cache.size} photos)")
            for (item in cache) {
                ensureActive()
                if (availablePlacements.isEmpty()) break
                if (item.useCount >= maxUses) continue

                var bestIndex = -1
                var bestDistance = Double.MAX_VALUE

                for (i in availablePlacements.indices) {
                    val placement = availablePlacements[i]
                    if (!isOrientationCompatible(item.orientation, placement.orientation)) continue

                    val distance = ImageProcessingUtils.quadrantDistance(
                        getQuadrants(item, placement.orientation),
                        placement.targetQuadrants
                    )
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestIndex = i
                    }
                }

                if (bestIndex >= 0) {
                    val selectedPlacement = availablePlacements[bestIndex]
                    if (placeParquetCell(
                            canvas, item, selectedPlacement, colorAdjustPercent,
                            occupied, unitSize, totalColumns, tRows
                        )
                    ) {
                        trackUsePixelSpacing(item, lastUsedPixelPositions, usage, selectedPlacement.x, selectedPlacement.y)
                    }
                    availablePlacements.removeAt(bestIndex)
                }
            }
        }

        availablePlacements.shuffle()

        reportProgress(onProgress, 25, "Matching images to cells (${availablePlacements.size} cells remaining)")
        var processed = 0
        var lastReported = 25

        // Compute spacing in pixel coordinates so parquet respects minSpacing.
        val rowSpacingPixels = if (minSpacing > 0) {
            minSpacing * maxOf(grid.landscapeCellHeight, grid.portraitCellHeight).coerceAtLeast(1)
        } else 0
        val colSpacingPixels = if (minSpacing > 0) {
            minSpacing * maxOf(grid.landscapeCellWidth, grid.portraitCellWidth).coerceAtLeast(1)
        } else 0

        for (placement in availablePlacements) {
            ensureActive()
            if (!tryPlaceParquetCell(
                    cache, canvas, lastUsedPixelPositions, usage, rowSpacingPixels, colSpacingPixels, maxUses,
                    colorAdjustPercent, occupied, unitSize, totalColumns, tRows,
                    placement, randomCellCandidates
                )
            ) {
                continue
            }

            processed++
            if (totalCells > 0) {
                val percent = 25 + ((processed * 70) / totalCells)
                if (percent != lastReported) {
                    reportProgress(onProgress, percent, "Placing cells: ${processed}/${availablePlacements.size}")
                    lastReported = percent
                }
            }
        }

        reportProgress(onProgress, 95, "Rendering final mosaic")

        mosaic
    }

    /**
     * Tries to place a parquet cell using occupancy grid.
     * Exact translation of C# TryPlaceParquetCell.
     */
    private fun tryPlaceParquetCell(
        cache: List<CellPhotoCache>,
        canvas: Canvas,
        lastUsedPositions: MutableMap<String, MutableList<Pair<Int, Int>>>,
        usage: MutableList<CellUsage>,
        rowSpacing: Int,
        colSpacing: Int,
        maxUses: Int,
        colorAdjustPercent: Int,
        occupied: Array<BooleanArray>,
        unitSize: Int,
        unitColumns: Int,
        unitRows: Int,
        placement: MosaicPlacement,
        randomCellCandidates: Int
    ): Boolean {
        val widthUnits = maxOf(1, placement.width / unitSize)
        val heightUnits = maxOf(1, placement.height / unitSize)

        if (!canPlace(occupied, placement.col, placement.row, widthUnits, heightUnits, unitColumns, unitRows)) {
            return false
        }

        // Try with normal constraints first
        val match = findBestMatch(
            cache, placement.targetQuadrants, maxUses, rowSpacing, colSpacing,
            placement.y, placement.x, randomCellCandidates,
            lastUsedPositions, placement.orientation
        ) ?: return false

        placeCell(canvas, match, placement, colorAdjustPercent)
        markOccupied(occupied, placement.col, placement.row, widthUnits, heightUnits)
        trackUsePixelSpacing(match, lastUsedPositions, usage, placement.x, placement.y)
        return true
    }

    /**
     * Places a parquet cell with occupancy check.
     * Exact translation of C# PlaceParquetCell.
     */
    private fun placeParquetCell(
        canvas: Canvas,
        match: CellPhotoCache,
        placement: MosaicPlacement,
        colorAdjustPercent: Int,
        occupied: Array<BooleanArray>,
        unitSize: Int,
        unitColumns: Int,
        unitRows: Int
    ): Boolean {
        val widthUnits = maxOf(1, placement.width / unitSize)
        val heightUnits = maxOf(1, placement.height / unitSize)

        if (!canPlace(occupied, placement.col, placement.row, widthUnits, heightUnits, unitColumns, unitRows)) {
            return false
        }

        placeCell(canvas, match, placement, colorAdjustPercent)
        markOccupied(occupied, placement.col, placement.row, widthUnits, heightUnits)
        return true
    }

    private fun isOrientationCompatible(photoOrientation: PhotoOrientation, requiredOrientation: PhotoOrientation): Boolean {
        return when (requiredOrientation) {
            PhotoOrientation.Landscape -> photoOrientation == PhotoOrientation.Landscape || photoOrientation == PhotoOrientation.Square
            PhotoOrientation.Portrait -> photoOrientation == PhotoOrientation.Portrait || photoOrientation == PhotoOrientation.Square
            else -> true
        }
    }

    /**
     * Finds the best matching cell photo for a target color region.
     */
    private fun findBestMatch(
        cache: List<CellPhotoCache>,
        target: CellQuadrantColors,
        maxUses: Int,
        rowSpacing: Int,
        colSpacing: Int,
        row: Int,
        col: Int,
        candidateCount: Int,
        lastUsedPositions: Map<String, List<Pair<Int, Int>>>,
        requiredOrientation: PhotoOrientation?
    ): CellPhotoCache? {
        val candidates = mutableListOf<Pair<CellPhotoCache, Double>>()

        for (item in cache) {
            if (item.useCount >= maxUses) continue
            if (requiredOrientation != null &&
                item.orientation != requiredOrientation &&
                item.orientation != PhotoOrientation.Square
            ) continue

            if ((rowSpacing > 0 || colSpacing > 0) && lastUsedPositions.containsKey(item.path)) {
                val positions = lastUsedPositions[item.path]!!
                if (positions.any {
                        kotlin.math.abs(it.first - row) <= rowSpacing &&
                        kotlin.math.abs(it.second - col) <= colSpacing
                    }
                ) {
                    continue
                }
            }

            val distance = ImageProcessingUtils.quadrantDistance(
                getQuadrants(item, requiredOrientation ?: PhotoOrientation.Landscape),
                target
            )
            candidates.add(Pair(item, distance))
        }

        if (candidates.isEmpty()) return null

        val ordered = candidates.sortedBy { it.second }.take(candidateCount.coerceAtLeast(1))
        return ordered[Random.nextInt(ordered.size)].first
    }

    /**
     * Gets the appropriate quadrants for a cache item based on orientation.
     */
    private fun getQuadrants(cache: CellPhotoCache, orientation: PhotoOrientation): CellQuadrantColors {
        return if (orientation == PhotoOrientation.Portrait) {
            cache.portraitQuadrants
        } else {
            cache.landscapeQuadrants
        }
    }

    /**
     * Places a cell image on the mosaic canvas.
     */
    private fun placeCell(
        canvas: Canvas,
        match: CellPhotoCache,
        placement: MosaicPlacement,
        colorAdjustPercent: Int
    ) {
        val cellBitmap = getCellBitmap(match, placement.orientation)?.copy(Bitmap.Config.RGB_565, true)
            ?: return

        if (colorAdjustPercent > 0) {
            ImageProcessingUtils.applyColorAdjustment(cellBitmap, placement.targetColor, colorAdjustPercent)
        }

        ImageProcessingUtils.drawBitmapOnCanvas(canvas, cellBitmap, placement.x, placement.y)
        cellBitmap.recycle()
    }

    /**
     * Gets the appropriate bitmap for a cache item based on orientation.
     */
    private fun getCellBitmap(cache: CellPhotoCache, orientation: PhotoOrientation): Bitmap? {
        return when (orientation) {
            PhotoOrientation.Portrait -> cache.resizedPortraitBitmap ?: cache.resizedLandscapeBitmap
            else -> cache.resizedLandscapeBitmap ?: cache.resizedPortraitBitmap
        }
    }

    /**
     * Tracks usage of a cell photo.
     */
    private fun trackUse(
        match: CellPhotoCache,
        lastUsedPositions: MutableMap<String, MutableList<Pair<Int, Int>>>,
        usage: MutableList<CellUsage>,
        row: Int,
        col: Int,
        x: Int,
        y: Int
    ) {
        match.useCount++
        lastUsedPositions.getOrPut(match.path) { mutableListOf() }.add(Pair(row, col))
        usage.add(CellUsage(match.path, x, y))
    }

    /**
     * Tracks usage of a cell photo with pixel-based spacing.
     */
    private fun trackUsePixelSpacing(
        match: CellPhotoCache,
        lastUsedPositions: MutableMap<String, MutableList<Pair<Int, Int>>>,
        usage: MutableList<CellUsage>,
        x: Int,
        y: Int
    ) {
        match.useCount++
        lastUsedPositions.getOrPut(match.path) { mutableListOf() }.add(Pair(y, x))
        usage.add(CellUsage(match.path, x, y))
    }

    /**
     * Checks if a region can be placed without overlapping occupied cells.
     */
    private fun canPlace(
        occupied: Array<BooleanArray>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        maxColumns: Int,
        maxRows: Int
    ): Boolean {
        if (x + width > maxColumns || y + height > maxRows) return false

        for (yy in y until y + height) {
            for (xx in x until x + width) {
                if (occupied[yy][xx]) return false
            }
        }
        return true
    }

    /**
     * Marks cells as occupied.
     */
    private fun markOccupied(
        occupied: Array<BooleanArray>,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        for (yy in y until y + height) {
            for (xx in x until x + width) {
                occupied[yy][xx] = true
            }
        }
    }

    /**
     * Gets color adjustment percentage from project settings.
     */
    private fun getColorAdjustPercent(project: PhotoMosaicProject): Int {
        val percent = project.selectedColorChange?.percentageChange ?: 0
        return if (project.selectedColorChange?.isCustom == true) {
            project.customColorChange
        } else {
            percent
        }.coerceIn(0, 100)
    }

    /**
     * Saves a bitmap as JPEG to the designated output directory.
     */
    private fun saveBitmapAsJpeg(bitmap: Bitmap, prefix: String): String {
        val tempFile = File.createTempFile(prefix, ".jpg", outputDir)
        tempFile.outputStream().buffered().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return tempFile.absolutePath
    }

    /**
     * Writes usage report as CSV.
     */
    private fun writeUsageReport(usage: List<CellUsage>, cache: List<CellPhotoCache>): String {
        val reportFile = File.createTempFile("mosaic_usage_", ".csv", outputDir)
        val usageLookup = usage.groupBy { it.path }

        reportFile.bufferedWriter().use { writer ->
            writer.write("Name,UseCount,X,Y\n")

            for (item in cache) {
                val name = escapeCsv(File(item.path).name)
                val entries = usageLookup[item.path]

                if (entries == null || entries.isEmpty()) {
                    writer.write("$name,${item.useCount},,\n")
                } else {
                    for (entry in entries) {
                        writer.write("$name,${item.useCount},${entry.x},${entry.y}\n")
                    }
                }
            }
        }

        return reportFile.absolutePath
    }

    /**
     * Escapes a value for CSV format.
     */
    private fun escapeCsv(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    /**
     * Builds the parquet pattern sequence.
     */
    private fun buildParquetSequence(pattern: PatternInfo): List<PhotoOrientation> {
        val sequence = mutableListOf<PhotoOrientation>()
        repeat(maxOf(1, pattern.landscapeCount)) {
            sequence.add(PhotoOrientation.Landscape)
        }
        repeat(maxOf(1, pattern.portraitCount)) {
            sequence.add(PhotoOrientation.Portrait)
        }
        return sequence
    }

    private fun resolvePatternInfo(pattern: PatternInfo, photos: List<CellPhoto>): PatternInfo {
        if (pattern.kind != PatternKind.Parquet) return pattern

        val counts = countAvailablePhotos(photos, pattern)
        val landscape = counts.landscape
        val portrait = counts.portrait

        if (landscape <= 0 || portrait <= 0) return pattern

        return if (landscape >= portrait) {
            val ratio = maxOf(1, landscape / portrait)
            PatternInfo(PatternKind.Parquet, ratio, 1)
        } else {
            val ratio = maxOf(1, portrait / landscape)
            PatternInfo(PatternKind.Parquet, 1, ratio)
        }
    }

    /**
     * Reports progress to callback.
     */
    private fun reportProgress(onProgress: ((MosaicGenerationProgress) -> Unit)?, percent: Int, stage: String) {
        onProgress?.invoke(MosaicGenerationProgress(percent.coerceIn(0, 100), stage))
    }

    /**
     * Gets pattern information from pattern configuration.
     */
    private fun getPatternInfo(patternConfig: CellPhotoPatternConfig?): PatternInfo {
        val name = patternConfig?.name ?: "Square"

        return when {
            name.equals("Landscape", ignoreCase = true) -> {
                PatternInfo(PatternKind.Landscape, 1, 0)
            }
            name.equals("Portrait", ignoreCase = true) -> {
                PatternInfo(PatternKind.Portrait, 0, 1)
            }
            name.startsWith("Parquet", ignoreCase = true) -> {
                val pattern = Pattern.compile("""(\d+)\s*L\s*(\d+)\s*P""", Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(name)
                if (matcher.find()) {
                    PatternInfo(
                        PatternKind.Parquet,
                        matcher.group(1).toInt(),
                        matcher.group(2).toInt()
                    )
                } else {
                    PatternInfo(PatternKind.Parquet, 1, 1)
                }
            }
            else -> PatternInfo(PatternKind.Square, 0, 0)
        }
    }
}











