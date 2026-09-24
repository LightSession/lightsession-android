package com.sample.lightsession

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

/**
 * A map in Google's fragment, which is how most native apps show one.
 *
 * Its words — street names, a shop, the pin over somebody's home — are painted into the map's own
 * picture, and the view Play services builds for the fragment is not a class anything can name. It
 * is here to be recorded: the map has to come out covered, in the replay and in the screen map.
 */
class MapActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = R.id.map_container }
        setContentView(container)
        val map = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.map_container, map).commitNow()
        map.getMapAsync { google ->
            val home = LatLng(-23.5613, -46.6565)
            google.addMarker(MarkerOptions().position(home).title("Home"))
            google.moveCamera(CameraUpdateFactory.newLatLngZoom(home, 16f))
        }
    }
}
