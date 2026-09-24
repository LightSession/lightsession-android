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
 * ## A host that moves without its composition changing
 *
 * "The rectangles change only when the composition does" is true of the composition and not of
 * where it sits. A `ComposeView` in a `ScrollView` or a `RecyclerView` row moves with its parent's
 * scroll, and nothing in the composition is written — no apply, so the cache stood, and its
 * rectangles are in window space. `ComposeImageInScrollingViewTest` measured it: a 160 dp image
 * covered before its `ScrollView` scrolled 400 px, and 168,000 of its pixels in the clear after,
 * the mask left behind where it had been. Every app that hosts Compose inside classic Views and
 * scrolls them showed its images that way.
 *
 * So each answer remembers where its host was and how big. A host that only moved takes its
 * rectangles with it, which is exact: the composition did not change, so everything in it moved
 * by the same amount. A host whose size changed is walked again, because a composition laid out
 * against new constraints can put its images anywhere, and that too arrives without an apply.
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
    private val cache: MutableMap<View, Cached> =
        Collections.synchronizedMap(WeakHashMap())

    /** One host's answer, and where the host was, and how big, when it was worked out. */
    private class Cached(
        val rects: List<Rect>,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * The screen-space rectangles of every image in this host's composition.
     *
     * @throws Exception when the composition cannot be read. Deliberate: the caller drops the
     *   frame, which is the only honest answer when the question is "what must be covered".
     */
    @OptIn(UiToolingDataApi::class)
    fun rectsIn(host: View, generator: SkeletonGenerator): List<Rect> {
        ensureObserver()

        val location = IntArray(2)
        host.getLocationOnScreen(location)
        if (!stale) {
            cache[host]?.takeIf { it.width == host.width && it.height == host.height }?.let { cached ->
                val dx = location[0] - cached.x
                val dy = location[1] - cached.y
                if (dx == 0 && dy == 0) return cached.rects
                return cached.rects.map { Rect(it).apply { offset(dx, dy) } }
            }
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

        cache[host] = Cached(rects, location[0], location[1], host.width, host.height)
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
