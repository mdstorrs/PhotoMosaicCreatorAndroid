package com.storrs.photomosaiccreatorandroid.models

import android.graphics.Bitmap
import java.io.File

// ============================================================================
// Enums and Constants
// ============================================================================

enum class PhotoOrientation {
    Landscape,
    Portrait,
    Square
}

enum class CellShape {
    Square,
    Rectangle4x3,
    Rectangle3x2
}

enum class CellImageFitMode {
    StretchToFit,
    CropCenter
}

enum class PrimaryImageSizingMode {
    KeepAspectRatio,
    CropCenter
}

enum class PatternKind {
    Square,
    Landscape,
    Portrait,
    Parquet
}

// ============================================================================
// Color Models
// ============================================================================

/**
 * Represents an RGB color value.
 */
data class RgbColor(
    val r: Int,
    val g: Int,
    val b: Int
) {
    init {
        require(r in 0..255) { "Red value must be 0-255" }
        require(g in 0..255) { "Green value must be 0-255" }
        require(b in 0..255) { "Blue value must be 0-255" }
    }

    fun toArgb(): Int = 0xFF000000.toInt() or
        ((r and 0xFF) shl 16) or
        ((g and 0xFF) shl 8) or
        (b and 0xFF)

    companion object {
        fun fromArgb(argb: Int): RgbColor {
            return RgbColor(
                (argb shr 16) and 0xFF,
                (argb shr 8) and 0xFF,
                argb and 0xFF
            )
        }

        val Gray = RgbColor(128, 128, 128)
    }
}

/**
 * Represents the average color in each quadrant of a cell image.
 */
data class CellQuadrantColors(
    val topLeft: RgbColor,
    val topRight: RgbColor,
    val bottomLeft: RgbColor,
    val bottomRight: RgbColor
)

// ============================================================================
// Cache Models
// ============================================================================

/**
 * Pre-processed and resized cell photo ready for placement.
 * This is cached during the build phase to avoid repeated processing.
 */
data class CellPhotoCache(
    val path: String,
    val orientation: PhotoOrientation,
    val averageColor: RgbColor,
    val resizedLandscapeBitmap: Bitmap?,
    val resizedPortraitBitmap: Bitmap?,
    val landscapeQuadrants: CellQuadrantColors,
    val portraitQuadrants: CellQuadrantColors,
    var useCount: Int = 0
) {
    fun cleanup() {
        resizedLandscapeBitmap?.recycle()
        resizedPortraitBitmap?.recycle()
    }
}

// ============================================================================
// Grid and Dimension Models
// ============================================================================

/**
 * Information about the grid layout for the mosaic.
 */
data class GridDimensions(
    val width: Int,
    val height: Int,
    val baseCellPixels: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val landscapeCellWidth: Int,
    val landscapeCellHeight: Int,
    val portraitCellWidth: Int,
    val portraitCellHeight: Int,
    val rows: Int,
    val columns: Int,
    val unitRows: Int,
    val unitColumns: Int
)

data class CellCounts(
    val total: Int,
    val landscape: Int,
    val portrait: Int
)

data class PhotoCounts(
    val total: Int,
    val landscape: Int,
    val portrait: Int
)

// ============================================================================
// Pattern Models
// ============================================================================

/**
 * Information about a cell photo pattern configuration.
 */
data class PatternInfo(
    val kind: PatternKind,
    val landscapeCount: Int,
    val portraitCount: Int
)

// ============================================================================
// Placement Models
// ============================================================================

/**
 * Represents a location where a cell image will be placed in the mosaic.
 */
data class MosaicPlacement(
    val row: Int,
    val col: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val orientation: PhotoOrientation,
    val targetColor: RgbColor,
    val targetQuadrants: CellQuadrantColors
)

/**
 * Tracks usage of a cell photo in the mosaic for reporting.
 */
data class CellUsage(
    val path: String,
    val x: Int,
    val y: Int
)

// ============================================================================
// Plan and Result Models
// ============================================================================

/**
 * Information about the mosaic that will be generated.
 */
data class MosaicPlan(
    val totalCells: Int,
    val availablePhotos: Int,
    val maxPhotoUses: Int,
    val landscapeCells: Int,
    val portraitCells: Int,
    val availableLandscapePhotos: Int,
    val availablePortraitPhotos: Int
)

/**
 * Progress information for mosaic generation.
 */
data class MosaicGenerationProgress(
    val percentComplete: Int,
    val currentStage: String
) {
    init {
        require(percentComplete in 0..100) { "Progress must be 0-100" }
    }
}

/**
 * Result of mosaic generation including file paths and statistics.
 */
data class MosaicResult(
    var gridRows: Int = 0,
    var gridColumns: Int = 0,
    var outputWidth: Int = 0,
    var outputHeight: Int = 0,
    var temporaryFilePath: String? = null,
    var overlayImagePath: String? = null,
    var usageReportPath: String? = null,
    var overlayOpacityPercent: Int = 0,
    var totalCellPhotos: Int = 0,
    var usedCellPhotos: Int = 0,
    var generationTimeMs: Long = 0,
    var errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}

// ============================================================================
// Project Configuration Models
// ============================================================================

/**
 * Represents a print size preset.
 */
data class PrintSize(
    val name: String,
    val width: Double,
    val height: Double,
    val isCustom: Boolean = false
)

/**
 * Represents a resolution setting.
 */
data class Resolution(
    val name: String,
    val ppi: Int
)

/**
 * Represents a cell size setting.
 */
data class CellSize(
    val name: String,
    val sizeMm: Double,
    val isCustom: Boolean = false
)

/**
 * Represents a color change setting.
 */
data class ColorChange(
    val name: String,
    val percentageChange: Int,
    val isCustom: Boolean = false
)

/**
 * Represents duplicate spacing constraints.
 */
data class DuplicateSpacing(
    val name: String,
    val minSpacing: Int
)

/**
 * Represents a cell photo to be used in the mosaic.
 */
data class CellPhoto(
    val path: String,
    val orientation: PhotoOrientation
)

/**
 * Represents a pattern configuration for cell photos.
 */
data class CellPhotoPatternConfig(
    val name: String
)

/**
 * The complete project configuration for mosaic generation.
 */
data class PhotoMosaicProject(
    val primaryImagePath: String? = null,
    val cellPhotos: List<CellPhoto> = emptyList(),
    val selectedPrintSize: PrintSize? = null,
    val selectedResolution: Resolution? = null,
    val selectedCellSize: CellSize? = null,
    val selectedCellPhotoPattern: CellPhotoPatternConfig? = null,
    val selectedColorChange: ColorChange? = null,
    val selectedDuplicateSpacing: DuplicateSpacing? = null,
    val customWidth: Double = 0.0,
    val customHeight: Double = 0.0,
    val customCellSize: Double = 0.0,
    val customColorChange: Int = 0,
    val cellShape: CellShape = CellShape.Square,
    val cellImageFitMode: CellImageFitMode = CellImageFitMode.CropCenter,
    val primaryImageSizingMode: PrimaryImageSizingMode = PrimaryImageSizingMode.KeepAspectRatio,
    val randomCellCandidates: Int = 5,
    val useAllImages: Boolean = false,
    val createReport: Boolean = true
)

