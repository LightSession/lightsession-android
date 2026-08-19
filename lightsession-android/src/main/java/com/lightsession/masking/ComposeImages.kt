package com.lightsession.masking

import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.tooling.data.UiToolingDataApi
import com.lightsession.mapper.ComposeLayoutInfo
import com.lightsession.mapper.SkeletonGenerator
import com.lightsession.mapper.computeLayoutInfos
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * Where the images are in a Compose screen, for the masker to cover.
 *
 * ## Why the semantics tree is not enough
 *
 * [MaskScanner] finds everything else through semantics, which is both cheap and live. Images are
 * the exception: `androidx.compose.foundation.Image` attaches semantics **only** when it is given
 * a `contentDescription`, so
 *
 * ```
 * Image(painter = …, contentDescription = null)
 * ```
 *
 * publishes no node at all. That is not an exotic spelling — it is what the accessibility guidance
 * tells an app to write for any image that is not itself information, and what image-loading calls
 * are routinely written with. Photos, avatars and scanned documents arrive that way, which is the
 * content `maskImages` exists for. Measured before this existed: of two images on screen, one
 * described and one decorative, `maskImages = true` covered one.
 *
 * What does carry every image is the layout node's modifier chain. `Image` paints through
 * `Modifier.paint`, whose element holds a [Painter] — found for both images in
 * `ImagePaintProbeTest`, with the node's own bounds, described or not.
 *
 * ## Why it is cached, and why the cache is safe
 *
 * The modifier chain is reachable only through the composition, and that walk is expensive.
 * Measured on a 23-node screen with `ImagePaintProbeTest`, at the level that decides — what one
 * call to `MaskScanner.scan` costs, since that is what runs for every captured frame:
 *
 * | scan | cost |
 * |---|---|
 * | text only, as masking cost before this existed | 2322 µs |
 * | text and images, cache warm | 2645 µs |
 * | text and images, cache cold | 25768 µs |
 *
 * The cold number is the whole reason for the cache: most of it is `asTree()`, which rebuilds a
 * `Group` per slot-table group and cannot be memoised because it *is* the snapshot. Paying it per
 * frame would spend more than a frame budget on rectangles that had not moved — masking runs on
 * the main thread, and the burst interval is one frame every 100 ms.
 *
 * The rectangles change only when the composition does, and a composition cannot change without a
 * state write, which commits as a snapshot apply. So the answer is cached and the apply observer
 * invalidates it — the argument `LateContent` makes for its own trigger, and the third place in
 * this SDK to use it. A still screen pays 300 µs over what it paid before; a screen that just
 * changed pays one walk.
 *
 * **A stale rectangle would be worse than no feature.** Covering where an image *was* leaves the
 * image itself in the clear somewhere else, so the cache is invalidated by any apply rather than by
 * a guess about which applies matter, and a recompute that fails throws rather than reporting no
 * images — `MaskScanner.scan`'s callers drop the frame when it throws, and a dropped frame is the
 * cheap half of that trade.
 *
 * ## What is not covered
 *
 * Custom drawing — `Canvas`, `drawWithContent`, a `Modifier.background(brush)` — is not an image
 * and is not reported. `Modifier.paint` is the mechanism `Image`, `Icon` and every image loader go
 * through; something that draws a photograph by hand into a `Canvas` is invisible here, and saying
 * so is better than implying a guarantee that does not hold.
 */
internal object ComposeImages {

    /** `Painter`-typed fields per modifier class, resolved once. Keyed on type, never on name. */
    private val painterFields: MutableMap<Class<*>, List<Field>> =
        Collections.synchronizedMap(WeakHashMap())

    private val lock = Any()
    private var observer: ObserverHandle? = null

    /** Cleared by any snapshot apply. Volatile because applies land on whatever thread wrote. */
    @Volatile
    private var stale = true

    /** Keyed by host view, so two windows — a sheet over a screen — do not share an answer. */
    private val cache: MutableMap<View, List<Rect>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * The screen-space rectangles of every image in this host's composition.
     *
     * @throws Exception when the composition cannot be read. Deliberate: the caller drops the
     *   frame, which is the only honest answer when the question is "what must be covered".
     */
    @OptIn(UiToolingDataApi::class)
    fun rectsIn(host: View, generator: SkeletonGenerator): List<Rect> {
        ensureObserver()

        if (!stale) {
            cache[host]?.let { return it }
        } else {
            // One apply invalidates every window: a state write can move anything, and the cost of
            // being wrong about which is a mask over the wrong pixels.
            cache.clear()
            stale = false
        }

        val tree = generator.compositionTreeOf(host)
            ?: throw IllegalStateException("no composition under ${host.javaClass.simpleName}")

        // No host offset is added. A layout node's `bounds` already arrive in the host window's
        // space — measured against the semantics rectangle for the same image, which is
        // `boundsInRoot` *plus* `getLocationOnScreen` and lands on exactly the same pixels. Adding
        // the offset here shifted every image mask down by the status bar's height on any app that
        // is not edge to edge, which is the direction that leaves the image showing.
        val rects = ArrayList<Rect>(4)

        fun walk(info: ComposeLayoutInfo) {
            when (info) {
                is ComposeLayoutInfo.LayoutNodeInfo -> {
                    if (info.modifiers.any { it.paintsAnImage() }) {
                        val bounds = info.bounds
                        if (bounds.width > 0 && bounds.height > 0) {
                            rects.add(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
                        }
                    }
                    info.children.forEach { walk(it) }
                }
                is ComposeLayoutInfo.SubcompositionInfo -> info.children.forEach { walk(it) }
                is ComposeLayoutInfo.AndroidViewInfo -> Unit
            }
        }
        tree.computeLayoutInfos().forEach { walk(it) }

        cache[host] = rects
        return rects
    }

    /** Whether this modifier element holds a painter — which is how Compose draws an image. */
    private fun Modifier.paintsAnImage(): Boolean {
        val fields = painterFields.getOrPut(javaClass) {
            runCatching {
                javaClass.declaredFields
                    .filter { Painter::class.java.isAssignableFrom(it.type) }
                    .onEach { it.isAccessible = true }
            }.getOrDefault(emptyList())
        }
        return fields.any { field -> runCatching { field.get(this) }.getOrNull() != null }
    }

    /**
     * Registered once and kept, gated on [stale].
     *
     * `Snapshot.registerApplyObserver` is process-global and disposing a handle from inside its own
     * dispatch is re-entrancy nothing documents — the reasoning `LateContent` records. Disarmed,
     * an apply costs one volatile write.
     */
    private fun ensureObserver() {
        if (observer != null) return
        synchronized(lock) {
            if (observer != null) return
            observer = Snapshot.registerApplyObserver { _, _ -> stale = true }
        }
    }
}
