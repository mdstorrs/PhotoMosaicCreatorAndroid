package com.storrs.photomosaiccreatorandroid

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.storrs.photomosaiccreatorandroid.ui.theme.PhotoMosaicCreatorAndroidTheme
import com.storrs.photomosaiccreatorandroid.ui.viewmodel.GenerationState
import com.storrs.photomosaiccreatorandroid.ui.viewmodel.MosaicViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import android.os.Environment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.FileProvider
//import com.storrs.photomosaiccreatorandroid.BuildConfig

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

private enum class WizardStep {
    Start,
    MainPhoto,
    CellPhotos,
    Options,
    Preview
}

@Composable
fun MosaicGeneratorScreen(viewModel: MosaicViewModel) {
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val primaryImagePath by viewModel.primaryImagePath.collectAsStateWithLifecycle()
    val cellPhotoPaths by viewModel.cellPhotoPaths.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val backgroundColor = Color(0xFFF7F5F0)
    val primaryAccent = Color(0xFF7FB7E6)
    val secondaryAccent = Color(0xFFFFD166)

    var currentStep by rememberSaveable { mutableStateOf(WizardStep.Start) }
    var showCellPhotoGrid by rememberSaveable { mutableStateOf(false) }
    var showAdvancedSettings by rememberSaveable { mutableStateOf(false) }
    var showDebugReport by rememberSaveable { mutableStateOf(false) }
    var debugReportText by remember { mutableStateOf("") }
    var previewResult by remember { mutableStateOf<com.storrs.photomosaiccreatorandroid.models.MosaicResult?>(null) }
    var lastAutoPreviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var overlayOpacity by rememberSaveable { mutableStateOf(0.5f) }
    var blurRadius by rememberSaveable { mutableStateOf(2f) }

    val goBack: () -> Unit = {
        currentStep = when (currentStep) {
            WizardStep.Start -> WizardStep.Start
            WizardStep.MainPhoto -> WizardStep.Start
            WizardStep.CellPhotos -> WizardStep.MainPhoto
            WizardStep.Options -> WizardStep.CellPhotos
            WizardStep.Preview -> WizardStep.Options
        }
    }

    BackHandler(enabled = showDebugReport) { showDebugReport = false }
    BackHandler(enabled = showCellPhotoGrid) { showCellPhotoGrid = false }
    BackHandler(enabled = currentStep != WizardStep.Start) { goBack() }

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

    if (generationState is GenerationState.Success) {
        val result = (generationState as GenerationState.Success).result
        val resultPath = result.temporaryFilePath
        if (resultPath != null && lastAutoPreviewPath != resultPath) {
            previewResult = result
            currentStep = WizardStep.Preview
            lastAutoPreviewPath = resultPath
        }
    }

    Surface(color = backgroundColor, modifier = Modifier.fillMaxSize()) {
        when {
            showCellPhotoGrid -> {
                CellPhotoGridScreen(
                    cellPhotoPaths = cellPhotoPaths,
                    onClose = { showCellPhotoGrid = false },
                    onAddMore = { multipleImagesLauncher.launch("image/*") },
                    onClearAll = { viewModel.clearCellPhotos() },
                    onDeleteSelected = { selected ->
                        viewModel.removeCellPhotos(selected)
                    }
                )
            }
            currentStep == WizardStep.Preview && previewResult != null -> {
                MosaicPreviewScreen(
                    result = previewResult!!,
                    primaryImagePath = primaryImagePath,
                    overlayOpacity = overlayOpacity,
                    blurRadius = blurRadius,
                    onOverlayOpacityChange = { overlayOpacity = it },
                    onBlurRadiusChange = { blurRadius = it },
                    onClose = goBack,
                    onStartAgain = {
                        viewModel.reset()
                        previewResult = null
                        lastAutoPreviewPath = null
                        currentStep = WizardStep.Start
                    }
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (currentStep) {
                        WizardStep.Start -> {
                            StepHeader(title = "Photo Mosaic Creator", showBack = false)
                            Spacer(modifier = Modifier.height(12.dp))
                            LargePrimaryButton(
                                text = "Create Mosaic",
                                onClick = { currentStep = WizardStep.MainPhoto },
                                color = primaryAccent
                            )
                        }
                        WizardStep.MainPhoto -> {
                            StepHeader(title = "Main Photo", onBack = goBack)
                            if (primaryImagePath != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(MaterialTheme.shapes.medium)
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
                            LargePrimaryButton(
                                text = if (primaryImagePath == null) "Add Main Photo" else "Change Main Photo",
                                onClick = { primaryImageLauncher.launch("image/*") },
                                color = primaryAccent
                            )
                            LargeSecondaryButton(
                                text = "Continue",
                                onClick = { currentStep = WizardStep.CellPhotos },
                                enabled = primaryImagePath != null
                            )
                        }
                        WizardStep.CellPhotos -> {
                            StepHeader(title = "Small Photos", onBack = goBack)
                            LargePrimaryButton(
                                text = if (cellPhotoPaths.isEmpty()) "Add small photos" else "Add More Photos",
                                onClick = { multipleImagesLauncher.launch("image/*") },
                                color = primaryAccent
                            )
                            LargeSecondaryButton(
                                text = "${cellPhotoPaths.size} photos selected",
                                onClick = { showCellPhotoGrid = true },
                                enabled = cellPhotoPaths.isNotEmpty(),
                                highlight = true
                            )
                            LargeSecondaryButton(
                                text = "Continue",
                                onClick = { currentStep = WizardStep.Options },
                                enabled = cellPhotoPaths.isNotEmpty()
                            )
                        }
                        WizardStep.Options -> {
                            StepHeader(title = "Select Options", onBack = goBack)
                            SettingsDropdown(
                                label = "Print Size",
                                selectedLabel = formatPrintSize(settings.selectedPrintSize),
                                options = settings.printSizes.map { formatPrintSize(it) },
                                onSelected = { index ->
                                    settings.printSizes.getOrNull(index)
                                        ?.let { viewModel.updatePrintSize(it) }
                                },
                                highlight = true
                            )
                            SettingsDropdown(
                                label = "Cell Photo Size",
                                selectedLabel = settings.selectedCellSize.label,
                                options = settings.cellSizes.map { it.label },
                                onSelected = { index ->
                                    settings.cellSizes.getOrNull(index)
                                        ?.let { viewModel.updateCellSize(it) }
                                },
                                highlight = true
                            )
                            SubtleButton(
                                text = "Advanced",
                                onClick = { showAdvancedSettings = true }
                            )
                            LargePrimaryButton(
                                text = "Go!",
                                onClick = {
                                    //if (BuildConfig.DEBUG) {
                                    //    debugReportText = viewModel.buildDebugReport(primaryImagePath, cellPhotoPaths)
                                    //    showDebugReport = true
                                    //} else {
                                    viewModel.generateMosaicWithDefaults()
                                    //}
                                },
                                color = secondaryAccent,
                                enabled = primaryImagePath != null && cellPhotoPaths.isNotEmpty()
                            )
                            if (generationState is GenerationState.Loading) {
                                GenerationProgressUI(progress, viewModel)
                            }
                        }
                        WizardStep.Preview -> Unit
                    }
                }
            }
        }

        if (showAdvancedSettings) {
            AdvancedSettingsDialog(
                settings = settings,
                onDismiss = { showAdvancedSettings = false },
                onUpdateColorChange = { viewModel.updateColorChange(it) },
                onUpdatePattern = { viewModel.updatePattern(it) },
                onUpdateUseAllImages = { viewModel.updateUseAllImages(it) },
                onUpdateMirrorImages = { viewModel.updateMirrorImages(it) }
            )
        }

        if (showDebugReport) {
            DebugReportDialog(
                reportText = debugReportText,
                onContinue = {
                    showDebugReport = false
                    viewModel.generateMosaicWithDefaults()
                },
                onCancel = { showDebugReport = false }
            )
        }
    }
}

@Composable
private fun StepHeader(title: String, onBack: (() -> Unit)? = null, showBack: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        if (showBack && onBack != null) {
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun LargePrimaryButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LargeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false
) {
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = content
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SubtleButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun GenerationProgressUI(
    progress: com.storrs.photomosaiccreatorandroid.models.MosaicGenerationProgress,
    viewModel: MosaicViewModel
) {
    val simpleStage = "Creating mosaic"

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
                text = "$simpleStage ${progress.percentComplete}%",
                style = MaterialTheme.typography.titleMedium
            )

            LinearProgressIndicator(
                progress = { progress.percentComplete / 100f },
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
    onOpenPreview: () -> Unit,
    onCreateAnother: () -> Unit
) {
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenPreview,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Open Preview")
                }
                Button(
                    onClick = onCreateAnother,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Create Another")
                }
            }
        }
    }
}

@Composable
fun MosaicPreviewScreen(
    result: com.storrs.photomosaiccreatorandroid.models.MosaicResult,
    primaryImagePath: String?,
    overlayOpacity: Float,
    blurRadius: Float,
    onOverlayOpacityChange: (Float) -> Unit,
    onBlurRadiusChange: (Float) -> Unit,
    onClose: () -> Unit,
    onStartAgain: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val blurredOverlayPath by produceState<String?>(
        initialValue = null,
        key1 = primaryImagePath,
        key2 = blurRadius
    ) {
        value = if (primaryImagePath.isNullOrBlank()) {
            null
        } else {
            prepareBlurredOverlay(context, primaryImagePath, blurRadius)
        }
    }

    val mosaicFile = remember(result.temporaryFilePath) {
        result.temporaryFilePath?.let { File(it) }
    }
    val overlayFile = remember(blurredOverlayPath) {
        blurredOverlayPath?.let { File(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Preview & Tweak",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text("Back")
            }
        }

        MosaicZoomableCanvas(
            mosaicFile = mosaicFile,
            overlayFile = overlayFile,
            overlayOpacity = overlayOpacity,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = "Overlay: ${(overlayOpacity * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = overlayOpacity,
            onValueChange = onOverlayOpacityChange,
            valueRange = 0f..1f,
            modifier = Modifier.height(48.dp)
        )

        Text(
            text = "Blur: ${blurRadius.roundToInt()}",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = blurRadius,
            onValueChange = onBlurRadiusChange,
            valueRange = 0f..25f,
            modifier = Modifier.height(48.dp)
        )

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (!isSaving && mosaicFile != null) {
                        isSaving = true
                        val exportFile = createCompositeForExport(
                            context = context,
                            mosaicFile = mosaicFile,
                            overlayFile = overlayFile,
                            overlayOpacity = overlayOpacity
                        ) ?: mosaicFile
                        val savedPath = viewModelSaveToGallery(context, exportFile)
                        saveMessage = if (savedPath != null) {
                            "✓ Saved to Gallery: ${File(savedPath).name}"
                        } else {
                            "✗ Failed to save to Gallery"
                        }
                        isSaving = false
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = mosaicFile != null && !isSaving
            ) {
                Text(if (isSaving) "Saving..." else "Save to Gallery")
            }
            Button(
                onClick = {
                    val exportFile = if (mosaicFile != null) {
                        createCompositeForExport(
                            context = context,
                            mosaicFile = mosaicFile,
                            overlayFile = overlayFile,
                            overlayOpacity = overlayOpacity
                        ) ?: mosaicFile
                    } else {
                        null
                    }
                    shareMosaic(context, exportFile)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = mosaicFile != null
            ) {
                Text("Share")
            }
        }

        Button(
            onClick = onStartAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Start again")
        }
    }
}

private fun createCompositeForExport(
    context: android.content.Context,
    mosaicFile: File,
    overlayFile: File?,
    overlayOpacity: Float
): File? {
    if (overlayFile == null || overlayOpacity <= 0f) return mosaicFile
    val opacityPercent = (overlayOpacity.coerceIn(0f, 1f) * 100f).roundToInt()
    val cacheFile = File(
        context.cacheDir,
        "mosaic_composite_${mosaicFile.nameWithoutExtension}_${overlayFile.nameWithoutExtension}_${opacityPercent}.jpg"
    )
    if (cacheFile.exists()) return cacheFile

    val mosaicBitmap = BitmapFactory.decodeFile(mosaicFile.absolutePath) ?: return null
    val overlayBitmap = BitmapFactory.decodeFile(overlayFile.absolutePath)
        ?: run {
            mosaicBitmap.recycle()
            return null
        }

    val output = Bitmap.createBitmap(
        mosaicBitmap.width,
        mosaicBitmap.height,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(output)
    canvas.drawBitmap(mosaicBitmap, 0f, 0f, null)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = (overlayOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
    }
    val dst = Rect(0, 0, mosaicBitmap.width, mosaicBitmap.height)
    canvas.drawBitmap(overlayBitmap, null, dst, paint)

    cacheFile.outputStream().use { out ->
        output.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    mosaicBitmap.recycle()
    overlayBitmap.recycle()
    output.recycle()

    return cacheFile
}

@Composable
private fun MosaicZoomableCanvas(
    mosaicFile: File?,
    overlayFile: File?,
    overlayOpacity: Float,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset += offsetChange
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .transformable(state = transformableState)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentAlignment = Alignment.Center
        ) {
            if (mosaicFile != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(mosaicFile)
                        .size(Size.ORIGINAL)
                        .build(),
                    contentDescription = "Mosaic preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            if (overlayFile != null && overlayOpacity > 0f) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(overlayFile)
                        .size(Size.ORIGINAL)
                        .build(),
                    contentDescription = "Original overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alpha = overlayOpacity
                )
            }
        }
    }
}

private fun viewModelSaveToGallery(context: android.content.Context, file: File): String? {
    return try {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val mosaicDir = File(picturesDir, "PhotoMosaics")
        if (!mosaicDir.exists()) {
            mosaicDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "mosaic_$timestamp.jpg"
        val savedFile = File(mosaicDir, fileName)

        file.copyTo(savedFile, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(savedFile.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
        savedFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private fun shareMosaic(context: android.content.Context, file: File?) {
    if (file == null || !file.exists()) return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Mosaic"))
}

private fun prepareBlurredOverlay(
    context: android.content.Context,
    primaryPath: String,
    blurRadius: Float
): String? {
    val radius = blurRadius.coerceIn(0f, 25f)
    val cacheFile = File(
        context.cacheDir,
        "mosaic_overlay_${primaryPath.hashCode()}_${radius.roundToInt()}.jpg"
    )
    if (cacheFile.exists()) return cacheFile.absolutePath

    val resized = loadScaledBitmap(primaryPath, 1024) ?: return null
    val blurred = if (radius <= 0f) {
        resized
    } else {
        blurBitmap(resized, radius)
    }

    cacheFile.outputStream().use { out ->
        blurred.compress(Bitmap.CompressFormat.JPEG, 92, out)
    }

    if (blurred != resized) {
        blurred.recycle()
    }
    resized.recycle()

    return cacheFile.absolutePath
}

private fun loadScaledBitmap(path: String, maxSide: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width <= 0 || height <= 0) return null

    var sampleSize = 1
    var longest = maxOf(width, height)
    while (longest / sampleSize > maxSide) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

    val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
    return if (scale < 1f) {
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { decoded.recycle() }
    } else {
        decoded
    }
}

private fun blurBitmap(input: Bitmap, radius: Float): Bitmap {
    // Simple stack blur (fast enough for 1024px overlays and avoids API-specific blur APIs).
    val bitmap = input.copy(Bitmap.Config.ARGB_8888, true)
    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val r = IntArray(w * h)
    val g = IntArray(w * h)
    val b = IntArray(w * h)
    var idx = 0
    while (idx < w * h) {
        val p = pix[idx]
        r[idx] = (p shr 16) and 0xff
        g[idx] = (p shr 8) and 0xff
        b[idx] = p and 0xff
        idx++
    }

    val rad = radius.roundToInt().coerceAtLeast(1)
    val div = rad * 2 + 1
    val vmin = IntArray(maxOf(w, h))

    var yi = 0
    var yw = 0
    val dv = IntArray(256 * div)
    var i = 0
    while (i < dv.size) {
        dv[i] = (i / div)
        i++
    }

    val rsum = IntArray(w * h)
    val gsum = IntArray(w * h)
    val bsum = IntArray(w * h)

    var y = 0
    while (y < h) {
        var rAcc = 0
        var gAcc = 0
        var bAcc = 0
        var x = -rad
        while (x <= rad) {
            val p = pix[yi + minOf(w - 1, maxOf(x, 0))]
            rAcc += (p shr 16) and 0xff
            gAcc += (p shr 8) and 0xff
            bAcc += p and 0xff
            x++
        }
        var xi = 0
        while (xi < w) {
            rsum[yi + xi] = dv[rAcc]
            gsum[yi + xi] = dv[gAcc]
            bsum[yi + xi] = dv[bAcc]

            if (y == 0) {
                vmin[xi] = minOf(xi + rad + 1, w - 1)
            }
            val p1 = pix[yw + vmin[xi]]
            val p2 = pix[yw + maxOf(xi - rad, 0)]
            rAcc += ((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)
            gAcc += ((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)
            bAcc += (p1 and 0xff) - (p2 and 0xff)
            xi++
        }
        yw += w
        yi += w
        y++
    }

    var x = 0
    while (x < w) {
        var rAcc = 0
        var gAcc = 0
        var bAcc = 0
        var yIndex = -rad
        while (yIndex <= rad) {
            val idx2 = maxOf(0, yIndex) * w + x
            rAcc += rsum[idx2]
            gAcc += gsum[idx2]
            bAcc += bsum[idx2]
            yIndex++
        }
        var y2 = 0
        var yi2 = x
        while (y2 < h) {
            pix[yi2] = (0xff shl 24) or (dv[rAcc] shl 16) or (dv[gAcc] shl 8) or dv[bAcc]
            if (x == 0) {
                vmin[y2] = minOf(y2 + rad + 1, h - 1) * w
            }
            val p1 = x + vmin[y2]
            val p2 = x + maxOf(y2 - rad, 0) * w
            rAcc += rsum[p1] - rsum[p2]
            gAcc += gsum[p1] - gsum[p2]
            bAcc += bsum[p1] - bsum[p2]
            yi2 += w
            y2++
        }
        x++
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    return bitmap
}

@Composable
fun DebugReportDialog(
    reportText: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        title = { Text("Debug Report") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = reportText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

@Composable
fun AdvancedSettingsDialog(
    settings: com.storrs.photomosaiccreatorandroid.ui.viewmodel.MosaicSettingsState,
    onDismiss: () -> Unit,
    onUpdateColorChange: (Int) -> Unit,
    onUpdatePattern: (com.storrs.photomosaiccreatorandroid.models.PatternKind) -> Unit,
    onUpdateUseAllImages: (Boolean) -> Unit,
    onUpdateMirrorImages: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Advanced Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Color Change: ${settings.colorChangePercent}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.colorChangePercent.toFloat(),
                    onValueChange = { value -> onUpdateColorChange(value.roundToInt()) },
                    valueRange = 0f..100f
                )

                SettingsDropdown(
                    label = "Pattern",
                    selectedLabel = settings.pattern.name,
                    options = listOf("Square", "Parquet"),
                    onSelected = { index ->
                        val pattern = if (index == 1)
                            com.storrs.photomosaiccreatorandroid.models.PatternKind.Parquet
                        else
                            com.storrs.photomosaiccreatorandroid.models.PatternKind.Square
                        onUpdatePattern(pattern)
                    }
                )

                if (settings.pattern == com.storrs.photomosaiccreatorandroid.models.PatternKind.Parquet) {
                    Text(
                        text = "Parquet ratio (L:P): ${settings.parquetRatio}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Landscape: ${settings.landscapeCount} | Portrait: ${settings.portraitCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = settings.useAllImages,
                        onCheckedChange = onUpdateUseAllImages
                    )
                    Text("Use All Images")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = settings.mirrorImages,
                        onCheckedChange = onUpdateMirrorImages
                    )
                    Text("Mirror Images")
                }
            }
        }
    )
}

@Composable
fun SettingsDropdown(
    label: String,
    selectedLabel: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    highlight: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = container,
                contentColor = content
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(selectedLabel, style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(index)
                    }
                )
            }
        }
    }
}

private fun formatPrintSize(option: com.storrs.photomosaiccreatorandroid.ui.viewmodel.PrintSizeOption): String {
    val width = formatInches(option.widthInches)
    val height = formatInches(option.heightInches)
    return "${option.label} (${width}\" x ${height}\")"
}

private fun formatInches(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

/**
 * Resolves a content URI to a stable file path.
 *
 * First attempts to read the real file path from MediaStore.
 * If the real path exists on disk it is returned directly.
 * Otherwise the image bytes are copied into the app's private
 * "cell_images" directory so the path survives across app restarts.
 */
fun getRealPathFromURI(context: android.content.Context, uri: Uri): String? {
    // 1. Try MediaStore real-path (fastest – no copy needed)
    if (uri.scheme == "content") {
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val realPath = it.getString(index)
                    if (realPath != null && File(realPath).exists()) {
                        return realPath
                    }
                }
            }
        } catch (_: Exception) { /* fall through */ }
    }

    if (uri.scheme == "file") {
        val filePath = uri.path
        if (filePath != null && File(filePath).exists()) return filePath
    }

    // 2. Fallback – copy the stream into app-private storage
    return try {
        val dir = File(context.filesDir, "cell_images")
        if (!dir.exists()) dir.mkdirs()

        val fileName = "img_${uri.hashCode().toUInt()}.jpg"
        val dest = File(dir, fileName)
        if (dest.exists()) return dest.absolutePath  // already copied

        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (dest.exists() && dest.length() > 0) dest.absolutePath else null
    } catch (e: Exception) {
        Log.e("MainActivity", "Error copying URI to app storage", e)
        null
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
    BackHandler(onBack = onClose)
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
