package com.lightsession

import android.graphics.Rect
import android.view.View
import android.webkit.WebView
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
 * That a web page is covered, since nothing on it is a view the walk can read.
 *
 * On a device because a WebView cannot be built without one, and because the walk it has to
 * survive is the real one: a WebView is a ViewGroup, and walked into as one it contributes nothing.
 */
@RunWith(AndroidJUnit4::class)
class WebViewMaskingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A screen with a title above a web page, laid out as a device would lay it out. */
    private fun screen(webVisibility: Int = View.VISIBLE): FrameLayout {
        lateinit var root: FrameLayout
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = FrameLayout(context).apply {
                addView(TextView(context).apply { text = "Order confirmation" }, FrameLayout.LayoutParams(600, 80))
                addView(
                    WebView(context).apply {
                        visibility = webVisibility
                        loadData("<p>Maria Silva, card ending 4242</p>", "text/html", "utf-8")
                    },
                    FrameLayout.LayoutParams(1080, 1200).apply { topMargin = 200 },
                )
            }
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1080, 2000)
        }
        return root
    }

    private fun scan(root: View, text: Boolean, images: Boolean): List<Rect> {
        var rects: List<Rect> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rects = MaskScanner().scan(root, maskText = text, maskImages = images)
        }
        return rects
    }

    @Test
    fun a_web_page_is_covered_whole_when_text_is_masked() {
        val root = screen()
        val page = root.getChildAt(1)
        val at = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { page.getLocationOnScreen(at) }

        val rects = scan(root, text = true, images = false)
        val covering = rects.filter { it.width() == 1080 && it.height() == 1200 }
        assertEquals("the page, once and whole: $rects", 1, covering.size)
        assertEquals("over the page, not beside it", Rect(at[0], at[1], at[0] + 1080, at[1] + 1200), covering.single())
    }

    @Test
    fun a_web_page_is_covered_when_only_images_are_masked() {
        // A page carries pictures as well as words.
        val rects = scan(screen(), text = false, images = true)
        assertEquals(listOf(1080 to 1200), rects.map { it.width() to it.height() })
    }

    @Test
    fun a_hidden_web_page_is_not_covered() {
        val rects = scan(screen(webVisibility = View.GONE), text = true, images = false)
        assertTrue("nothing the size of the page: $rects", rects.none { it.width() == 1080 && it.height() == 1200 })
    }

    @Test
    fun nothing_is_covered_when_nothing_is_masked() {
        assertTrue(scan(screen(), text = false, images = false).isEmpty())
    }
}
