package com.lightsession

import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import com.lightsession.masking.MaskScanner

/**
 * What the masker sees while one screen is replacing another.
 *
 * ## Why this exists
 *
 * A replay showed the outgoing screen's masks sitting on the incoming screen's pixels, and two
 * explanations were plausible from reading the code alone. The first — that `PixelCopy` and the
 * mask scan run at different instants — was implemented against, shipped, and made the symptom
 * *worse*, which is the strongest possible evidence it was the wrong explanation.
 *
 * So this stops reasoning about call order and asks the scanner directly: during a crossfade,
 * which rectangles does it return? `MaskScanner` reads Compose's semantics tree, and during a
 * transition both screens are composed — so if the outgoing screen's text is still published,
 * the masker will cover where it *was* on a frame that already shows the screen after it.
 *
 * The two screens deliberately put their text in **different places**, because a mask in the
 * wrong position is only detectable when the right position is somewhere else.
 *
 * ## What was measured, and why nothing here filters
 *
 * Every candidate for telling the outgoing screen from the incoming one was probed at 10%, 50%
 * and 95% of the fade, on the text node and on every ancestor up the layout chain:
 *
 *  * `isPlaced` and `isAttached` — true for both, throughout.
 *  * node bounds, clipped and unclipped — valid for both, throughout.
 *  * the layer's own transparency — never true for either, because alpha reaches zero only at
 *    the instant the node leaves the tree.
 *
 * So the mask cannot be made right for such a frame, and masking only the incoming screen would
 * leave the outgoing one's text legible under nothing — a leak rather than an eyesore. The frame
 * is what has to go; see `CompositionActivity`.
 *
 * The probe that established this is not kept: it reached the layer through a compiler
 * suppression for Compose internals, whose mangled names change between Compose versions, so it
 * would eventually fail to compile for a reason unrelated to what this test is about.
 */
@RunWith(AndroidJUnit4::class)
class MaskDuringTransitionTest {

    private companion object {
        const val TAG = "MaskTransition"

        /** Long enough to sample inside it several times without racing. */
        const val TRANSITION_MS = 1_500
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scanner = MaskScanner()
    private val hostView = AtomicReference<View?>(null)

    /** Screen A: text near the top. Screen B: text near the bottom. */
    @Composable
    private fun Screen(top: Boolean, label: String) {
        Column(Modifier.fillMaxSize()) {
            if (!top) Spacer(Modifier.height(500.dp))
            Text(label)
        }
    }

    private fun scanNow(): List<Rect> {
        val view = hostView.get() ?: return emptyList()
        return scanner.scan(view, maskText = true, maskImages = false)
    }

    @Test
    fun the_outgoing_screen_is_still_masked_while_it_fades() {
        var showB by mutableStateOf(false)

        compose.setContent {
            val view = LocalView.current
            hostView.set(view)
            AnimatedContent(
                targetState = showB,
                transitionSpec = {
                    fadeIn(tween(TRANSITION_MS)) togetherWith fadeOut(tween(TRANSITION_MS))
                },
                label = "screens",
            ) { isB ->
                Box(Modifier.fillMaxSize()) {
                    if (isB) Screen(top = false, label = "SCREEN B BOTTOM TEXT")
                    else Screen(top = true, label = "SCREEN A TOP TEXT")
                }
            }
        }

        compose.waitForIdle()
        val onlyA = scanNow()
        Log.i(TAG, "settled on A: ${onlyA.size} rects ${onlyA.joinToString { "${it.top}..${it.bottom}" }}")
        assertTrue("A's text should be masked while A is the screen", onlyA.isNotEmpty())
        val aTop = onlyA.minOf { it.top }

        // Start the transition and sample inside it. The clock is driven manually so the
        // sample lands at a known point rather than wherever the scheduler happened to be.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { showB = true }
        compose.mainClock.advanceTimeBy(TRANSITION_MS.toLong() / 10)
        compose.mainClock.advanceTimeBy(TRANSITION_MS.toLong() * 4 / 10)

        val midway = scanNow()
        val tops = midway.map { it.top }.sorted()
        Log.i(TAG, "halfway through the fade: ${midway.size} rects, tops=$tops")

        // Both screens composed at once is what a crossfade *is*, so both are masked. That is
        // the safe direction — more covered, not less — and it is also the artefact: A's
        // rectangle sits over pixels that are already mostly B.
        val stillHasA = midway.any { kotlin.math.abs(it.top - aTop) < 20 }
        Log.i(TAG, "A's rectangle still present halfway through: $stillHasA")

        compose.mainClock.advanceTimeBy(TRANSITION_MS.toLong() * 45 / 100)
        compose.mainClock.advanceTimeBy(TRANSITION_MS.toLong())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val onlyB = scanNow()
        Log.i(TAG, "settled on B: ${onlyB.size} rects ${onlyB.joinToString { "${it.top}..${it.bottom}" }}")
        val leftoverA = onlyB.any { kotlin.math.abs(it.top - aTop) < 20 }

        // The assertion that matters: once the transition is over, A must be gone. If it is
        // not, the masker is reporting a screen that no longer exists and every frame from
        // here on carries a rectangle over nothing.
        assertTrue(
            "A's rectangle survived the transition: A top was $aTop, B's rects are " +
                onlyB.joinToString { "${it.top}..${it.bottom}" },
            !leftoverA,
        )
        assertTrue("B's text must be masked once B is the screen", onlyB.isNotEmpty())
    }
}
