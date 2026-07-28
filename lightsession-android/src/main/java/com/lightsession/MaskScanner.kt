package com.lightsession

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.node.RootForTest
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
     * be. Falls back to the whole view when the layout is not available — covering too
     * much is the right way to be wrong here.
     */
    private fun addTextRects(view: TextView, into: MutableList<Rect>) {
        if (view.text.isNullOrEmpty() && view.hint.isNullOrEmpty()) return

        val layout = view.layout ?: run {
            addViewRect(view, into)
            return
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        for (line in 0 until layout.lineCount) {
            val bounds = Rect()
            layout.getLineBounds(line, bounds)
            val left = location[0] + view.paddingLeft + layout.getLineLeft(line).toInt()
            val right = location[0] + view.paddingLeft + layout.getLineRight(line).toInt()
            val rect = Rect(
                left,
                location[1] + view.paddingTop + bounds.top,
                right,
                location[1] + view.paddingTop + bounds.bottom,
            )
            if (!rect.isEmpty) into.add(rect)
        }
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
            val rect = Rect(
                location[0] + bounds.left.toInt(),
                location[1] + bounds.top.toInt(),
                location[0] + bounds.right.toInt(),
                location[1] + bounds.bottom.toInt(),
            )
            if (!rect.isEmpty) into.add(rect)
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
            if (config.getOrNull(SemanticsProperties.Role) == Role.Image) return true
            if (config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true) {
                return true
            }
        }

        return false
    }
}
