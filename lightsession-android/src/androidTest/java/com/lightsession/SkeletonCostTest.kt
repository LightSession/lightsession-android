package com.lightsession

import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `WireframeMode.RECTS` actually saves the host app, measured.
 *
 * The claim is that sending rectangles instead of an encoded wireframe makes the SDK
 * cheaper. That claim is easy to overstate, because the two paths share their most
 * expensive step: **walking the view hierarchy**. Both have to do it, and no change to
 * the wire format makes it cheaper. So the honest question is not "how fast is the new
 * path" but "what fraction of the old path's cost was avoidable", and answering it
 * needs all three numbers separately:
 *
 *  * the hierarchy walk — irreducible, paid by both;
 *  * `+ draw to a Bitmap, JPEG-encode, Base64` — what `BITMAP` adds;
 *  * `+ flatten and serialise` — what `RECTS` adds.
 *
 * Both paths run over the *same* hierarchy, in the same process, so the difference is
 * the feature and nothing else.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     --tests '*SkeletonCostTest'
 * and read the `SkeletonCost` lines in logcat.
 */
@RunWith(AndroidJUnit4::class)
class SkeletonCostTest {

    private companion object {
        const val TAG = "SkeletonCost"

        /** A 1080×2400 phone, the resolution the screen map captures at. */
        const val WIDTH = 1080
        const val HEIGHT = 2400

        const val WARMUP = 3
        const val RUNS = 15
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A hierarchy with the shape a real screen has.
     *
     * Depth matters as much as node count — `scanViewHierarchy` recurses, and a flat
     * row of 200 views is a different cost from 200 views nested eight deep. This is a
     * toolbar, a scrolling column of cards each holding an image, two lines of text and
     * a button, and a bottom bar: roughly what a wallet's home screen looks like.
     */
    private fun buildScreen(rows: Int): View {
        val root = FrameLayout(context)
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column)

        column.addView(TextView(context).apply { text = "Toolbar" })

        repeat(rows) { index ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.WHITE)
            }
            card.addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96)
            })
            val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            lines.addView(TextView(context).apply { text = "Row $index" })
            lines.addView(TextView(context).apply { text = "subtitle" })
            card.addView(lines)
            card.addView(Button(context).apply { text = "Go" })
            column.addView(card)
        }

        column.addView(EditText(context).apply { setText("amount") })
        column.addView(Button(context).apply { text = "Send" })

        // Measured and laid out by hand: `scanViewHierarchy` skips anything with no
        // dimensions, so an unlaid-out tree would measure an empty walk and report a
        // flattering number.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, WIDTH, HEIGHT)
        return root
    }

    private fun countViews(view: View): Int =
        1 + if (view is ViewGroup) (0 until view.childCount).sumOf { countViews(view.getChildAt(it)) } else 0

    private inline fun median(block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val samples = DoubleArray(RUNS) {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000.0
        }
        samples.sort()
        return samples[RUNS / 2]
    }

    @Test
    fun rects_avoid_the_encode_but_not_the_walk() {
        val generator = SkeletonGenerator()
        val background = Color.WHITE

        for (rows in intArrayOf(8, 20)) {
            val screen = buildScreen(rows)
            val views = countViews(screen)
            val rects = generator.scanOnly(screen)

            val walk = median { generator.scanOnly(screen) }

            // The whole old path: walk, allocate a full-resolution bitmap, draw every
            // rect, JPEG-encode, Base64. This is what the device used to pay per screen.
            var jpegBytes = 0
            var base64Chars = 0
            val bitmapPath = median {
                val bitmap = generator.bitmapFrom(screen, background)!!
                val encoded = generator.bitmapToBase64(bitmap)
                base64Chars = encoded.length
                jpegBytes = encoded.length / 4 * 3
                bitmap.recycle()
            }

            // The whole new path: walk, flatten, serialise.
            var payloadBytes = 0
            val rectsPath = median {
                val frame = generator.frameFrom(screen, background)!!
                payloadBytes = frame.toJson().toString().toByteArray().size
            }

            Log.w(TAG, "=== $views views / $rects rects, ${WIDTH}x$HEIGHT, median of $RUNS ===")
            Log.w(TAG, String.format("  hierarchy walk (both pay)  %8.2f ms", walk))
            Log.w(TAG, String.format("  BITMAP path total          %8.2f ms   %6d B upload", bitmapPath, base64Chars))
            Log.w(TAG, String.format("  RECTS  path total          %8.2f ms   %6d B upload", rectsPath, payloadBytes))
            Log.w(TAG, String.format("  saved per screen           %8.2f ms   %6d B  (%.1fx less upload)",
                bitmapPath - rectsPath, base64Chars - payloadBytes,
                base64Chars.toDouble() / payloadBytes))
            Log.w(TAG, String.format("  the walk is %.0f%% of the new path — the part no wire format can fix",
                walk / rectsPath * 100))
            Log.w(TAG, String.format("  jpeg ~%d B before base64 (+33%% on the wire)", jpegBytes))

            // Asserted, not merely printed, so a regression fails rather than scrolling
            // past in logcat.
            //
            // Time is the claim that holds unconditionally: the encode is bound by
            // resolution, so skipping it saves the same milliseconds on every screen.
            assertTrue(
                "RECTS ($rectsPath ms) is not faster than BITMAP ($bitmapPath ms)",
                rectsPath < bitmapPath
            )
            // Bytes are only asserted to be *smaller*, deliberately.
            //
            // A first version of this demanded 5x and failed at 7810 B against 27656 B,
            // which was the test correcting me rather than the other way round: the
            // BITMAP path uploads a JPEG of the *wireframe* — flat coloured rectangles,
            // which JPEG compresses very well — not a JPEG of the real screen. The
            // 80 KB figure quoted from `MaskingCostTest` is a textured screenshot,
            // which is the right frame for the masking question and the wrong one for
            // this. The rect payload also grows with node count while the JPEG grows
            // with resolution, so the ratio is a property of the screen, not of the
            // feature. See the logged numbers.
            assertTrue(
                "RECTS payload ($payloadBytes B) is not smaller than BITMAP ($base64Chars B)",
                payloadBytes < base64Chars
            )
        }
    }
}
