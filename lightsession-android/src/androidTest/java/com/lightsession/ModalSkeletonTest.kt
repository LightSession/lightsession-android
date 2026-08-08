package com.lightsession

import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.mapper.SkeletonFrame
import com.lightsession.mapper.SkeletonGenerator
import curtains.Curtains
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a modal's wireframe describes the modal, and not the screen behind it.
 *
 * ## What this was
 *
 * `SkeletonGenerator` only ever walked `activity.window.decorView`, and a dialog or a bottom sheet
 * is its own window with its own view tree. So a sub-screen — `doctor/detail/{id} › Página
 * inferior` — was created correctly and then filled with a wireframe of the page *behind* the
 * sheet. Seen side by side on the screen map: the screenshot layer showed the sheet over a dimmed
 * page, and the wireframe beside it showed the page, with no sheet on it anywhere.
 *
 * ## The fixture, and why it is shaped this way
 *
 * The screen behind puts all of its content in the **top half**; a `ModalBottomSheet` occupies the
 * bottom. So "did the wireframe describe the right window" becomes a question about *where* the
 * rectangles are, which no amount of colour or theme can confuse — and it fails loudly in the
 * direction that matters, because the old behaviour puts rectangles exactly where this asserts
 * there are none.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.ModalSkeletonTest
 */
@RunWith(AndroidJUnit4::class)
class ModalSkeletonTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "ModalSkeleton"

        /** The settle detector gives up at five seconds; this has to clear that comfortably. */
        const val TIMEOUT_SECONDS = 20L
    }

    @Before
    fun attachGeometry() {
        // `frameFrom` reports the display as the frame's size. Unattached it falls back to the
        // scanned root's own size, which is precisely what this test is checking it no longer does.
        ScreenGeometry.attach(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun frameOf(overlay: View?): SkeletonFrame? {
        var result: SkeletonFrame? = null
        val done = CountDownLatch(1)
        val generator = SkeletonGenerator()
        compose.runOnUiThread {
            val onComplete: (SkeletonFrame?) -> Unit = { frame ->
                result = frame
                done.countDown()
            }
            if (overlay != null) {
                generator.generateOverlaySkeletonFrame(compose.activity, overlay, onComplete)
            } else {
                generator.generateSkeletonFrame(compose.activity, onComplete)
            }
        }
        assertTrue("the wireframe never came back", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return result
    }

    /** The modal's window, which is any attached root that is not the Activity's own. */
    private fun modalRoot(): View? {
        val base = compose.activity.window.decorView
        return Curtains.rootViews.lastOrNull { it !== base && it.isAttachedToWindow }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun a_sheets_wireframe_is_the_sheet_and_not_the_page_behind_it() {
        var open by mutableStateOf(false)
        compose.setContent {
            // Everything the page has is in its top half.
            Column(Modifier.fillMaxSize()) {
                repeat(6) { row ->
                    Text("page row $row", modifier = Modifier.fillMaxWidth().height(40.dp))
                }
                Box(Modifier.fillMaxSize())
            }
            if (open) {
                ModalBottomSheet(
                    onDismissRequest = { open = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        repeat(3) { row -> Text("sheet row $row") }
                        Box(Modifier.height(160.dp))
                    }
                }
            }
        }
        compose.waitForIdle()

        val page = frameOf(overlay = null)
        assertNotNull("the page produced no wireframe at all", page)
        val screen = ScreenGeometry.size()
        Log.i(TAG, "page: ${page!!.rects.size} rects, frame ${page.width}x${page.height}")

        // The frame is the display, not the scanned root. This held by accident before — the two
        // were the same thing for a full-screen Activity — and stops holding for an overlay window.
        assertEquals("the page frame is not the display's width", screen.width, page.width)
        assertEquals("the page frame is not the display's height", screen.height, page.height)

        val pageContent = page.rects.filter { it.top < screen.height / 2 }
        assertTrue(
            "the fixture put nothing in the top half, so it cannot tell the two windows apart",
            pageContent.size >= 3,
        )

        open = true
        compose.waitForIdle()
        // The sheet animates up; nothing about it is measurable until it has actually drawn.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val root = modalRoot()
        assertNotNull("the sheet did not open as its own window", root)
        val location = IntArray(2)
        root!!.getLocationOnScreen(location)
        // The open question this answers: whether a `ModalBottomSheet`'s decor is the whole screen
        // (scrim included) or only the sheet. It decides what "only the sheet" looks like.
        Log.i(
            TAG,
            "sheet window: ${root.width}x${root.height} at (${location[0]},${location[1]}), " +
                "screen $screen",
        )

        val sheet = frameOf(overlay = root)
        assertNotNull("the sheet produced no wireframe", sheet)
        Log.i(
            TAG,
            "sheet: ${sheet!!.rects.size} rects, frame ${sheet.width}x${sheet.height}, " +
                "tops ${sheet.rects.map { it.top }.sorted().take(6)}",
        )

        assertEquals("the sheet frame is not the display's width", screen.width, sheet.width)
        assertEquals("the sheet frame is not the display's height", screen.height, sheet.height)
        assertTrue("the sheet's wireframe is empty", sheet.rects.isNotEmpty())

        // Not just "not empty". A `ModalBottomSheet`'s window is full-screen — measured, its decor
        // is the whole display — so its own root containers span everything and would satisfy every
        // other check here on their own, leaving a frame that contains the sheet's *window* and
        // none of the sheet. What proves the content arrived is a rectangle that starts in the half
        // the sheet actually occupies.
        val sheetContent = sheet.rects.filter { it.top >= screen.height / 2 }
        assertTrue(
            "the sheet's wireframe has nothing in the bottom half, where the sheet is: " +
                sheet.rects.joinToString { "${it.kind} ${it.top}..${it.bottom}" },
            sheetContent.size >= 3,
        )
        Log.i(TAG, "sheet content in the bottom half: ${sheetContent.size} rects")

        // The assertion. A `ModalBottomSheet` sits against the bottom, so anything the wireframe
        // placed in the top half came from the page behind — which is the whole defect.
        //
        // Rectangles that span the screen are exempt: the sheet's own window may be full-screen,
        // and a scrim or root container legitimately covers everything. What must not appear is a
        // rectangle that *lives* in the top half.
        val intruders = sheet.rects.filter {
            it.top < screen.height / 2 && it.bottom < screen.height / 2
        }
        assertTrue(
            "the sheet's wireframe contains ${intruders.size} rectangle(s) confined to the top " +
                "half of the screen, where only the page behind it has content: " +
                intruders.take(5).joinToString { "${it.kind} ${it.top}..${it.bottom}" },
            intruders.isEmpty(),
        )
    }

    /**
     * With nothing open, asking for an overlay that has gone yields nothing rather than the page.
     *
     * The tempting fallback — "no overlay, use the Activity" — would reintroduce the defect
     * silently, on exactly the path where a sheet is dismissed while its own wireframe is being
     * waited for.
     */
    @Test
    fun a_dismissed_overlay_produces_no_wireframe_rather_than_the_page_behind() {
        compose.setContent { Text("page") }
        compose.waitForIdle()

        // A view that was never attached stands in for one detached mid-wait: both are "the window
        // this was going to describe is not there any more".
        val detached = View(compose.activity)
        assertNotNull("the page itself should still capture", frameOf(overlay = null))
        assertEquals(
            "a gone overlay fell back to the Activity's own wireframe",
            null,
            frameOf(overlay = detached),
        )
    }
}
