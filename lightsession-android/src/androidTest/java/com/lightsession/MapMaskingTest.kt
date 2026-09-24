package com.lightsession

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.SupportMapFragment
import com.lightsession.masking.MaskScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a map is covered, since it paints its words into a picture of its own.
 *
 * On a device because the walk it has to survive is the real one over real views. The map classes
 * are stand-ins under Google's names; see `FakeMaps.kt`.
 */
@RunWith(AndroidJUnit4::class)
class MapMaskingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** An app's own map, a subclass of the library's, which is how most apps hold one. */
    private class StoreLocatorMap(context: Context) : MapView(context)

    private fun screenWith(make: (Context) -> View): Pair<FrameLayout, View> {
        lateinit var root: FrameLayout
        lateinit var map: View
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            map = make(context)
            root = FrameLayout(context).apply {
                addView(map, FrameLayout.LayoutParams(1080, 1500).apply { topMargin = 300 })
            }
            root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 1080, 2000)
        }
        return root to map
    }

    private fun coveredWhole(root: View, map: View, text: Boolean = true, images: Boolean = false): Boolean {
        var rects: List<Rect> = emptyList()
        val at = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rects = MaskScanner().scan(root, maskText = text, maskImages = images)
            map.getLocationOnScreen(at)
        }
        return rects == listOf(Rect(at[0], at[1], at[0] + map.width, at[1] + map.height))
    }

    @Test
    fun a_map_view_is_covered_whole() {
        val (root, map) = screenWith { MapView(it) }
        assertTrue(coveredWhole(root, map))
    }

    @Test
    fun an_apps_own_map_view_is_covered_too() {
        val (root, map) = screenWith { StoreLocatorMap(it) }
        assertTrue(coveredWhole(root, map))
    }

    @Test
    fun a_map_is_covered_when_only_images_are_masked() {
        val (root, map) = screenWith { MapView(it) }
        assertTrue(coveredWhole(root, map, text = false, images = true))
    }

    @Test
    fun the_view_of_a_map_fragment_is_covered() {
        // What Play services builds for the fragment is not a public class; the fragment that owns
        // it is, and the fragment library records it on the view.
        val (root, map) = screenWith { context ->
            FrameLayout(context).apply {
                setTag(androidx.fragment.R.id.fragment_container_view_tag, SupportMapFragment())
            }
        }
        assertTrue(coveredWhole(root, map))
    }

    @Test
    fun a_plain_container_is_not_a_map() {
        val (root, _) = screenWith { FrameLayout(it) }
        var rects: List<Rect> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            rects = MaskScanner().scan(root, maskText = true, maskImages = false)
        }
        assertEquals(emptyList<Rect>(), rects)
    }
}
