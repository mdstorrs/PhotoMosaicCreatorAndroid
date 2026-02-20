package com.storrs.photomosaiccreatorandroid.preview

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class BrushSize {
    Small,
    Medium,
    Large
}

fun brushRadiusFor(size: BrushSize, imageWidth: Int, imageHeight: Int): Float {
    val minSide = min(imageWidth, imageHeight).coerceAtLeast(1)
    return when (size) {
        BrushSize.Small -> minSide / 40f
        BrushSize.Medium -> minSide / 20f
        BrushSize.Large -> minSide / 10f
    }
}

/**
 * Paint a soft radial-gradient circle onto the mask bitmap.
 * The brush accumulates — painting over the same area builds up intensity
 * towards white (255). Mask values clamp at 255 (=1.0).
 *
 * @param brushIntensity 0..1 — how much white a single stroke adds at center
 */
fun paintSoftBrushOnMask(
    mask: Bitmap,
    cx: Float,
    cy: Float,
    radius: Float,
    brushIntensity: Float
) {
    if (!mask.isMutable || radius <= 0f) return
    val alpha = (brushIntensity.coerceIn(0f, 1f) * 255f).roundToInt()
    val gradient = RadialGradient(
        cx, cy, radius.coerceAtLeast(1f),
        intArrayOf(
            Color.argb(alpha, 255, 255, 255),       // center: full brush intensity
            Color.argb((alpha * 0.5f).toInt(), 255, 255, 255), // mid: half
            Color.argb(0, 255, 255, 255)             // edge: transparent
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = gradient
        blendMode = BlendMode.PLUS   // accumulates — never exceeds 255
    }
    val canvas = Canvas(mask)
    canvas.drawCircle(cx, cy, radius.coerceAtLeast(1f), paint)
}

fun createEmptyMask(width: Int, height: Int): Bitmap {
    val safeW = width.coerceAtLeast(1)
    val safeH = height.coerceAtLeast(1)
    return Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
}

fun createSolidMask(width: Int, height: Int, alpha: Int): Bitmap {
    val mask = createEmptyMask(width, height)
    val canvas = Canvas(mask)
    canvas.drawColor(Color.argb(alpha.coerceIn(0, 255), 255, 255, 255))
    return mask
}

fun combineMasks(
    faceMask: Bitmap?,
    paintMask: Bitmap?,
    width: Int,
    height: Int
): Bitmap {
    val mask = createEmptyMask(width, height)
    val canvas = Canvas(mask)
    if (faceMask != null) {
        canvas.drawBitmap(faceMask, 0f, 0f, null)
    }
    if (paintMask != null) {
        val paint = Paint().apply {
            blendMode = BlendMode.PLUS
        }
        canvas.drawBitmap(paintMask, 0f, 0f, paint)
    }
    return mask
}

fun applyMaskToOverlay(overlay: Bitmap, mask: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.drawBitmap(overlay, 0f, 0f, null)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = BlendMode.DST_IN
    }
    canvas.drawBitmap(mask, 0f, 0f, paint)
    return output
}

suspend fun generateFaceMaskBitmap(
    sourceBitmap: Bitmap,
    maskWidth: Int,
    maskHeight: Int,
    faceRadiusScale: Float = 1.5f
): Bitmap? {
    if (maskWidth <= 0 || maskHeight <= 0) return null

    val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .build()

    val detector = FaceDetection.getClient(detectorOptions)
    val input = InputImage.fromBitmap(sourceBitmap, 0)
    val faces = detectFaces(detector, input)
    if (faces.isEmpty()) return null

    val mask = createEmptyMask(maskWidth, maskHeight)
    val canvas = Canvas(mask)

    val scaleX = maskWidth.toFloat() / sourceBitmap.width.toFloat()
    val scaleY = maskHeight.toFloat() / sourceBitmap.height.toFloat()

    val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)
    }

    for (face in faces) {
        drawFaceGradientMask(canvas, face, scaleX, scaleY, facePaint, faceRadiusScale)
        drawLandmarkBoosts(canvas, face, scaleX, scaleY, faceRadiusScale)
    }

    detector.close()
    return mask
}

private suspend fun detectFaces(
    detector: com.google.mlkit.vision.face.FaceDetector,
    input: InputImage
): List<Face> = suspendCancellableCoroutine { cont ->
    detector.process(input)
        .addOnSuccessListener { faces ->
            if (cont.isActive) cont.resume(faces)
        }
        .addOnFailureListener {
            if (cont.isActive) cont.resume(emptyList())
        }
}

private fun drawFaceGradientMask(
    canvas: Canvas,
    face: Face,
    scaleX: Float,
    scaleY: Float,
    paint: Paint,
    faceRadiusScale: Float
) {
    val bounds = face.boundingBox
    val cx = bounds.exactCenterX() * scaleX
    val cy = bounds.exactCenterY() * scaleY
    val w = bounds.width() * scaleX
    val h = bounds.height() * scaleY

    // faceRadiusScale controls how far beyond the face bounding box the mask extends
    // 0.5 = tight around eyes/mouth, 1.5 = standard, 3.0 = covers full head + hair
    val radius = max(w, h) / 2f * faceRadiusScale
    // Multi-stop gradient: solid center, then gradual fade for softer edges
    val gradient = RadialGradient(
        0f,
        0f,
        radius,
        intArrayOf(
            Color.argb(255, 255, 255, 255),  // center: full
            Color.argb(255, 255, 255, 255),  // hold solid to 40%
            Color.argb(180, 255, 255, 255),  // start fade
            Color.argb(80, 255, 255, 255),   // mid fade
            Color.argb(0, 255, 255, 255)     // edge: transparent
        ),
        floatArrayOf(0f, 0.4f, 0.65f, 0.85f, 1f),
        Shader.TileMode.CLAMP
    )
    paint.shader = gradient

    canvas.save()
    canvas.translate(cx, cy)
    val scale = if (h == 0f) 1f else w / h
    canvas.scale(scale, 1f)
    val rect = RectF(-radius, -radius, radius, radius)
    canvas.drawOval(rect, paint)
    canvas.restore()

    paint.shader = null
}

private fun drawLandmarkBoosts(canvas: Canvas, face: Face, scaleX: Float, scaleY: Float, faceRadiusScale: Float) {
    val faceSize = max(face.boundingBox.width(), face.boundingBox.height()).toFloat()
    // Scale landmark boost radii proportionally with face radius
    val landmarkScale = (faceRadiusScale / 1.5f).coerceIn(0.5f, 2f)
    val eyeRadius = faceSize * 0.18f * scaleX * landmarkScale
    val mouthRadius = faceSize * 0.20f * scaleX * landmarkScale

    val boostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        blendMode = BlendMode.PLUS
    }

    val landmarks = listOf(
        face.getLandmark(FaceLandmark.LEFT_EYE)?.position to eyeRadius,
        face.getLandmark(FaceLandmark.RIGHT_EYE)?.position to eyeRadius,
        face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position to mouthRadius
    )

    for ((point, radius) in landmarks) {
        if (point != null) {
            val cx = point.x * scaleX
            val cy = point.y * scaleY
            val gradient = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(200, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            boostPaint.shader = gradient
            canvas.drawCircle(cx, cy, radius, boostPaint)
        }
    }
    boostPaint.shader = null
}
