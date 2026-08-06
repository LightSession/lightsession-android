package com.lightsession

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.replay.ScreenDrawing
import curtains.phoneWindow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a dialog's pixels end up, and where its mask ends up, held against each other.
 *
 * ## What was reported, and what it turned out to be
 *
 * Two symptoms from a real app: a mask rectangle sitting a few pixels above the text inside a
 * dialog, and the screen behind the dialog contributing masks that landed on top of it. Reading
 * the code produced four candidates, two of them mutually exclusive explanations for the offset,
 * so this was written to measure rather than to argue. All four were real.
 *
 * Measured on a 1080×2400 device, three-button navigation, system bars 63 and 126:
 *
 * ```
 *                                        before        after
 * dialog window, real vs drawn            906 / 937     906 / 906
 * text top, software vs surface path      1172 / 1141   1141 / 1141
 * glyphs left visible under the mask      1212..1243    none
 * centre of a blank white dialog          #9E9E9E       #FFFFFF
 * platform AlertDialog composited         false         true
 * centred TextView: ink vs mask           132..169 / 0..71    132..169 / 114..185
 * ```
 *
 * ## Why it took a specific device to see it
 *
 * The window-placement error is `(statusBar - navBar) / 2`. Under gesture navigation both bars
 * are 63 pixels and it is exactly zero — the first run of this test reproduced nothing for that
 * reason. **Run it with three-button navigation**, or the first two cases below pass without
 * having tested anything:
 *
 * ```
 * adb shell cmd overlay disable com.android.internal.systemui.navbar.gestural
 * adb shell cmd overlay enable  com.android.internal.systemui.navbar.threebutton
 * ```
 *
 * Everything logs under `DialogMasking`, including on a pass: the numbers are the point, and a
 * device where the fixture cannot stage the fault should say so rather than go quietly green.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.DialogMaskingTest
 */
@RunWith(AndroidJUnit4::class)
class DialogMaskingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val TAG = "DialogMasking"

        /** Ink is black on white, so anything this dark is a glyph rather than a shadow. */
        const val INK_LUMA = 90

        /** A row needs this many dark pixels to count as ink, so antialiasing alone cannot. */
        const val INK_PIXELS_PER_ROW = 4
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaults = Triple(Masking.text, Masking.images, Masking.debugHighlight)

    @Before
    fun attachGeometry() {
        // Without this `ScreenGeometry` falls back to the shared metrics object; the SDK attaches
        // in `LightSession.init`, which no test goes through.
        ScreenGeometry.attach(context)
    }

    @After
    fun restorePolicy() {
        Masking.text = defaults.first
        Masking.images = defaults.second
        Masking.debugHighlight = defaults.third
    }

    // ------------------------------------------------------------------ plumbing

    /** The same reflection `ScreenDrawing` uses, so the probe sees exactly what it sees. */
    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<Pair<View, WindowManager.LayoutParams?>> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val views = cls.getDeclaredField("mViews")
            .apply { isAccessible = true }[instance] as List<View>
        val params = cls.getDeclaredField("mParams")
            .apply { isAccessible = true }[instance] as List<WindowManager.LayoutParams>
        return views.mapIndexed { index, view -> view to params.getOrNull(index) }
    }

    /**
     * What the software path used to place this window at, kept as the reference the fix is
     * measured against.
     *
     * `ScreenDrawing.calculateViewPositionOriginal` is gone — it is the defect — so the formula
     * is reproduced here rather than reflected into. A device where this and
     * `getLocationOnScreen` still agree (both system bars the same height) cannot demonstrate
     * either the bug or the fix, which is worth knowing when a run comes back clean.
     */
    private fun gravityGuess(view: View, params: WindowManager.LayoutParams?): Pair<Int, Int> {
        val screen = ScreenGeometry.size()
        if (params == null) {
            return Pair((screen.width - view.width) / 2, (screen.height - view.height) / 2)
        }
        val x = when {
            params.x != 0 -> params.x
            params.gravity and android.view.Gravity.CENTER_HORIZONTAL ==
                android.view.Gravity.CENTER_HORIZONTAL -> (screen.width - view.width) / 2
            params.gravity and android.view.Gravity.RIGHT == android.view.Gravity.RIGHT ->
                screen.width - view.width
            else -> 0
        }
        val y = when {
            params.y != 0 -> params.y
            params.gravity and android.view.Gravity.CENTER_VERTICAL ==
                android.view.Gravity.CENTER_VERTICAL -> (screen.height - view.height) / 2
            params.gravity and android.view.Gravity.BOTTOM == android.view.Gravity.BOTTOM ->
                screen.height - view.height
            else -> 0
        }
        return Pair(
            x.coerceIn(0, maxOf(0, screen.width - view.width)),
            y.coerceIn(0, maxOf(0, screen.height - view.height)),
        )
    }

    /** Whether the surface path recognises this root as a window it can copy. */
    private fun dialogWindowOf(drawing: ScreenDrawing, view: View): android.view.Window? =
        ScreenDrawing::class.java
            .getDeclaredMethod("findDialogWindow", View::class.java)
            .apply { isAccessible = true }
            .invoke(drawing, view) as android.view.Window?

    private fun forceSurfacePath(drawing: ScreenDrawing, forced: Boolean) {
        ScreenDrawing::class.java.getDeclaredField("surfaceCaptureRequired").apply {
            isAccessible = true
            setBoolean(drawing, forced)
        }
    }

    private fun locationOf(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** The Activity's own decor is the base window; anything else attached is an overlay. */
    private fun overlayRoots(): List<Pair<View, WindowManager.LayoutParams?>> {
        val base = compose.activity.window.decorView
        return windowRoots().filter { it.first !== base && it.first.visibility == View.VISIBLE }
    }

    private fun capture(drawing: ScreenDrawing, viaSurface: Boolean): Bitmap? {
        forceSurfacePath(drawing, viaSurface)
        if (!viaSurface) {
            var result: Bitmap? = null
            compose.runOnUiThread { result = drawing.captureToBitmap(1.0f) }
            return result
        }
        var result: Bitmap? = null
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            drawing.captureToBitmapAsync(1.0f, compose.activity.window) { bitmap ->
                result = bitmap
                done.countDown()
            }
        }
        assertTrue("the surface capture never came back", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun luma(pixel: Int): Int =
        (Color.red(pixel) * 30 + Color.green(pixel) * 59 + Color.blue(pixel) * 11) / 100

    /**
     * The rows of [bitmap] that hold glyphs, within a horizontal band.
     *
     * The whole height is scanned on purpose: if the capture places the dialog somewhere other
     * than where it really is, looking only inside its real bounds would find nothing and report
     * "no ink" instead of "the ink moved".
     */
    private fun inkRows(
        bitmap: Bitmap,
        fromX: Int,
        toX: Int,
        fromY: Int = 0,
        toY: Int = bitmap.height,
    ): IntRange? {
        var top = -1
        var bottom = -1
        val row = IntArray(toX - fromX)
        for (y in fromY.coerceAtLeast(0) until toY.coerceAtMost(bitmap.height)) {
            bitmap.getPixels(row, 0, row.size, fromX, y, row.size, 1)
            // Opaque *and* dark. A pooled capture buffer is erased to transparent, so anywhere the
            // hierarchy did not paint reads as black and every row counts as ink — which is how
            // the first run of this measured `0..2399` and said nothing.
            val dark = row.count { Color.alpha(it) > 250 && luma(it) < INK_LUMA }
            if (dark >= INK_PIXELS_PER_ROW) {
                if (top < 0) top = y
                bottom = y
            }
        }
        return if (top < 0) null else top..bottom
    }

    /** Writes a capture out so it can be looked at, rather than only measured. */
    private fun dump(bitmap: Bitmap, name: String) {
        val file = java.io.File(context.getExternalFilesDir(null), "$name.png")
        java.io.FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i(TAG, "wrote ${file.absolutePath}")
    }

    private fun systemBars(): android.graphics.Insets =
        compose.activity.window.decorView.rootWindowInsets
            .getInsets(WindowInsets.Type.systemBars())

    // ------------------------------------------------------- 1. window placement

    /**
     * Where the dialog window is, against where the software path computes it should be.
     *
     * `calculateViewPositionOriginal` re-derives the position from `LayoutParams.gravity` and
     * `ScreenGeometry.size()`. `Dialog` sets `Gravity.CENTER`, so it centres in the *display*,
     * while the window manager centred it in the display minus the system bars. The masks use
     * `getLocationOnScreen`, which is the truth. This prints the gap between the two.
     */
    @Test
    fun where_each_window_is_against_the_gravity_guess_that_used_to_place_it() {
        openComposeDialog()

        val drawing = ScreenDrawing()
        val bars = systemBars()
        Log.i(TAG, "screen=${ScreenGeometry.size()} systemBars=$bars " +
            "predicted-delta-y=${(bars.top - bars.bottom) / 2}")

        for ((view, params) in windowRoots()) {
            if (view.visibility != View.VISIBLE) continue
            val real = locationOf(view)
            val computed = gravityGuess(view, params)
            val isBase = view === compose.activity.window.decorView
            Log.i(
                TAG,
                "window ${view.javaClass.simpleName}${if (isBase) " (base)" else ""} " +
                    "size=${view.width}x${view.height} " +
                    "real=(${real.left},${real.top}) computed=$computed " +
                    "delta=(${real.left - computed.first},${real.top - computed.second}) " +
                    "gravity=0x${Integer.toHexString(params?.gravity ?: 0)} " +
                    "lp=(${params?.x},${params?.y},${params?.width},${params?.height})",
            )
        }
        drawing.release()
    }

    // --------------------------------------------------- 2. mask against the ink

    /**
     * The end-to-end number: where the mask would be painted, against where the text actually
     * came out in the image that was produced.
     *
     * Run on both capture paths from the same screen. If the two disagree, the offset belongs to
     * the capture path rather than to the scanner — which is the question this whole probe exists
     * to settle.
     */
    @Test
    fun a_dialogs_text_is_covered_on_both_capture_paths() {
        openComposeDialog()

        val dialogRoot = overlayRoots().firstOrNull()?.first
        assertNotNull("no overlay window; the dialog did not open", dialogRoot)
        val dialogBounds = locationOf(dialogRoot!!)
        Log.i(TAG, "dialog decor bounds = $dialogBounds")

        val maskRects = MaskScanner().scan(dialogRoot, maskText = true, maskImages = false)
        Log.i(TAG, "dialog mask rects = ${maskRects.joinToString { "${it.top}..${it.bottom}" }}")
        val maskTop = maskRects.minOfOrNull { it.top }
        val maskBottom = maskRects.maxOfOrNull { it.bottom }

        // The dialog's own horizontal band, scanned over the full height: if a capture path puts
        // the dialog somewhere other than where it is, the ink has to be findable there.
        val fromX = dialogBounds.left + dialogBounds.width() / 4
        val toX = dialogBounds.right - dialogBounds.width() / 4

        val drawing = ScreenDrawing()
        Log.i(
            TAG,
            "bars=${systemBars()} dialog real top=${dialogBounds.top} " +
                "gravity-guess top=${gravityGuess(dialogRoot, overlayRoots().first().second).second}",
        )
        val inkTops = mutableMapOf<String, Int?>()
        for (masked in booleanArrayOf(false, true)) {
            Masking.text = masked
            for (viaSurface in booleanArrayOf(false, true)) {
                val label = (if (viaSurface) "surface" else "software") +
                    (if (masked) "-masked" else "-plain")
                val frame = capture(drawing, viaSurface)
                if (frame == null) {
                    Log.w(TAG, "$label: no capture")
                    continue
                }
                // The dialog's own band only. The Activity's title bar above it and the
                // navigation bar below are both dark and opaque, and scanning the whole height
                // made the first run of this report `0..2399` and say nothing.
                val ink = inkRows(frame, fromX, toX, dialogBounds.top, dialogBounds.bottom)
                inkTops[label] = ink?.first
                Log.i(
                    TAG,
                    "$label: ${frame.width}x${frame.height} ${frame.config} " +
                        "ink=${ink?.let { "${it.first}..${it.last}" } ?: "none"} " +
                        "mask=$maskTop..$maskBottom " +
                        "delta=${if (ink != null && maskTop != null) maskTop - ink.first else null}",
                )
                dump(frame, label)
                drawing.recycleBitmap(frame)
            }
        }
        drawing.release()

        // The two paths composite completely differently — one draws the hierarchy, the other
        // copies rendered surfaces — so agreeing on where the text landed is a real check that
        // neither is placing the dialog window by anything but `getLocationOnScreen`.
        Log.i(TAG, "ink tops: $inkTops")
        val software = inkTops["software-plain"]
        val surface = inkTops["surface-plain"]
        assertNotNull("the software path found no text in the dialog", software)
        assertNotNull("the surface path found no text in the dialog", surface)
        assertTrue(
            "the two capture paths disagree about where the dialog is: software put its text " +
                "at $software, surface at $surface",
            kotlin.math.abs(software!! - surface!!) <= 2,
        )

        // And the point of all of it: with masking on, no glyph survives inside the dialog on
        // either path. This is what a mask 31 pixels too high failed.
        assertNull(
            "the software path left text visible inside the dialog at ${inkTops["software-masked"]}",
            inkTops["software-masked"],
        )
        assertNull(
            "the surface path left text visible inside the dialog at ${inkTops["surface-masked"]}",
            inkTops["surface-masked"],
        )
    }

    // ------------------------------------------------------------- 3. occlusion

    /**
     * That the screen behind the dialog is masked *on top of* the dialog.
     *
     * `maskScreen` unions the rectangles of every attached window and paints them in one pass
     * after compositing, so nothing stops a rectangle belonging to a window underneath from
     * landing on a window above it. Asserted rather than logged: it does not depend on the
     * device.
     */
    @Test
    fun the_screen_behind_is_not_masked_on_top_of_the_dialog() {
        var open by mutableStateOf(false)
        compose.setContent {
            Column(Modifier.fillMaxSize().background(ComposeColor.White)) {
                // Spaced out on purpose: a solid wall of text would mask the whole screen and the
                // resulting image could not show *where* the intruding rectangles land.
                repeat(12) { row ->
                    Text(
                        "background row $row with enough text to be worth masking",
                        color = ComposeColor.Black,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.height(120.dp))
                }
            }
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Surface(color = ComposeColor.White) {
                        Box(Modifier.size(280.dp, 200.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        val base = compose.activity.window.decorView
        val dialogRoot = overlayRoots().firstOrNull()?.first
        assertNotNull("the dialog did not open", dialogRoot)
        val dialogBounds = locationOf(dialogRoot!!)

        val behind = MaskScanner().scan(base, maskText = true, maskImages = false)
        val intruding = behind.filter { Rect.intersects(it, dialogBounds) }
        Log.i(
            TAG,
            "behind=${behind.size} rects, ${intruding.size} of them intersect the dialog " +
                "$dialogBounds: ${intruding.take(5).joinToString { "${it.top}..${it.bottom}" }}",
        )

        // The fixture has to actually stage the problem, or what follows proves nothing.
        assertTrue(
            "no rectangle from the screen behind falls inside the dialog, so this fixture " +
                "cannot tell a fixed build from a broken one",
            intruding.isNotEmpty(),
        )

        // Sampled inside one of the intruding rectangles, not at the dialog's centre. The centre
        // is the tempting choice and it is the wrong one: it only happens to sit under a
        // background rectangle if the rows behind land that way, and an earlier version of this
        // fixture spaced them out and went green against a build that had the fault.
        val intruder = intruding.first()
        val probeY = (maxOf(intruder.top, dialogBounds.top) +
            minOf(intruder.bottom, dialogBounds.bottom)) / 2
        val probeX = dialogBounds.centerX()
        Log.i(TAG, "sampling ($probeX,$probeY), inside background rect $intruder")

        // The dialog is a plain white panel with nothing in it, so mask grey there can only have
        // come from the screen underneath. Both paths, because they composite differently and
        // each had its own version of the fault.
        Masking.text = true
        val drawing = ScreenDrawing()
        for (viaSurface in booleanArrayOf(false, true)) {
            val label = if (viaSurface) "surface" else "software"
            val frame = capture(drawing, viaSurface)
            assertNotNull("$label: no capture", frame)
            val pixel = frame!!.getPixel(probeX, probeY)
            Log.i(TAG, "$label: pixel inside the dialog = #%06X".format(pixel and 0xFFFFFF))
            dump(frame, "occlusion-$label")
            drawing.recycleBitmap(frame)
            assertEquals(
                "$label: the screen behind was masked on top of the dialog",
                Color.WHITE,
                pixel,
            )
        }
        drawing.release()
    }

    // ------------------------------------------- 4. masked but never composited

    /**
     * A classic `AlertDialog` is drawn as well as masked.
     *
     * `surfaceLayers` used to recognise a window only through `DialogWindowProvider`, which is
     * Compose's interface, while the mask pass walked every attached window — so a platform
     * dialog contributed grey rectangles to an image it was absent from. `findDialogWindow` now
     * falls back to Curtains, which reflects `DecorView.mWindow`.
     *
     * That fallback is the part worth re-checking on a new platform version: `SurfaceCompositeTest`
     * records an API where a Curtains field lookup stopped working. Measured working on API 36,
     * and logged here either way so a future failure names itself.
     */
    @Test
    fun a_platform_dialog_is_composited_as_well_as_masked() {
        compose.setContent { Box(Modifier.fillMaxSize().background(ComposeColor.White)) }
        compose.waitForIdle()

        compose.runOnUiThread {
            AlertDialog.Builder(compose.activity)
                .setTitle("Confirmar")
                .setMessage("Excluir este registro?")
                .setPositiveButton("Sim", null)
                .setNegativeButton("Nao", null)
                .show()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        val drawing = ScreenDrawing()
        val overlay = overlayRoots().firstOrNull()?.first
        assertNotNull("the platform dialog did not open", overlay)

        val recognised = dialogWindowOf(drawing, overlay!!)
        val rects = MaskScanner().scan(overlay, maskText = true, maskImages = false)
        Log.i(
            TAG,
            "platform dialog: composited=${recognised != null}, masked with ${rects.size} rects " +
                "at ${rects.joinToString { "${it.top}..${it.bottom}" }}",
        )
        // Whether there is any route to this window at all. Curtains reflects `DecorView.mWindow`,
        // and `SurfaceCompositeTest` records that API 36 stopped exposing the field it reads —
        // this says whether that is still true, since it decides if defect 4 is fixable here.
        val viaCurtains = runCatching { overlay.phoneWindow }.getOrNull()
        Log.i(TAG, "platform dialog reachable via Curtains phoneWindow: ${viaCurtains != null}")
        drawing.release()

        assertTrue("nothing was masked, so the comparison says nothing", rects.isNotEmpty())
        assertNotNull(
            "the platform dialog is masked but not composited, so its rectangles land on a " +
                "frame it is absent from" +
                if (viaCurtains == null) " — and Curtains can no longer reach its window" else "",
            recognised,
        )
    }

    // --------------------------------------- 5. the TextView vertical-gravity gap

    /**
     * A `TextView` whose text is vertically centred, masked and drawn side by side.
     *
     * `MaskScanner.addTextRects` builds its rectangle from `paddingTop + getLineBounds`, while
     * `TextView.onDraw` translates by `extendedPaddingTop + getVerticalOffset()`. Nothing about
     * this needs a dialog — a laid-out view is enough — which is what makes it a clean
     * discriminator against the window-placement explanation.
     */
    @Test
    fun a_vertically_centred_TextView_is_masked_where_its_text_is() {
        val width = 900
        val height = 300
        val view = TextView(context).apply {
            text = "MMMMMMMM"
            textSize = 20f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val ink = inkRows(bitmap, 0, width)

        val rects = MaskScanner().scan(view, maskText = true, maskImages = false)
        Log.i(
            TAG,
            "centred TextView: height=$height layoutHeight=${view.layout?.height} " +
                "padTop=${view.paddingTop} extPadTop=${view.extendedPaddingTop} " +
                "ink=${ink?.let { "${it.first}..${it.last}" }} " +
                "mask=${rects.joinToString { "${it.top}..${it.bottom}" }} " +
                "delta=${if (ink != null && rects.isNotEmpty()) rects.minOf { it.top } - ink.first else null}",
        )

        // The same view with top gravity, as the control: if this one also disagrees the cause is
        // something other than the missing vertical offset.
        val top = TextView(context).apply {
            text = "MMMMMMMM"
            textSize = 20f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            gravity = android.view.Gravity.TOP
        }
        top.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        top.layout(0, 0, width, height)
        val topBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        top.draw(android.graphics.Canvas(topBitmap))
        val topInk = inkRows(topBitmap, 0, width)
        val topRects = MaskScanner().scan(top, maskText = true, maskImages = false)
        Log.i(
            TAG,
            "top-gravity TextView: ink=${topInk?.let { "${it.first}..${it.last}" }} " +
                "mask=${topRects.joinToString { "${it.top}..${it.bottom}" }} " +
                "delta=${if (topInk != null && topRects.isNotEmpty()) topRects.minOf { it.top } - topInk.first else null}",
        )

        bitmap.recycle()
        topBitmap.recycle()

        // Both cases, held to the same standard: the mask has to contain the glyphs. The
        // top-gravity one always did, which is what makes it the control — if it ever starts
        // failing, the cause is the line geometry rather than the vertical offset.
        for ((label, pair) in listOf("centred" to (ink to rects), "top" to (topInk to topRects))) {
            val (band, masks) = pair
            assertNotNull("$label: no ink was drawn, so nothing was measured", band)
            assertTrue("$label: nothing was masked", masks.isNotEmpty())
            assertTrue(
                "$label: the mask ${masks.minOf { it.top }}..${masks.maxOf { it.bottom }} does " +
                    "not cover the text ${band!!.first}..${band.last}",
                masks.minOf { it.top } <= band.first && masks.maxOf { it.bottom } >= band.last,
            )
        }
    }

    // ------------------------------------------------------------------ fixtures

    private fun openComposeDialog() {
        var open by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(ComposeColor.White))
            if (open) {
                Dialog(onDismissRequest = { open = false }) {
                    Surface(color = ComposeColor.White) {
                        Box(Modifier.size(280.dp, 200.dp), contentAlignment = Alignment.Center) {
                            Text("MMMMMMMM", color = ComposeColor.Black, fontSize = 28.sp)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        open = true
        compose.waitForIdle()
        // The dialog animates in; nothing is measurable until it has actually drawn.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
}
