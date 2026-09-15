package com.lightsession

import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.masking.MaskScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the masker finds in a screen whose content is drawn into a surface.
 *
 * Flutter, Unity, a video player, a map view and a WebGL canvas all share one shape: the app's
 * content is painted into a `SurfaceView` or `TextureView` rather than composed from `View`s. The
 * masker walks `View`s (and, for Compose, the semantics tree). Neither sees inside a surface, so
 * there is nothing on such a screen it can recognise as text.
 *
 * ## Why an empty result is the dangerous one
 *
 * The interesting half is not that the scan finds nothing — it is what "nothing" means downstream.
 * `MaskScanner` throws when a scan *fails*, and `ScreenDrawing` drops the frame when it does,
 * precisely so that "found nothing" and "failed" cannot be confused. But a surface-rendered screen
 * is not a failure: the walk completes and returns an empty list, which the capture path reads as
 * "nothing to cover" and ships. The frame then carries every word on the screen, and no warning is
 * logged anywhere.
 *
 * That is the reason `lightsession_flutter` must fail closed rather than trust the default: an
 * app whose screens the masker cannot read must not have those screens recorded at all. This test
 * is the evidence for that decision, kept here — beside the masker whose behaviour it describes —
 * rather than in the plugin that acts on it.
 *
 * The `TextView` sibling is the control. It is outside the surface and must be covered; if it is
 * not, the scan is broken and the empty result inside proves nothing.
 */
@RunWith(AndroidJUnit4::class)
class SurfaceContentMaskingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun a_screen_drawn_into_a_surface_yields_no_masks_and_no_failure() {
        lateinit var control: TextView
        lateinit var root: FrameLayout

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = FrameLayout(context).apply {
                // The content a surface-rendering toolkit puts on screen. Nothing inside it is a
                // View: the pixels come from the surface, which the walk cannot enter.
                addView(SurfaceView(context), FrameLayout.LayoutParams(1080, 1800))
                addView(TextureView(context), FrameLayout.LayoutParams(1080, 200))
                // The control, and the only thing here the masker can recognise.
                control = TextView(context).apply { text = "4111 1111 1111 1111" }
                addView(control, FrameLayout.LayoutParams(600, 80))
            }
            root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1080, 2000)
        }

        val withControl = MaskScanner().scan(root, maskText = true, maskImages = false)

        // The control proves the scan works on this hierarchy at all.
        assertTrue(
            "the masker found nothing even for a plain TextView — the scan is broken, so the " +
                "surface result below would prove nothing",
            withControl.isNotEmpty(),
        )

        // Now the same screen without anything the masker can read — which is every screen of a
        // surface-rendered app.
        lateinit var surfaceOnly: FrameLayout
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            surfaceOnly = FrameLayout(context).apply {
                addView(SurfaceView(context), FrameLayout.LayoutParams(1080, 1800))
                addView(TextureView(context), FrameLayout.LayoutParams(1080, 200))
            }
            surfaceOnly.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.EXACTLY),
            )
            surfaceOnly.layout(0, 0, 1080, 2000)
        }

        // Returns, rather than throws. That is the whole finding: the capture path treats this as
        // "nothing to cover" and ships the frame, where a thrown scan would have dropped it.
        val masks = MaskScanner().scan(surfaceOnly, maskText = true, maskImages = false)

        assertEquals(
            "a surface-rendered screen produced mask rectangles, which would mean the masker can " +
                "see inside a surface after all — if so, the plugin's fail-closed rule can be relaxed",
            0,
            masks.size,
        )
    }
}
