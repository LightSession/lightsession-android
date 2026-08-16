package com.lightsession

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a modal's wireframe carries the corners the app actually asked for.
 *
 * The skeleton drew every dialog and sheet as a hard-edged box. Rounding them by a constant would
 * look right for the apps that use the defaults and wrong for the app that squared its corners on
 * purpose, so the radii are read from the composition instead — see [com.lightsession.mapper.CornerShapes],
 * and `ModalCornerProbeTest` for the measurement that showed the View outline knows nothing about
 * a Compose modal.
 *
 * These assert the three shapes an app actually produces: rounded at the top, rounded all round,
 * and genuinely square. The third is the one that makes the other two safe to trust.
 */
@RunWith(AndroidJUnit4::class)
class ModalCornerRadiusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The newest window is the modal; the Activity's own decor is the first. */
    private fun overlayRoot(): View? = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(instance) as? List<View>)?.lastOrNull()
    }.getOrNull()

    /** Every rect of the modal's own wireframe that carries radii. */
    private fun roundedRects(): List<com.lightsession.mapper.SkeletonRect> {
        var rects: List<com.lightsession.mapper.SkeletonRect> = emptyList()
        rule.runOnUiThread {
            val root = overlayRoot() ?: return@runOnUiThread
            val frame = SkeletonGenerator().frameFrom(root, backgroundColor = 0, overlay = true)
            rects = frame?.rects.orEmpty().filter { it.radii != null }
        }
        rule.waitForIdle()
        return rects
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun a_sheet_is_rounded_at_the_top_only() {
        rule.setContent {
            ModalBottomSheet(
                onDismissRequest = {},
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(Modifier.fillMaxWidth().height(240.dp)) { Text("sheet content") }
            }
        }
        rule.waitForIdle()

        val rounded = roundedRects()
        assertTrue("no rect carries corner radii", rounded.isNotEmpty())

        val radii = rounded.first().radii
        assertNotNull(radii)
        // 28dp is Material 3's sheet corner. Asserted as "top rounded, bottom square" rather than
        // as a pixel count, so a theme change or a different density does not fail this.
        assertTrue("the top is not rounded: $radii", radii!!.topLeft > 0 && radii.topRight > 0)
        assertEquals("a sheet's bottom corners are square", 0, radii.bottomLeft)
        assertEquals("a sheet's bottom corners are square", 0, radii.bottomRight)
    }

    @Test
    fun a_material_dialog_is_rounded_on_every_corner() {
        rule.setContent {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = { Text("ok") },
                title = { Text("dialog title") },
                text = { Text("dialog content") },
            )
        }
        rule.waitForIdle()

        val radii = roundedRects().firstOrNull()?.radii
        assertNotNull("no rect carries corner radii", radii)
        assertTrue(
            "a Material dialog is rounded on all four corners: $radii",
            radii!!.topLeft > 0 && radii.topRight > 0 &&
                radii.bottomLeft > 0 && radii.bottomRight > 0,
        )
    }

    /**
     * The half that makes the feature honest.
     *
     * `Surface`'s default shape is `RectangleShape`, so this dialog really is square — and an app
     * that squared its corners deliberately produces exactly this. Reporting nothing here is what
     * distinguishes reading the app's shape from assuming a house style.
     */
    @Test
    fun a_square_dialog_reports_no_radii() {
        rule.setContent {
            Dialog(onDismissRequest = {}) {
                Surface {
                    Column(Modifier.fillMaxWidth().height(160.dp)) { Text("square content") }
                }
            }
        }
        rule.waitForIdle()

        assertTrue("a square dialog reported corner radii", roundedRects().isEmpty())
    }

    /**
     * A rounded thing that is not a modal is rounded too, and the rest of the screen is not.
     *
     * Keyed by bounds rather than by "this is the modal", so a rounded card inside a sheet gets its
     * own corners — and, just as importantly, the text and containers around it get none.
     */
    @Test
    fun radii_land_on_the_rounded_rect_and_nowhere_else() {
        rule.setContent {
            Column(Modifier.fillMaxWidth()) {
                Text("a line of text, which is not rounded")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                ) {
                    Text("inside the rounded surface")
                }
            }
        }
        rule.waitForIdle()

        var rounded = 0
        var total = 0
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val frame = SkeletonGenerator().frameFrom(root, backgroundColor = 0)
            val rects = frame?.rects.orEmpty()
            total = rects.size
            rounded = rects.count { it.radii != null }
        }
        rule.waitForIdle()

        assertTrue("the fixture produced no wireframe", total > 0)
        assertEquals("exactly the one rounded surface should carry radii", 1, rounded)
    }

    /** Square rects say nothing on the wire: the field is absent, not zero. */
    @Test
    fun a_square_rect_omits_the_field() {
        rule.setContent { Text("plain", Modifier.fillMaxWidth()) }
        rule.waitForIdle()

        var json = ""
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val frame = SkeletonGenerator().frameFrom(root, backgroundColor = 0)
            json = frame?.toJson()?.toString().orEmpty()
        }
        rule.waitForIdle()

        assertTrue("no frame was produced", json.isNotEmpty())
        assertNull(
            "a square screen should not mention corners at all",
            json.takeIf { it.contains("\"rad\"") },
        )
    }
}
