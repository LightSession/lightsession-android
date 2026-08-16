package com.lightsession

import android.graphics.Outline
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the corner radius of a dialog or a sheet is readable, or only guessable.
 *
 * The skeleton draws both as square boxes today. Rounding them by a constant would look right
 * for the apps that use the defaults and wrong for the app that deliberately squared its
 * corners — and being confidently wrong about someone's UI is the failure mode this SDK keeps
 * paying for. So before choosing a constant, this asks the two places an answer could live:
 *
 *  1. **the View outline** — `ViewOutlineProvider.getOutline` fills an `Outline` whose
 *     `getRadius()` returns a real radius for a round-rect, and a negative number for anything
 *     else. Free and exact when the rounding is done by a View background;
 *  2. **the composition** — a Compose `Surface`, `ModalBottomSheet` or `Card` receives its
 *     `Shape` as a parameter, and a parameter lives in the slot table this SDK already walks.
 *     `RoundedCornerShape` carries four `CornerSize`s that convert to px given size and density.
 *
 * Prints what each yields for a Material 3 sheet and a Compose dialog. Read the `Corners` lines.
 */
@RunWith(AndroidJUnit4::class)
class ModalCornerProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "Corners"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun printWhatASheetSaysAboutItsCorners() {
        rule.setContent {
            ModalBottomSheet(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(Modifier.fillMaxWidth().height(240.dp)) { Text("sheet content") }
            }
        }
        rule.waitForIdle()
        report("ModalBottomSheet")
    }

    @Test
    fun printWhatADialogSaysAboutItsCorners() {
        rule.setContent {
            // The Material dialog, not a bare `Dialog { Surface { } }` — that one really is
            // square, because `Surface`'s default shape is `RectangleShape`, and probing it
            // proves nothing except that the fixture was wrong.
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                confirmButton = { Text("ok") },
                title = { Text("dialog title") },
                text = { Text("dialog content") },
            )
        }
        rule.waitForIdle()
        report("AlertDialog")
    }

    private fun report(what: String) {
        rule.runOnUiThread {
            Log.i(TAG, "===== $what =====")
            val windows = windowRoots()
            Log.i(TAG, "windows attached: ${windows.size}")
            // The modal is the newest window; the Activity's own decor is the first.
            val overlay = windows.lastOrNull() ?: return@runOnUiThread

            // 1. the View outline, on the modal root and every descendant that has one
            var outlinesFound = 0
            forEachView(overlay) { view ->
                val provider = view.outlineProvider ?: return@forEachView
                val outline = Outline()
                runCatching { provider.getOutline(view, outline) }.onFailure { return@forEachView }
                if (outline.isEmpty) return@forEachView
                val radius = runCatching { outline.radius }.getOrDefault(-1f)
                if (radius > 0f) {
                    outlinesFound++
                    Log.i(
                        TAG,
                        "  outline radius=${radius}px on ${view.javaClass.simpleName} " +
                            "(${view.width}x${view.height})",
                    )
                }
            }
            Log.i(TAG, "  views reporting a rounded outline: $outlinesFound")

            // 2. the composition, through the same route the skeleton scan uses
            val shapes = shapesIn(overlay)
            Log.i(TAG, "  Shape instances in the composition: ${shapes.size}")
            val density = overlay.resources.displayMetrics.density
            for (shape in shapes.take(8)) {
                if (shape is CornerBasedShape) {
                    Log.i(
                        TAG,
                        "  ${shape.javaClass.simpleName}: topStart=${shape.topStart} " +
                            "topEnd=${shape.topEnd} bottomEnd=${shape.bottomEnd} " +
                            "bottomStart=${shape.bottomStart} (density=$density)",
                    )
                } else {
                    Log.i(TAG, "  ${shape.javaClass.name} (not corner-based)")
                }
            }
        }
        rule.waitForIdle()
    }

    @OptIn(UiToolingDataApi::class)
    private fun shapesIn(root: View): List<Shape> {
        val generator = SkeletonGenerator()
        val tree = runCatching { generator.compositionTreeOf(root) }.getOrNull()
        if (tree == null) {
            Log.i(TAG, "  NO COMPOSITION TREE for ${root.javaClass.simpleName}")
            return emptyList()
        }
        val found = ArrayList<Shape>()
        fun walk(group: Group) {
            for (datum in group.data) {
                if (datum is Shape && found.none { it === datum }) found.add(datum)
            }
            group.children.forEach { walk(it) }
        }
        runCatching { walk(tree) }
        return found
    }

    private fun windowRoots(): List<View> = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(instance) as? List<View>).orEmpty()
    }.getOrDefault(emptyList())

    private fun forEachView(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forEachView(view.getChildAt(i), block)
        }
    }
}
