package com.lightsession.mapper

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.android.FlutterView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a host-reported screen is recognised as a Flutter one.
 *
 * By name, since the SDK cannot refer to the embedding's classes, and through the superclasses,
 * since an app's Activity is always its own subclass of the embedding's.
 */
class ReportedScreenKindTest {

    private val activities = setOf(
        "io.flutter.embedding.android.FlutterActivity",
        "io.flutter.embedding.android.FlutterFragmentActivity",
    )

    @Test
    fun `an app's own Flutter activity is recognised through its superclass`() {
        assertTrue(ReportedScreenKind.extendsAny(MainActivity::class.java, activities))
        assertTrue(ReportedScreenKind.extendsAny(FragmentMainActivity::class.java, activities))
    }

    @Test
    fun `however deep the app's own hierarchy goes`() {
        assertTrue(ReportedScreenKind.extendsAny(BrandedActivity::class.java, activities))
    }

    @Test
    fun `an activity that is not a Flutter one is not`() {
        assertFalse(ReportedScreenKind.extendsAny(ReactActivity::class.java, activities))
        assertFalse(ReportedScreenKind.extendsAny(Any::class.java, activities))
    }

    @Test
    fun `a view is matched the same way, for a Flutter screen inside a native activity`() {
        val view = setOf("io.flutter.embedding.android.FlutterView")
        assertTrue(ReportedScreenKind.extendsAny(TexturedFlutterView::class.java, view))
        assertFalse(ReportedScreenKind.extendsAny(ReactRootView::class.java, view))
    }

    @Test
    fun `a class that only borrows the name is not the embedding's`() {
        // Same simple name, another package: a match on `simpleName` would claim it.
        assertFalse(ReportedScreenKind.extendsAny(com.example.FlutterActivity::class.java, activities))
    }

    private class MainActivity : FlutterActivity()
    private class FragmentMainActivity : FlutterFragmentActivity()
    private open class BaseAppActivity : FlutterActivity()
    private class BrandedActivity : BaseAppActivity()
    private class ReactActivity
    private class TexturedFlutterView : FlutterView()
    private class ReactRootView
}
