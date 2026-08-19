package com.lightsession.masking

import android.graphics.Rect
import android.text.Layout
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
import com.lightsession.mapper.SkeletonGenerator
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull

/**
 * Finds what to cover, cheaply enough to run on every captured frame.
 *
 * ## Why this is not the skeleton's traversal
 *
 * Masking's first implementation reused [com.lightsession.mapper.SkeletonGenerator]'s
 * scan, on the reasonable-sounding grounds that it already classified text and images in
 * both Compose and classic Views. Measured on Phoenix, on device, per captured frame:
 *
 * ```
 * scan 26.59 ms   draw 0.07 ms   rects 7
 * scan 12.81 ms   draw 0.06 ms   rects 2
 * scan  6.41 ms   draw 0.06 ms   rects 2
 * ```
 *
 * Drawing masks is free, as the earlier measurement promised. *Finding* them was not: at
 * three captures a second that is up to 80 ms of main-thread time per second, and a
 * gesture burst runs at ten a second.
 *
 * The cost is not in reading semantics — it is `CompositionData.asTree()`, which
 * materialises the entire composition group tree with call chains and source locations.
 * The wireframe needs that: it has to tell a Card from a Column to draw structure.
 * Masking does not. It needs one question answered per node — *is this text* — and
 * Compose already publishes exactly that, for accessibility, through
 * [androidx.compose.ui.semantics.SemanticsOwner]. Walking the accessibility tree is what
 * this does instead.
 *
 * That also makes it more reliable in release, not less: accessibility depends on
 * semantics, so R8 cannot strip them, whereas composable *names* come from compiler
 * source information that Compose erases in release builds.
 *
 * ## Coordinates
 *
 * Everything returned is in **screen** pixels, because that is the space
 * `ScreenDrawing.captureToBitmap` draws in. Compose reports bounds relative to its own
 * root, so each is offset by the host view's screen location. Getting this wrong is the
 * quiet failure mode for a masker: the frame still comes out with grey blocks on it, and
 * the text is still readable somewhere else.
 */
internal class MaskScanner {

    /**
     * Only for its route to the composition, which [ComposeImages] needs and which has already
     * needed correcting twice — so there is one copy of it, in `SkeletonGenerator`, and this holds
     * the instance rather than growing a second.
     */
    private val skeletonGenerator = SkeletonGenerator()

    private companion object {
        const val TAG = "MaskScanner"

        /** Warned once per process; it describes a build, not an event. */
        @Volatile
        var semanticsWarningIssued = false
    }

    /**
     * Rectangles to cover within this view hierarchy.
     *
     * @throws Exception if a Compose scan fails. Deliberate: for the wireframe a failed
     *   scan degrades to a plainer picture, but here "found nothing" and "failed" are
     *   indistinguishable from the outside, and treating a failure as an all-clear
     *   publishes an unmasked screen. The caller drops the frame instead.
     */
    fun scan(root: View, maskText: Boolean, maskImages: Boolean): List<Rect> {
        if (!maskText && !maskImages) return emptyList()
        val rects = ArrayList<Rect>(32)
        collect(root, maskText, maskImages, rects)
        return rects
    }

    private fun collect(
        view: View,
        maskText: Boolean,
        maskImages: Boolean,
        into: MutableList<Rect>,
    ) {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return

        if (view is RootForTest) {
            collectCompose(view, maskText, maskImages, into)
            // No descent: a Compose host's children are composition nodes, not Views.
            // Interop views hosted inside it are reported by the semantics tree.
            return
        }

        when {
            // EditText is a TextView, and it is caught by the same branch — which is
            // what we want. A field's contents are the most sensitive thing on a screen.
            view is TextView && maskText -> addTextRects(view, into)
            view is ImageView && maskImages -> addViewRect(view, into)
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collect(view.getChildAt(index), maskText, maskImages, into)
            }
        }
    }

    /**
     * Per text line, not per view.
     *
     * A `TextView` box is as wide as its layout, which for a centred or short line is
     * mostly empty space. Masking the box hides more of the screen than the text
     * occupies, and a replay full of oversized blocks is harder to read than it needs to
     * be. Falls back to the whole view when the line geometry is not available — covering
     * too much is the right way to be wrong here.
     *
     * ## Where the text actually is
     *
     * The origin below is not `padding` — it is what `TextView.onDraw` translates by:
     *
     * ```java
     * canvas.translate(compoundPaddingLeft, extendedPaddingTop + voffsetText);
     * ```
     *
     * Three things separate that from `paddingLeft`/`paddingTop`, and the last one is the
     * expensive one. A compound drawable widens the left padding. `maxLines` with an
     * ellipsis makes the extended padding differ from the plain one. And **any vertical
     * gravity other than TOP shifts the text down inside its box** — which is the default
     * for a button, for a list row with a `minHeight`, and for the title and message of an
     * AppCompat `AlertDialog`.
     *
     * Measured, on a 900×300 `TextView` with `gravity=center_vertical` holding one line:
     *
     * ```
     * ink   132..169      mask (before)  0..71      the two do not overlap at all
     * ink    18..55       mask (top gravity control) 0..71   covered
     * ```
     *
     * A mask 132 pixels above its text is not a cosmetic fault. It produces a frame that
     * looks masked — there is a grey block on it — while leaving the text perfectly
     * legible underneath, which passes a glance and ships.
     */
    private fun addTextRects(view: TextView, into: MutableList<Rect>) {
        if (view.text.isNullOrEmpty() && view.hint.isNullOrEmpty()) return

        // An empty field showing its hint draws from `mHintLayout`, which `getLayout` does not
        // return, so there is no line geometry to read. The whole field is covered instead: it is
        // about to hold what someone types, and that is the capture that matters.
        val layout = view.layout?.takeIf { !view.text.isNullOrEmpty() } ?: run {
            addViewRect(view, into)
            return
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        val originX = location[0] + view.compoundPaddingLeft - view.scrollX
        val originY = location[1] + view.extendedPaddingTop + verticalOffset(view, layout) -
            view.scrollY

        // The text is clipped to its view, so the mask is too. Without this a scrolled field
        // reports the lines above and below the visible ones and covers whatever is next to it.
        val clip = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )

        for (line in 0 until layout.lineCount) {
            val bounds = Rect()
            layout.getLineBounds(line, bounds)
            val rect = Rect(
                originX + layout.getLineLeft(line).toInt(),
                originY + bounds.top,
                originX + layout.getLineRight(line).toInt(),
                originY + bounds.bottom,
            )
            // `intersect` narrows the rect to the overlap and answers false when there is none.
            if (rect.intersect(clip)) into.add(rect)
        }
    }

    /**
     * What `TextView.getVerticalOffset` would return, rebuilt from public API.
     *
     * The framework's own is package-private, so this mirrors it rather than calls it — same
     * three branches, same `>> 1`, and the same `getBoxHeight` built from the *measured* height
     * and the extended padding.
     *
     * Not mirrored: the optical-inset term, which only applies under
     * `android:layoutMode="opticalBounds"`. A parent using it would shift this by the inset,
     * and reflecting into `isLayoutModeOptical` to find out costs more than the case is worth.
     */
    private fun verticalOffset(view: TextView, layout: Layout): Int {
        val gravity = view.gravity and Gravity.VERTICAL_GRAVITY_MASK
        if (gravity == Gravity.TOP) return 0

        val boxHeight = view.measuredHeight - view.extendedPaddingTop - view.extendedPaddingBottom
        val textHeight = layout.height
        if (textHeight >= boxHeight) return 0

        return if (gravity == Gravity.BOTTOM) boxHeight - textHeight else (boxHeight - textHeight) shr 1
    }

    private fun addViewRect(view: View, into: MutableList<Rect>) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height,
        )
        if (!rect.isEmpty) into.add(rect)
    }

    private fun collectCompose(
        host: View,
        maskText: Boolean,
        maskImages: Boolean,
        into: MutableList<Rect>,
    ) {
        // Images first, and from the composition rather than from semantics: a decorative
        // `Image` publishes no semantics node at all, so this is the only place it exists. Only
        // when the flag is on — an app that does not ask for image masking never pays for the
        // composition walk. See [ComposeImages] for the cost and the cache.
        //
        // Allowed to throw, like the rest of this scan: the caller drops the frame, and a dropped
        // frame is cheaper than a frame with an uncovered photograph in it.
        // Two sources for images, deliberately, and deduplicated because they overlap. The
        // composition finds every image that paints through a painter, described or not; semantics
        // keeps the rarer case the composition cannot see — something drawn by hand into a
        // `Canvas` and declared `Role.Image` has no painter to find. A described `Image` is found
        // by both, and covering the same rectangle twice is free but reads as two images to anyone
        // counting.
        val seen = HashSet<Rect>()
        fun add(rect: Rect) {
            if (!rect.isEmpty && seen.add(rect)) into.add(rect)
        }

        if (maskImages) {
            ComposeImages.rectsIn(host, skeletonGenerator).forEach { add(it) }
        }

        val owner = (host as RootForTest).semanticsOwner
        val nodes = owner.getAllSemanticsNodes(mergingEnabled = false)

        if (nodes.isEmpty()) {
            // Nothing to mask, or nothing visible to the masker — and those are not the
            // same thing. A composition with text always publishes semantics for it, so
            // an empty tree on a screen that clearly has content means masking is blind
            // here, and saying so is better than shipping the frame quietly.
            if (!semanticsWarningIssued) {
                semanticsWarningIssued = true
                Log.w(TAG, "Compose host exposes no semantics; its text cannot be masked")
            }
            return
        }

        val location = IntArray(2)
        host.getLocationOnScreen(location)

        for (node in nodes) {
            if (!shouldMask(node, maskText, maskImages)) continue

            val bounds = node.boundsInRoot
            add(
                Rect(
                    location[0] + bounds.left.toInt(),
                    location[1] + bounds.top.toInt(),
                    location[0] + bounds.right.toInt(),
                    location[1] + bounds.bottom.toInt(),
                ),
            )
        }
    }

    /**
     * Whether this node holds content worth covering.
     *
     * Text, editable text, and a field that merely *accepts* text — the last one because
     * an empty input still becomes a full one, and the capture that catches it typed is
     * the capture that matters.
     *
     * `ContentDescription` counts as an image: it is how an icon or a photo describes
     * itself, and it is also where an app sometimes puts the very thing it is displaying.
     */
    private fun shouldMask(node: SemanticsNode, maskText: Boolean, maskImages: Boolean): Boolean {
        val config = node.config

        if (maskText) {
            if (config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true) return true
            if (config.getOrNull(SemanticsProperties.EditableText) != null) return true
            if (config.getOrNull(SemanticsActions.SetText) != null) return true
        }

        if (maskImages) {
            // `Role.Image` is what a described image publishes, and it is free to ask. Every
            // image — described or not — is also reported by [ComposeImages], so this is the
            // cheap half of a belt and braces rather than the only source.
            //
            // A `ContentDescription` used to count as an image here, and no longer does. It is
            // not evidence of one: an icon button, a switch, anything described for a screen
            // reader carries one, so the flag covered controls as though they were photographs.
            // Over-covering is the safe direction and was the right default while the
            // composition was unreadable; now that it is read, the looser rule only hides
            // widgets nobody asked to hide.
            if (config.getOrNull(SemanticsProperties.Role) == Role.Image) return true
        }

        return false
    }
}
