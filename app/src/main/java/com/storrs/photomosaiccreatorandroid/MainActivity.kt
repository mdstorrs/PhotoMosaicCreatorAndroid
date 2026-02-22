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
import android.provider.OpenableColumns
import android.content.Context
import android.database.Cursor
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.detectTapGestures
// import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlin.math.min
import kotlin.math.roundToInt
import android.os.Environment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.FileProvider
//import com.storrs.photomosaiccreatorandroid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.storrs.photomosaiccreatorandroid.preview.BrushSize
import com.storrs.photomosaiccreatorandroid.preview.applyMaskToOverlay
import com.storrs.photomosaiccreatorandroid.preview.brushRadiusFor
import com.storrs.photomosaiccreatorandroid.preview.combineMasks
import com.storrs.photomosaiccreatorandroid.preview.createEmptyMask
import com.storrs.photomosaiccreatorandroid.preview.createSolidMask
import com.storrs.photomosaiccreatorandroid.preview.generateFaceMaskBitmap
import com.storrs.photomosaiccreatorandroid.preview.paintSoftBrushOnMask
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.storrs.photomosaiccreatorandroid.models.PatternKind
import kotlin.math.floor

// ── Color Palette ──
private val SoftParchment = Color(0xFFF9F7F2)
private val GoldenLab = Color(0xFFF4D35E)
private val DeepNavy = Color(0xFF1F2D3D)
private val SlateBlue = Color(0xFF5A7D9A)
private val WarmGrey = Color(0xFFA8A196)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
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

    val backgroundColor = Color(0xFFF9F7F2)
    val primaryAccent = Color(0xFFF4D35E)
    val secondaryAccent = Color(0xFF5A7D9A)
    val deepNavy = Color(0xFF1F2D3D)
    val warmGrey = Color(0xFFA8A196)

    var currentStep by rememberSaveable { mutableStateOf(WizardStep.Start) }
    var showDebugReport by rememberSaveable { mutableStateOf(false) }
    var debugReportText by remember { mutableStateOf("") }
    var previewResult by remember { mutableStateOf<com.storrs.photomosaiccreatorandroid.models.MosaicResult?>(null) }
    var lastAutoPreviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var overlayOpacity by rememberSaveable { mutableStateOf(0.3f) }
    var faceOverlayBoost by rememberSaveable { mutableStateOf(0.5f) }

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
            currentStep == WizardStep.Preview && previewResult != null -> {
                MosaicPreviewScreen(
                    result = previewResult!!,
                    primaryImagePath = primaryImagePath,
                    overlayOpacity = overlayOpacity,
                    faceOverlayBoost = faceOverlayBoost,
                    onOverlayOpacityChange = { overlayOpacity = it },
                    onFaceOverlayBoostChange = { faceOverlayBoost = it },
                    onClose = goBack,
                    onStartAgain = {
                        viewModel.reset()
                        previewResult = null
                        lastAutoPreviewPath = null
                        currentStep = WizardStep.Start
                    }
                )
            }
            currentStep == WizardStep.CellPhotos -> {
                // ── Full-screen Cell Photos step with grid ──
                var selectedCells by remember { mutableStateOf(setOf<String>()) }
                var showOverflowMenu by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SoftParchment)
                ) {
                    TitleBar(title = "Small Photos", onBack = goBack, barColor = deepNavy)

                    // ── Photo count label ──
                    if (cellPhotoPaths.isNotEmpty()) {
                        Text(
                            text = "${cellPhotoPaths.size} photos",
                            style = MaterialTheme.typography.bodySmall,
                            color = WarmGrey,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            textAlign = TextAlign.End
                        )
                    }

                    // ── Scrollable image grid ──
                    if (cellPhotoPaths.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No photos added yet.\nTap \"Add Photos\" to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarmGrey,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(cellPhotoPaths) { path ->
                                val isSelected = selectedCells.contains(path)
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color = if (isSelected) SlateBlue else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            selectedCells = if (isSelected) {
                                                selectedCells - path
                                            } else {
                                                selectedCells + path
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

                    // ── Bottom toolbar ──
                    Divider(color = WarmGrey.copy(alpha = 0.3f), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SoftParchment)
                            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { multipleImagesLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryAccent,
                                contentColor = deepNavy
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Text("Add Photos", style = MaterialTheme.typography.titleSmall)
                        }
                        Button(
                            onClick = { currentStep = WizardStep.Options },
                            enabled = cellPhotoPaths.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = secondaryAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Text("Continue", style = MaterialTheme.typography.titleSmall)
                        }

                        // ── 3-dot overflow menu ──
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = "More options",
                                    tint = DeepNavy
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete Selected") },
                                    onClick = {
                                        viewModel.removeCellPhotos(selectedCells.toList())
                                        selectedCells = emptySet()
                                        showOverflowMenu = false
                                    },
                                    enabled = selectedCells.isNotEmpty()
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear All") },
                                    onClick = {
                                        viewModel.clearCellPhotos()
                                        selectedCells = emptySet()
                                        showOverflowMenu = false
                                    },
                                    enabled = cellPhotoPaths.isNotEmpty()
                                )
                            }
                        }
                    }
                }
            }
            currentStep == WizardStep.Options -> {
                // ── Full-screen Options step with bottom toolbar & slide-out panel ──
                var showOptionsPanel by remember { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SoftParchment)
                    ) {
                    TitleBar(title = "Select Options", onBack = goBack, barColor = deepNavy)

                    // ── Scrollable middle area (image preview) ──
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (primaryImagePath != null) {
                            MosaicGridPreview(
                                primaryImagePath = primaryImagePath!!,
                                pattern = settings.pattern,
                                printSize = settings.selectedPrintSize,
                                cellSize = settings.selectedCellSize,
                                landscapeCount = settings.landscapeCount,
                                portraitCount = settings.portraitCount
                            )
                        }
                    }

                    // ── Bottom toolbar ──
                    Divider(color = WarmGrey.copy(alpha = 0.3f), thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SoftParchment)
                            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showOptionsPanel = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryAccent,
                                contentColor = deepNavy
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Text("Options", style = MaterialTheme.typography.titleSmall)
                        }

                        Button(
                            onClick = { viewModel.generateMosaicWithDefaults() },
                            enabled = primaryImagePath != null && cellPhotoPaths.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = secondaryAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                        ) {
                            Text("Go!", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    }

                    // ── Animated slide-out options panel (scrim + panel) ──
                    // Scrim with fade
                    AnimatedVisibility(
                        visible = showOptionsPanel,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable { showOptionsPanel = false }
                        )
                    }
                    // Panel sliding down from top
                    AnimatedVisibility(
                        visible = showOptionsPanel,
                        enter = slideInVertically(
                            initialOffsetY = { fullHeight -> -fullHeight },
                            animationSpec = tween(350)
                        ) + fadeIn(animationSpec = tween(350)),
                        exit = slideOutVertically(
                            targetOffsetY = { fullHeight -> -fullHeight },
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color.White,
                                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                                )
                                .statusBarsPadding()
                                .padding(bottom = 8.dp)
                        ) {
                            // Panel header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Options",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = DeepNavy
                                )
                                TextButton(onClick = { showOptionsPanel = false }) {
                                    Text("Done", color = SlateBlue)
                                }
                            }

                            Divider(color = WarmGrey.copy(alpha = 0.2f), thickness = 1.dp)

                            // Panel content – scrollable
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Print Size
                                SettingsDropdown(
                                    label = "Print Size",
                                    selectedLabel = formatPrintSize(settings.selectedPrintSize),
                                    options = settings.printSizes.map { formatPrintSize(it) },
                                    onSelected = { index ->
                                        settings.printSizes.getOrNull(index)
                                            ?.let { viewModel.updatePrintSize(it) }
                                    }
                                )

                                // Cell Photo Size
                                SettingsDropdown(
                                    label = "Cell Photo Size",
                                    selectedLabel = settings.selectedCellSize.label,
                                    options = settings.cellSizes.map { it.label },
                                    onSelected = { index ->
                                        settings.cellSizes.getOrNull(index)
                                            ?.let { viewModel.updateCellSize(it) }
                                    }
                                )

                                Divider(
                                    color = WarmGrey.copy(alpha = 0.15f),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )

                                // ── Advanced settings inline ──
                                Text(
                                    "Advanced",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = DeepNavy
                                )

                                // Color Change
                                Text(
                                    "Color Change: ${settings.colorChangePercent}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DeepNavy
                                )
                                Slider(
                                    value = settings.colorChangePercent.toFloat(),
                                    onValueChange = { viewModel.updateColorChange(it.roundToInt()) },
                                    valueRange = 0f..100f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = SlateBlue,
                                        activeTrackColor = SlateBlue
                                    )
                                )

                                // Pattern
                                val patternOptions = PatternKind.values().toList()
                                SettingsDropdown(
                                    label = "Pattern",
                                    selectedLabel = settings.pattern.name,
                                    options = patternOptions.map { it.name },
                                    onSelected = { index ->
                                        patternOptions.getOrNull(index)
                                            ?.let { viewModel.updatePattern(it) }
                                    }
                                )

                                // Use All Images
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = settings.useAllImages,
                                        onCheckedChange = { viewModel.updateUseAllImages(it) },
                                        colors = CheckboxDefaults.colors(checkedColor = SlateBlue)
                                    )
                                    Text(
                                        "Use All Images",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DeepNavy
                                    )
                                }

                                // Mirror Images
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = settings.mirrorImages,
                                        onCheckedChange = { viewModel.updateMirrorImages(it) },
                                        colors = CheckboxDefaults.colors(checkedColor = SlateBlue)
                                    )
                                    Text(
                                        "Mirror Images",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = DeepNavy
                                    )
                                }
                            }
                        }
                    }

                    // ── Fullscreen progress overlay ──
                    if (generationState is GenerationState.Loading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(160.dp)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { progress.percentComplete / 100f },
                                        modifier = Modifier.size(160.dp),
                                        color = GoldenLab,
                                        strokeWidth = 8.dp,
                                        trackColor = Color.White.copy(alpha = 0.2f)
                                    )
                                    Text(
                                        text = "${progress.percentComplete}%",
                                        style = MaterialTheme.typography.headlineLarge,
                                        color = Color.White
                                    )
                                }

                                Text(
                                    text = "Creating mosaic",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )

                                Button(
                                    onClick = { viewModel.cancelGeneration() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.2f),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(50.dp)
                                ) {
                                    Text("Cancel", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (currentStep) {
                        WizardStep.Start -> {
                            TitleBar(title = "MosaicMatrix", onBack = null, barColor = deepNavy)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LargePrimaryButton(
                                    text = "Create Mosaic",
                                    onClick = { currentStep = WizardStep.MainPhoto },
                                    color = secondaryAccent,
                                    textColor = Color.White
                                )
                            }
                        }
                        WizardStep.MainPhoto -> {
                            TitleBar(title = "Main Photo", onBack = goBack, barColor = deepNavy)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Square bounding box: always visible
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (primaryImagePath != null) {
                                        // Read image dimensions to get aspect ratio
                                        val imageAspectRatio = remember(primaryImagePath) {
                                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            BitmapFactory.decodeFile(primaryImagePath, opts)
                                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                                opts.outWidth.toFloat() / opts.outHeight.toFloat()
                                            } else {
                                                1f
                                            }
                                        }
                                        val imageModifier = if (imageAspectRatio >= 1f) {
                                            Modifier.fillMaxWidth().aspectRatio(imageAspectRatio)
                                        } else {
                                            Modifier.fillMaxHeight().aspectRatio(imageAspectRatio)
                                        }
                                        AsyncImage(
                                            model = File(primaryImagePath!!),
                                            contentDescription = "Primary image preview",
                                            modifier = imageModifier.clip(RoundedCornerShape(16.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        // Placeholder image
                                        Image(
                                            painter = painterResource(id = R.drawable.main_image),
                                            contentDescription = "Main image placeholder",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(16.dp)),
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
                                    enabled = primaryImagePath != null,
                                    color = secondaryAccent
                                )
                            }
                        }
                        WizardStep.CellPhotos -> Unit // handled above
                        WizardStep.Options -> Unit // handled above
                        WizardStep.Preview -> Unit
                    }
                }
            }
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
private fun TitleBar(title: String, onBack: (() -> Unit)?, barColor: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(48.dp))
            }
        }
    }
}

@Composable
private fun StepHeader(title: String, onBack: (() -> Unit)? = null, showBack: Boolean = true) {
    TitleBar(title = title, onBack = if (showBack) onBack else null, barColor = Color(0xFF1F2D3D))
}

@Composable
private fun LargePrimaryButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
    textColor: Color = DeepNavy
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = color,
                contentColor = textColor
            ),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(60.dp)
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LargeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false,
    color: Color = Color(0xFF5A7D9A)
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = color,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(60.dp)
        ) {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SubtleButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White,
                contentColor = DeepNavy
            ),
            border = BorderStroke(1.dp, WarmGrey),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(44.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
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
    faceOverlayBoost: Float,
    onOverlayOpacityChange: (Float) -> Unit,
    onFaceOverlayBoostChange: (Float) -> Unit,
    onClose: () -> Unit,
    onStartAgain: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Tab state: 0 = Overlay, 1 = Face Enhance, 2 = Feature Paint
    var activeTab by rememberSaveable { mutableStateOf(0) }

    // Face enhance
    var faceEnhanceEnabled by rememberSaveable { mutableStateOf(false) }
    var faceRadiusScale by rememberSaveable { mutableStateOf(1.5f) }  // 0.5 = eyes/mouth only, 3.0 = full head

    // Paint state
    var brushSize by rememberSaveable { mutableStateOf(BrushSize.Medium) }
    var brushIntensity by rememberSaveable { mutableStateOf(0.4f) }        // how much white each stroke adds
    var paintOverlayStrength by rememberSaveable { mutableStateOf(0.2f) }  // slider: how strongly the mask is rendered
    var paintHasContent by remember { mutableStateOf(false) }
    var paintMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var paintMaskUndo by remember { mutableStateOf<Bitmap?>(null) }        // one-step undo snapshot
    // Direct state for the rendered paint overlay — no produceState
    var paintOverlayImage by remember { mutableStateOf<ImageBitmap?>(null) }

    // Derived: painting is active only when on the Paint tab
    val isPainting = activeTab == 2

    // Paint mask at lower resolution for responsive brush strokes
    val paintMaskMaxSide = 1024

    val blurredOverlayPath by produceState<String?>(
        initialValue = null,
        key1 = primaryImagePath
    ) {
        value = if (primaryImagePath.isNullOrBlank()) {
            null
        } else {
            prepareBlurredOverlay(context, primaryImagePath, 0f)
        }
    }

    val mosaicFile = remember(result.temporaryFilePath) {
        result.temporaryFilePath?.let { File(it) }
    }

    val mosaicSize = remember(mosaicFile?.absolutePath) {
        getImageSize(mosaicFile?.absolutePath)
    }

    LaunchedEffect(mosaicSize) {
        val size = mosaicSize ?: return@LaunchedEffect
        val maskScale = paintMaskMaxSide.toFloat() / maxOf(size.first, size.second).toFloat()
        val mw = if (maskScale < 1f) (size.first * maskScale).toInt().coerceAtLeast(1) else size.first
        val mh = if (maskScale < 1f) (size.second * maskScale).toInt().coerceAtLeast(1) else size.second
        paintMaskBitmap = createEmptyMask(mw, mh)
        paintHasContent = false
        paintOverlayImage = null
    }

    val faceMaskBitmap by produceState<Bitmap?>(
        initialValue = null,
        keys = arrayOf(primaryImagePath, mosaicSize, faceEnhanceEnabled, faceRadiusScale)
    ) {
        val size = mosaicSize
        if (!faceEnhanceEnabled || primaryImagePath.isNullOrBlank() || size == null) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) {
                val scaled = loadScaledBitmap(primaryImagePath, 1024)
                val maskScale = 1024f / maxOf(size.first, size.second).toFloat()
                val mw = if (maskScale < 1f) (size.first * maskScale).toInt().coerceAtLeast(1) else size.first
                val mh = if (maskScale < 1f) (size.second * maskScale).toInt().coerceAtLeast(1) else size.second
                val mask = if (scaled != null) {
                    generateFaceMaskBitmap(scaled, mw, mh, faceRadiusScale)
                } else null
                scaled?.recycle()
                mask
            }
        }
    }

    val overlayBaseBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = blurredOverlayPath,
        key2 = mosaicSize
    ) {
        val size = mosaicSize
        val path = blurredOverlayPath
        if (size == null || path.isNullOrBlank()) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) {
                // Cap overlay image to 1024px long side
                val overlayMaxSide = 1024
                val scale = overlayMaxSide.toFloat() / maxOf(size.first, size.second).toFloat()
                val tw = if (scale < 1f) (size.first * scale).toInt().coerceAtLeast(1) else size.first
                val th = if (scale < 1f) (size.second * scale).toInt().coerceAtLeast(1) else size.second
                loadBitmapScaled(path, tw, th)
            }
        }
    }

    // Face-only masked overlay (controlled by face enhance slider)
    val faceMaskedOverlayBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = overlayBaseBitmap,
        key2 = faceMaskBitmap,
        key3 = faceEnhanceEnabled
    ) {
        val base = overlayBaseBitmap
        val faceMask = if (faceEnhanceEnabled) faceMaskBitmap else null
        if (base == null || faceMask == null) {
            value = null
        } else {
            val scaledMask = Bitmap.createScaledBitmap(faceMask, base.width, base.height, true)
            val masked = applyMaskToOverlay(base, scaledMask)
            if (scaledMask != faceMask) scaledMask.recycle()
            value = masked.asImageBitmap()
        }
    }

    // Rebuild the paint overlay bitmap from the mask — called directly after each paint stroke
    fun rebuildPaintOverlay() {
        val base = overlayBaseBitmap ?: return
        val mask = paintMaskBitmap ?: return
        if (!paintHasContent) {
            paintOverlayImage = null
            return
        }
        val scaledMask = Bitmap.createScaledBitmap(mask, base.width, base.height, true)
        val masked = applyMaskToOverlay(base, scaledMask)
        if (scaledMask != mask) scaledMask.recycle()
        paintOverlayImage = masked.asImageBitmap()
    }

    // Save an undo snapshot before the first stroke of each gesture
    var undoSavedForGesture by remember { mutableStateOf(false) }

    val handlePaintAt: (Float, Float) -> Unit = handlePaintAt@{ x, y ->
        if (!isPainting) return@handlePaintAt
        val size = mosaicSize
        val mask = paintMaskBitmap
        if (size == null || mask == null) return@handlePaintAt

        // Save undo snapshot once per gesture (first stroke)
        if (!undoSavedForGesture) {
            paintMaskUndo = mask.copy(mask.config ?: Bitmap.Config.ARGB_8888, true)
            undoSavedForGesture = true
        }

        // mapToImage returns coordinates in full mosaic-resolution space,
        // but the paint mask is at capped preview resolution — scale down
        val scaleX = mask.width.toFloat() / size.first.toFloat()
        val scaleY = mask.height.toFloat() / size.second.toFloat()
        val maskX = x * scaleX
        val maskY = y * scaleY

        val radius = brushRadiusFor(brushSize, mask.width, mask.height)
        paintSoftBrushOnMask(mask, maskX, maskY, radius, brushIntensity)
        paintHasContent = true
        rebuildPaintOverlay()
    }

    // Reset undo flag when gesture ends (tab switch or new gesture will reset)
    val handlePaintGestureEnd: () -> Unit = {
        undoSavedForGesture = false
    }

    // ── LAYOUT ──────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .background(Color(0xFFF9F7F2))
    ) {
        TitleBar(title = "Preview", onBack = onClose, barColor = Color(0xFF1F2D3D))

        // ── Mosaic canvas (takes remaining space between header and controls) ──
        MosaicZoomableCanvas(
            mosaicFile = mosaicFile,
            baseOverlayBitmap = overlayBaseBitmap?.asImageBitmap(),
            faceMaskedOverlayBitmap = faceMaskedOverlayBitmap,
            paintMaskedOverlayBitmap = paintOverlayImage,
            backgroundOverlayOpacity = overlayOpacity,
            faceOverlayOpacity = faceOverlayBoost,
            paintOverlayStrength = paintOverlayStrength,
            paintModeEnabled = isPainting,
            brushSize = brushSize,
            mosaicWidth = mosaicSize?.first ?: 0,
            mosaicHeight = mosaicSize?.second ?: 0,
            onPaintAt = handlePaintAt,
            onPaintGestureEnd = handlePaintGestureEnd,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )

        // ── Tab bar ──
        val tabLabels = listOf("Overlay", "Face Enhance", "Feature Paint")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabLabels.forEachIndexed { index, label ->
                val isActive = activeTab == index
                val bgColor = if (isActive) SlateBlue else WarmGrey.copy(alpha = 0.35f)
                val txtColor = if (isActive) Color.White else DeepNavy
                Button(
                    onClick = { activeTab = index },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = bgColor,
                        contentColor = txtColor
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }

        // ── Tab content ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (activeTab) {
                // ── TAB 0: Overlay ──
                0 -> {
                    PreviewSlider(
                        label = "Overlay Strength",
                        value = overlayOpacity,
                        onValueChange = onOverlayOpacityChange,
                        range = 0f..1f,
                        displayPercent = true
                    )
                }
                // ── TAB 1: Face Enhance ──
                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = faceEnhanceEnabled,
                            onCheckedChange = { faceEnhanceEnabled = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Face Detection", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (faceEnhanceEnabled) {
                        PreviewSlider(
                            label = "Face Overlay Strength",
                            value = faceOverlayBoost,
                            onValueChange = onFaceOverlayBoostChange,
                            range = 0f..1f,
                            displayPercent = true
                        )
                        val radiusLabel = when {
                            faceRadiusScale <= 0.7f -> "Eyes & Mouth"
                            faceRadiusScale <= 1.2f -> "Face Only"
                            faceRadiusScale <= 2.0f -> "Head"
                            faceRadiusScale <= 3.5f -> "Full Head"
                            else -> "Maximum"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { faceRadiusScale = (faceRadiusScale - 0.25f).coerceAtLeast(0.5f) },
                                enabled = faceRadiusScale > 0.5f,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Less")
                            }
                            Text(
                                text = radiusLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(min = 90.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = { faceRadiusScale = (faceRadiusScale + 0.25f).coerceAtMost(5f) },
                                enabled = faceRadiusScale < 5f,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("More")
                            }
                        }
                    }
                }
                // ── TAB 2: Feature Paint ──
                2 -> {
                    Text(
                        text = "Draw on the mosaic to reveal the original image. Two fingers to pan/zoom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888)
                    )
                    PreviewSlider(
                        label = "Paint Overlay Strength",
                        value = paintOverlayStrength,
                        onValueChange = { paintOverlayStrength = it.coerceIn(0f, 1f) },
                        range = 0f..1f,
                        displayPercent = true
                    )
                    PreviewSlider(
                        label = "Brush Intensity",
                        value = brushIntensity,
                        onValueChange = { brushIntensity = it.coerceIn(0.05f, 1f) },
                        range = 0.05f..1f,
                        displayPercent = true
                    )
                    Text("Brush Size", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BrushSizeButton(
                            label = "Small",
                            selected = brushSize == BrushSize.Small,
                            onClick = { brushSize = BrushSize.Small },
                            modifier = Modifier.weight(1f)
                        )
                        BrushSizeButton(
                            label = "Medium",
                            selected = brushSize == BrushSize.Medium,
                            onClick = { brushSize = BrushSize.Medium },
                            modifier = Modifier.weight(1f)
                        )
                        BrushSizeButton(
                            label = "Large",
                            selected = brushSize == BrushSize.Large,
                            onClick = { brushSize = BrushSize.Large },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val undo = paintMaskUndo
                                if (undo != null) {
                                    paintMaskBitmap = undo
                                    paintMaskUndo = null
                                    paintHasContent = true
                                    rebuildPaintOverlay()
                                }
                            },
                            enabled = paintMaskUndo != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Undo")
                        }
                        OutlinedButton(
                            onClick = {
                                val size = mosaicSize
                                if (size != null) {
                                    val maskScale = paintMaskMaxSide.toFloat() / maxOf(size.first, size.second).toFloat()
                                    val mw = if (maskScale < 1f) (size.first * maskScale).toInt().coerceAtLeast(1) else size.first
                                    val mh = if (maskScale < 1f) (size.second * maskScale).toInt().coerceAtLeast(1) else size.second
                                    paintMaskBitmap = createEmptyMask(mw, mh)
                                    paintHasContent = false
                                    paintOverlayImage = null
                                    paintMaskUndo = null
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear All")
                        }
                    }
                }
            }
        }

        // ── Save message ──
        if (saveMessage != null) {
            Text(
                text = saveMessage!!,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
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

        // ── Bottom toolbar: Save / Share / Start again ──
        Divider(color = WarmGrey.copy(alpha = 0.3f), thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SoftParchment)
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (!isSaving && mosaicFile != null) {
                        isSaving = true
                        val exportFile = createCompositeForExport(
                            context = context,
                            mosaicFile = mosaicFile,
                            baseOverlayBitmap = overlayBaseBitmap,
                            faceMaskedBitmap = faceMaskedOverlayBitmap?.asAndroidBitmap(),
                            paintMaskedBitmap = paintOverlayImage?.asAndroidBitmap(),
                            backgroundOpacity = overlayOpacity,
                            faceOpacity = faceOverlayBoost,
                            paintOpacity = paintOverlayStrength
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
                    .height(60.dp),
                enabled = mosaicFile != null && !isSaving,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = SlateBlue)
            ) {
                Text(if (isSaving) "Saving..." else "Save", style = MaterialTheme.typography.titleSmall)
            }
            Button(
                onClick = {
                    val exportFile = if (mosaicFile != null) {
                        createCompositeForExport(
                            context = context,
                            mosaicFile = mosaicFile,
                            baseOverlayBitmap = overlayBaseBitmap,
                            faceMaskedBitmap = faceMaskedOverlayBitmap?.asAndroidBitmap(),
                            paintMaskedBitmap = paintOverlayImage?.asAndroidBitmap(),
                            backgroundOpacity = overlayOpacity,
                            faceOpacity = faceOverlayBoost,
                            paintOpacity = paintOverlayStrength
                        ) ?: mosaicFile
                    } else {
                        null
                    }
                    shareMosaic(context, exportFile)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                enabled = mosaicFile != null,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = SlateBlue)
            ) {
                Text("Share", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton(
                onClick = onStartAgain,
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, WarmGrey)
            ) {
                Text("Start Again", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Displays the primary image with a white grid overlay representing the tile pattern.
 * For Square: uniform grid. For Parquet: alternating landscape (4:3) and portrait (3:4) cells.
 */
@Composable
private fun MosaicGridPreview(
    primaryImagePath: String,
    pattern: PatternKind,
    printSize: com.storrs.photomosaiccreatorandroid.ui.viewmodel.PrintSizeOption,
    cellSize: com.storrs.photomosaiccreatorandroid.ui.viewmodel.CellSizeOption,
    landscapeCount: Int,
    portraitCount: Int
) {
    // Calculate grid dimensions based on print size and cell size
    val printWidthMm = printSize.widthInches * 25.4
    val printHeightMm = printSize.heightInches * 25.4
    val cellMm = cellSize.sizeMm

    // Print aspect ratio (always portrait orientation for the mosaic output)
    val printAspect = (printWidthMm / printHeightMm).toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(printAspect)
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Main image fills the box, cropped to match print proportions
        AsyncImage(
            model = File(primaryImagePath),
            contentDescription = "Main image with grid overlay",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Grid overlay drawn on top
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val lineColor = Color.White.copy(alpha = 0.6f)
            val lineWidth = 1.5f

            when (pattern) {
                PatternKind.Square -> {
                    val cols = floor(printWidthMm / cellMm).toInt().coerceAtLeast(1)
                    val rows = floor(printHeightMm / cellMm).toInt().coerceAtLeast(1)
                    val cellW = w / cols
                    val cellH = h / rows

                    // Vertical lines
                    for (i in 1 until cols) {
                        val x = i * cellW
                        drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = lineWidth)
                    }
                    // Horizontal lines
                    for (i in 1 until rows) {
                        val y = i * cellH
                        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = lineWidth)
                    }
                }

                PatternKind.Parquet -> {
                    // Mirror the real engine: base cell is square, forced to 4:3 for parquet
                    // Landscape = 4 units wide × 3 units tall
                    // Portrait  = 3 units wide × 4 units tall

                    // Compute parquet ratio
                    val ratioL: Int
                    val ratioP: Int
                    if (landscapeCount == 0 && portraitCount == 0) {
                        ratioL = 2; ratioP = 1
                    } else if (landscapeCount >= portraitCount) {
                        ratioP = 1
                        ratioL = if (portraitCount > 0) maxOf(1, landscapeCount / portraitCount) else 2
                    } else {
                        ratioL = 1
                        ratioP = if (landscapeCount > 0) maxOf(1, portraitCount / landscapeCount) else 2
                    }

                    // Build pattern sequence: [L, L, ..., P, P, ...]
                    val sequence = mutableListOf<Boolean>() // true = landscape
                    repeat(ratioL) { sequence.add(true) }
                    repeat(ratioP) { sequence.add(false) }

                    // Use a unit grid approach matching the real engine
                    // Unit size = cellMm / 4 so landscape = 4 units, portrait = 3 units
                    val unitMm = cellMm / 4.0
                    val lWu = 4  // landscape width in units
                    val lHu = 3  // landscape height in units
                    val pWu = 3  // portrait width in units
                    val pHu = 4  // portrait height in units
                    val deltaUnits = pHu - lHu  // = 1

                    // Scale from units to pixels on screen
                    val unitPxX = (w / (printWidthMm / unitMm)).toFloat()
                    val unitPxY = (h / (printHeightMm / unitMm)).toFloat()

                    // How many unit columns/rows fit in the print area
                    val unitColumns = (printWidthMm / unitMm).toInt()
                    val unitRows = (printHeightMm / unitMm).toInt()

                    // Padding calculations (matching real engine)
                    val cycleWidthUnits = (ratioL * lWu) + (ratioP * pWu)
                    val cyclesAcross = maxOf(1, (unitColumns + cycleWidthUnits - 1) / cycleWidthUnits) + 1
                    val maxPortraitsPerRow = ratioP * cyclesAcross
                    val topPaddingUnits = deltaUnits * maxPortraitsPerRow
                    val rowCount = maxOf(1, (unitRows + topPaddingUnits + lHu - 1) / lHu)
                    val leftPaddingUnits = pWu * rowCount
                    val totalColumns = unitColumns + leftPaddingUnits + cycleWidthUnits
                    val tRows = unitRows + topPaddingUnits + pHu

                    // Occupancy grid for the preview
                    val occupied = Array(tRows) { BooleanArray(totalColumns) }

                    for (rowIdx in 0 until rowCount) {
                        if (rowIdx * lHu >= tRows) break
                        val baseYUnit = (rowIdx * lHu) - topPaddingUnits
                        val rowOffsetUnits = -rowIdx * pWu
                        var xUnit = leftPaddingUnits + rowOffsetUnits
                        var patternIndex = 0
                        var currentYUnit = baseYUnit

                        while (xUnit < totalColumns) {
                            val yUnitForOccupancy = currentYUnit + topPaddingUnits
                            if (yUnitForOccupancy < 0 || yUnitForOccupancy >= tRows || xUnit < 0) {
                                xUnit++
                                continue
                            }

                            val isLandscape = sequence[patternIndex]
                            val wUnits = if (isLandscape) lWu else pWu
                            val hUnits = if (isLandscape) lHu else pHu

                            // Check occupancy
                            var canPlace = true
                            if (xUnit + wUnits > totalColumns || yUnitForOccupancy + hUnits > tRows) {
                                canPlace = false
                            } else {
                                for (dy in 0 until hUnits) {
                                    for (dx in 0 until wUnits) {
                                        if (occupied[yUnitForOccupancy + dy][xUnit + dx]) {
                                            canPlace = false
                                            break
                                        }
                                    }
                                    if (!canPlace) break
                                }
                            }

                            if (!canPlace) {
                                xUnit++
                                continue
                            }

                            // Mark occupied
                            for (dy in 0 until hUnits) {
                                for (dx in 0 until wUnits) {
                                    occupied[yUnitForOccupancy + dy][xUnit + dx] = true
                                }
                            }

                            // Convert to screen pixel coordinates
                            val pixelX = (xUnit - leftPaddingUnits) * unitPxX
                            val pixelY = currentYUnit * unitPxY
                            val pixelW = wUnits * unitPxX
                            val pixelH = hUnits * unitPxY

                            // Only draw if at least partially visible
                            if (pixelX + pixelW > 0 && pixelY + pixelH > 0 && pixelX < w && pixelY < h) {
                                drawRect(
                                    color = lineColor,
                                    topLeft = Offset(pixelX, pixelY),
                                    size = androidx.compose.ui.geometry.Size(pixelW, pixelH),
                                    style = Stroke(width = lineWidth)
                                )
                            }

                            if (!isLandscape && deltaUnits > 0) {
                                currentYUnit += deltaUnits
                            }

                            patternIndex = (patternIndex + 1) % sequence.size
                            xUnit += wUnits
                        }
                    }
                }

                else -> {
                    // Landscape or Portrait only — simple uniform grid
                    val cols = floor(printWidthMm / cellMm).toInt().coerceAtLeast(1)
                    val rows = floor(printHeightMm / cellMm).toInt().coerceAtLeast(1)
                    val cellW = w / cols
                    val cellH = h / rows

                    for (i in 1 until cols) {
                        val x = i * cellW
                        drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = lineWidth)
                    }
                    for (i in 1 until rows) {
                        val y = i * cellH
                        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = lineWidth)
                    }
                }
            }
        }
    }
}

/** Reusable slider row for the preview tabs. */
@Composable
private fun PreviewSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    displayPercent: Boolean
) {
    val displayValue = if (displayPercent) "${(value * 100).roundToInt()}%" else "${value.roundToInt()}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: $displayValue",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(160.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        )
    }
}

@Composable
private fun BrushSizeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = colors
    ) {
        Text(label)
    }
}

private fun createCompositeForExport(
    context: android.content.Context,
    mosaicFile: File,
    baseOverlayBitmap: Bitmap?,
    faceMaskedBitmap: Bitmap?,
    paintMaskedBitmap: Bitmap?,
    backgroundOpacity: Float,
    faceOpacity: Float,
    paintOpacity: Float = 1f
): File? {
    val hasBackground = baseOverlayBitmap != null && backgroundOpacity > 0f
    val hasFace = faceMaskedBitmap != null && faceOpacity > 0f
    val hasPaint = paintMaskedBitmap != null && paintOpacity > 0f
    if (!hasBackground && !hasFace && !hasPaint) return mosaicFile

    val bgPct = (backgroundOpacity.coerceIn(0f, 1f) * 100f).roundToInt()
    val facePct = (faceOpacity.coerceIn(0f, 1f) * 100f).roundToInt()
    val paintPct = (paintOpacity.coerceIn(0f, 1f) * 100f).roundToInt()
    val paintHash = if (hasPaint) paintMaskedBitmap.hashCode() else 0
    val cacheFile = File(
        context.cacheDir,
        "mosaic_composite_${mosaicFile.nameWithoutExtension}_bg${bgPct}_f${facePct}_p${paintPct}_${paintHash}.jpg"
    )
    if (cacheFile.exists()) return cacheFile

    val mosaicBitmap = BitmapFactory.decodeFile(mosaicFile.absolutePath) ?: return null

    val output = Bitmap.createBitmap(
        mosaicBitmap.width,
        mosaicBitmap.height,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(output)
    canvas.drawBitmap(mosaicBitmap, 0f, 0f, null)

    val dst = Rect(0, 0, mosaicBitmap.width, mosaicBitmap.height)

    // Layer 1: uniform background overlay everywhere
    if (hasBackground) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (backgroundOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        canvas.drawBitmap(baseOverlayBitmap!!, null, dst, bgPaint)
    }

    // Layer 2: face-masked overlay
    if (hasFace) {
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (faceOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        canvas.drawBitmap(faceMaskedBitmap!!, null, dst, facePaint)
    }

    // Layer 3: paint-masked overlay
    if (hasPaint) {
        val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (paintOpacity.coerceIn(0f, 1f) * 255f).roundToInt()
        }
        canvas.drawBitmap(paintMaskedBitmap!!, null, dst, pPaint)
    }

    cacheFile.outputStream().use { out ->
        output.compress(Bitmap.CompressFormat.JPEG, 95, out)
    }

    mosaicBitmap.recycle()
    output.recycle()

    return cacheFile
}

@Composable
private fun MosaicZoomableCanvas(
    mosaicFile: File?,
    baseOverlayBitmap: ImageBitmap?,
    faceMaskedOverlayBitmap: ImageBitmap?,
    paintMaskedOverlayBitmap: ImageBitmap?,
    backgroundOverlayOpacity: Float,
    faceOverlayOpacity: Float,
    paintOverlayStrength: Float,
    paintModeEnabled: Boolean,
    brushSize: BrushSize,
    mosaicWidth: Int,
    mosaicHeight: Int,
    onPaintAt: (Float, Float) -> Unit,
    onPaintGestureEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Only use transformable when NOT in paint mode — it captures single-finger drags for pan
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 8f)
        offset += offsetChange
        offset = clampOffset(viewSize, mosaicWidth, mosaicHeight, scale, offset)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SoftParchment)
            .onSizeChanged {
                viewSize = it
                offset = clampOffset(viewSize, mosaicWidth, mosaicHeight, scale, offset)
            }
            .pointerInput(paintModeEnabled) {
                if (!paintModeEnabled) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    )
                }
            }
            .pointerInput(paintModeEnabled, brushSize, mosaicWidth, mosaicHeight, scale, offset, viewSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!paintModeEnabled) return@awaitEachGesture

                    val startPos = mapToImage(
                        down.position,
                        viewSize,
                        mosaicWidth,
                        mosaicHeight,
                        scale,
                        offset
                    )
                    if (startPos != null) onPaintAt(startPos.x, startPos.y)
                    // Consume to prevent transformable from picking up single-finger drag
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount > 1) {
                            // Multi-touch detected — stop painting and let transformable handle it
                            break
                        }

                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToUp()) break

                        if (change.positionChanged()) {
                            val pos = mapToImage(
                                change.position,
                                viewSize,
                                mosaicWidth,
                                mosaicHeight,
                                scale,
                                offset
                            )
                            if (pos != null) onPaintAt(pos.x, pos.y)
                            // Consume to prevent pan
                            change.consume()
                        }
                    }
                    // Gesture ended — notify so undo snapshot is ready for next gesture
                    onPaintGestureEnd()
                }
            }
            .then(
                if (!paintModeEnabled) {
                    // Normal mode: full transformable (pinch zoom + single-finger pan)
                    Modifier.transformable(state = transformableState)
                } else {
                    // Paint mode: only allow multi-touch pinch zoom via transformable
                    Modifier.transformable(state = transformableState)
                }
            )
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

            // Layer 1: uniform background overlay across the entire mosaic
            if (baseOverlayBitmap != null && backgroundOverlayOpacity > 0f) {
                Image(
                    bitmap = baseOverlayBitmap,
                    contentDescription = "Background overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alpha = backgroundOverlayOpacity
                )
            }

            // Layer 2: face-masked overlay (controlled by face enhance slider)
            if (faceMaskedOverlayBitmap != null && faceOverlayOpacity > 0f) {
                Image(
                    bitmap = faceMaskedOverlayBitmap,
                    contentDescription = "Face detail overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alpha = faceOverlayOpacity
                )
            }

            // Layer 3: paint-masked overlay (controlled by Paint Overlay Strength slider)
            if (paintMaskedOverlayBitmap != null && paintOverlayStrength > 0f) {
                Image(
                    bitmap = paintMaskedOverlayBitmap,
                    contentDescription = "Painted detail overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alpha = paintOverlayStrength
                )
            }
        }
    }
}

private fun clampOffset(
    viewSize: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
    offset: Offset
): Offset {
    if (viewSize.width == 0 || viewSize.height == 0 || imageWidth <= 0 || imageHeight <= 0) {
        return offset
    }

    // Base fit scale for ContentScale.Fit inside the viewport.
    val baseScale = min(
        viewSize.width / imageWidth.toFloat(),
        viewSize.height / imageHeight.toFloat()
    )
    val scaledWidth = imageWidth * baseScale * scale
    val scaledHeight = imageHeight * baseScale * scale

    val maxShiftX = if (scaledWidth <= viewSize.width) 0f
    else (scaledWidth - viewSize.width) / 2f
    val maxShiftY = if (scaledHeight <= viewSize.height) 0f
    else (scaledHeight - viewSize.height) / 2f

    val clampedX = offset.x.coerceIn(-maxShiftX, maxShiftX)
    val clampedY = offset.y.coerceIn(-maxShiftY, maxShiftY)
    return Offset(clampedX, clampedY)
}

private fun mapToImage(
    position: Offset,
    viewSize: IntSize,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
    offset: Offset
): Offset? {
    if (viewSize.width == 0 || viewSize.height == 0 || imageWidth <= 0 || imageHeight <= 0) return null

    // graphicsLayer scales around the center of the view, then translates.
    // Reverse that: subtract center, subtract translation, divide by scale, add center back.
    val cx = viewSize.width / 2f
    val cy = viewSize.height / 2f
    val localX = (position.x - cx - offset.x) / scale + cx
    val localY = (position.y - cy - offset.y) / scale + cy

    // Now localX/localY is in the unscaled inner Box coordinate space.
    // The image is Fit-scaled and centered within that Box.
    val baseScale = min(viewSize.width / imageWidth.toFloat(), viewSize.height / imageHeight.toFloat())
    val displayWidth = imageWidth * baseScale
    val displayHeight = imageHeight * baseScale
    val imgLeft = (viewSize.width - displayWidth) / 2f
    val imgTop = (viewSize.height - displayHeight) / 2f

    val x = (localX - imgLeft) / baseScale
    val y = (localY - imgTop) / baseScale
    if (x < 0f || y < 0f || x > imageWidth || y > imageHeight) return null
    return Offset(x, y)
}


private fun getImageSize(path: String?): Pair<Int, Int>? {
    if (path.isNullOrBlank()) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return options.outWidth to options.outHeight
}

private fun loadBitmapScaled(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(path) ?: return null
    if (bitmap.width == targetWidth && bitmap.height == targetHeight) return bitmap
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        targetWidth.coerceAtLeast(1),
        targetHeight.coerceAtLeast(1),
        true
    )
    if (scaled != bitmap) bitmap.recycle()
    return scaled
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

private fun blurBitmap(input: Bitmap, radius: Float): Bitmap {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    selectedLabel: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    highlight: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val container = if (highlight) GoldenLab else SoftParchment
    val content = DeepNavy

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = DeepNavy)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .height(56.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = container,
                    unfocusedContainerColor = container,
                    focusedTextColor = content,
                    unfocusedTextColor = content,
                    focusedBorderColor = WarmGrey,
                    unfocusedBorderColor = WarmGrey
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun formatPrintSize(option: com.storrs.photomosaiccreatorandroid.ui.viewmodel.PrintSizeOption): String {
    return "${option.label} (${option.widthInches}\" x ${option.heightInches}\")"
}


@Composable
private fun DebugReportDialog(
    reportText: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Debug Report") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(reportText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}


private fun getRealPathFromURI(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") {
        return uri.path
    }

    val displayName = queryDisplayName(context, uri) ?: "image_${System.currentTimeMillis()}.jpg"
    val cacheFile = File(context.cacheDir, displayName)

    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    } finally {
        cursor?.close()
    }
}
