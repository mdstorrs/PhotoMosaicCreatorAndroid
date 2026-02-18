package com.storrs.photomosaiccreatorandroid.services

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.storrs.photomosaiccreatorandroid.models.RgbColor
import com.storrs.photomosaiccreatorandroid.models.CellQuadrantColors
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Utilities for efficient image processing operations.
 */
object ImageProcessingUtils {

    /**
     * Resizes a bitmap using nearest neighbor filtering for speed.
     * More efficient than Bitmap.createScaledBitmap for large batches.
     */
    fun resizeBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source.copy(source.config ?: Bitmap.Config.RGB_565, false)
        }

        val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(target)
        val srcRect = Rect(0, 0, source.width, source.height)
        val dstRect = Rect(0, 0, targetWidth, targetHeight)
        canvas.drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }

    /**
     * Crops a bitmap to a centered region.
     */
    fun cropBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source.copy(source.config ?: Bitmap.Config.RGB_565, false)
        }

        val cropX = ((source.width - targetWidth) / 2).coerceAtLeast(0)
        val cropY = ((source.height - targetHeight) / 2).coerceAtLeast(0)
        val cropWidth = targetWidth.coerceAtMost(source.width - cropX)
        val cropHeight = targetHeight.coerceAtMost(source.height - cropY)

        return Bitmap.createBitmap(source, cropX, cropY, cropWidth, cropHeight)
    }

    /**
     * Prepares a primary image by resizing and cropping to target dimensions.
     * Uses efficient sampling to get close to target size before precise scaling.
     */
    fun preparePrimaryImage(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        keepAspectRatio: Boolean
    ): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) {
            return source.copy(source.config ?: Bitmap.Config.RGB_565, false)
        }

        val scaledBitmap = if (keepAspectRatio) {
            resizeBitmap(source, targetWidth, targetHeight)
        } else {
            val scale = maxOf(
                targetWidth / source.width.toFloat(),
                targetHeight / source.height.toFloat()
            )
            val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)

            val temp = resizeBitmap(source, scaledWidth, scaledHeight)
            cropBitmap(temp, targetWidth, targetHeight).apply { temp.recycle() }
        }

        return scaledBitmap
    }

    /**
     * Calculates average color using fast sampling.
     * Samples every ~50th pixel for speed on large images.
     */
    fun getAverageColorFast(bitmap: Bitmap): RgbColor {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var pixelCount = 0

        val step = maxOf(1, bitmap.width / 50)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val pixel = pixels[y * bitmap.width + x]
                rSum += (pixel shr 16) and 0xFF
                gSum += (pixel shr 8) and 0xFF
                bSum += pixel and 0xFF
                pixelCount++
            }
        }

        return if (pixelCount > 0) {
            RgbColor(
                (rSum / pixelCount).toInt(),
                (gSum / pixelCount).toInt(),
                (bSum / pixelCount).toInt()
            )
        } else {
            RgbColor.Gray
        }
    }

    /**
     * Calculates average color of a region using fast sampling.
     */
    fun getAverageColorRegionFast(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): RgbColor {
        val x1 = x.coerceAtLeast(0)
        val y1 = y.coerceAtLeast(0)
        val x2 = (x + width).coerceAtMost(bitmap.width)
        val y2 = (y + height).coerceAtMost(bitmap.height)

        val w = x2 - x1
        val h = y2 - y1
        if (w <= 0 || h <= 0) return RgbColor.Gray

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var pixelCount = 0

        val step = maxOf(1, w / 10)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var sy = y1
        while (sy < y2) {
            var sx = x1
            while (sx < x2) {
                val pixel = pixels[sy * bitmap.width + sx]
                rSum += (pixel shr 16) and 0xFF
                gSum += (pixel shr 8) and 0xFF
                bSum += pixel and 0xFF
                pixelCount++
                sx += step
            }
            sy += step
        }

        return if (pixelCount > 0) {
            RgbColor(
                (rSum / pixelCount).toInt(),
                (gSum / pixelCount).toInt(),
                (bSum / pixelCount).toInt()
            )
        } else {
            RgbColor.Gray
        }
    }

    /**
     * Calculates average color of a region with boundary clamping.
     */
    fun getAverageColorRegionClamped(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): RgbColor {
        val sampleX = x.coerceAtLeast(0)
        val sampleY = y.coerceAtLeast(0)
        val sampleWidth = (width - maxOf(0, -x)).coerceAtLeast(1)
        val sampleHeight = (height - maxOf(0, -y)).coerceAtLeast(1)

        val finalWidth = sampleWidth.coerceAtMost(bitmap.width - sampleX)
        val finalHeight = sampleHeight.coerceAtMost(bitmap.height - sampleY)

        return if (finalWidth <= 0 || finalHeight <= 0) {
            RgbColor.Gray
        } else {
            getAverageColorRegionFast(bitmap, sampleX, sampleY, finalWidth, finalHeight)
        }
    }

    /**
     * Gets the average colors of each quadrant of a bitmap.
     */
    fun getQuadrantColors(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        clamp: Boolean
    ): CellQuadrantColors {
        val halfWidth = maxOf(1, width / 2)
        val halfHeight = maxOf(1, height / 2)
        val remainingWidth = maxOf(1, width - halfWidth)
        val remainingHeight = maxOf(1, height - halfHeight)

        val getColor = if (clamp) {
            { bmp: Bitmap, xx: Int, yy: Int, w: Int, h: Int ->
                getAverageColorRegionClamped(bmp, xx, yy, w, h)
            }
        } else {
            { bmp: Bitmap, xx: Int, yy: Int, w: Int, h: Int ->
                getAverageColorRegionFast(bmp, xx, yy, w, h)
            }
        }

        val topLeft = getColor(bitmap, x, y, halfWidth, halfHeight)
        val topRight = getColor(bitmap, x + halfWidth, y, remainingWidth, halfHeight)
        val bottomLeft = getColor(bitmap, x, y + halfHeight, halfWidth, remainingHeight)
        val bottomRight = getColor(bitmap, x + halfWidth, y + halfHeight, remainingWidth, remainingHeight)

        return CellQuadrantColors(topLeft, topRight, bottomLeft, bottomRight)
    }

    /**
     * Calculates Euclidean distance between two RGB colors.
     */
    fun colorDistance(c1: RgbColor, c2: RgbColor): Double {
        val dr = c1.r - c2.r
        val dg = c1.g - c2.g
        val db = c1.b - c2.b
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    /**
     * Calculates total distance between two sets of quadrant colors.
     */
    fun quadrantDistance(source: CellQuadrantColors, target: CellQuadrantColors): Double {
        return colorDistance(source.topLeft, target.topLeft) +
                colorDistance(source.topRight, target.topRight) +
                colorDistance(source.bottomLeft, target.bottomLeft) +
                colorDistance(source.bottomRight, target.bottomRight)
    }

    /**
     * Applies color adjustment to a bitmap.
     */
    fun applyColorAdjustment(bitmap: Bitmap, targetColor: RgbColor, percentChange: Int) {
        if (percentChange <= 0) return

        val factor = percentChange / 100f
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).toInt()
            val g = ((pixel shr 8) and 0xFF).toInt()
            val b = (pixel and 0xFF).toInt()

            val newR = (r * (1 - factor) + targetColor.r * factor).toInt().coerceIn(0, 255)
            val newG = (g * (1 - factor) + targetColor.g * factor).toInt().coerceIn(0, 255)
            val newB = (b * (1 - factor) + targetColor.b * factor).toInt().coerceIn(0, 255)

            pixels[i] = 0xFF000000.toInt() or (newR shl 16) or (newG shl 8) or newB
        }

        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /**
     * Applies Gaussian blur to a bitmap. Downsamples for performance if needed.
     */
    fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) {
            return source.copy(source.config ?: Bitmap.Config.RGB_565, true)
        }

        val maxBlurDimension = 1024
        val maxSourceDimension = maxOf(source.width, source.height)

        val workingBitmap = if (maxSourceDimension > maxBlurDimension) {
            val scale = maxBlurDimension / maxSourceDimension.toFloat()
            val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
            resizeBitmap(source, targetWidth, targetHeight)
        } else {
            source.copy(source.config ?: Bitmap.Config.RGB_565, true)
        }

        // Simple box blur implementation
        return applyBoxBlur(workingBitmap, radius)
    }

    private fun applyBoxBlur(source: Bitmap, radius: Int): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.RGB_565)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

        val blurred = IntArray(pixels.size)
        val boxSize = 2 * radius + 1
        val boxSizeSquared = boxSize * boxSize

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val ny = (y + dy).coerceIn(0, source.height - 1)
                        val nx = (x + dx).coerceIn(0, source.width - 1)
                        val pixel = pixels[ny * source.width + nx]
                        rSum += (pixel shr 16) and 0xFF
                        gSum += (pixel shr 8) and 0xFF
                        bSum += pixel and 0xFF
                    }
                }

                blurred[y * source.width + x] = 0xFF000000.toInt() or
                        ((rSum / boxSizeSquared) shl 16) or
                        ((gSum / boxSizeSquared) shl 8) or
                        (bSum / boxSizeSquared)
            }
        }

        output.setPixels(blurred, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    /**
     * Composites a cell image onto the mosaic at the specified location.
     */
    fun drawBitmapOnCanvas(canvas: Canvas, cell: Bitmap, x: Int, y: Int) {
        val rect = Rect(x, y, x + cell.width, y + cell.height)
        canvas.drawBitmap(cell, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /**
     * OPTIMIZATION: Calculates average color of a region using a pre-loaded pixel array.
     * This avoids the expensive bitmap.getPixels() call that was being done repeatedly.
     * Use this when analyzing multiple regions of the same bitmap.
     */
    fun getAverageColorRegionFastFromPixels(
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): RgbColor {
        val x1 = x.coerceAtLeast(0)
        val y1 = y.coerceAtLeast(0)
        val x2 = (x + width).coerceAtMost(bitmapWidth)
        val y2 = (y + height).coerceAtMost(bitmapHeight)

        val w = x2 - x1
        val h = y2 - y1
        if (w <= 0 || h <= 0) return RgbColor.Gray

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var pixelCount = 0

        val step = maxOf(1, w / 10)

        var sy = y1
        while (sy < y2) {
            var sx = x1
            while (sx < x2) {
                val pixel = pixels[sy * bitmapWidth + sx]
                rSum += (pixel shr 16) and 0xFF
                gSum += (pixel shr 8) and 0xFF
                bSum += pixel and 0xFF
                pixelCount++
                sx += step
            }
            sy += step
        }

        return if (pixelCount > 0) {
            RgbColor(
                (rSum / pixelCount).toInt(),
                (gSum / pixelCount).toInt(),
                (bSum / pixelCount).toInt()
            )
        } else {
            RgbColor.Gray
        }
    }

    /**
     * OPTIMIZATION: Gets the average colors of each quadrant using a pre-loaded pixel array.
     * This avoids repeated bitmap.getPixels() calls when analyzing multiple grid cells.
     */
    fun getQuadrantColorsFromPixels(
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        clamp: Boolean
    ): CellQuadrantColors {
        val halfWidth = maxOf(1, width / 2)
        val halfHeight = maxOf(1, height / 2)
        val remainingWidth = maxOf(1, width - halfWidth)
        val remainingHeight = maxOf(1, height - halfHeight)

        val getColor = if (clamp) {
            { xx: Int, yy: Int, w: Int, h: Int ->
                getAverageColorRegionClampedFromPixels(pixels, bitmapWidth, bitmapHeight, xx, yy, w, h)
            }
        } else {
            { xx: Int, yy: Int, w: Int, h: Int ->
                getAverageColorRegionFastFromPixels(pixels, bitmapWidth, bitmapHeight, xx, yy, w, h)
            }
        }

        val topLeft = getColor(x, y, halfWidth, halfHeight)
        val topRight = getColor(x + halfWidth, y, remainingWidth, halfHeight)
        val bottomLeft = getColor(x, y + halfHeight, halfWidth, remainingHeight)
        val bottomRight = getColor(x + halfWidth, y + halfHeight, remainingWidth, remainingHeight)

        return CellQuadrantColors(topLeft, topRight, bottomLeft, bottomRight)
    }

    /**
     * OPTIMIZATION: Calculates average color with boundary clamping using a pre-loaded pixel array.
     */
    private fun getAverageColorRegionClampedFromPixels(
        pixels: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): RgbColor {
        val x1 = x.coerceAtLeast(0)
        val y1 = y.coerceAtLeast(0)
        val x2 = (x + width).coerceAtMost(bitmapWidth)
        val y2 = (y + height).coerceAtMost(bitmapHeight)

        val w = x2 - x1
        val h = y2 - y1
        if (w <= 0 || h <= 0) return RgbColor.Gray

        val sampleX = x1 + (w / 4).coerceAtLeast(1)
        val sampleY = y1 + (h / 4).coerceAtLeast(1)
        val sampleWidth = (w / 2).coerceAtLeast(1)
        val sampleHeight = (h / 2).coerceAtLeast(1)

        return getAverageColorRegionFastFromPixels(
            pixels, bitmapWidth, bitmapHeight,
            sampleX, sampleY, sampleWidth, sampleHeight
        )
    }
}







