# API Reference - Core Mosaic Generation Service

## Main Service Class: `CoreMosaicGenerationService`

### Public Methods

#### `suspend fun generateMosaic()`
```kotlin
suspend fun generateMosaic(
    project: PhotoMosaicProject,
    maxUsesOverride: Int? = null,
    onProgress: ((MosaicGenerationProgress) -> Unit)? = null,
    scope: CoroutineScope = GlobalScope
): MosaicResult
```

**Purpose**: Generates a complete photo mosaic from a project configuration.

**Parameters**:
- `project: PhotoMosaicProject` - Configuration with primary image, cell photos, print settings
- `maxUsesOverride: Int?` - Optional override for max times a photo can be used (default: calculated from plan)
- `onProgress: ((MosaicGenerationProgress) -> Unit)?` - Callback for progress updates
- `scope: CoroutineScope` - Coroutine scope for cancellation (default: GlobalScope)

**Returns**: `MosaicResult` containing:
- File paths to generated mosaic and overlay image
- Grid dimensions and statistics
- Error message if generation failed
- Generation time in milliseconds

**Stages** (reported via onProgress):
1. Validating (0%)
2. Get Pattern (1%)
3. Verify Primary Image (2%)
4. Calculating Grid (3%)
5. Loading Primary Image (4%)
6. Building Cell Cache (5-10%, pre-processing)
7. Building Mosaic Plan (10%)
8. Preparing Primary Image (10%)
9. Creating Mosaic (10-95%)
10. Saving Results (95%)
11. Writing Report (98%)
12. Complete (100%)

**Example**:
```kotlin
val result = service.generateMosaic(
    project,
    onProgress = { progress ->
        println("${progress.percentComplete}% - ${progress.currentStage}")
    }
)

if (result.isSuccess) {
    println("Saved to: ${result.temporaryFilePath}")
} else {
    println("Error: ${result.errorMessage}")
}
```

---

#### `suspend fun buildMosaicPlan()`
```kotlin
suspend fun buildMosaicPlan(
    project: PhotoMosaicProject,
    scope: CoroutineScope = GlobalScope
): MosaicPlan
```

**Purpose**: Generates a plan without creating the mosaic (useful for UI previews).

**Parameters**:
- `project: PhotoMosaicProject` - Configuration to analyze
- `scope: CoroutineScope` - Coroutine scope

**Returns**: `MosaicPlan` containing:
- `totalCells: Int` - Number of cells needed
- `availablePhotos: Int` - Total photos available
- `maxPhotoUses: Int` - Recommended max uses per photo
- `landscapeCells: Int` - Landscape orientation cells needed
- `portraitCells: Int` - Portrait orientation cells needed
- `availableLandscapePhotos: Int` - Available landscape photos
- `availablePortraitPhotos: Int` - Available portrait photos

**Example**:
```kotlin
val plan = service.buildMosaicPlan(project)
println("Need $${plan.totalCells} cells from ${plan.availablePhotos} photos")
println("Recommended max uses: ${plan.maxPhotoUses}")
```

---

#### `fun loadBitmap()`
```kotlin
fun loadBitmap(
    path: String,
    maxWidth: Int,
    maxHeight: Int
): Bitmap?
```

**Purpose**: Loads a bitmap efficiently with smart sampling.

**Parameters**:
- `path: String` - File path to image
- `maxWidth: Int` - Maximum desired width (actual may be smaller)
- `maxHeight: Int` - Maximum desired height (actual may be smaller)

**Returns**: `Bitmap?` - Loaded bitmap or null if failed. Uses `RGB_565` format.

**Details**:
- Calculates optimal `inSampleSize` before loading
- Loads in `RGB_565` format (16-bit) to save memory
- Returns null on any error (logged via Log.e)

---

## Image Processing Utilities: `ImageProcessingUtils`

### Color Analysis Functions

#### `fun getAverageColorFast()`
```kotlin
fun getAverageColorFast(bitmap: Bitmap): RgbColor
```
Samples every ~50th pixel for fast average color calculation.

#### `fun getAverageColorRegionFast()`
```kotlin
fun getAverageColorRegionFast(
    bitmap: Bitmap,
    x: Int,
    y: Int,
    width: Int,
    height: Int
): RgbColor
```
Fast average color of a specific region with sampling.

#### `fun getAverageColorRegionClamped()`
```kotlin
fun getAverageColorRegionClamped(
    bitmap: Bitmap,
    x: Int,
    y: Int,
    width: Int,
    height: Int
): RgbColor
```
Region average with boundary clamping for out-of-bounds coordinates.

#### `fun getQuadrantColors()`
```kotlin
fun getQuadrantColors(
    bitmap: Bitmap,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    clamp: Boolean
): CellQuadrantColors
```
Gets average color of top-left, top-right, bottom-left, bottom-right quadrants.

---

### Distance Calculation Functions

#### `fun colorDistance()`
```kotlin
fun colorDistance(c1: RgbColor, c2: RgbColor): Double
```
Euclidean distance between two RGB colors (0.0 to 442.0 range).

#### `fun quadrantDistance()`
```kotlin
fun quadrantDistance(
    source: CellQuadrantColors,
    target: CellQuadrantColors
): Double
```
Sum of distances between all four quadrants.

---

### Image Manipulation Functions

#### `fun resizeBitmap()`
```kotlin
fun resizeBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int
): Bitmap
```
Resize bitmap to exact dimensions using bicubic interpolation.

#### `fun cropBitmap()`
```kotlin
fun cropBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int
): Bitmap
```
Center-crop bitmap to target dimensions.

#### `fun preparePrimaryImage()`
```kotlin
fun preparePrimaryImage(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
    keepAspectRatio: Boolean
): Bitmap
```
Resize primary image, optionally preserving aspect ratio or cropping.

#### `fun applyColorAdjustment()`
```kotlin
fun applyColorAdjustment(
    bitmap: Bitmap,
    targetColor: RgbColor,
    percentChange: Int
)
```
Blends bitmap pixels toward target color (0-100%).

#### `fun blurBitmap()`
```kotlin
fun blurBitmap(
    source: Bitmap,
    radius: Int
): Bitmap
```
Applies Gaussian blur with optional downsampling for performance.

#### `fun drawBitmapOnCanvas()`
```kotlin
fun drawBitmapOnCanvas(
    canvas: Canvas,
    cell: Bitmap,
    x: Int,
    y: Int
)
```
Efficiently draws a bitmap to canvas at specified position.

---

## Data Models

### Configuration Models

#### `PhotoMosaicProject`
```kotlin
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
```

#### `CellPhoto`
```kotlin
data class CellPhoto(
    val path: String,
    val orientation: PhotoOrientation
)
```

#### `PrintSize`
```kotlin
data class PrintSize(
    val name: String,
    val width: Double,
    val height: Double,
    val isCustom: Boolean = false
)
```

#### `Resolution`
```kotlin
data class Resolution(
    val name: String,
    val ppi: Int
)
```

#### `CellSize`
```kotlin
data class CellSize(
    val name: String,
    val sizeMm: Double,
    val isCustom: Boolean = false
)
```

### Result Models

#### `MosaicResult`
```kotlin
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
)
```

Property: `isSuccess: Boolean` - True if no error occurred

#### `MosaicGenerationProgress`
```kotlin
data class MosaicGenerationProgress(
    val percentComplete: Int,  // 0-100
    val currentStage: String
)
```

#### `MosaicPlan`
```kotlin
data class MosaicPlan(
    val totalCells: Int,
    val availablePhotos: Int,
    val maxPhotoUses: Int,
    val landscapeCells: Int,
    val portraitCells: Int,
    val availableLandscapePhotos: Int,
    val availablePortraitPhotos: Int
)
```

### Color Models

#### `RgbColor`
```kotlin
data class RgbColor(
    val r: Int,  // 0-255
    val g: Int,  // 0-255
    val b: Int   // 0-255
)
```

Methods:
- `toArgb(): Int` - Convert to ARGB integer
- Companion `Gray = RgbColor(128, 128, 128)`
- Companion `fromArgb(argb: Int): RgbColor` - Create from ARGB

#### `CellQuadrantColors`
```kotlin
data class CellQuadrantColors(
    val topLeft: RgbColor,
    val topRight: RgbColor,
    val bottomLeft: RgbColor,
    val bottomRight: RgbColor
)
```

---

## Enumerations

#### `PhotoOrientation`
```kotlin
enum class PhotoOrientation {
    Landscape,  // width > height
    Portrait,   // height > width
    Square      // width == height
}
```

#### `CellShape`
```kotlin
enum class CellShape {
    Square,           // 1:1 ratio
    Rectangle4x3,     // 4:3 ratio
    Rectangle3x2      // 3:2 ratio
}
```

#### `CellImageFitMode`
```kotlin
enum class CellImageFitMode {
    StretchToFit,  // Distort to fit
    CropCenter     // Preserve aspect, crop center
}
```

#### `PrimaryImageSizingMode`
```kotlin
enum class PrimaryImageSizingMode {
    KeepAspectRatio,  // Fit within bounds
    CropCenter        // Fill bounds, crop edges
}
```

#### `PatternKind`
```kotlin
enum class PatternKind {
    Square,      // All cells landscape
    Landscape,   // Only landscape orientation
    Portrait,    // Only portrait orientation
    Parquet      // Mix of orientations
}
```

---

## Error Handling

### Exception Types

**Thrown by service**:
- `IllegalStateException` - Invalid project configuration
- `CancellationException` - Generation cancelled

**Caught and reported**:
- All exceptions result in `result.errorMessage` being set
- No exceptions propagate (safe for UI layer)
- All resource cleanup happens in `finally` blocks

### Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| "Primary image is not selected" | `primaryImagePath` is null/blank | Set a valid primary image path |
| "No cell photos have been added" | `cellPhotos` is empty | Add at least one cell photo |
| "Primary image file not found" | File doesn't exist | Verify file path |
| "Unable to load primary image" | Can't read/decode file | Check file format and permissions |
| "No valid cell photos were loaded" | All cell photos failed to load | Check cell photo paths and formats |

---

## Performance Tips

1. **Use RGB_565**: Bitmaps automatically use this format (50% memory savings)
2. **Cache once**: All images cached in `buildCellCache()` - reuse the result
3. **Progress intervals**: Report every 500ms max, not every percent
4. **Cancel early**: Support cancellation for responsive UI
5. **Memory monitoring**: For 1000+ images, consider implementing LRU cache
6. **Grid size**: Larger cells = faster generation (fewer to process)
7. **Sampling**: Color sampling is ~2500x faster than full analysis

---

## Migration from C#

**What changed**:
- `IProgress<T>` → Function type `(T) -> Unit`
- `async/await` → `suspend` functions with coroutines
- `BitmapSource` → Android `Bitmap`
- `Point` → Pair of ints
- `Dictionary` → `Map`/`MutableMap`
- Nullable types explicit with `?`

**What stayed the same**:
- Algorithm logic identical
- Grid calculation approach
- Color matching formula
- Parquet pattern implementation
- File I/O for reports

---

## Testing Checklist

- [ ] Can generate mosaic with various grid sizes
- [ ] Handles cancellation gracefully
- [ ] Memory doesn't leak (bitmaps recycled)
- [ ] Progress reports reach 100%
- [ ] All cell photos attempted (even if some fail)
- [ ] Output files are valid JPEGs
- [ ] Report CSV is properly formatted
- [ ] Different patterns work correctly
- [ ] Custom sizing works
- [ ] Color adjustment applies

