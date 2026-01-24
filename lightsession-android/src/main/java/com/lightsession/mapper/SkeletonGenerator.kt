package com.lightsession.mapper

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.*
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.NodeGroup
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.core.view.children
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap
import radiography.ExperimentalRadiographyComposeApi
class SkeletonGenerator {

    private data class SkeletonNode(
        val rect: Rect,
        val type: NodeType,
        val color: Int,
        val style: Paint.Style,
        val name: String? = null, // 🆕 ADICIONADO: Nome real do composable ou View
        val children: List<SkeletonNode> = emptyList()
    )

    private enum class NodeType {
        CONTAINER, TEXT, IMAGE, INPUT, BUTTON, WEBVIEW, COMPOSE_HOST, UNKNOWN, CARD
    }

    private val defaultColors: Map<NodeType, Int> = mapOf(
        NodeType.CONTAINER to -0x1f1f20, // #E0E0E0
        NodeType.TEXT to Color.DKGRAY,
        NodeType.IMAGE to -0x424243, // #BDBDBD
        NodeType.INPUT to -0x1f1f20, // #E0E0E0
        NodeType.BUTTON to -0x9dff3200, // #6200EE
        NodeType.UNKNOWN to Color.TRANSPARENT,
        NodeType.CARD to Color.WHITE,
        NodeType.WEBVIEW to Color.LTGRAY,
        NodeType.COMPOSE_HOST to Color.TRANSPARENT
    ) as Map<NodeType, Int>

    /**
     * Detecta se a Activity contém uma tela Compose.
     */
    fun isComposeScreen(activity: Activity): Boolean {
        val rootView = activity.window.decorView.rootView
        return containsComposeView(rootView)
    }

    private fun containsComposeView(view: View): Boolean {
        if (view.javaClass.name.contains("AndroidComposeView")) {
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

    fun generateSkeletonBitmap(activity: Activity, onComplete: (Bitmap?) -> Unit) {
        val rootView = activity.window.decorView.rootView

        // Detecta se é uma tela Compose
        val isComposeScreen = containsComposeView(rootView)

        // Se a view já está pronta, gera imediatamente (ou com delay para Compose)
        if (rootView.width > 0 && rootView.height > 0) {
            if (isComposeScreen) {
                // Para Compose, aguarda um frame adicional para garantir que a recomposição foi concluída
                Log.d("SkeletonGenerator", "Compose detected, waiting for recomposition...")
                rootView.postDelayed({
                    val bitmap = generateSkeletonBitmapSync(activity, rootView)
                    onComplete(bitmap)
                }, 1000)
            } else {
                val bitmap = generateSkeletonBitmapSync(activity, rootView)
                onComplete(bitmap)
            }
            return
        }

        // Caso contrário, aguarda o layout ser concluído
        rootView.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)

                if (rootView.width > 0 && rootView.height > 0) {
                    if (isComposeScreen) {
                        // Para Compose, aguarda mais um pouco após o layout
                        Log.d("SkeletonGenerator", "Layout ready, waiting for Compose recomposition...")
                        rootView.postDelayed({
                            val bitmap = generateSkeletonBitmapSync(activity, rootView)
                            onComplete(bitmap)
                        }, 300)
                    } else {
                        val bitmap = generateSkeletonBitmapSync(activity, rootView)
                        onComplete(bitmap)
                    }
                } else {
                    // Se ainda assim não tiver dimensões, retorna null
                    onComplete(null)
                }
            }
        })
    }

    private fun generateSkeletonBitmapSync(activity: Activity, rootView: View): Bitmap? {
        if (rootView.width == 0 || rootView.height == 0) return null

        val skeletonTree = scanViewHierarchy(rootView) ?: return null
        val skeletonTreeTexto = renderHierarchyAsText()
        val bitmap = createBitmap(rootView.width, rootView.height)
        val canvas = Canvas(bitmap)

        // Pega a cor de fundo da janela
        val windowBackground = getWindowBackgroundColor(activity)
        canvas.drawColor(windowBackground)

        renderTreeToCanvas(skeletonTree, canvas)
        return bitmap
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
     * Escaneia a hierarquia completa, detectando Views Android e Compose
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

        // 🆕 CORREÇÃO: Armazena o nome da View Android também
        val viewName = if (isComposeView(view)) {
            "AndroidComposeView"
        } else {
            view.javaClass.simpleName
        }

        return SkeletonNode(rect, type, color, style, viewName, childrenNodes)
    }

    /**
     * Detecta se é um AndroidComposeView
     */
    private fun isComposeView(view: View): Boolean {
        return view.javaClass.name.contains("AndroidComposeView")
    }

    fun getCurrentActivity(): Activity? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null)
            .let { activityThread ->
                @Suppress("UNCHECKED_CAST")
                val mActivities = activityThread.javaClass
                    .getDeclaredField("mActivities")
                    .apply { isAccessible = true }
                    .get(activityThread) as Map<Any, Any>

                mActivities.values
                    .mapNotNull { record ->
                        record.javaClass.getDeclaredField("activity")
                            .apply { isAccessible = true }
                            .get(record) as? Activity
                    }
                    .lastOrNull { it.window?.decorView?.isShown == true }
            }
    } catch (e: Throwable) {
        null
    }

    /**
     * Usa a mesma técnica do Radiography para escanear Compose usando UI Tooling API
     */
    @OptIn(UiToolingDataApi::class)
    private fun scanComposeHierarchyUsingTooling(composeView: View): List<SkeletonNode> {
        try {
            val keyedTags = composeView.getKeyedTags() ?: return emptyList()
            val composition = keyedTags.findComposition() ?: return emptyList()
            val actualComposition = composition.unwrap()
            val composer = actualComposition.getComposer() ?: return emptyList()

            val rootGroup = composer.compositionData.asTree()

            // Usa a função computeLayoutInfos do Radiography
            // Passamos null para semanticsOwner pois não é estritamente necessário para o esqueleto
            val layoutInfos = rootGroup.computeLayoutInfos(semanticsOwner = null)

            Log.d("SkeletonGen", "Found ${layoutInfos.count()} layout infos")

            // Converte ComposeLayoutInfo para SkeletonNode
            return layoutInfos.map { convertLayoutInfoToSkeletonNode(it) }.toList()

        } catch (e: Exception) {
            Log.e("SkeletonGenerator", "Failed to scan Compose hierarchy using Tooling API", e)
            return emptyList()
        }
    }

    /**
     * Converte ComposeLayoutInfo para SkeletonNode
     */
    private fun convertLayoutInfoToSkeletonNode(layoutInfo: ComposeLayoutInfo): SkeletonNode {
        return when (layoutInfo) {
            is ComposeLayoutInfo.LayoutNodeInfo -> {
                val rect = Rect(
                    layoutInfo.bounds.left,
                    layoutInfo.bounds.top,
                    layoutInfo.bounds.right,
                    layoutInfo.bounds.bottom
                )
                val type = determineComposeNodeType(layoutInfo.name)
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
                // Para AndroidView, escaneia a view Android recursivamente
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

    private fun Composition.unwrap(): Composition {
        val className = this::class.java.name
        if (className != "androidx.compose.ui.platform.WrappedComposition") {
            return this
        }

        return try {
            val wrappedClass = Class.forName("androidx.compose.ui.platform.WrappedComposition")
            val originalField = wrappedClass.getDeclaredField("original")
            originalField.isAccessible = true
            originalField.get(this) as Composition
        } catch (e: Exception) {
            Log.w("SkeletonGenerator", "Could not unwrap WrappedComposition", e)
            this
        }
    }

    private fun Composition.getComposer(): Composer? {
        val className = this::class.java.name
        if (className != "androidx.compose.runtime.CompositionImpl") {
            Log.w("SkeletonGenerator", "Composition is not CompositionImpl: $className")
            return null
        }

        return try {
            val compositionImplClass = Class.forName("androidx.compose.runtime.CompositionImpl")
            val composerField = compositionImplClass.getDeclaredField("composer")
            composerField.isAccessible = true
            composerField.get(this) as? Composer
        } catch (e: Exception) {
            Log.e("SkeletonGenerator", "Error extracting Composer", e)
            null
        }
    }

    /**
     * 🆕 CORREÇÃO PRINCIPAL: Parseia recursivamente a árvore de Groups ACUMULANDO o callChain
     */
    @OptIn(UiToolingDataApi::class)
    private fun parseGroupTree(group: Group, parentCallChain: List<String>): List<SkeletonNode> {
        // 🆕 Acumula o nome deste grupo no callChain
        val callChain: List<String> = if (group.name != null) {
            parentCallChain + group.name!!
        } else {
            parentCallChain
        }

        // Se é NodeGroup, cria um SkeletonNode
        if (group is NodeGroup) {
            val bounds = group.box

            // Ignora grupos com bounds inválidos (mas processa filhos)
            if (bounds.width > 0 && bounds.height > 0) {
                val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

                // 🆕 CHAVE: Usa o PRIMEIRO nome do callChain
                val name = callChain.firstOrNull() ?: ""
                val type = determineComposeNodeType(name)
                val color: Int = defaultColors[type] ?: Color.LTGRAY

                // Determina o estilo
                val style = when (type) {
                    NodeType.TEXT, NodeType.BUTTON, NodeType.IMAGE, NodeType.INPUT -> Paint.Style.FILL
                    NodeType.CONTAINER -> {
                        val nameLower = name.lowercase()
                        if ("card" in nameLower || "surface" in nameLower || bounds.width * bounds.height < 500000) {
                            Paint.Style.FILL
                        } else {
                            Paint.Style.STROKE
                        }
                    }
                    else -> Paint.Style.STROKE
                }

                // 🆕 CORREÇÃO: Recursivamente processa filhos, MAS RESETA O CALLCHAIN
                val children = group.children.flatMap {
                    parseGroupTree(it, emptyList())
                }.toMutableList()

                return listOf(SkeletonNode(
                    rect = rect,
                    type = type,
                    color = color,
                    style = style,
                    name = name.ifEmpty { null }, // 🆕 Armazena o nome real
                    children = children
                ))
            } else {
                // Bounds inválido, só processa filhos
                return group.children.flatMap {
                    parseGroupTree(it, emptyList())
                }
            }
        }
        return group.children.flatMap { child ->
            parseGroupTree(child, callChain)
        }
    }

    /**
     * 🆕 CORREÇÃO: Agora recebe String em vez de NodeGroup
     */
    private fun determineComposeNodeType(name: String): NodeType {
        val nameLower = name.lowercase()

        return when {
            "text" in nameLower && "field" !in nameLower -> NodeType.TEXT
            "textfield" in nameLower || "outlinedtextfield" in nameLower || "basictextfield" in nameLower -> NodeType.INPUT
            "button" in nameLower || "iconbutton" in nameLower || "floatingactionbutton" in nameLower -> NodeType.BUTTON
            "image" in nameLower || "icon" in nameLower -> NodeType.IMAGE
            "box" in nameLower || "column" in nameLower || "row" in nameLower ||
                    "surface" in nameLower || "card" in nameLower || "scaffold" in nameLower -> NodeType.CONTAINER
            else -> NodeType.CONTAINER
        }
    }

    /**
     * Determina o tipo de um View Android
     */
    private fun determineNodeType(view: View): NodeType {
        if (view.javaClass.name.contains("AndroidComposeView")) return NodeType.COMPOSE_HOST
        if (view.javaClass.name.contains("CardView")) return NodeType.CARD
        if (view is WebView) return NodeType.WEBVIEW
        if (view is android.widget.EditText) return NodeType.INPUT
        if (view is android.widget.Button) return NodeType.BUTTON
        if (view is TextView) return NodeType.TEXT
        if (view is ImageView) return NodeType.IMAGE
        if (view is ViewGroup && view.childCount > 0) return NodeType.CONTAINER
        return if (view is ViewGroup) NodeType.CONTAINER else NodeType.UNKNOWN
    }

    /**
     * Extrai visuais de Views Android
     */
    private fun extractVisuals(view: View, type: NodeType): Pair<Int, Paint.Style> {
        // Para AndroidComposeView, usa cor transparente (os filhos Compose terão suas cores)
        if (isComposeView(view)) {
            return Pair(Color.TRANSPARENT, Paint.Style.FILL)
        }

        // 1. TENTATIVA: CardView
        if (view.javaClass.name.contains("CardView")) {
            try {
                val method = view.javaClass.getMethod("getCardBackgroundColor")
                val colorStateList = method.invoke(view) as? android.content.res.ColorStateList
                colorStateList?.defaultColor?.let { return Pair(it, Paint.Style.FILL) }
            } catch (e: Exception) {}
        }

        // 2. TENTATIVA: Background Tint
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList?.defaultColor?.let {
                if (it != 0 && it != Color.TRANSPARENT) {
                    return Pair(it, Paint.Style.FILL)
                }
            }
        }

        // 3. TENTATIVA: Extração Profunda do Drawable
        extractColorFromDrawable(view.background)?.let { extracted ->
            if (extracted != 0 && extracted != Color.TRANSPARENT) {
                return Pair(extracted, Paint.Style.FILL)
            }
        }

        // 4. TRATAMENTO ESPECIAL PARA BOTÕES
        if (view is android.widget.Button) {
            getThemeColor(view.context, android.R.attr.colorPrimary)?.let {
                return Pair(it, Paint.Style.FILL)
            }
            return Pair(Color.parseColor("#6200EE"), Paint.Style.FILL)
        }

        // 5. TEXTOS
        if (view is TextView) {
            return Pair(view.currentTextColor, Paint.Style.FILL)
        }

        // 6. FALLBACKS
        if (type == NodeType.CONTAINER) {
            return Pair(defaultColors[NodeType.CONTAINER] ?: Color.LTGRAY, Paint.Style.STROKE)
        }

        return Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
    }

    /**
     * Renderiza a hierarquia de views como texto ASCII art
     */
    fun renderHierarchyAsText(): String {
        val activity = getCurrentActivity() ?: return "Nenhuma Activity encontrada no momento"

        val rootView = activity.window?.decorView ?: return "Window/decorView não disponível"

        if (rootView.width <= 0 || rootView.height <= 0) {
            return "View raiz ainda não está pronta (${rootView.width}×${rootView.height})"
        }

        val skeletonTree = scanViewHierarchy(rootView)
            ?: return "Não foi possível escanear a hierarquia de views"

        return buildString {
            appendLine("${activity.javaClass.simpleName} (${activity::class.qualifiedName})")
            appendLine("  package: ${activity.packageName}")
            appendLine("  window focus: ${rootView.hasWindowFocus()}")
            appendLine("  root view: ${rootView.javaClass.simpleName} (${rootView.width}×${rootView.height})")

            renderNodeAsText(
                node = skeletonTree,
                depth = 0,
                isLast = true,
                parentIsLast = booleanArrayOf()
            )
        }
    }

    private fun StringBuilder.renderNodeAsText(
        node: SkeletonNode,
        depth: Int,
        isLast: Boolean,
        parentIsLast: BooleanArray
    ) {
        // Non-breaking space no início
        append('\u00a0')

        // Desenha as linhas verticais dos pais
        for (i in 0 until depth - 1) {
            append(if (parentIsLast[i]) "  " else "│ ")
        }

        // Desenha o conector do nó atual
        if (depth > 0) {
            append(if (isLast) "╰─" else "├─")
        }

        // 🆕 CORREÇÃO: Usa o nome real ou fallback para o tipo
        val displayName = node.name ?: when (node.type) {
            NodeType.TEXT -> "Text"
            NodeType.BUTTON -> "Button"
            NodeType.IMAGE -> "Image"
            NodeType.INPUT -> "TextField"
            NodeType.CONTAINER -> "Container"
            NodeType.WEBVIEW -> "WebView"
            NodeType.CARD -> "Card"
            NodeType.COMPOSE_HOST -> "AndroidComposeView"
            NodeType.UNKNOWN -> "Unknown"
        }
        append(displayName)

        // Atributos
        append(" { ")
        val attributes = mutableListOf<String>()

        val width = node.rect.width()
        val height = node.rect.height()
        attributes.add("${width}×${height}px")
        attributes.add("pos:(${node.rect.left},${node.rect.top})")

        if (node.color != Color.TRANSPARENT) {
            val colorHex = String.format("#%08X", node.color)
            attributes.add("color:$colorHex")
        }

        attributes.add(if (node.style == Paint.Style.FILL) "fill" else "stroke")

        if (node.children.isNotEmpty()) {
            attributes.add("children:${node.children.size}")
        }

        append(attributes.joinToString(", "))
        append(" }")
        appendLine()

        // Renderiza filhos recursivamente
        node.children.forEachIndexed { index, child ->
            val childIsLast = (index == node.children.size - 1)
            val newParentIsLast = parentIsLast.copyOf(depth + 1)
            newParentIsLast[depth] = isLast
            renderNodeAsText(child, depth + 1, childIsLast, newParentIsLast)
        }
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

    /**
     * Renderiza a árvore no canvas
     */
    private fun renderTreeToCanvas(node: SkeletonNode, canvas: Canvas) {
        // Não desenha nós transparentes
        if (node.color != Color.TRANSPARENT) {
            val paint = Paint().apply {
                color = node.color
                style = node.style
                strokeWidth = if (node.style == Paint.Style.STROKE) 2f else 0f
                isAntiAlias = true
            }

            when (node.type) {
                NodeType.TEXT -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(node.rect, paint)
                }
                NodeType.BUTTON -> {
                    paint.style = Paint.Style.FILL
                    val rectF = RectF(node.rect)
                    canvas.drawRoundRect(rectF, 16f, 16f, paint)
                }
                NodeType.IMAGE -> {
                    paint.style = Paint.Style.FILL
                    val rectF = RectF(node.rect)
                    canvas.drawOval(rectF, paint)
                }
                NodeType.INPUT -> {
                    // Fundo
                    paint.style = Paint.Style.FILL
                    paint.color = -0x0a0a0b // #F5F5F5
                    val rectF = RectF(node.rect)
                    canvas.drawRoundRect(rectF, 8f, 8f, paint)

                    // Borda
                    paint.style = Paint.Style.STROKE
                    paint.color = node.color
                    paint.strokeWidth = 2f
                    canvas.drawRoundRect(rectF, 8f, 8f, paint)
                }
                NodeType.CONTAINER, NodeType.CARD -> {
                    canvas.drawRect(node.rect, paint)
                }
                else -> {
                    canvas.drawRect(node.rect, paint)
                }
            }
        }

        // Renderiza filhos recursivamente
        node.children.forEach { child ->
            renderTreeToCanvas(child, canvas)
        }
    }
}

