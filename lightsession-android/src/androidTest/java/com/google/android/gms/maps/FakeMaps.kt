package com.google.android.gms.maps

import android.content.Context
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * Stand-ins named like Google's map classes, for the test that a map is covered. The SDK depends on
 * no map library and recognises one by name, so the names are what a test has to give it.
 */
open class MapView(context: Context) : FrameLayout(context)

class SupportMapFragment : Fragment()
