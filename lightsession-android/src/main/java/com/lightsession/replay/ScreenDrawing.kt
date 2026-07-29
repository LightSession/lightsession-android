package com.lightsession.replay

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import kotlin.coroutines.resume
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import com.lightsession.mapper.SkeletonScreenGenerator
import java.io.ByteArrayOutputStream
import com.lightsession.Masking
import com.lightsession.MaskScanner

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

        /** Draw and encode overlap by one frame; two buffers covers it. */
        private const val MAX_POOLED_BITMAPS = 2

        object ScalePresets {
            const val THUMBNAIL = 0.1f
            const val LOW_QUALITY = 0.25f
            const val MEDIUM_QUALITY = 0.5f
            const val HIGH_QUALITY = 0.75f
            const val ORIGINAL = 1.0f
        }
    }

    // Pool of reusable objects.
    //
    // The bitmap is the expensive one and it was the one NOT pooled: a
    // 540x1168 RGB_565 buffer is 1.2 MB, allocated and recycled on every
    // capture, three times a second. Paint and Canvas — which were pooled — cost
    // almost nothing. Pooling the bitmap is also what lets the draw happen on
    // the main thread while the JPEG encode happens somewhere else: the encoder
    // hands the buffer back when it is done with it.
    /**
     * The traversal that finds what to cover.
     *
     * Purpose-built rather than reused from the wireframe generator: that one walks the
     * whole composition group tree, which measured 6-27 ms per frame on a real Compose
     * screen. See [MaskScanner].
     */
    private val masker = MaskScanner()


    private val paintPool = mutableListOf<Paint>()
    private val canvasPool = mutableListOf<Canvas>()
    private val byteArrayPool = mutableListOf<ByteArrayOutputStream>()
    private val bitmapPool = java.util.concurrent.ConcurrentLinkedQueue<Bitmap>()
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
    /**
     * Draws the current screen into a bitmap. **Main thread only.**
     *
     * `view.draw(canvas)` reads live View state, so it cannot move off the UI
     * thread — that constraint is real and is why this half stays here. The
     * JPEG encode is a different matter: it is pure CPU over a finished buffer,
     * and it used to run here too. At three captures a second that was roughly
     * 8-20ms of avoidable UI time per capture. [encodeToJpeg] does it elsewhere.
     *
     * The returned bitmap belongs to the pool. Pass it to [encodeToJpeg], or to
     * [recycleBitmap] if you decide not to encode it, otherwise the pool starves
     * and every capture allocates again.
     */
    /**
     * Captures the screen, choosing whichever method this app's content allows.
     *
     * Asynchronous because one of the two methods is. `PixelCopy` reads the window's
     * rendered surface, which the compositor hands over on its own schedule, and there
     * is no synchronous form of it — blocking the main thread on its callback would
     * trade dropped frames for an ANR.
     *
     * @param onResult always called, on the main thread, with null when nothing could
     *   be captured.
     */
    fun captureToBitmapAsync(
        scaleFactor: Float = globalScaleFactor,
        /**
         * The window everything else is stacked on, or null to use the foreground Activity
         * the screen mapper is tracking. See [surfaceLayers] for why it cannot be derived.
         */
        baseWindow: android.view.Window? = null,
        onResult: (Bitmap?) -> Unit,
    ) {
        if (!surfaceCaptureRequired) {
            val drawn = captureToBitmap(scaleFactor)
            if (drawn != null || !surfaceCaptureRequired) {
                // Either it worked, or it failed for a reason PixelCopy would not fix.
                onResult(drawn)
                return
            }
            // The draw set the flag: this app renders something a software canvas
            // cannot take. Fall through and never try the draw again.
        }
        captureViaSurface(
            scaleFactor,
            baseWindow ?: com.lightsession.mapper.ScreenMapperIntegration.getInstance()
                .currentActivity()?.window,
            onResult,
        )
    }

    /**
     * Whether the software draw has proven impossible for this app's content.
     *
     * Latched rather than retried per frame. The cause is a `Bitmap.Config.HARDWARE`
     * somewhere in the hierarchy — what Coil and Glide decode into by default on API 26
     * and above — and a screen that has one will have it again. Retrying would throw,
     * log a stack trace and drop a frame, three times a second.
     */
    @Volatile
    private var surfaceCaptureRequired = false

    /** One window to copy, and where on the screen its pixels belong. */
    private class SurfaceLayer(val window: android.view.Window, val bounds: Rect)

    /**
     * Every window that can be copied, furthest back first.
     *
     * `PixelCopy` reads one window's surface, and a Compose `Dialog` or `ModalBottomSheet`
     * is a window of its own — which is why the copy used to come back as the screen
     * *behind* the dialog. Worse, silently: masking walks all the windows, so the dialog's
     * text was covered on an image that did not contain the dialog, and the result looked
     * like a correctly masked screen rather than a missing one.
     *
     * `WindowManagerGlobal.mViews` is in the order windows were added, which is their
     * z-order, so drawing them in sequence stacks them the way the compositor did.
     *
     * A window is reached through `DialogWindowProvider`, a public Compose interface
     * implemented by `DialogLayout`, `ModalBottomSheetDialogLayout` and the modal rail's
     * layout. It is on a *child* of the dialog's decor view, hence the search.
     *
     * A `Popup` — a dropdown, a tooltip — has no `Window` at all: `PopupLayout` is added to
     * the WindowManager directly. Those cannot be copied and are left out, which is the same
     * answer as before for them.
     */
    private fun surfaceLayers(baseWindow: android.view.Window?): List<SurfaceLayer> {
        val layers = mutableListOf<SurfaceLayer>()
        val metrics = Resources.getSystem().displayMetrics

        if (baseWindow != null) {
            layers.add(
                SurfaceLayer(baseWindow, Rect(0, 0, metrics.widthPixels, metrics.heightPixels)),
            )
        }

        for (root in getWindowManagerViewsAndParams()?.views.orEmpty()) {
            if (root.visibility != View.VISIBLE || root.width <= 0 || root.height <= 0) continue
            if (root === baseWindow?.decorView) continue
            val window = findDialogWindow(root) ?: continue
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            layers.add(
                SurfaceLayer(
                    window,
                    Rect(
                        location[0],
                        location[1],
                        location[0] + root.width,
                        location[1] + root.height,
                    ),
                ),
            )
        }
        return layers
    }

    private fun findDialogWindow(view: View): android.view.Window? {
        if (view is androidx.compose.ui.window.DialogWindowProvider) return view.window
        if (view !is android.view.ViewGroup) return null
        for (index in 0 until view.childCount) {
            findDialogWindow(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    /**
     * Copies the rendered surfaces of every window and stacks them.
     *
     * The fallback to the software draw, because it is more expensive — but it works, since
     * it takes the pixels the compositor already produced and so neither knows nor cares
     * that some of them came from a hardware bitmap.
     *
     * `PixelCopy` scales into the destination, so the frame is copied straight into a
     * pooled buffer at capture size rather than at full resolution and shrunk. At
     * CaptureQuality.LOW that is the difference between 0.6 MB and 10 MB per frame.
     */
    private fun captureViaSurface(
        scaleFactor: Float,
        baseWindow: android.view.Window?,
        onResult: (Bitmap?) -> Unit,
    ) {
        val layers = surfaceLayers(baseWindow)
        if (layers.isEmpty()) {
            Log.w("ScreenCaptureUtils", "no foreground window to copy from")
            onResult(null)
            return
        }

        val metrics = Resources.getSystem().displayMetrics
        val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
        val width = (metrics.widthPixels * effectiveScale).toInt()
        val height = (metrics.heightPixels * effectiveScale).toInt()
        if (width <= 0 || height <= 0) {
            onResult(null)
            return
        }

        // ARGB_8888 regardless of scale. PixelCopy rejects destinations it cannot write,
        // and the software path's RGB_565-below-half-scale optimisation is one of them.
        val target = obtainBitmap(width, height, Bitmap.Config.ARGB_8888)

        copyLayers(layers, 0, target, effectiveScale) { copied ->
            if (!copied) {
                recycleBitmap(target)
                mainHandler.post { onResult(null) }
                return@copyLayers
            }
            // Masks are drawn after the copies rather than into them, because a copy is the
            // screen exactly as rendered — unmasked. Same canvas transform as the software
            // path, so the rectangles stay in screen coordinates.
            mainHandler.post {
                val canvas = getCanvasFromPool()
                canvas.setBitmap(target)
                if (effectiveScale != 1.0f) canvas.scale(effectiveScale, effectiveScale)
                runCatching {
                    maskScreen(canvas, getWindowManagerViewsAndParams()?.views.orEmpty())
                }.onFailure {
                    // A frame whose masks could not be computed must not ship: the copy is
                    // the real screen, and unmasked is the one state it may never be in.
                    Log.e("ScreenCaptureUtils", "masking failed; dropping frame", it)
                    canvas.setBitmap(null)
                    returnCanvasToPool(canvas)
                    recycleBitmap(target)
                    onResult(null)
                    return@post
                }
                canvas.setBitmap(null)
                returnCanvasToPool(canvas)
                onResult(target)
            }
        }
    }

    /**
     * Copies `layers[index]` and everything above it into `target`, then reports.
     *
     * Recursive rather than a loop because `PixelCopy` answers on a callback: the next
     * window cannot be asked for until the previous one has arrived.
     *
     * The single-layer case — no dialog open, which is nearly every frame — copies straight
     * into `target`, exactly as this did before there was any compositing. Only a screen
     * that actually has a second window pays for the intermediate buffer and the blit.
     */
    private fun copyLayers(
        layers: List<SurfaceLayer>,
        index: Int,
        target: Bitmap,
        scale: Float,
        onDone: (Boolean) -> Unit,
    ) {
        if (index >= layers.size) {
            onDone(true)
            return
        }

        val layer = layers[index]
        val direct = layers.size == 1
        val layerWidth = ((layer.bounds.width()) * scale).toInt().coerceAtLeast(1)
        val layerHeight = ((layer.bounds.height()) * scale).toInt().coerceAtLeast(1)

        val destination = if (direct) {
            target
        } else {
            obtainBitmap(layerWidth, layerHeight, Bitmap.Config.ARGB_8888)
        }

        val request = {
            PixelCopy.request(layer.window, null, destination, { status ->
                if (status != PixelCopy.SUCCESS) {
                    Log.w("ScreenCaptureUtils", "PixelCopy failed with status $status")
                    if (!direct) recycleBitmap(destination)
                    onDone(false)
                    return@request
                }
                if (!direct) {
                    val canvas = getCanvasFromPool()
                    canvas.setBitmap(target)
                    runCatching {
                        canvas.drawBitmap(
                            destination,
                            null,
                            Rect(
                                (layer.bounds.left * scale).toInt(),
                                (layer.bounds.top * scale).toInt(),
                                (layer.bounds.right * scale).toInt(),
                                (layer.bounds.bottom * scale).toInt(),
                            ),
                            null,
                        )
                    }
                    canvas.setBitmap(null)
                    returnCanvasToPool(canvas)
                    recycleBitmap(destination)
                }
                copyLayers(layers, index + 1, target, scale, onDone)
            }, copyHandler())
        }

        try {
            request()
        } catch (e: Throwable) {
            // A dialog can be dismissed between listing the windows and copying them, which
            // makes its surface invalid. The layers already stacked are still a truthful
            // picture of the screen, so the frame is kept rather than dropped.
            Log.w("ScreenCaptureUtils", "PixelCopy request rejected for layer $index", e)
            if (!direct) recycleBitmap(destination)
            if (index == 0) onDone(false) else onDone(true)
        }
    }

    /**
     * Delivers PixelCopy results off the main thread; the work is reposted to it.
     *
     * Created on demand and torn down by [release], rather than a `by lazy` that started a
     * thread nothing ever stopped. Nullable rather than lazy for exactly that reason: a
     * `lazy` can be read but not un-read, so there was no way to quit the looper and no way
     * to start a fresh one afterwards — and `release` is called on a path that can be
     * followed by more capturing.
     */
    private val copyLock = Any()
    private var copyThread: android.os.HandlerThread? = null
    private var copyHandler: android.os.Handler? = null

    private fun copyHandler(): android.os.Handler = synchronized(copyLock) {
        copyHandler ?: android.os.HandlerThread("ls-pixelcopy").let { thread ->
            thread.start()
            copyThread = thread
            android.os.Handler(thread.looper).also { copyHandler = it }
        }
    }
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun captureToBitmap(scaleFactor: Float = globalScaleFactor): Bitmap? {
        return try {
            val windowData = getWindowManagerViewsAndParams() ?: return null
            val views = windowData.views!!
            val params = windowData.params

            val displayMetrics = Resources.getSystem().displayMetrics
            val effectiveScale = scaleFactor.coerceIn(0.1f, 1.0f)
            val width = (displayMetrics.widthPixels * effectiveScale).toInt()
            val height = (displayMetrics.heightPixels * effectiveScale).toInt()
            if (width <= 0 || height <= 0) return null

            val config =
                if (effectiveScale <= 0.5f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val bitmap = obtainBitmap(width, height, config)

            val canvas = getCanvasFromPool()
            canvas.setBitmap(bitmap)
            if (effectiveScale != 1.0f) {
                canvas.scale(effectiveScale, effectiveScale)
            }

            processViewsOptimized(views, params, canvas, effectiveScale, displayMetrics)

            // Masking happens here, and the position is the point.
            //
            // The canvas still carries the single `scale(effectiveScale)` transform, and
            // `processViewsOptimized` leaves it balanced, so this draws in *screen*
            // coordinates — which is the space the rectangles are already in. No
            // conversion against the scale factor, which is where a mask would silently
            // land on the wrong pixels at anything other than full quality.
            //
            // And it is before the encode, which is what makes it a privacy control
            // rather than a rendering choice: the unmasked pixels exist only in this
            // bitmap, on this thread, and are overwritten before anything reads them.
            //
            // Every capture path goes through here — replay frames and the screen map's
            // screenshots both — so none of them can be written without masking.
            maskScreen(canvas, views)

            // Detach so the canvas can be reused without holding the bitmap.
            canvas.setBitmap(null)
            returnCanvasToPool(canvas)

            bitmap
        } catch (e: Throwable) {
            // A hardware bitmap in the hierarchy — Coil and Glide decode into one by
            // default on API 26+ — cannot be drawn onto a software canvas, and it takes
            // the whole capture down rather than just its own pixels. Latch it so the
            // next capture goes straight to PixelCopy instead of throwing again.
            if (isHardwareBitmapFailure(e)) {
                if (!surfaceCaptureRequired) {
                    surfaceCaptureRequired = true
                    Log.i(
                        "ScreenCaptureUtils",
                        "hierarchy contains a hardware bitmap; switching to surface capture"
                    )
                    metricsHardwareBitmapFallbacks++
                }
            } else {
                Log.w("ScreenCaptureUtils", "Failed to draw screen", e)
            }
            null
        }
    }

    /** How many times this process fell back. Read by tests. */
    @Volatile
    internal var metricsHardwareBitmapFallbacks: Int = 0
        private set

    /**
     * Whether this is the hardware-bitmap case rather than an ordinary draw failure.
     *
     * Matched on the message because the platform throws a plain `IllegalArgumentException`
     * for it, with no distinguishing type. Checked against the cause chain too: the throw
     * happens deep inside `View.draw` and is sometimes wrapped on the way out.
     */
    private fun isHardwareBitmapFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message.orEmpty()
            if (current is IllegalArgumentException && message.contains("hardware bitmap", true)) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * Covers whatever [Masking] says to cover, across every window.
     *
     * One traversal per window, because a dialog is a separate window with its own
     * hierarchy and the text inside it is no less sensitive than the text behind it.
     *
     * Failures are swallowed on purpose, with one exception: if the *scan* throws, the
     * frame is dropped rather than shipped unmasked. A traversal that fails is a
     * traversal that found nothing, and "found nothing" is indistinguishable from "there
     * was nothing to mask" — shipping the frame would be treating an error as an
     * all-clear. Losing one frame of a replay is cheap; leaking a screen is not.
     */
    private fun maskScreen(canvas: Canvas, views: List<View>) {
        if (!Masking.enabled) return

        val rects = ArrayList<Rect>(32)
        for (view in views) {
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
            rects.addAll(masker.scan(view, Masking.text, Masking.images))
        }
        Masking.draw(canvas, rects)
    }

    /**
     * Compresses a bitmap from [captureToBitmap] and returns it to the pool.
     *
     * Safe to call from a background thread — nothing here touches View state.
     */
    fun encodeToJpeg(bitmap: Bitmap, scaleFactor: Float = globalScaleFactor): ByteArray? {
        return try {
            convertBitmapToByteArrayOptimized(bitmap, calculateCompressionQuality(scaleFactor))
        } catch (e: Throwable) {
            Log.w("ScreenCaptureUtils", "Failed to encode frame", e)
            null
        } finally {
            recycleBitmap(bitmap)
        }
    }

    /**
     * Captures and encodes in one step, on the calling thread.
     *
     * Kept for callers that need a synchronous result on the main thread — the
     * screen-map skeleton path, which runs once per navigation rather than
     * several times a second.
     */
    fun captureCurrentScreenOptimized(scaleFactor: Float = globalScaleFactor): ByteArray? {
        val bitmap = captureToBitmap(scaleFactor) ?: return null
        return encodeToJpeg(bitmap, scaleFactor)
    }

    private fun obtainBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        // Reuse only an exact match: a rotation or a scale change makes the
        // pooled buffers the wrong shape, and they are dropped rather than
        // reconfigured.
        while (true) {
            val candidate = bitmapPool.poll() ?: break
            if (!candidate.isRecycled &&
                candidate.width == width &&
                candidate.height == height &&
                candidate.config == config
            ) {
                // Stale pixels would show through anywhere the hierarchy does
                // not paint.
                candidate.eraseColor(android.graphics.Color.TRANSPARENT)
                return candidate
            }
            if (!candidate.isRecycled) candidate.recycle()
        }
        return Bitmap.createBitmap(width, height, config)
    }

    /** Returns a bitmap to the pool. Idempotent-safe: a recycled one is dropped. */
    fun recycleBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        // Two buffers is enough for draw-then-encode overlap; more would just
        // hold memory. The third and beyond are released.
        if (bitmapPool.size < MAX_POOLED_BITMAPS) {
            bitmapPool.offer(bitmap)
        } else {
            bitmap.recycle()
        }
    }

    /**
     * Captures the current screen and returns both Base64 and dimensions.
     *
     * @return Pair<String?, Pair<Int, Int>?> - (Base64 string, (width, height)) or (null, null) on error
     */
    /**
     * The screen as base64, taking whichever capture path this app's content allows.
     *
     * A suspending twin of [captureScreenAsBase64] for the screen map, which already
     * runs inside a coroutine. The blocking form cannot reach the PixelCopy fallback —
     * it has nowhere to wait — so on a screen holding a hardware bitmap it returns null
     * and the screen keeps its wireframe forever. That is exactly what happened to a
     * doctor list whose avatars Coil had decoded into hardware bitmaps.
     */
    suspend fun captureScreenAsBase64Async(): Pair<String?, Pair<Int, Int>?> {
        val bitmap = kotlin.coroutines.suspendCoroutine<Bitmap?> { continuation ->
            captureToBitmapAsync(ScalePresets.ORIGINAL) { continuation.resume(it) }
        } ?: return Pair(null, null)

        val bytes = encodeToJpeg(bitmap, ScalePresets.ORIGINAL) ?: return Pair(null, null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val dimensions = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            Pair(bounds.outWidth, bounds.outHeight)
        } else {
            null
        }
        return Pair(Base64.encodeToString(bytes, Base64.NO_WRAP), dimensions)
    }

    fun captureScreenAsBase64(): Pair<String?, Pair<Int, Int>?> {
        return try {
            val byteArray = captureCurrentScreenOptimized(ScalePresets.ORIGINAL)
            byteArray?.let {
                // Só o cabeçalho. Isto lia as dimensões decodificando o JPEG inteiro —
                // alocar ~10 MB e descomprimir uma tela cheia para ler dois inteiros que
                // estão nos primeiros bytes do arquivo, e que o único chamador
                // (`ScreenMapperIntegration.takeScreenshot`) descarta, porque prefere as
                // do `displayMetrics`. `inJustDecodeBounds` mantém o contrato da função
                // sem tocar em pixel nenhum.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
                val dimensions = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    Pair(bounds.outWidth, bounds.outHeight)
                } else {
                    null
                }
                Pair(Base64.encodeToString(it, Base64.NO_WRAP), dimensions)
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
    /** Raw bitmap of the current screen. Main thread only; caller owns it. */
    fun getWindowManagerComposedBitmapOptimized(scaleFactor: Float = globalScaleFactor): Bitmap? =
        captureToBitmap(scaleFactor)

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
                bitmap.recycle()
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

    private fun returnCanvasToPool(canvas: Canvas) {
        if (canvasPool.size < 2) canvasPool.add(canvas)
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
    /**
     * Everything [clearObjectPools] frees, plus the PixelCopy thread.
     *
     * Split from `clearObjectPools` because that one runs whenever view monitoring is
     * uninstalled and monitoring can be installed again afterwards, while this is for
     * shutting the recorder down for good. Quitting the looper on the reinstallable path
     * would leave the next capture posting to a dead thread.
     */
    fun release() {
        clearObjectPools()
        synchronized(copyLock) {
            copyThread?.quitSafely()
            copyThread = null
            copyHandler = null
        }
    }

    fun clearObjectPools() {
        while (true) {
            val bitmap = bitmapPool.poll() ?: break
            if (!bitmap.isRecycled) bitmap.recycle()
        }
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
