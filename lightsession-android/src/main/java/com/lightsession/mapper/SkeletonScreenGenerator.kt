package com.lightsession.mapper

import com.lightsession.ScreenGeometry
import android.content.res.Resources
import android.graphics.*
import android.util.Base64
import android.util.Log
import com.lightsession.replay.ScreenDrawing
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class SkeletonScreenGenerator {

    companion object {
        // Skeleton colors
        private const val BACKGROUND_COLOR = 0xFFF5F5F5.toInt() // Very light gray
        private const val SKELETON_COLOR = 0xFFE0E0E0.toInt() // Medium gray
        private const val SHIMMER_COLOR = 0xFFF0F0F0.toInt() // Light gray (for optional shimmer effect)

        // Settings
        private const val MIN_ELEMENT_WIDTH = 0.3f // 30% of screen width
        private const val MAX_ELEMENT_WIDTH = 0.9f // 90% of screen width
        private const val MIN_ELEMENT_HEIGHT = 40 // pixels
        private const val MAX_ELEMENT_HEIGHT = 200 // pixels
        private const val PADDING = 16 // pixels
        private const val ELEMENT_SPACING = 12 // pixels
        private const val CORNER_RADIUS = 8f // pixels
    }

    private var globalScaleFactor = ScreenDrawing.Companion.ScalePresets.MEDIUM_QUALITY

    /**
     * Generates a random screen screen
     * @param width width of the screen (will be climbing by the scalefactor)
     * @param height height of the screen (will be scaled by the scalefactor)
     * @param scalefactor scale factor for optimization
     * @return bitmap with Skeleton Screen
     */
    fun generateRandomSkeleton(
        width: Int = ScreenGeometry.width,
        height: Int = ScreenGeometry.height,
        scaleFactor: Float = globalScaleFactor
    ): Bitmap {
        val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
        val scaledWidth = (width * effectiveScale).toInt()
        val scaledHeight = (height * effectiveScale).toInt()

        // Use RGB_565 for smaller scales to save memory
        val config = if (effectiveScale <= 0.5f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

        val bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, config)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = effectiveScale > 0.5f // Anti-alias only for higher qualities
        }

        // Fill the background
        canvas.drawColor(BACKGROUND_COLOR)

        // Adjust dimensions based on scale
        val scaledPadding = (PADDING * effectiveScale).toInt()
        val scaledSpacing = (ELEMENT_SPACING * effectiveScale).toInt()
        val scaledCornerRadius = CORNER_RADIUS * effectiveScale

        // Generate random elements
        var currentY = scaledPadding

        while (currentY < scaledHeight - scaledPadding) {
            // Decide the element type randomly
            when (Random.nextInt(0, 4)) {
                0 -> {
                    // Large Header (title)
                    currentY = drawTitleSkeleton(canvas, paint, scaledWidth, currentY, effectiveScale, scaledCornerRadius)
                }
                1 -> {
                    // Card with image and text
                    currentY = drawCardSkeleton(canvas, paint, scaledWidth, currentY, effectiveScale, scaledCornerRadius)
                }
                2 -> {
                    // List of items
                    currentY = drawListItemsSkeleton(canvas, paint, scaledWidth, currentY, effectiveScale, scaledCornerRadius)
                }
                3 -> {
                    // Text paragraph
                    currentY = drawParagraphSkeleton(canvas, paint, scaledWidth, currentY, effectiveScale, scaledCornerRadius)
                }
            }

            currentY += scaledSpacing * 2
        }

        return bitmap
    }

    /**
     * Generates a skeleton screen and returns it as a compressed ByteArray.
     */
    private fun generateRandomSkeletonAsByteArray(
        width: Int = ScreenGeometry.width,
        height: Int = ScreenGeometry.height,
        scaleFactor: Float = globalScaleFactor
    ): ByteArray? {
        return try {
            val bitmap = generateRandomSkeleton(width, height, scaleFactor)
            val quality = calculateCompressionQuality(scaleFactor)

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val result = stream.toByteArray()

            // Clean up resources
            bitmap.recycle()
            stream.close()

            result
        } catch (e: Exception) {
            Log.e("SkeletonScreenGenerator", "Failed to generate skeleton as ByteArray", e)
            null
        }
    }

    /**
     * Generates a skeleton screen and returns it as a Base64 string.
     */
    fun generateRandomSkeletonAsBase64(
        width: Int = ScreenGeometry.width,
        height: Int = ScreenGeometry.height,
        scaleFactor: Float = globalScaleFactor
    ): String? {
        val byteArray = generateRandomSkeletonAsByteArray(width, height, scaleFactor)
        return byteArray?.let {
            try {
                Base64.encodeToString(it, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e("SkeletonScreenGenerator", "Failed to encode skeleton to Base64", e)
                null
            }
        }
    }

    /**
     * Draws a title skeleton.
     */
    private fun drawTitleSkeleton(
        canvas: Canvas,
        paint: Paint,
        screenWidth: Int,
        startY: Int,
        scaleFactor: Float,
        cornerRadius: Float
    ): Int {
        paint.color = SKELETON_COLOR

        val scaledPadding = (PADDING * scaleFactor).toInt()
        val scaledSpacing = (ELEMENT_SPACING * scaleFactor).toInt()

        val titleWidth = screenWidth * Random.nextFloat(0.4f, 0.7f)
        val titleHeight = (Random.nextInt(30, 50) * scaleFactor).toInt()

        val rect = RectF(
            scaledPadding.toFloat(),
            startY.toFloat(),
            scaledPadding + titleWidth,
            startY + titleHeight.toFloat()
        )

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        return startY + titleHeight + scaledSpacing
    }

    /**
     * Draws a card skeleton.
     */
    private fun drawCardSkeleton(
        canvas: Canvas,
        paint: Paint,
        screenWidth: Int,
        startY: Int,
        scaleFactor: Float,
        cornerRadius: Float
    ): Int {
        paint.color = SKELETON_COLOR

        val scaledPadding = (PADDING * scaleFactor).toInt()
        val scaledSpacing = (ELEMENT_SPACING * scaleFactor).toInt()
        val currentY = startY

        // Square or rectangular image
        val imageSize = (Random.nextInt(80, 150) * scaleFactor).toInt()
        val imageRect = RectF(
            scaledPadding.toFloat(),
            currentY.toFloat(),
            scaledPadding + imageSize.toFloat(),
            currentY + imageSize.toFloat()
        )
        canvas.drawRoundRect(imageRect, cornerRadius, cornerRadius, paint)

        // Texts next to the image
        val textStartX = scaledPadding + imageSize + scaledSpacing
        val textMaxWidth = screenWidth - textStartX - scaledPadding

        // Card title
        val titleWidth = textMaxWidth * Random.nextFloat(0.6f, 0.9f)
        val titleRect = RectF(
            textStartX.toFloat(),
            currentY + 10f * scaleFactor,
            textStartX + titleWidth,
            currentY + 30f * scaleFactor
        )
        canvas.drawRoundRect(titleRect, cornerRadius, cornerRadius, paint)

        // Subtitle
        val subtitleWidth = textMaxWidth * Random.nextFloat(0.4f, 0.7f)
        val subtitleRect = RectF(
            textStartX.toFloat(),
            currentY + 40f * scaleFactor,
            textStartX + subtitleWidth,
            currentY + 55f * scaleFactor
        )
        canvas.drawRoundRect(subtitleRect, cornerRadius, cornerRadius, paint)

        return startY + imageSize + scaledSpacing
    }

    /**
     * Draws a list skeleton.
     */
    private fun drawListItemsSkeleton(
        canvas: Canvas,
        paint: Paint,
        screenWidth: Int,
        startY: Int,
        scaleFactor: Float,
        cornerRadius: Float
    ): Int {
        paint.color = SKELETON_COLOR

        val scaledPadding = (PADDING * scaleFactor).toInt()
        val scaledSpacing = (ELEMENT_SPACING * scaleFactor).toInt()
        var currentY = startY
        val itemCount = Random.nextInt(2, 5)

        repeat(itemCount) {
            // Circle (avatar)
            val circleRadius = 20f * scaleFactor
            canvas.drawCircle(
                scaledPadding + circleRadius,
                currentY + circleRadius,
                circleRadius,
                paint
            )

            // Text line
            val textStartX = scaledPadding + (circleRadius * 2).toInt() + scaledSpacing
            val textWidth = (screenWidth - textStartX - scaledPadding) * Random.nextFloat(0.5f, 0.9f)

            val textRect = RectF(
                textStartX.toFloat(),
                currentY + 10f * scaleFactor,
                textStartX + textWidth,
                currentY + 30f * scaleFactor
            )
            canvas.drawRoundRect(textRect, cornerRadius, cornerRadius, paint)

            currentY += (circleRadius * 2 + scaledSpacing).toInt()
        }

        return currentY
    }

    /**
     * Draws a paragraph skeleton.
     */
    private fun drawParagraphSkeleton(
        canvas: Canvas,
        paint: Paint,
        screenWidth: Int,
        startY: Int,
        scaleFactor: Float,
        cornerRadius: Float
    ): Int {
        paint.color = SKELETON_COLOR

        val scaledPadding = (PADDING * scaleFactor).toInt()
        var currentY = startY
        val lineCount = Random.nextInt(2, 4)
        val lineHeight = (20 * scaleFactor).toInt()
        val lineSpacing = (8 * scaleFactor).toInt()

        repeat(lineCount) { index ->
            // The last line is usually shorter
            val widthMultiplier = if (index == lineCount - 1) {
                Random.nextFloat(0.3f, 0.6f)
            } else {
                Random.nextFloat(0.7f, 0.95f)
            }

            val lineWidth = (screenWidth - scaledPadding * 2) * widthMultiplier

            val rect = RectF(
                scaledPadding.toFloat(),
                currentY.toFloat(),
                scaledPadding + lineWidth,
                currentY + lineHeight.toFloat()
            )

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            currentY += lineHeight + lineSpacing
        }

        return currentY
    }

    /**
     * Calculates the compression quality based on the scale factor.
     */
    private fun calculateCompressionQuality(scaleFactor: Float): Int {
        return when {
            scaleFactor <= 0.25f -> 60
            scaleFactor <= 0.5f -> 75
            scaleFactor <= 0.75f -> 85
            else -> 90
        }
    }

    /**
     * Sets the default global scale factor.
     */
    fun setGlobalScaleFactor(scaleFactor: Float) {
        globalScaleFactor = scaleFactor.coerceIn(0.1f, 1.0f)
    }

    /**
     * Gets the current global scale factor.
     */
    fun getGlobalScaleFactor(): Float = globalScaleFactor

    /**
     * Extension function for Random.nextFloat with a range.
     */
    private fun Random.nextFloat(from: Float, until: Float): Float {
        return from + nextFloat() * (until - from)
    }
}