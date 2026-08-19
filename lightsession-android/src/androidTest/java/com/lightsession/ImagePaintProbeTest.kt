package com.lightsession

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import android.util.SparseArray
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.ComposeLayoutInfo
import com.lightsession.mapper.SkeletonGenerator
import com.lightsession.mapper.computeLayoutInfos
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a decorative Compose image leaves behind, and what finding it would cost.
 *
 * The masker asks the semantics tree, and an `Image(contentDescription = null)` publishes no
 * semantics at all — so `maskImages = true` covers the described image and leaves the decorative
 * one in the clear. The corner-radius work found that a layout node's **modifier chain** is
 * reachable, crosses subcompositions and carries the node's own bounds, so this asks whether the
 * painter is reachable the same way.
 *
 * It also times both walks, because `MaskScanner`'s kdoc promises to be cheap enough to run on
 * every captured frame and the layout walk is the one the skeleton pays once per screen.
 */
@RunWith(AndroidJUnit4::class)
class ImagePaintProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "ImgPaint"
    }

    @OptIn(UiToolingDataApi::class)
    @Test
    fun printWhatCarriesTheImage() {
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                repeat(20) { Text("filler line $it to make the screen realistic") }
                Image(
                    painter = ColorPainter(Color.Red),
                    contentDescription = "a described photo",
                    modifier = Modifier.size(120.dp),
                )
                Image(
                    painter = ColorPainter(Color.Blue),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
        rule.waitForIdle()

        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val composeView = findComposeView(root) ?: return@runOnUiThread
            val tree = SkeletonGenerator().compositionTreeOf(composeView) ?: return@runOnUiThread

            // 1. what does each layout node's modifier chain hold that could name an image?
            var nodes = 0
            fun walk(info: ComposeLayoutInfo) {
                when (info) {
                    is ComposeLayoutInfo.LayoutNodeInfo -> {
                        nodes++
                        for (m in info.modifiers) {
                            val interesting = m.javaClass.declaredFields.filter { f ->
                                val n = f.type.name
                                "Painter" in n || "ImageBitmap" in n || "Brush" in n ||
                                    "Vector" in n || "ColorFilter" in n
                            }
                            if (interesting.isNotEmpty()) {
                                val values = interesting.map { f ->
                                    runCatching { f.isAccessible = true; f.get(m) }.getOrNull()
                                        ?.javaClass?.simpleName
                                }
                                Log.i(
                                    TAG,
                                    "  PAINT node='${info.name}' ${info.bounds} " +
                                        "modifier=${m.javaClass.simpleName} " +
                                        "fields=${interesting.map { it.type.simpleName }} " +
                                        "values=$values",
                                )
                            }
                        }
                        info.children.forEach { walk(it) }
                    }
                    is ComposeLayoutInfo.SubcompositionInfo -> info.children.forEach { walk(it) }
                    is ComposeLayoutInfo.AndroidViewInfo -> Unit
                }
            }
            tree.computeLayoutInfos().forEach { walk(it) }
            Log.i(TAG, "layout nodes walked = $nodes")

            // 2. what each walk costs, warm, on this screen
            val semanticsOwner = (composeView as? RootForTest)?.semanticsOwner
            repeat(3) {
                semanticsOwner?.getAllSemanticsNodes(mergingEnabled = false)
                SkeletonGenerator().compositionTreeOf(composeView)?.computeLayoutInfos()?.count()
            }
            val semanticsNs = timeOf(10) {
                semanticsOwner?.getAllSemanticsNodes(mergingEnabled = false)?.size
            }
            val layoutNs = timeOf(10) {
                SkeletonGenerator().compositionTreeOf(composeView)?.computeLayoutInfos()?.count()
            }
            val treeNs = timeOf(10) { SkeletonGenerator().compositionTreeOf(composeView) }
            val cached = SkeletonGenerator().compositionTreeOf(composeView)!!
            val infosNs = timeOf(10) { cached.computeLayoutInfos().count() }
            Log.i(TAG, "semantics walk = ${semanticsNs / 1000}us   layout walk = ${layoutNs / 1000}us")
            Log.i(TAG, "  split: compositionTreeOf = ${treeNs / 1000}us   computeLayoutInfos = ${infosNs / 1000}us")

            // Reflection versus asTree: the first is memoisable, the second is not.
            val composer = composerOf(composeView)
            val reflectionNs = timeOf(10) { composerOf(composeView) }
            val asTreeNs = timeOf(10) { composer?.compositionData?.asTree() }
            Log.i(TAG, "  reflection chain = ${reflectionNs / 1000}us   asTree = ${asTreeNs / 1000}us")

            // What the masker now costs per captured frame, which is the number that decided the
            // cache: cold once, then warm for as long as nothing writes state.
            val scanner = com.lightsession.masking.MaskScanner()
            val cold = timeOf(1) { scanner.scan(root, maskText = true, maskImages = true) }
            val warm = timeOf(10) { scanner.scan(root, maskText = true, maskImages = true) }
            val textOnly = timeOf(10) { scanner.scan(root, maskText = true, maskImages = false) }
            Log.i(
                TAG,
                "  mask scan: cold=${cold / 1000}us  warm=${warm / 1000}us  " +
                    "text-only=${textOnly / 1000}us",
            )
        }
        rule.waitForIdle()
    }

    private inline fun timeOf(times: Int, block: () -> Unit): Long {
        val start = System.nanoTime()
        repeat(times) { block() }
        return (System.nanoTime() - start) / times
    }

    /** The same chain `SkeletonGenerator.compositionTreeOf` walks, to time it apart from asTree. */
    private fun composerOf(composeView: View): Composer? = runCatching {
        val tags = View::class.java.getDeclaredField("mKeyedTags").also { it.isAccessible = true }
            .get(composeView) as? SparseArray<*> ?: return null
        var composition: Composition? = null
        for (i in 0 until tags.size()) (tags.valueAt(i) as? Composition)?.let { composition = it }
        var current = composition ?: return null
        repeat(10) {
            val inner = current.javaClass.declaredFields.asSequence().mapNotNull { f ->
                runCatching { f.isAccessible = true; f.get(current) as? Composition }.getOrNull()
            }.firstOrNull { it !== current } ?: return@repeat
            current = inner
        }
        current.javaClass.declaredFields.asSequence().mapNotNull { f ->
            runCatching { f.isAccessible = true; f.get(current) as? Composer }.getOrNull()
        }.firstOrNull()
    }.getOrNull()

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findComposeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
