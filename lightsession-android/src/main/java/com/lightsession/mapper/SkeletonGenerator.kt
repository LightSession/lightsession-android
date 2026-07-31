package com.lightsession.mapper

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.NodeGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.core.view.children
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap

class SkeletonGenerator {

    private companion object {
        const val MAX_UNWRAP_DEPTH = 8
    }

    private data class SkeletonNode(
        val rect: Rect,
        val type: NodeType,
        val color: Int,
        val style: Paint.Style,
        val name: String? = null,
        val children: List<SkeletonNode> = emptyList()
    )

    private enum class NodeType {
        CONTAINER, TEXT, IMAGE, INPUT, BUTTON, WEBVIEW, COMPOSE_HOST, UNKNOWN, CARD
    }

    private val defaultColors: Map<NodeType, Int> = mapOf(
        NodeType.CONTAINER to Color.parseColor("#E0E0E0"),
        NodeType.TEXT to Color.parseColor("#4CAF50"),
        NodeType.IMAGE to Color.parseColor("#2196F3"),
        NodeType.INPUT to Color.parseColor("#FF9800"),
        NodeType.BUTTON to Color.parseColor("#9C27B0"),
        NodeType.UNKNOWN to Color.parseColor("#757575"),
        NodeType.CARD to Color.parseColor("#FFEB3B"),
        NodeType.WEBVIEW to Color.parseColor("#00BCD4"),
        NodeType.COMPOSE_HOST to Color.TRANSPARENT
    )

    private fun containsComposeView(view: View): Boolean {
        if (isComposeView(view)) {
            return true
        }
        if (view is ViewGroup) {
            view.children.forEach { child ->
                if (containsComposeView(child)) {
                    return true
                }
            }
        }
        return false
    }

    private val settleDetector = ComposeSettleDetector()

    /**
     * How many LayoutNodes the screen's composition has emitted so far.
     *
     * Used as a "there is content" signal. Only asked once the screen looks quiet, so walking the
     * composition costs one or two passes per navigation rather than one per frame.
     */
    private fun composeNodeCount(rootView: View): Int {
        val composeView = findComposeView(rootView) ?: return 0
        return runCatching { scanComposeHierarchyUsingTooling(composeView).size }.getOrDefault(0)
    }

    private fun findComposeView(view: View): View? {
        if (isComposeView(view)) return view
        if (view is ViewGroup) {
            view.children.forEach { child -> findComposeView(child)?.let { return it } }
        }
        return null
    }

    /**
     * Waits for the screen to have something on it, then makes something out of it.
     *
     * A navigation event can arrive before the screen exists. Compose was the known case — the event
     * lands before the composition has emitted a single node — and the fix was to wait for the tree
     * to *stop changing* rather than to guess a delay, which the previous `postDelayed(1000)` did
     * badly in both directions.
     *
     * The mistake was believing Compose was the only such case. Everything else took a fast path
     * asking `rootView.width > 0 && height > 0` — whether the window has a **size** — and captured
     * on the spot. A window has a size immediately; that says nothing about whether anything has
     * been drawn into it.
     *
     * React Native is the second case, and it is not a niche one: the whole app is one Activity whose
     * content is rendered by JavaScript after `onResume`. Measured on a stock RN app, the wireframe
     * went out 238ms *before* the JS bundle logged `Running "example"`, so the scan walked an empty
     * `ReactRootView` and stored a blank page — while the real screenshot, which waits, came out
     * perfect. Any host that fills its tree asynchronously would have failed the same way: a Flutter
     * view, a WebView, a layout inflated off a coroutine.
     *
     * So there is one path now, and the question it asks is "is there anything here yet" rather than
     * "does the window have a size". A classic Activity that is already laid out answers yes on the
     * first frame and is captured with no added delay — the fast path is not lost, it just stopped
     * being a special case that assumed the answer.
     *
     * The wait lives here rather than in each capture mode because it is the only hard part:
     * [generateSkeletonFrame] and [generateSkeletonBitmap] differ only in what they do with the
     * hierarchy once it exists.
     *
     * @param onComplete receives null when there was nothing to capture. No skeleton beats a blank
     *   one marked valid — which is exactly what React Native was producing.
     */
    private fun <T> awaitDrawableRoot(
        activity: Activity,
        produce: (Activity, View) -> T?,
        onComplete: (T?) -> Unit,
    ) {
        settleDetector.await(
            activity = activity,
            hasContent = {
                val root = activity.window?.decorView?.rootView
                root != null && root.width > 0 && root.height > 0 && contentCount(root) > 0
            },
            onSettled = { settled ->
                val root = settled.window?.decorView?.rootView
                onComplete(root?.let { produce(settled, it) })
            },
        )
    }

    /**
     * How much of the *app's own* content this screen would draw.
     *
     * Counting rects was the obvious measure and it was wrong. `scanOnly` returns exactly what the
     * capture will draw, which is the right property — but from the decor view it is never zero. A
     * window arrives with its own furniture: a status bar background, a navigation bar, the content
     * frame. On React Native that furniture settled the wait 4ms before the JS bundle logged
     * `Running "example"`, and the stored wireframe was those rects and nothing else: a thin border
     * around a white page, byte-for-byte identical to the blank one it was meant to fix.
     *
     * So the question is asked twice as narrowly. Only under `android.R.id.content`, which is where
     * an app's views live and the window's own do not. And only *leaves* — text, an image, an input,
     * a button — because a container proves nothing: an empty `ReactRootView` is a `ViewGroup` and
     * counts as a rect while showing nothing at all. A wireframe with no leaves is a blank wireframe,
     * which is the thing being avoided, so that is the thing to measure.
     *
     * Compose is still asked through the tooling data: its nodes are not Views, so a View walk sees
     * only the host.
     */
    private fun contentCount(rootView: View): Int {
        if (containsComposeView(rootView)) return composeNodeCount(rootView)

        val content = rootView.findViewById<View>(android.R.id.content) ?: rootView
        return runCatching { contentLeaves(scanViewHierarchy(content)) }.getOrDefault(0)
    }

    /**
     * Nodes that put something on the page, as opposed to nodes that only hold others.
     *
     * `UNKNOWN` counts, and that is the whole subtlety. A first version excluded it as "not real
     * content", which broke a kind of screen this SDK is meant to handle: a plain `View` with a
     * background — a splash holding a logo, a chart, anything drawn rather than composed of widgets —
     * classifies as `UNKNOWN`, because it is not a TextView or an ImageView or a ViewGroup. Excluding
     * it meant such a screen never satisfied the wait, timed out after five seconds and stored **no
     * wireframe at all**, where before it stored a correct one immediately. Trading a blank wireframe
     * on React Native for a missing wireframe on Android is not a fix.
     *
     * An empty container is what does not count, and it is the thing being distinguished. Under
     * `android.R.id.content` a React Native app that has not rendered yet is exactly one
     * `ReactRootView` — a `ViewGroup` with no children — while any laid-out Android screen has
     * something that draws. That difference is the signal; "is it a widget" never was.
     */
    private fun contentLeaves(node: SkeletonNode?): Int {
        if (node == null) return 0
        val self = when (node.type) {
            NodeType.TEXT, NodeType.IMAGE, NodeType.INPUT,
            NodeType.BUTTON, NodeType.CARD, NodeType.WEBVIEW,
            // A plain View that draws. See the kdoc: excluding this cost Android screens their
            // wireframe entirely.
            NodeType.UNKNOWN -> 1
            // Holds others and draws only an outline. Its children are counted below, so a container
            // with content still answers yes — an empty one, which is what an unrendered React Native
            // root looks like, does not.
            NodeType.CONTAINER, NodeType.COMPOSE_HOST -> 0
        }
        return self + node.children.sumOf { contentLeaves(it) }
    }

    /**
     * Describes the screen as rectangles, for the server to draw.
     *
     * The normal path. Allocates no bitmap and encodes no JPEG: it walks the hierarchy — the
     * irreducible cost of knowing what is on screen — and returns a few KB of geometry. See
     * [SkeletonFrame].
     */
    fun generateSkeletonFrame(activity: Activity, onComplete: (SkeletonFrame?) -> Unit) {
        awaitDrawableRoot(activity, ::generateSkeletonFrameSync, onComplete)
    }

    /**
     * Draws the wireframe here and hands back the bitmap.
     *
     * Kept for a backend that does not yet understand `skeleton` in the screen payload. Produces the
     * same image [generateSkeletonFrame] produces on the server, paying the encode that path does
     * not.
     */
    fun generateSkeletonBitmap(activity: Activity, onComplete: (Bitmap?) -> Unit) {
        awaitDrawableRoot(activity, ::generateSkeletonBitmapSync, onComplete)
    }

    /** Aborts a wait in progress — a new navigation, or the user touching the screen. */
    fun cancelPendingCapture() {
        settleDetector.cancel()
    }

    private fun generateSkeletonFrameSync(activity: Activity, rootView: View): SkeletonFrame? =
        frameFrom(rootView, getWindowBackgroundColor(activity))

    /**
     * The scan and the serialisation, without the Activity.
     *
     * The Activity was only there for a theme lookup, and demanding one made the expensive path
     * impossible to measure without standing up a whole screen. Separating the two is what lets
     * `SkeletonCostTest` time this path against [bitmapFrom] over the *same* hierarchy — the only
     * honest comparison between them.
     */
    internal fun frameFrom(rootView: View, backgroundColor: Int): SkeletonFrame? {
        if (rootView.width == 0 || rootView.height == 0) return null

        val skeletonTree = scanViewHierarchy(rootView) ?: return null
        val rects = ArrayList<SkeletonRect>(64)
        flattenForWire(skeletonTree, rects)

        return SkeletonFrame(
            width = rootView.width,
            height = rootView.height,
            background = backgroundColor,
            rects = rects,
        )
    }

    /**
     * Flattens the tree in paint order, the order [renderTreeToCanvas] paints in.
     *
     * Pre-order, parent before children — that is what puts a child on top. And it skips a
     * transparent node while keeping its children, which is how `COMPOSE_HOST` marks itself: it
     * exists to say where Compose begins, which is not something a wireframe shows. Diverging from
     * here would change the image with nothing to announce it.
     */
    private fun flattenForWire(node: SkeletonNode, into: MutableList<SkeletonRect>) {
        if (node.color != Color.TRANSPARENT) {
            into.add(
                SkeletonRect(
                    left = node.rect.left,
                    top = node.rect.top,
                    right = node.rect.right,
                    bottom = node.rect.bottom,
                    kind = node.type.name,
                    color = node.color,
                    stroke = node.style == Paint.Style.STROKE,
                )
            )
        }
        node.children.forEach { flattenForWire(it, into) }
    }

    private fun generateSkeletonBitmapSync(activity: Activity, rootView: View): Bitmap? =
        bitmapFrom(rootView, getWindowBackgroundColor(activity))

    /** The same scan as [frameFrom], drawing here instead. See its kdoc. */
    internal fun bitmapFrom(rootView: View, backgroundColor: Int): Bitmap? {
        if (rootView.width == 0 || rootView.height == 0) return null

        val skeletonTree = scanViewHierarchy(rootView) ?: return null
        val bitmap = createBitmap(rootView.width, rootView.height)
        val canvas = Canvas(bitmap)

        canvas.drawColor(backgroundColor)

        renderTreeToCanvas(skeletonTree, canvas)
        return bitmap
    }

    /** The scan alone, to separate the irreducible cost from what each mode adds to it. */
    internal fun scanOnly(rootView: View): Int {
        val tree = scanViewHierarchy(rootView) ?: return 0
        val rects = ArrayList<SkeletonRect>(64)
        flattenForWire(tree, rects)
        return rects.size
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun getWindowBackgroundColor(activity: Activity): Int {
        val typedValue = TypedValue()
        val theme = activity.theme
        if (theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
        }
        return Color.WHITE
    }

    /**
     * Walks the whole hierarchy, picking up both Android Views and Compose
     */
    private fun scanViewHierarchy(view: View): SkeletonNode? {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)

        val childrenNodes = mutableListOf<SkeletonNode>()

        if (isComposeView(view)) {
            val composeNodes = scanComposeHierarchyUsingTooling(view)
            childrenNodes.addAll(composeNodes)
        } else if (view is ViewGroup) {
            view.children.forEach { childView ->
                scanViewHierarchy(childView)?.let { childrenNodes.add(it) }
            }
        }

        val type = determineNodeType(view)
        val (color, style) = extractVisuals(view, type)

        val viewName = if (isComposeView(view)) {
            "AndroidComposeView"
        } else {
            view.javaClass.simpleName
        }

        return SkeletonNode(rect, type, color, style, viewName, childrenNodes)
    }

    /**
     * Recognises the host of a composition.
     *
     * `AndroidComposeView` is internal to Compose and R8 renames it — in release it becomes
     * something like `p0.r`, so matching on the class name never hits. When that happens a Compose
     * screen is treated as a classic view: the generator walks the View tree, finds no children
     * inside the host, and produces an empty skeleton with no error at all.
     *
     * `RootForTest` is the public interface that host implements, through `ViewRootForTest`. A type
     * check is resolved by the compiler against the real class and survives obfuscation.
     */
    private fun isComposeView(view: View): Boolean = view is RootForTest

    @OptIn(UiToolingDataApi::class)
    private fun scanComposeHierarchyUsingTooling(composeView: View): List<SkeletonNode> {
        try {
            val keyedTags = composeView.getKeyedTags() ?: return emptyList()
            val composition = keyedTags.findComposition() ?: return emptyList()
            val actualComposition = composition.unwrap()
            val composer = actualComposition.getComposer() ?: return emptyList()

            val rootGroup = composer.compositionData.asTree()

            // The semantics tree is the source of type information that SURVIVES minification:
            // accessibility depends on it, so R8 cannot strip it. Composable names, by contrast,
            // come from the compiler's source information, which the Compose runtime removes in
            // release through -assumenosideeffects.
            val semantics = SemanticsIndex((composeView as? RootForTest)?.semanticsOwner)
            if (semantics.isEmpty) {
                Log.d("SkeletonGenerator", "No semantics available; falling back to call-chain names")
            }

            val layoutInfos = rootGroup.computeLayoutInfos(semantics = semantics)

            // Translated into screen coordinates.
            //
            // `Group.box` is relative to the composition's root; `scanViewHierarchy` uses
            // `getLocationOnScreen` for Views. Both went into the same tree unconverted — which
            // went unnoticed only because an edge-to-edge app puts the `AndroidComposeView` at
            // (0,0), making the offset zero. Anywhere else the wireframe comes out shifted, and a
            // shifted mask leaves text in view: cosmetic in one case, a privacy failure in the
            // other.
            val hostLocation = IntArray(2)
            composeView.getLocationOnScreen(hostLocation)

            return layoutInfos
                .map { convertLayoutInfoToSkeletonNode(it) }
                .map { translate(it, hostLocation[0], hostLocation[1]) }
                .toList()

        } catch (e: Exception) {
            Log.e("SkeletonGenerator", "Failed to scan Compose hierarchy using Tooling API", e)
            return emptyList()
        }
    }

    /** Moves a whole subtree, to bring the composition into screen space. */
    private fun translate(node: SkeletonNode, dx: Int, dy: Int): SkeletonNode {
        if (dx == 0 && dy == 0) return node
        val moved = Rect(node.rect)
        moved.offset(dx, dy)
        return node.copy(
            rect = moved,
            children = node.children.map { translate(it, dx, dy) },
        )
    }

    private fun convertLayoutInfoToSkeletonNode(layoutInfo: ComposeLayoutInfo): SkeletonNode {
        return when (layoutInfo) {
            is ComposeLayoutInfo.LayoutNodeInfo -> {
                val rect = Rect(
                    layoutInfo.bounds.left,
                    layoutInfo.bounds.top,
                    layoutInfo.bounds.right,
                    layoutInfo.bounds.bottom
                )
                // Semantics primeiro (confiável em release), nome depois (só existe em debug).
                val type = classifyBySemantics(layoutInfo.semanticsNodes)
                    ?: determineComposeNodeType(layoutInfo.name)
                val color = defaultColors[type] ?: Color.LTGRAY
                val style = if (type == NodeType.CONTAINER) Paint.Style.STROKE else Paint.Style.FILL

                val children = layoutInfo.children.map { convertLayoutInfoToSkeletonNode(it) }.toMutableList()

                SkeletonNode(
                    rect = rect,
                    type = type,
                    color = color,
                    style = style,
                    name = layoutInfo.name.ifEmpty { null },
                    children = children
                )
            }
            is ComposeLayoutInfo.SubcompositionInfo -> {
                val rect = Rect(
                    layoutInfo.bounds.left,
                    layoutInfo.bounds.top,
                    layoutInfo.bounds.right,
                    layoutInfo.bounds.bottom
                )
                val children = layoutInfo.children.map { convertLayoutInfoToSkeletonNode(it) }.toMutableList()

                SkeletonNode(
                    rect = rect,
                    type = NodeType.CONTAINER,
                    color = defaultColors[NodeType.CONTAINER] ?: Color.LTGRAY,
                    style = Paint.Style.STROKE,
                    name = layoutInfo.name.ifEmpty { null },
                    children = children
                )
            }
            is ComposeLayoutInfo.AndroidViewInfo -> {
                scanViewHierarchy(layoutInfo.view) ?: SkeletonNode(
                    rect = Rect(),
                    type = NodeType.UNKNOWN,
                    color = Color.TRANSPARENT,
                    style = Paint.Style.FILL,
                    name = "AndroidView"
                )
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun View.getKeyedTags(): SparseArray<*>? {
        return try {
            val field = View::class.java.getDeclaredField("mKeyedTags")
            field.isAccessible = true
            field.get(this) as? SparseArray<*>
        } catch (e: Exception) {
            Log.e("SkeletonGenerator", "Error accessing mKeyedTags", e)
            null
        }
    }

    private fun SparseArray<*>.findComposition(): Composition? {
        for (i in 0 until size()) {
            val value = valueAt(i)
            if (value is Composition) {
                return value
            }
        }
        return null
    }

    /**
     * Unwraps a [Composition] down to the real implementation.
     *
     * Does **not** compare class names. In a minified release build R8 renames
     * `androidx.compose.ui.platform.WrappedComposition` to something like `p0.V0` and
     * `androidx.compose.runtime.CompositionImpl` to `H.w` — the previous version compared
     * `this::class.java.name` against the literal, never matched, returned the wrapper unwrapped, and
     * [getComposer] right after it returned null. The result: every Compose screen produced an empty
     * skeleton, silently.
     *
     * Looking by *type* works after obfuscation: `is Composition` is resolved by the compiler against
     * the real class rather than by name.
     */
    private fun Composition.unwrap(): Composition {
        var current: Composition = this
        // Wrappers aninhados são possíveis; o limite evita ciclo patológico.
        repeat(MAX_UNWRAP_DEPTH) {
            val inner = current.javaClass.declaredFields.asSequence()
                .mapNotNull { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(current) as? Composition
                    }.getOrNull()
                }
                .firstOrNull { it !== current }
                ?: return current
            current = inner
        }
        return current
    }

    /**
     * Pulls the [Composer] out of a [Composition], by type as well.
     */
    private fun Composition.getComposer(): Composer? {
        val found = javaClass.declaredFields.asSequence()
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(this) as? Composer
                }.getOrNull()
            }
            .firstOrNull()

        if (found == null) {
            Log.w(
                "SkeletonGenerator",
                "No Composer field on ${javaClass.name}; Compose hierarchy unavailable"
            )
        }
        return found
    }

    @OptIn(UiToolingDataApi::class)
    private fun parseGroupTree(group: Group, parentCallChain: List<String>): List<SkeletonNode> {
        val callChain: List<String> = if (!group.name.isNullOrBlank()) {
            parentCallChain + group.name!!
        } else {
            parentCallChain
        }

        if (group is NodeGroup) {
            val bounds = group.box
            if (bounds.width > 0 && bounds.height > 0) {
                val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

                val type = resolveNodeTypeFromChain(callChain)

                val children = group.children.flatMap {
                    parseGroupTree(it, callChain)
                }.toMutableList()

                val isLeaf = children.isEmpty()

                val (finalColor, style) = when {
                    type in listOf(NodeType.TEXT, NodeType.IMAGE, NodeType.BUTTON, NodeType.INPUT, NodeType.CARD) -> {
                        Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
                    }
                    type == NodeType.CONTAINER && isLeaf -> {
                        Pair(Color.parseColor("#E0E0E0"), Paint.Style.FILL)
                    }
                    type == NodeType.CONTAINER && !isLeaf -> {
                        Pair(Color.parseColor("#BDBDBD"), Paint.Style.STROKE)
                    }
                    else -> {
                        Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
                    }
                }

                return listOf(SkeletonNode(
                    rect = rect,
                    type = type,
                    color = finalColor,
                    style = style,
                    name = callChain.lastOrNull() ?: "Unknown",
                    children = children
                ))
            }
        }

        return group.children.flatMap { child ->
            parseGroupTree(child, callChain)
        }
    }

    private fun resolveNodeTypeFromChain(callChain: List<String>): NodeType {
        for (name in callChain.reversed()) {
            val type = determineComposeNodeType(name)
            if (type != NodeType.CONTAINER && type != NodeType.UNKNOWN) {
                return type
            }
        }
        return NodeType.CONTAINER
    }


    /**
     * Derives a node's type from the semantics tree.
     *
     * Returns null when semantics says nothing useful, so the caller falls
     * no heurístico por nome.
     */
    private fun classifyBySemantics(nodes: List<SemanticsNode>): NodeType? {
        if (nodes.isEmpty()) return null

        for (node in nodes) {
            val config = node.config

            config.getOrNull(SemanticsProperties.Role)?.let { role ->
                when (role) {
                    Role.Button, Role.Checkbox, Role.Switch,
                    Role.RadioButton, Role.Tab -> return NodeType.BUTTON
                    Role.Image -> return NodeType.IMAGE
                    Role.DropdownList -> return NodeType.INPUT
                    else -> Unit
                }
            }

            // An editable field: EditableText, or the set-text action when the value is empty.
            if (config.getOrNull(SemanticsProperties.EditableText) != null ||
                config.getOrNull(SemanticsActions.SetText) != null
            ) {
                return NodeType.INPUT
            }

            if (config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true) {
                return NodeType.TEXT
            }

            // A description with no text is the icon/image pattern.
            if (config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true) {
                return NodeType.IMAGE
            }

            // Clickable with no explicit role is still a touch target.
            if (config.getOrNull(SemanticsActions.OnClick) != null) {
                return NodeType.BUTTON
            }
        }

        return null
    }

    private fun determineComposeNodeType(name: String): NodeType {
        val nameLower = name.lowercase()

        return when {
            // Text components
            "text" in nameLower && "field" !in nameLower -> NodeType.TEXT
            "basictext" in nameLower -> NodeType.TEXT
            "label" in nameLower -> NodeType.TEXT

            // Input components
            "textfield" in nameLower || "outlinedtextfield" in nameLower || "basictextfield" in nameLower -> NodeType.INPUT

            // Button components
            "button" in nameLower || "iconbutton" in nameLower ||
            "floatingactionbutton" in nameLower || "fab" in nameLower -> NodeType.BUTTON

            // Image components
            "image" in nameLower || "icon" in nameLower ||
            "vectorpainter" in nameLower || "painter" in nameLower -> NodeType.IMAGE

            // Card components
            "card" in nameLower -> NodeType.CARD

            // WebView
            "webview" in nameLower -> NodeType.WEBVIEW

            // Containers comuns
            "box" in nameLower || "column" in nameLower || "row" in nameLower ||
            "surface" in nameLower || "scaffold" in nameLower ||
            "lazycol" in nameLower || "lazyrow" in nameLower ||
            "layout" in nameLower -> NodeType.CONTAINER

            else -> NodeType.CONTAINER
        }
    }

    /**
     * Works out the type of an Android View
     */
    private fun determineNodeType(view: View): NodeType {
        if (isComposeView(view)) return NodeType.COMPOSE_HOST
        if (view.javaClass.name.contains("CardView")) return NodeType.CARD
        if (view is WebView) return NodeType.WEBVIEW
        if (view is android.widget.EditText) return NodeType.INPUT
        if (view is android.widget.Button) return NodeType.BUTTON
        if (view is TextView) return NodeType.TEXT
        if (view is ImageView) return NodeType.IMAGE
        if (view is ViewGroup && view.childCount > 0) return NodeType.CONTAINER
        return if (view is ViewGroup) NodeType.CONTAINER else NodeType.UNKNOWN
    }

    private fun extractVisuals(view: View, type: NodeType): Pair<Int, Paint.Style> {
        if (isComposeView(view)) {
            return Pair(Color.TRANSPARENT, Paint.Style.FILL)
        }

        if (view.javaClass.name.contains("CardView")) {
            try {
                val method = view.javaClass.getMethod("getCardBackgroundColor")
                val colorStateList = method.invoke(view) as? android.content.res.ColorStateList
                colorStateList?.defaultColor?.let { return Pair(it, Paint.Style.FILL) }
            } catch (e: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList?.defaultColor?.let {
                if (it != 0 && it != Color.TRANSPARENT) {
                    return Pair(it, Paint.Style.FILL)
                }
            }
        }

        extractColorFromDrawable(view.background)?.let { extracted ->
            if (extracted != 0 && extracted != Color.TRANSPARENT) {
                return Pair(extracted, Paint.Style.FILL)
            }
        }

        if (view is android.widget.Button) {
            getThemeColor(view.context, android.R.attr.colorPrimary)?.let {
                return Pair(it, Paint.Style.FILL)
            }
            return Pair(Color.parseColor("#6200EE"), Paint.Style.FILL)
        }

        if (view is TextView) {
            return Pair(view.currentTextColor, Paint.Style.FILL)
        }

        if (type == NodeType.CONTAINER) {
            return Pair(defaultColors[NodeType.CONTAINER] ?: Color.LTGRAY, Paint.Style.STROKE)
        }

        return Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
    }

    private fun extractColorFromDrawable(drawable: Drawable?): Int? {
        if (drawable == null) return null

        when (drawable) {
            is ColorDrawable -> return drawable.color

            is GradientDrawable -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    drawable.color?.defaultColor?.let { return it }
                }
                return null
            }

            is StateListDrawable -> {
                return extractColorFromDrawable(drawable.current)
            }

            is LayerDrawable -> {
                if (drawable.numberOfLayers > 0) {
                    return extractColorFromDrawable(drawable.getDrawable(0))
                }
            }

            is InsetDrawable -> {
                return extractColorFromDrawable(drawable.drawable)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && drawable is RippleDrawable) {
            if (drawable.numberOfLayers > 0) {
                return extractColorFromDrawable(drawable.getDrawable(0))
            }
        }

        return null
    }

    private fun getThemeColor(context: android.content.Context, attribute: Int): Int? {
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(attribute, typedValue, true)) {
            return typedValue.data
        }
        return null
    }

    private fun renderTreeToCanvas(node: SkeletonNode, canvas: Canvas) {
        if (node.color == Color.TRANSPARENT) {
            node.children.forEach { renderTreeToCanvas(it, canvas) }
            return
        }

        val paint = Paint().apply {
            color = node.color
            style = node.style
            strokeWidth = if (node.style == Paint.Style.STROKE) 4f else 0f
            isAntiAlias = true
        }

        // Square corners, and filled rectangles draw the same as outlined ones.
        //
        // There was a `drawRoundRect(rect, 12f, 12f)` here, and the server's renderer copied the 12
        // so that moving the drawing over there would not change what a user sees. It changed
        // anyway: this `Paint` has `isAntiAlias`, the server's has no antialiasing at all, and the
        // same arc that comes out smooth here comes out in one-pixel steps there. Copying the radius
        // without being able to copy the antialiasing made the corner *more* obvious than the
        // original.
        //
        // Square is what this drawing already looked like, and it is the only version that does not
        // depend on antialiasing to look right — there is no arc to sample badly. A thin rectangle
        // gains the most: a 6px divider had its radius clamped to 3 and came out a pill.
        canvas.drawRect(node.rect, paint)

        node.children.forEach { child ->
            renderTreeToCanvas(child, canvas)
        }
    }
}

