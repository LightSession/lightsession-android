package com.lightsession.replay

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.lightsession.mapper.SkeletonScreenGenerator
import java.io.ByteArrayOutputStream

/**
 * Utility class for optimized screen capture and object pool management.
 *
 * This class encapsulates all the logic for:
 * - Screen capture using WindowManagerGlobal
 * - Optimized view processing
 * - Management of reusable object pools
 * - Bitmap compression and conversion
 */
internal class ScreenDrawing {

    companion object {
        private const val DIMMING_STEP = 0.2f

        object ScalePresets {
            const val THUMBNAIL = 0.1f
            const val LOW_QUALITY = 0.25f
            const val MEDIUM_QUALITY = 0.5f
            const val HIGH_QUALITY = 0.75f
            const val ORIGINAL = 1.0f
        }
    }

    // Pool of reusable objects
    private val paintPool = mutableListOf<Paint>()
    private val canvasPool = mutableListOf<Canvas>()
    private val byteArrayPool = mutableListOf<ByteArrayOutputStream>()
    private val skeletonGenerator = SkeletonScreenGenerator()

    private var globalScaleFactor = ScalePresets.MEDIUM_QUALITY

    /**
     * Data class to encapsulate WindowManager reflection results.
     *
     * @property views List of views obtained from WindowManagerGlobal
     * @property params List of layout parameters corresponding to the views
     */
    private data class WindowManagerData(
        val views: List<View>?,
        val params: List<WindowManager.LayoutParams>?
    )

    /** I KNOW I KNOW BUT IT WAS THE ONLY WAY IT WORKED
     * Gets views and their parameters from WindowManagerGlobal using reflection.
     * This function is marked as lint suppressed due to the use of private APIs.
     *
     * Uses Java reflection to access:
     * - WindowManagerGlobal.getInstance()
     * - WindowManagerGlobal.mViews field
     * - WindowManagerGlobal.mParams field
     *
     * @return WindowManagerData containing views and parameters, or null if reflection fails
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getWindowManagerViewsAndParams(): WindowManagerData? {
        return try {
            val windowManagerClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstanceMethod = windowManagerClass.getMethod("getInstance")
            val windowManagerInstance = getInstanceMethod.invoke(null)

            val mViewsField =
                windowManagerClass.getDeclaredField("mViews").apply { isAccessible = true }
            val mParamsField =
                windowManagerClass.getDeclaredField("mParams").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val views = mViewsField[windowManagerInstance] as? List<View>

            @Suppress("UNCHECKED_CAST")
            val params = mParamsField[windowManagerInstance] as? List<WindowManager.LayoutParams>

            if (views.isNullOrEmpty()) {
                Log.w("ScreenCaptureUtils", "No views found from WindowManagerGlobal.")
                return null
            }

            WindowManagerData(views, params)
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to access WindowManagerGlobal via reflection", e)
            null
        }
    }

    /**
     * Captures the current screen in an optimized way and returns it as a ByteArray.
     *
     * This method performs the following operations:
     * 1. Gets all views from WindowManagerGlobal via reflection
     * 2. Creates a scaled bitmap based on the provided scale factor
     * 3. Draws all visible views onto the bitmap canvas
     * 4. Compresses the bitmap to JPEG format with quality based on scale factor
     * 5. Returns the compressed image as a ByteArray
     *
     * @param scaleFactor Scale factor to resize the image (0.1f to 1.0f)
     * @return ByteArray of the captured image in JPEG format, or null if capture fails
     */
    fun captureCurrentScreenOptimized(scaleFactor: Float = globalScaleFactor): ByteArray? {
        var finalBitmap: Bitmap? = null
        return try {
            val windowData = getWindowManagerViewsAndParams() ?: return null
            val views = windowData.views!! // Non-null asserted because of the check in getWindowManagerViewsAndParams
            val params = windowData.params

            val displayMetrics = Resources.getSystem().displayMetrics
            val originalWidth = displayMetrics.widthPixels
            val originalHeight = displayMetrics.heightPixels
            val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
            val screenWidth = (originalWidth * effectiveScale).toInt()
            val screenHeight = (originalHeight * effectiveScale).toInt()

            Log.d(
                "ScreenCaptureUtils", "Creating bitmap: ${screenWidth}x${screenHeight}" +
                        " (${(effectiveScale * 100).toInt()}% of ${originalWidth}x${originalHeight})"
            )

            val config =
                if (effectiveScale <= 0.5f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            finalBitmap = Bitmap.createBitmap(screenWidth, screenHeight, config)
            val canvas = getCanvasFromPool()
            canvas.setBitmap(finalBitmap)

            if (effectiveScale != 1.0f) {
                canvas.scale(effectiveScale, effectiveScale)
            }

            processViewsOptimized(views, params, canvas, effectiveScale, displayMetrics)

            val compressionQuality = calculateCompressionQuality(effectiveScale)
            convertBitmapToByteArrayOptimized(finalBitmap, compressionQuality)
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to create optimized composed bitmap", e)
            null
        } finally {
            finalBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    /**
     * Captures the current screen and returns both Base64 and dimensions.
     *
     * @return Pair<String?, Pair<Int, Int>?> - (Base64 string, (width, height)) or (null, null) on error
     */
    fun captureScreenAsBase64(): Pair<String?, Pair<Int, Int>?> {
        return try {
            val byteArray = captureCurrentScreenOptimized(ScalePresets.ORIGINAL)
            byteArray?.let {
                // Decodificar para obter as dimensões
                val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                val dimensions = Pair(bitmap.width, bitmap.height)
                val base64 = Base64.encodeToString(it, Base64.NO_WRAP)
                bitmap.recycle() // Liberar memória do bitmap temporário
                Pair(base64, dimensions)
            } ?: Pair(null, null)
        } catch (e: Exception) {
            Log.e("ScreenCaptureUtils", "Failed to capture screen with dimensions", e)
            Pair(null, null)
        }
    }

    /**
     * Captures the current screen and returns it as a Bitmap (without ByteArray conversion).
     *
     * Similar to captureCurrentScreenOptimized but returns the raw Bitmap instead of
     * compressing it to a ByteArray. Useful when you need to perform additional
     * processing on the bitmap before compression.
     *
     * @param scaleFactor Scale factor to resize the image (0.1f to 1.0f)
     * @return Bitmap of the captured screen, or null if capture fails
     */
    fun getWindowManagerComposedBitmapOptimized(scaleFactor: Float = globalScaleFactor): Bitmap? {
        val finalBitmap: Bitmap?
        return try {
            val windowData = getWindowManagerViewsAndParams() ?: return null
            val views = windowData.views!!
            val params = windowData.params

            val displayMetrics = Resources.getSystem().displayMetrics
            val originalWidth = displayMetrics.widthPixels
            val originalHeight = displayMetrics.heightPixels
            val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
            val screenWidth = (originalWidth * effectiveScale).toInt()
            val screenHeight = (originalHeight * effectiveScale).toInt()

            Log.d(
                "ScreenCaptureUtils", "Creating bitmap: ${screenWidth}x${screenHeight}" +
                        " (${(effectiveScale * 100).toInt()}% of ${originalWidth}x${originalHeight})"
            )

            val config =
                if (effectiveScale <= 0.5f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            finalBitmap = Bitmap.createBitmap(screenWidth, screenHeight, config)
            val canvas = getCanvasFromPool()
            canvas.setBitmap(finalBitmap)

            if (effectiveScale != 1.0f) {
                canvas.scale(effectiveScale, effectiveScale)
            }

            processViewsOptimized(views, params, canvas, effectiveScale, displayMetrics)

            return finalBitmap
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to create optimized composed bitmap", e)
            null
        }
    }

    /**
     * Calculates the compression quality based on the scale factor.
     *
     * Uses different compression qualities to balance file size and image quality:
     * - Scale <= 0.25f: 60% quality (smaller thumbnails)
     * - Scale <= 0.5f: 75% quality (low quality images)
     * - Scale <= 0.75f: 85% quality (medium quality images)
     * - Scale > 0.75f: 90% quality (high quality images)
     *
     * @param scaleFactor The scale factor used for the bitmap
     * @return Compression quality percentage (60-90)
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
     * Processes and draws views on the canvas in an optimized way.
     *
     * This method performs several optimizations:
     * 1. Filters out invisible views (visibility != VISIBLE, alpha <= 0.1f, zero dimensions)
     * 2. Filters out very small views when using low scale factors
     * 3. Calculates proper view positions based on WindowManager.LayoutParams
     * 4. Applies dimming effect to layered views for depth perception
     * 5. Uses object pooling for Paint objects to reduce garbage collection
     *
     * @param views List of views to process and draw
     * @param params Layout parameters for positioning the views
     * @param canvas Canvas to draw the views on
     * @param scaleFactor Scale factor being used (affects minimum view size filter)
     * @param displayMetrics Display metrics for screen dimensions and density
     */
    private fun processViewsOptimized(
        views: List<View>,
        params: List<WindowManager.LayoutParams>?,
        canvas: Canvas,
        scaleFactor: Float,
        displayMetrics: android.util.DisplayMetrics
    ) {
        val visibleViews = views.filterIndexed { index, view ->
            view.visibility == View.VISIBLE &&
                    view.width > 0 && view.height > 0 &&
                    view.alpha > 0.1f
        }

        val minViewSize = if (scaleFactor <= 0.25f) 50 else 0
        val significantViews = visibleViews.filter { view ->
            view.width >= minViewSize || view.height >= minViewSize
        }

        significantViews.forEachIndexed { index, view ->
            canvas.save()

            val originalIndex = views.indexOf(view)
            val layoutParams = params?.getOrNull(originalIndex)
            val (x, y) = calculateViewPositionOriginal(view, layoutParams, displayMetrics)

            canvas.translate(x.toFloat(), y.toFloat())
            view.draw(canvas)

            if (index < significantViews.size - 1) {
                val paint = getPaintFromPool()
                paint.color = Color.BLACK
                paint.alpha =
                    (calculateDimmingAlphaOptimized(significantViews.size - 1 - index) * 255).toInt()
                canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), paint)
                returnPaintToPool(paint)
            }

            canvas.restore()
        }
    }

    /**
     * Calculates the original position of a view on the screen.
     *
     * Determines view position based on WindowManager.LayoutParams gravity and coordinates:
     * - Uses layoutParams.x and layoutParams.y as primary position
     * - Falls back to gravity-based positioning (CENTER_HORIZONTAL, RIGHT, CENTER_VERTICAL, BOTTOM)
     * - Applies bounds checking to ensure views stay within screen boundaries
     * - Handles edge cases where layout parameters are null or invalid
     *
     * @param view The view whose position needs to be calculated
     * @param layoutParams WindowManager layout parameters containing positioning info
     * @param displayMetrics Display metrics for screen dimensions
     * @return Pair of (x, y) coordinates for the view position
     */
    private fun calculateViewPositionOriginal(
        view: View,
        layoutParams: WindowManager.LayoutParams?,
        displayMetrics: android.util.DisplayMetrics
    ): Pair<Int, Int> {
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        return try {
            if (layoutParams != null) {
                val x = when {
                    layoutParams.x != 0 -> layoutParams.x
                    layoutParams.gravity and Gravity.CENTER_HORIZONTAL == Gravity.CENTER_HORIZONTAL ->
                        (screenWidth - view.width) / 2

                    layoutParams.gravity and Gravity.RIGHT == Gravity.RIGHT ->
                        screenWidth - view.width

                    else -> 0
                }

                val y = when {
                    layoutParams.y != 0 -> layoutParams.y
                    layoutParams.gravity and Gravity.CENTER_VERTICAL == Gravity.CENTER_VERTICAL ->
                        (screenHeight - view.height) / 2

                    layoutParams.gravity and Gravity.BOTTOM == Gravity.BOTTOM ->
                        screenHeight - view.height

                    else -> 0
                }

                // Validation to avoid invalid positions
                var resHeight = 0
                if (screenHeight - view.height > 0) {
                    resHeight = screenHeight - view.height
                }
                Pair(
                    x.coerceIn(0, screenWidth - view.width),
                    y.coerceIn(0, resHeight)
                )
            } else {
                Pair((screenWidth - view.width) / 2, (screenHeight - view.height) / 2)
            }
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to calculate view position", e)
            Pair(0, 0)
        }
    }

    /**
     * Generates a random screen Skeleton and returns as Byarray.
     *
     * Uses the same optimizations as screen capture:
     * - Reuse of Object Pools
     * - Bitmap configuration based on scalefactor
     * - Optimized compression
     *
     * @param scalefactor scale factor to resize skeleton (0.1f to 1.0f)
     * @param width personalized width (optional, uses screen dimensions by default)
     * @Param Height Personalized Height (optional, uses screen dimensions by default)
     * @return bytearray containing the Skeleton Screen in JPEG format, or null in case of error
     */
    fun generateRandomSkeletonScreen(
        scaleFactor: Float = globalScaleFactor,
        width: Int? = null,
        height: Int? = null
    ): ByteArray? {
        return try {
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = width ?: displayMetrics.widthPixels
            val screenHeight = height ?: displayMetrics.heightPixels

            // Synchronizes the scalefactor between the two classes
            skeletonGenerator.setGlobalScaleFactor(scaleFactor)

            val bitmap = skeletonGenerator.generateRandomSkeleton(
                screenWidth,
                screenHeight,
                scaleFactor
            )

            val compressionQuality = calculateCompressionQuality(scaleFactor)
            val result = convertBitmapToByteArrayOptimized(bitmap, compressionQuality)

            if (!bitmap.isRecycled) bitmap.recycle()

            result
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to generate skeleton screen", e)
            null
        }
    }

    /**
     * Generates a random skeleton screen and returns both Base64 and dimensions.
     *
     * @param scaleFactor scale factor to resize skeleton (0.1f to 1.0f)
     * @param width personalized width (optional, uses screen dimensions by default)
     * @param height personalized height (optional, uses screen dimensions by default)
     * @return Pair<String?, Pair<Int, Int>?> - (Base64 string, (width, height)) or (null, null) on error
     */
    fun generateRandomSkeletonScreenAsBase64(
        scaleFactor: Float = globalScaleFactor,
        width: Int? = null,
        height: Int? = null
    ): Pair<String?, Pair<Int, Int>?> {
        return try {
            val byteArray = generateRandomSkeletonScreen(ScalePresets.ORIGINAL, width, height)
            byteArray?.let {
                val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                val dimensions = Pair(bitmap.width, bitmap.height)
                val base64 = Base64.encodeToString(it, Base64.NO_WRAP)
                bitmap.recycle() // Liberar memória do bitmap temporário
                Pair(base64, dimensions)
            } ?: Pair(null, null)
        } catch (e: Exception) {
            Log.e("ScreenCaptureUtils", "Failed to generate skeleton with dimensions", e)
            Pair(null, null)
        }
    }



    /**
     * Generates a random screen screen and returns as bitmap.
     *
     * Similar to the other methods of Skeleton, but returns the Bitmap Raw
     * For additional processing before compression.
     *
     * @param scalefactor scale factor to resize skeleton (0.1f to 1.0f)
     * @param width personalized width (optional, uses screen dimensions by default)
     * @Param Height Personalized Height (optional, uses screen dimensions by default)
     * @return Bitmap of Skeleton Screen, or null in case of error
     */
    fun generateRandomSkeletonScreenBitmap(
        scaleFactor: Float = globalScaleFactor,
        width: Int? = null,
        height: Int? = null
    ): Bitmap? {
        return try {
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = width ?: displayMetrics.widthPixels
            val screenHeight = height ?: displayMetrics.heightPixels

            // Sincroniza o scaleFactor entre as duas classes
            skeletonGenerator.setGlobalScaleFactor(scaleFactor)

            skeletonGenerator.generateRandomSkeleton(screenWidth, screenHeight, scaleFactor)
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to generate skeleton screen bitmap", e)
            null
        }
    }

    /**
     * Calculates the dimming alpha value based on the number of layers below.
     *
     * Creates a layered depth effect by applying progressive dimming to views:
     * - Each layer below adds DIMMING_STEP (0.2f) alpha
     * - Maximum dimming is capped at 0.6f (60% opacity)
     * - This creates a visual depth effect where lower layers appear darker
     *
     * @param layersBelow Number of view layers below the current view
     * @return Alpha value for dimming effect (0.0f to 0.6f)
     */
    private fun calculateDimmingAlphaOptimized(layersBelow: Int): Float {
        return (layersBelow * DIMMING_STEP).coerceAtMost(0.6f)
    }

    /**
     * Converts a bitmap to ByteArray with specified quality using object pooling.
     *
     * Uses a pooled ByteArrayOutputStream to reduce memory allocations:
     * 1. Gets a ByteArrayOutputStream from the pool (or creates new if pool is empty)
     * 2. Compresses the bitmap to JPEG format with specified quality
     * 3. Converts to ByteArray
     * 4. Returns the stream to the pool for reuse
     *
     * @param bitmap The bitmap to convert
     * @param quality JPEG compression quality (0-100)
     * @return ByteArray containing the compressed JPEG data
     */
    private fun convertBitmapToByteArrayOptimized(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = getByteArrayStreamFromPool()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val result = stream.toByteArray()
        returnByteArrayStreamToPool(stream)
        return result
    }


    /**
     * Gets a Paint object from the pool or creates a new one if the pool is empty.
     *
     * Object pooling optimization to reduce garbage collection:
     * - Reuses existing Paint objects when available
     * - Resets the Paint object state before returning
     * - Creates new Paint with ANTI_ALIAS_FLAG if pool is empty
     *
     * @return A clean Paint object ready for use
     */
    private fun getPaintFromPool(): Paint {
        return if (paintPool.isNotEmpty()) {
            paintPool.removeAt(paintPool.size - 1).apply { reset() }
        } else {
            Paint(Paint.ANTI_ALIAS_FLAG)
        }
    }

    /**
     * Returns a Paint object to the pool for reuse.
     *
     * Maintains a pool of up to 5 Paint objects:
     * - Resets the Paint object state before storing
     * - Only stores if pool size is under the limit (5)
     * - Excess Paint objects are allowed to be garbage collected
     *
     * @param paint The Paint object to return to the pool
     */
    private fun returnPaintToPool(paint: Paint) {
        if (paintPool.size < 5) {
            paint.reset()
            paintPool.add(paint)
        }
    }

    /**
     * Converte coordenadas da tela original para coordenadas da screenshot escalada
     */
    fun convertOriginalToScaledCoordinates(
        originalX: Float,
        originalY: Float,
        scaleFactor: Float = globalScaleFactor
    ): Pair<Float, Float> {
        val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
        return Pair(
            originalX * effectiveScale,
            originalY * effectiveScale
        )
    }

    /**
     * Converte coordenadas da screenshot escalada para coordenadas da tela original
     */
    fun convertScaledToOriginalCoordinates(
        scaledX: Float,
        scaledY: Float,
        scaleFactor: Float = globalScaleFactor
    ): Pair<Float, Float> {
        val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
        return Pair(
            scaledX / effectiveScale,
            scaledY / effectiveScale
        )
    }

    /**
     * Obtém o scale factor atual sendo usado nas capturas
     */
    fun getCurrentScaleFactor(): Float = globalScaleFactor

    /**
     * Gets a Canvas object from the pool or creates a new one if the pool is empty.
     *
     * Canvas objects are lightweight and reused for bitmap drawing operations.
     * Note: Canvas state is managed externally (setBitmap, transformations, etc.)
     *
     * @return A Canvas object ready for bitmap assignment
     */
    private fun getCanvasFromPool(): Canvas {
        return if (canvasPool.isNotEmpty()) {
            canvasPool.removeAt(canvasPool.size - 1)
        } else {
            Canvas()
        }
    }

    /**
     * Gets a ByteArrayOutputStream from the pool or creates a new one if the pool is empty.
     *
     * ByteArrayOutputStream pooling for bitmap compression operations:
     * - Resets the stream state before returning (clears previous data)
     * - Creates new stream if pool is empty
     * - Used for JPEG compression output
     *
     * @return A clean ByteArrayOutputStream ready for use
     */
    private fun getByteArrayStreamFromPool(): ByteArrayOutputStream {
        return if (byteArrayPool.isNotEmpty()) {
            val stream = byteArrayPool.removeAt(byteArrayPool.size - 1)
            stream.reset()
            stream
        } else {
            ByteArrayOutputStream()
        }
    }

    /**
     * Returns a ByteArrayOutputStream to the pool for reuse.
     *
     * Maintains a pool of up to 3 ByteArrayOutputStream objects:
     * - Resets the stream state before storing (clears data)
     * - Only stores if pool size is under the limit (3)
     * - Excess streams are allowed to be garbage collected
     *
     * @param stream The ByteArrayOutputStream to return to the pool
     */
    private fun returnByteArrayStreamToPool(stream: ByteArrayOutputStream) {
        if (byteArrayPool.size < 3) {
            stream.reset()
            byteArrayPool.add(stream)
        }
    }

    /**
     * Clears all object pools and releases resources.
     *
     * Cleanup method that should be called when the ScreenDrawing instance
     * is no longer needed:
     * - Clears Paint object pool
     * - Clears Canvas object pool
     * - Closes and clears ByteArrayOutputStream pool
     * - Helps prevent memory leaks in long-running applications
     */
    fun clearObjectPools() {
        paintPool.clear()
        canvasPool.clear()
        byteArrayPool.forEach { it.close() }
        byteArrayPool.clear()
    }

    /**
     * Sets the global default scale factor for screen capture operations.
     *
     * This scale factor will be used as the default value when no specific
     * scale factor is provided to capture methods:
     * - Value is clamped between 0.1f and 1.0f
     * - Affects default behavior of captureCurrentScreenOptimized() and
     *   getWindowManagerComposedBitmapOptimized()
     *
     * @param scaleFactor The default scale factor (0.1f to 1.0f)
     */
    fun setGlobalScaleFactor(scaleFactor: Float) {
        globalScaleFactor = scaleFactor.coerceIn(0.1f, 1.0f)
    }

    /**
     * Gets the current global scale factor.
     *
     * @return The current global scale factor value (0.1f to 1.0f)
     */
    fun getGlobalScaleFactor(): Float = globalScaleFactor
}