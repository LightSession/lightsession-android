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
        /** Teto para o desembrulho de Composition aninhada. */
        const val MAX_UNWRAP_DEPTH = 8
    }

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
        NodeType.CONTAINER to Color.parseColor("#E0E0E0"), // Cinza claro para containers
        NodeType.TEXT to Color.parseColor("#4CAF50"),      // Verde para texto
        NodeType.IMAGE to Color.parseColor("#2196F3"),     // Azul para imagens
        NodeType.INPUT to Color.parseColor("#FF9800"),     // Laranja para inputs
        NodeType.BUTTON to Color.parseColor("#9C27B0"),    // Roxo para botões
        NodeType.UNKNOWN to Color.parseColor("#757575"),   // Cinza escuro para desconhecidos
        NodeType.CARD to Color.parseColor("#FFEB3B"),      // Amarelo para cards
        NodeType.WEBVIEW to Color.parseColor("#00BCD4"),   // Ciano para webview
        NodeType.COMPOSE_HOST to Color.TRANSPARENT         // Transparente para o host
    )

    /**
     * Detecta se a Activity contém uma tela Compose.
     */
    fun isComposeScreen(activity: Activity): Boolean {
        val rootView = activity.window.decorView.rootView
        return containsComposeView(rootView)
    }

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
     * Conta quantos LayoutNodes a composição da tela já produziu.
     *
     * Usado como sinal de "tem conteúdo". Só é chamado quando a tela parece
     * quieta, então o custo de varrer a composição é pago uma ou duas vezes por
     * navegação, não a cada quadro.
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
     * Espera a tela ficar desenhável e então produz algo a partir dela.
     *
     * Para uma Activity clássica, a hierarquia já está medida quando chegamos
     * aqui e a captura é imediata.
     *
     * Para Compose não: o evento de navegação chega antes da composição emitir
     * qualquer nó. A versão anterior contornava com `postDelayed(1000)`, um
     * palpite que era longo demais para tela estática e curto demais para tela
     * que carrega dados — e quando errava, gravava um skeleton vazio sem avisar.
     * Agora [ComposeSettleDetector] espera a composição *parar de mudar*, o que
     * é rápido quando dá e paciente quando precisa.
     *
     * A espera vive aqui, e não em cada modo de captura, porque é a única parte
     * difícil: [generateSkeletonFrame] e [generateSkeletonBitmap] diferem só no
     * que fazem com a hierarquia depois que ela existe.
     *
     * @param onComplete recebe null quando não houve o que capturar. Melhor
     *   nenhum skeleton do que um em branco marcado como válido.
     */
    private fun <T> awaitDrawableRoot(
        activity: Activity,
        produce: (Activity, View) -> T?,
        onComplete: (T?) -> Unit,
    ) {
        val rootView = activity.window.decorView.rootView

        if (!containsComposeView(rootView)) {
            // View clássica: já está desenhável.
            if (rootView.width > 0 && rootView.height > 0) {
                onComplete(produce(activity, rootView))
                return
            }
            rootView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        rootView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        onComplete(
                            if (rootView.width > 0 && rootView.height > 0) {
                                produce(activity, rootView)
                            } else {
                                null
                            }
                        )
                    }
                }
            )
            return
        }

        settleDetector.await(
            activity = activity,
            hasContent = {
                val root = activity.window?.decorView?.rootView
                root != null && root.width > 0 && root.height > 0 && composeNodeCount(root) > 0
            },
            onSettled = { settled ->
                val root = settled.window?.decorView?.rootView
                onComplete(root?.let { produce(settled, it) })
            },
        )
    }

    /**
     * Descreve a tela como retângulos, para o servidor desenhar.
     *
     * O caminho normal. Não aloca bitmap, não codifica JPEG: percorre a
     * hierarquia — que é o custo irredutível de saber o que tem na tela — e
     * devolve uns poucos KB de geometria. Ver [SkeletonFrame].
     */
    fun generateSkeletonFrame(activity: Activity, onComplete: (SkeletonFrame?) -> Unit) {
        awaitDrawableRoot(activity, ::generateSkeletonFrameSync, onComplete)
    }

    /**
     * Desenha o wireframe aqui e devolve o bitmap.
     *
     * Mantido para um backend que ainda não entenda `skeleton` no payload de
     * tela. Produz a mesma imagem que [generateSkeletonFrame] produz no
     * servidor, pagando o encode que aquele caminho não paga.
     */
    fun generateSkeletonBitmap(activity: Activity, onComplete: (Bitmap?) -> Unit) {
        awaitDrawableRoot(activity, ::generateSkeletonBitmapSync, onComplete)
    }

    /** Aborta uma espera em andamento (nova navegação, ou o usuário tocou a tela). */
    fun cancelPendingCapture() {
        settleDetector.cancel()
    }

    private fun generateSkeletonFrameSync(activity: Activity, rootView: View): SkeletonFrame? =
        frameFrom(rootView, getWindowBackgroundColor(activity))

    /**
     * A varredura e a serialização, sem a Activity.
     *
     * A Activity só servia para uma consulta de tema, e exigi-la tornava o caminho
     * caro impossível de medir sem subir uma tela inteira. Separar as duas coisas é o
     * que permite `SkeletonCostTest` cronometrar este caminho contra [bitmapFrom]
     * sobre a *mesma* hierarquia — que é a única comparação honesta entre eles.
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
     * Achata a árvore na ordem de pintura, do jeito que [renderTreeToCanvas] pinta.
     *
     * Pré-ordem, pai antes dos filhos — é o que faz o filho cair em cima. E pula
     * o nó transparente mantendo os filhos, que é como `COMPOSE_HOST` se marca:
     * ele existe para dizer onde o Compose começa, o que um wireframe não mostra.
     * Divergir daqui mudaria a imagem sem que nada avisasse.
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

    /** A mesma varredura de [frameFrom], mas desenhando aqui. Ver o kdoc de lá. */
    internal fun bitmapFrom(rootView: View, backgroundColor: Int): Bitmap? {
        if (rootView.width == 0 || rootView.height == 0) return null

        val skeletonTree = scanViewHierarchy(rootView) ?: return null
        val bitmap = createBitmap(rootView.width, rootView.height)
        val canvas = Canvas(bitmap)

        canvas.drawColor(backgroundColor)

        renderTreeToCanvas(skeletonTree, canvas)
        return bitmap
    }

    /** Só a varredura, para separar o custo irredutível do que cada modo acrescenta. */
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
     * Detecta o host de uma composição.
     *
     * `AndroidComposeView` é interno do Compose e o R8 o renomeia — em release
     * vira algo como `p0.r`, então casar o nome da classe nunca acerta. Quando
     * isso acontece a tela Compose é tratada como view clássica: o gerador
     * percorre a árvore de Views, não encontra filhos dentro do host, e produz
     * um skeleton vazio sem erro nenhum.
     *
     * `RootForTest` é a interface pública que esse host implementa (via
     * `ViewRootForTest`). Checar o tipo é resolvido pelo compilador contra a
     * classe real e sobrevive à ofuscação.
     */
    private fun isComposeView(view: View): Boolean = view is RootForTest

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

            // A árvore de semantics é a fonte de tipo que SOBREVIVE à minificação:
            // acessibilidade depende dela, então o R8 não pode removê-la. Os nomes
            // de composable, em contraste, vêm da source information do compilador,
            // que o runtime do Compose apaga em release via -assumenosideeffects.
            val semantics = SemanticsIndex((composeView as? RootForTest)?.semanticsOwner)
            if (semantics.isEmpty) {
                Log.d("SkeletonGenerator", "No semantics available; falling back to call-chain names")
            }

            val layoutInfos = rootGroup.computeLayoutInfos(semantics = semantics)

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

    /**
     * Desembrulha uma [Composition] até chegar na implementação real.
     *
     * NÃO compara nome de classe. Num build de release minificado o R8 renomeia
     * `androidx.compose.ui.platform.WrappedComposition` para algo como `p0.V0`, e
     * `androidx.compose.runtime.CompositionImpl` para `H.w` — a versão anterior
     * comparava `this::class.java.name` com o literal, nunca casava, devolvia o
     * wrapper sem desembrulhar e o [getComposer] logo em seguida retornava null.
     * Resultado: toda tela Compose gerava um skeleton vazio, em silêncio.
     *
     * Buscar por *tipo* funciona depois da ofuscação: `is Composition` é resolvido
     * pelo compilador contra a classe real, não pelo nome.
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
     * Extrai o [Composer] de uma [Composition], também por tipo.
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

    /**
     * 🆕 CORREÇÃO PRINCIPAL: Parseia recursivamente a árvore de Groups ACUMULANDO o callChain
     */
    /**
     * 🆕 CORREÇÃO: Varre a hierarquia para encontrar o tipo mais específico (Text, Image, etc)
     */
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

                // Tenta descobrir o tipo pelo nome, se não conseguir, assume CONTAINER
                val type = resolveNodeTypeFromChain(callChain)

                // Processa os filhos primeiro para saber se este nó é um "Pai" ou uma "Folha"
                val children = group.children.flatMap {
                    parseGroupTree(it, callChain) // Passa o callChain acumulado
                }.toMutableList()

                val isLeaf = children.isEmpty()

                // LÓGICA DE CORES E ESTILOS:
                // 1. Elementos específicos (Text, Image, Button, Input, Card) -> FILL com cor vibrante
                // 2. Container folha (sem filhos) -> FILL com cinza claro
                // 3. Container pai (com filhos) -> STROKE com cinza claro (apenas borda)
                val (finalColor, style) = when {
                    // Elementos específicos sempre têm cores vibrantes e são preenchidos
                    type in listOf(NodeType.TEXT, NodeType.IMAGE, NodeType.BUTTON, NodeType.INPUT, NodeType.CARD) -> {
                        Pair(defaultColors[type] ?: Color.LTGRAY, Paint.Style.FILL)
                    }
                    // Container folha (bloco de conteúdo sem filhos identificados)
                    type == NodeType.CONTAINER && isLeaf -> {
                        Pair(Color.parseColor("#E0E0E0"), Paint.Style.FILL)
                    }
                    // Container pai (apenas organizador) - apenas borda
                    type == NodeType.CONTAINER && !isLeaf -> {
                        Pair(Color.parseColor("#BDBDBD"), Paint.Style.STROKE)
                    }
                    // Outros casos
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
     * Deriva o tipo do nó a partir da árvore de semantics.
     *
     * Retorna null quando a semantics não diz nada de útil, para o chamador cair
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

            // Campo editável: EditableText, ou a ação de escrita quando o valor está vazio.
            if (config.getOrNull(SemanticsProperties.EditableText) != null ||
                config.getOrNull(SemanticsActions.SetText) != null
            ) {
                return NodeType.INPUT
            }

            if (config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true) {
                return NodeType.TEXT
            }

            // Descrição sem texto é o padrão de ícone/imagem.
            if (config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true) {
                return NodeType.IMAGE
            }

            // Clicável sem role explícito ainda é um alvo de toque.
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
     * Determina o tipo de um View Android
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

        if (node.style == Paint.Style.FILL) {
            val rectF = RectF(node.rect)
            canvas.drawRoundRect(rectF, 12f, 12f, paint)
        } else {
            canvas.drawRect(node.rect, paint)
        }

        node.children.forEach { child ->
            renderTreeToCanvas(child, canvas)
        }
    }
}

