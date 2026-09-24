package com.lightsession.masking

import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a view is a map, which draws its own words.
 *
 * A map paints street names, the names of shops and a pin over somebody's home into a picture it
 * renders itself — none of it is text any view in the tree holds, so the mask scan saw nothing to
 * cover. Covered whole, like a web page, since there is no telling its words from the rest of it.
 *
 * Recognised by class name, through the superclasses, because this SDK depends on no map library:
 * the public view classes of the map SDKs an app is likely to use, and, for Google's, the fragment
 * most native apps show a map with. That fragment's view is not a public class at all — it is built
 * by Play services' own code — so it is found through the fragment that owns it, which the fragment
 * library records on every fragment's root view.
 *
 * Verified on a device for Google's `MapView`, the view `google_maps_flutter` embeds, and for its
 * `SupportMapFragment`. The other names are those libraries' documented classes and have not been
 * run here; a name that is wrong matches nothing and costs nothing.
 */
internal object NativeMaps {

    private val VIEWS = setOf(
        "com.google.android.gms.maps.MapView",
        "com.mapbox.maps.MapView",
        "com.mapbox.mapboxsdk.maps.MapView",
        "org.maplibre.android.maps.MapView",
        "org.osmdroid.views.MapView",
        "com.here.sdk.mapview.MapView",
        "com.huawei.hms.maps.MapView",
    )

    private val FRAGMENTS = setOf(
        "com.google.android.gms.maps.SupportMapFragment",
        "com.google.android.gms.maps.MapFragment",
        "com.mapbox.mapboxsdk.maps.SupportMapFragment",
        "org.maplibre.android.maps.SupportMapFragment",
        "com.huawei.hms.maps.SupportMapFragment",
    )

    /** Per class, since the scan asks about every view on every captured frame. */
    private val known = ConcurrentHashMap<Class<*>, Boolean>()

    fun isMap(view: View): Boolean {
        if (isOneOf(view.javaClass, VIEWS)) return true
        val owner = view.getTag(androidx.fragment.R.id.fragment_container_view_tag) ?: return false
        return isOneOf(owner.javaClass, FRAGMENTS)
    }

    private fun isOneOf(type: Class<*>, names: Set<String>): Boolean =
        known.getOrPut(type) { extendsAny(type, VIEWS) || extendsAny(type, FRAGMENTS) } &&
            extendsAny(type, names)

    /** Whether [type] is, or descends from, a class named in [names]. */
    internal fun extendsAny(type: Class<*>, names: Set<String>): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name in names) return true
            current = current.superclass
        }
        return false
    }
}
