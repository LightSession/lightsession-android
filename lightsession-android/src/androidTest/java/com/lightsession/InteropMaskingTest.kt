package com.lightsession

import android.graphics.Rect
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.masking.MaskScanner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a classic View's text is covered when Compose hosts it through `AndroidView`.
 *
 * ## Why this needs a device rather than a reading
 *
 * The scan has two halves that meet at exactly one place. [MaskScanner.collect] walks real
 * `View`s and covers any `TextView` it finds; when it reaches a Compose host it hands over to
 * the semantics walk and **stops descending**, on the stated grounds that "interop views hosted
 * inside it are reported by the semantics tree". Everything turns on whether that sentence is
 * true, and that is a question about what Compose publishes at runtime — not something the SDK's
 * own source can answer. A `TextView` inside `AndroidView` is simultaneously a real child View of
 * the Compose host *and* a subtree the View walk never reaches, so if semantics does not carry its
 * text, the text is covered by nobody.
 *
 * The failure it would produce is the worst kind this SDK has: the rest of the screen masks
 * correctly, so a frame with a legible card number on it passes a glance at the dashboard.
 * `AndroidView` is not exotic — it is how a Compose app embeds a legacy form, a map, a payment
 * widget or a chart.
 *
 * The Compose `Text` beside it is the control. If neither is covered the test is broken rather
 * than the scanner; if the Compose one is covered and the interop one is not, the gap is real and
 * the boundary is exactly where it was predicted to be.
 */
@RunWith(AndroidJUnit4::class)
class InteropMaskingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Kept apart so a failure says which half of the screen went uncovered. */
    private class Views(val label: TextView, val field: EditText)

    private var views: Views? = null

    private fun Rect.covers(view: android.widget.TextView): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        // A point just inside where the text *starts*, not the view's centre. The mask is per text
        // line and exactly as wide as the text, so a short "hunter2" in a match-parent field is a
        // narrow band on the left — the view's centre sits in empty field and a correct mask does
        // not contain it. The first version of this test asserted the centre and failed a fix that
        // was working; the point that is always inside a correct text mask is the text origin.
        val textStartX = at[0] + view.compoundPaddingLeft + 4
        val centreY = at[1] + view.height / 2
        return contains(textStartX, centreY)
    }

    @Test
    fun a_classic_TextView_hosted_by_AndroidView_is_masked() {
        compose.setContent {
            Column {
                // The control: ordinary Compose text, which the semantics walk does see.
                Text("compose-side secret", modifier = Modifier.testTag("composeText"))
                AndroidView(
                    factory = { context ->
                        val label = TextView(context).apply { text = "4111 1111 1111 1111" }
                        val field = EditText(context).apply { setText("hunter2") }
                        views = Views(label, field)
                        android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            addView(label)
                            addView(field)
                        }
                    },
                )
            }
        }
        compose.waitForIdle()

        val hosted = views ?: error("AndroidView never built its content")

        val rects = compose.activity.let { activity ->
            var found: List<Rect> = emptyList()
            compose.runOnUiThread {
                found = MaskScanner().scan(
                    activity.window.decorView,
                    maskText = true,
                    maskImages = false,
                )
            }
            found
        }

        // The control first. If this fails the scan itself is broken and the interop result below
        // says nothing.
        val composeCovered = rects.any { rect ->
            // The Compose text has no View of its own, so it is checked by the count instead: a
            // working scan on this screen finds at least one rectangle.
            !rect.isEmpty
        }
        assertTrue(
            "the scan found no rectangles at all — the control failed, so this test proves nothing",
            composeCovered,
        )

        val labelCovered = rects.any { it.covers(hosted.label) }
        val fieldCovered = rects.any { it.covers(hosted.field) }

        assertTrue(
            "a TextView hosted through AndroidView was NOT masked: its text ships legible. " +
                "rects=${rects.size}, label=${hosted.label.width}x${hosted.label.height}",
            labelCovered,
        )
        assertTrue(
            "an EditText hosted through AndroidView was NOT masked: typed input ships legible",
            fieldCovered,
        )
    }
}
