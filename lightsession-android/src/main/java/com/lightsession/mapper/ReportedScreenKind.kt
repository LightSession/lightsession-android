package com.lightsession.mapper

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup

/**
 * Which toolkit named a screen the host reported, for its kind in the map.
 *
 * Every host-reported screen used to be [ScreenMapperIntegration.ScreenType.REACT_NATIVE], because
 * React Native was the only host there was. A Flutter app reports its screens the same way, through
 * `setScreen`, and every one of them was stored as React Native — a label that reads as a bug to
 * whoever opens the map of a Flutter app.
 *
 * Detected rather than declared, so it is right for an app that wires the SDK by hand as much as
 * for one using the plugin, and right per screen in an app that hosts Flutter inside a native one.
 * Two questions, cheapest first. Is the Activity a Flutter one — answered from its class, on any
 * thread. If not, does its window hold a `FlutterView` — the add-to-app case, a `FlutterFragment`
 * inside an ordinary Activity — answered by walking the views, which is only done on the main
 * thread, since React Native reports from its own.
 *
 * An app with no Flutter in it asks neither: whether the embedding is on the classpath is learned
 * once, and without it the answer is React Native, as it always was.
 */
internal object ReportedScreenKind {

    private const val FLUTTER_VIEW = "io.flutter.embedding.android.FlutterView"
    private val FLUTTER_ACTIVITIES = setOf(
        "io.flutter.embedding.android.FlutterActivity",
        "io.flutter.embedding.android.FlutterFragmentActivity",
    )

    private val flutterPresent: Boolean by lazy {
        runCatching {
            Class.forName(FLUTTER_VIEW, false, ReportedScreenKind::class.java.classLoader)
        }.isSuccess
    }

    fun of(activity: Activity): ScreenMapperIntegration.ScreenType {
        if (!flutterPresent) return ScreenMapperIntegration.ScreenType.REACT_NATIVE
        val flutter = extendsAny(activity.javaClass, FLUTTER_ACTIVITIES) ||
            (Looper.myLooper() == Looper.getMainLooper() && holdsFlutterView(activity))
        return if (flutter) {
            ScreenMapperIntegration.ScreenType.FLUTTER
        } else {
            ScreenMapperIntegration.ScreenType.REACT_NATIVE
        }
    }

    /**
     * Whether [type] is, or descends from, a class named in [names].
     *
     * By name, because this SDK does not depend on Flutter and cannot refer to its classes. The
     * superclasses are walked because an app's `MainActivity` is a subclass of the embedding's
     * Activity, never the embedding's Activity itself.
     */
    internal fun extendsAny(type: Class<*>, names: Set<String>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name in names) return true
            current = current.superclass
        }
        return false
    }

    private fun holdsFlutterView(activity: Activity): Boolean {
        val root = activity.window?.peekDecorView() ?: return false
        val names = setOf(FLUTTER_VIEW)
        val pending = ArrayDeque<View>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val view = pending.removeLast()
            if (extendsAny(view.javaClass, names)) return true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
            }
        }
        return false
    }
}
