package com.lightsession.mapper

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

class SkeletonGenerator {

    private data class SkeletonNode(
        val rect: Rect,
        val type: NodeType,
        val color: Int,
        val style: Paint.Style,
        val children: List<SkeletonNode> = emptyList()
    )

    private enum class NodeType {
        CONTAINER, TEXT, IMAGE, INPUT, BUTTON, WEBVIEW, COMPOSE_HOST, UNKNOWN, CARD
    }

    private val defaultColors = mapOf(
        NodeType.CONTAINER to Color.parseColor("#E0E0E0"),
        NodeType.TEXT to Color.DKGRAY,
        NodeType.IMAGE to Color.parseColor("#BDBDBD"),
        NodeType.INPUT to Color.parseColor("#E0E0E0"),
        NodeType.BUTTON to Color.parseColor("#6200EE"),
        NodeType.UNKNOWN to Color.TRANSPARENT,
        NodeType.CARD to Color.WHITE,
        NodeType.WEBVIEW to Color.LTGRAY,
        NodeType.COMPOSE_HOST to Color.TRANSPARENT
    )

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
                }, 1000) // 300ms para dar tempo da recomposição
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

        // DETECÇÃO DE COMPOSE - A forma correta usando Radiography technique!
        if (isComposeView(view)) {
            Log.d("SkeletonGenerator", "AndroidComposeView detected! Scanning using UI Tooling API...")
            val composeNodes = scanComposeHierarchyUsingTooling(view)
            childrenNodes.addAll(composeNodes)
        } else if (view is ViewGroup) {
            // View Android tradicional
            view.children.forEach { childView ->
                scanViewHierarchy(childView)?.let { childrenNodes.add(it) }
            }
        }

        val type = determineNodeType(view)
        val (color, style) = extractVisuals(view, type)

        return SkeletonNode(rect, type, color, style, childrenNodes)
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
     * AQUI ESTÁ A MÁGICA! 🎉
     * Usa a mesma técnica do Radiography para escanear Compose usando UI Tooling API
     */
    @OptIn(UiToolingDataApi::class)
    private fun scanComposeHierarchyUsingTooling(composeView: View): List<SkeletonNode> {
        try {
            // 1. Acessar mKeyedTags via reflection
            val keyedTags = composeView.getKeyedTags() ?: run {
                Log.w("SkeletonGenerator", "Could not access mKeyedTags")
                return emptyList()
            }

            // 2. Encontrar a Composition
            val composition = keyedTags.findComposition() ?: run {
                Log.w("SkeletonGenerator", "Could not find Composition")
                return emptyList()
            }

            // 3. Unwrap se necessário
            val actualComposition = composition.unwrap()

            // 4. Extrair o Composer
            val composer = actualComposition.getComposer() ?: run {
                Log.w("SkeletonGenerator", "Could not get Composer")
                return emptyList()
            }

            // 5. USAR A TOOLING API! (A linha mágica)
            val compositionData = composer.compositionData
            val rootGroup = compositionData.asTree()

            Log.d("SkeletonGenerator", "Successfully extracted Compose tree!")

            // 6. Parsear a árvore de Groups
            return parseGroupTree(rootGroup)

        } catch (e: Exception) {
            Log.e("SkeletonGenerator", "Failed to scan Compose hierarchy using Tooling API", e)
            return emptyList()
        }
    }

    /**
     * Acessa o campo privado mKeyedTags do View
     */
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

    /**
     * Encontra a Composition no SparseArray
     */
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
     * Desempacota WrappedComposition
     */
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

    /**
     * Extrai o Composer da Composition
     */
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
     * Parseia recursivamente a árvore de Groups do Compose
     */
    @OptIn(UiToolingDataApi::class)
    private fun parseGroupTree(group: Group): List<SkeletonNode> {
        val result = mutableListOf<SkeletonNode>()

        // NodeGroup = algo que desenha na tela (LayoutNode)
        if (group is NodeGroup) {
            val bounds = group.box

            // Ignora grupos com bounds inválidos
            if (bounds.width > 0 && bounds.height > 0) {
                val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                val type = determineComposeNodeType(group)
                val color = defaultColors[type] ?: Color.LTGRAY

                // Determina o estilo: FILL para elementos visíveis, STROKE apenas para containers grandes
                val style = when (type) {
                    NodeType.TEXT, NodeType.BUTTON, NodeType.IMAGE, NodeType.INPUT -> Paint.Style.FILL
                    NodeType.CONTAINER -> {
                        // Se o container é pequeno ou tem nome específico, usa FILL, senão STROKE
                        val name = group.name?.lowercase() ?: ""
                        if ("card" in name || "surface" in name || bounds.width * bounds.height < 500000) {
                            Paint.Style.FILL
                        } else {
                            Paint.Style.STROKE
                        }
                    }
                    else -> Paint.Style.STROKE
                }

                // Recursivamente processa filhos
                val children = group.children.flatMap { parseGroupTree(it) }.toMutableList()

                result.add(SkeletonNode(
                    rect = rect,
                    type = type,
                    color = color,
                    style = style,
                    children = children
                ))
            }
        } else {
            // Não é NodeGroup, apenas processa filhos
            group.children.forEach { child ->
                result.addAll(parseGroupTree(child))
            }
        }

        return result
    }

    /**
     * Determina o tipo de um composable baseado no nome
     */
    @OptIn(UiToolingDataApi::class)
    private fun determineComposeNodeType(group: NodeGroup): NodeType {
        val name = group.name?.lowercase() ?: ""

        return when {
            "text" in name && "field" !in name -> NodeType.TEXT
            "textfield" in name || "outlinedtextfield" in name || "basictextfield" in name -> NodeType.INPUT
            "button" in name || "iconbutton" in name || "floatingactionbutton" in name -> NodeType.BUTTON
            "image" in name || "icon" in name -> NodeType.IMAGE
            "box" in name || "column" in name || "row" in name ||
            "surface" in name || "card" in name || "scaffold" in name -> NodeType.CONTAINER
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
            return Pair(defaultColors[NodeType.CONTAINER]!!, Paint.Style.STROKE)
        }

        return Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
    }

    /**
     * Renderiza a hierarquia de views como texto ASCII art, similar ao Radiography.scan()
     * Útil para debug e comparação com o output do Radiography
     *
     * Exemplo de output:
     * MainActivity:
     * window-focus:true
     *  DecorView { 1080×2400px, pos:(0,0), color:#FFFFFFFF, fill }
     *  ╰─LinearLayout { 1080×2400px, pos:(0,0), color:#E0E0E0, stroke }
     *    ├─AndroidComposeView { 1080×2148px, pos:(0,252), fill }
     *    │ ╰─Column { 1080×2148px, pos:(0,252), color:#LTGRAY, stroke }
     *    │   ├─Text { 200×40px, pos:(440,600), color:#DKGRAY, fill }
     *    │   ╰─Button { 300×100px, pos:(390,700), color:#6200EE, fill }
     */
    fun renderHierarchyAsText(): String {
        val activity = getCurrentActivity() ?: return "Nenhuma Activity encontrada no momento"

        // Pega a view raiz da janela (decorView)
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

            // Aqui você renderiza a árvore
            renderNodeAsText(
                node = skeletonTree,
                depth = 0,
                isLast = true,
                parentIsLast = booleanArrayOf() // ou use MutableList<Boolean> se preferir
            )
        }
    }

    private fun StringBuilder.renderNodeAsText(
        node: SkeletonNode,
        depth: Int,
        isLast: Boolean,
        parentIsLast: BooleanArray
    ) {
        // Adiciona o non-breaking space no início (igual ao Radiography usa \u00a0)
        append('\u00a0')

        // Desenha as linhas verticais dos pais
        for (i in 0 until depth - 1) {
            append(if (parentIsLast[i]) "  " else "│ ")
        }

        // Desenha o conector do nó atual
        if (depth > 0) {
            append(if (isLast) "╰─" else "├─")
        }

        // Nome do tipo
        val typeName = when (node.type) {
            NodeType.TEXT -> "Text"
            NodeType.BUTTON -> "Button"
            NodeType.IMAGE -> "Image"
            NodeType.INPUT -> "TextField"
            NodeType.CONTAINER -> "Container"
            NodeType.WEBVIEW -> "WebView"
            NodeType.UNKNOWN -> "Unknown"
            else -> {}
        }
        append(typeName)

        // Atributos
        append(" { ")
        val attributes = mutableListOf<String>()

        // Dimensões
        val width = node.rect.width()
        val height = node.rect.height()
        attributes.add("${width}×${height}px")

        // Posição (x,y)
        attributes.add("pos:(${node.rect.left},${node.rect.top})")

        // Cor (em hex)
        if (node.color != Color.TRANSPARENT) {
            val colorHex = String.format("#%08X", node.color)
            attributes.add("color:$colorHex")
        }

        // Estilo
        attributes.add(if (node.style == Paint.Style.FILL) "fill" else "stroke")

        // Número de filhos
        if (node.children.isNotEmpty()) {
            attributes.add("children:${node.children.size}")
        }

        append(attributes.joinToString(", "))
        append(" }")
        appendLine()

        // Renderiza filhos recursivamente
        val children = node.children
        children.forEachIndexed { index, child ->
            val childIsLast = (index == children.size - 1)
            val newParentIsLast = parentIsLast.copyOf(depth + 1)
            newParentIsLast[depth] = isLast
            renderNodeAsText(child, depth + 1, childIsLast, newParentIsLast)
        }
    }

    /**
     * Função recursiva para achar a cor dentro de Drawables complexos
     */
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

        // RippleDrawable (API 21+)
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
        // Não desenha nós transparentes (como AndroidComposeView container)
        if (node.color != Color.TRANSPARENT) {
            val paint = Paint().apply {
                color = node.color
                style = node.style
                strokeWidth = if (node.style == Paint.Style.STROKE) 2f else 0f
                isAntiAlias = true
            }

            when (node.type) {
                NodeType.TEXT -> {
                    // Textos são retângulos preenchidos com cor cinza escuro
                    paint.style = Paint.Style.FILL
                    canvas.drawRect(node.rect, paint)
                }
                NodeType.BUTTON -> {
                    // Botões são retângulos arredondados preenchidos
                    paint.style = Paint.Style.FILL
                    canvas.drawRoundRect(
                        node.rect.left.toFloat(),
                        node.rect.top.toFloat(),
                        node.rect.right.toFloat(),
                        node.rect.bottom.toFloat(),
                        16f, 16f, paint
                    )
                }
                NodeType.IMAGE -> {
                    // Imagens são ovais preenchidos
                    paint.style = Paint.Style.FILL
                    canvas.drawOval(
                        node.rect.left.toFloat(),
                        node.rect.top.toFloat(),
                        node.rect.right.toFloat(),
                        node.rect.bottom.toFloat(),
                        paint
                    )
                }
                NodeType.INPUT -> {
                    // Inputs são retângulos com fundo claro e borda
                    // Primeiro desenha o fundo
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#F5F5F5")
                    canvas.drawRoundRect(
                        node.rect.left.toFloat(),
                        node.rect.top.toFloat(),
                        node.rect.right.toFloat(),
                        node.rect.bottom.toFloat(),
                        8f, 8f, paint
                    )
                    // Depois desenha a borda
                    paint.style = Paint.Style.STROKE
                    paint.color = node.color
                    paint.strokeWidth = 2f
                    canvas.drawRoundRect(
                        node.rect.left.toFloat(),
                        node.rect.top.toFloat(),
                        node.rect.right.toFloat(),
                        node.rect.bottom.toFloat(),
                        8f, 8f, paint
                    )
                }
                NodeType.CONTAINER, NodeType.CARD -> {
                    // Containers: se for FILL, preenche, senão apenas contorno
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

