package com.lightsession

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.masking.MaskScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What `maskImages` covers in a Compose screen.
 *
 * ## The defect this pinned down, and now guards
 *
 * Every other masking test in this suite builds its images with `ImageView`, so the View path was
 * measured and the Compose path never had been. Measured then: of two `Image` composables on
 * screen, `maskImages = true` produced **one** rectangle, and the one it missed was the one
 * declared decorative.
 *
 * The cause is that [MaskScanner] asks the *semantics* tree, and `androidx.compose.foundation.Image`
 * only attaches semantics when it is given a `contentDescription`:
 *
 * ```
 * val semantics = if (contentDescription != null) {
 *     Modifier.semantics { this.contentDescription = ...; this.role = Role.Image }
 * } else Modifier
 * ```
 *
 * So `contentDescription = null` publishes no node at all — nothing to find, nothing to cover.
 * That is not an exotic case: it is what the accessibility guidance tells an app to write for any
 * image that is not itself information, and what image loaders are routinely called with. Photos,
 * avatars and scanned documents are exactly the content that arrives that way.
 *
 * ## How it is fixed
 *
 * Images are read from the layout node's modifier chain — `Image` paints through `Modifier.paint`,
 * whose element holds a `Painter`, described or not. See [com.lightsession.masking.ComposeImages]
 * for why that walk is cached and what a stale rectangle would cost.
 */
@RunWith(AndroidJUnit4::class)
class ComposeImageMaskTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun content() {
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                // The positive control: text masking works, so a zero here would mean the fixture
                // is wrong rather than the image path.
                Text("a line of text, for a positive control")
                // Described for accessibility. Publishes semantics, and is found.
                Image(
                    painter = ColorPainter(Color.Red),
                    contentDescription = "a described photo",
                    modifier = Modifier.size(120.dp),
                )
                // Declared decorative, which is what most images in most apps are. Publishes
                // nothing, and is not found.
                Image(
                    painter = ColorPainter(Color.Blue),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun both_compose_images_are_masked_described_or_not() {
        content()

        var textOnly = 0
        var images: List<android.graphics.Rect> = emptyList()
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val scanner = MaskScanner()
            textOnly = scanner.scan(root, maskText = true, maskImages = false).size
            images = scanner.scan(root, maskText = false, maskImages = true)
        }
        val imagesOnly = images.size

        assertTrue("the fixture is wrong: text is not being masked either", textOnly > 0)
        assertEquals(
            "both images on screen must be covered; got $images",
            2,
            imagesOnly,
        )
    }

    /** The mechanism, pinned: semantics knows about one of the two, which is why it is not the source. */
    @Test
    fun semantics_alone_would_still_only_see_one() {
        content()

        var described = 0
        var total = 0
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val owner = (root.findComposeRoot() as? RootForTest)?.semanticsOwner
            val nodes = owner?.getAllSemanticsNodes(mergingEnabled = false).orEmpty()
            total = nodes.size
            described = nodes.count {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            }
        }

        // One described image, and no node anywhere for the decorative one. This is the whole
        // mechanism: the masker cannot cover what the tree does not mention.
        assertTrue("no semantics at all: the fixture never composed", total > 0)
        assertEquals("only the described image is in the semantics tree", 1, described)
    }

    private fun android.view.View.findComposeRoot(): android.view.View? {
        if (this is RootForTest) return this
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findComposeRoot()?.let { return it }
        }
        return null
    }

    /**
     * The rectangle follows the image when it moves.
     *
     * The safety-critical half of the cache. The composition walk is too expensive to run for every
     * captured frame, so the answer is cached and invalidated by snapshot applies — and a cache
     * that went stale would cover where the image *was*, leaving the image itself in the clear
     * somewhere else. That is worse than not having the feature, so it gets the test.
     */
    @Test
    fun a_moved_image_is_masked_where_it_now_is() {
        val pushedDown = mutableStateOf(false)
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                if (pushedDown.value) {
                    Text("a line that appeared above the image, moving it down")
                }
                Image(
                    painter = ColorPainter(Color.Blue),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
            }
        }
        rule.waitForIdle()

        val before = imageRects()
        assertEquals("one image, one rect", 1, before.size)

        rule.runOnUiThread { pushedDown.value = true }
        rule.waitForIdle()

        val after = imageRects()
        assertEquals("still one image", 1, after.size)
        assertTrue(
            "the mask did not follow the image: before=${before.first()} after=${after.first()}",
            after.first().top > before.first().top,
        )
    }

    private fun imageRects(): List<android.graphics.Rect> {
        var rects: List<android.graphics.Rect> = emptyList()
        rule.runOnUiThread {
            rects = MaskScanner().scan(
                rule.activity.window.decorView,
                maskText = false,
                maskImages = true,
            )
        }
        rule.waitForIdle()
        return rects
    }
}
