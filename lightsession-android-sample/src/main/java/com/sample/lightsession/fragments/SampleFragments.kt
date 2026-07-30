package com.sample.lightsession.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.sample.lightsession.R

/**
 * The fragments the two fragment-based graphs are built from.
 *
 * Views are built in code rather than inflated from layout XML. There is nothing to demonstrate in a
 * layout file here — what these screens exist to exercise is the *mapper's* path through a
 * `NavHostFragment`, and a dozen small XML files would be a dozen files nobody reads.
 */

/** A plain View-based destination. No Compose in this one. */
class XmlOneFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = column(inflater) { root ->
        root.addView(heading(inflater, "Fragment one (XML views)"))
        root.addView(
            body(
                inflater,
                "A conventional Navigation destination. The SDK finds this graph's NavController " +
                    "through the FragmentManager, so this is a screen with no integration code.",
            ),
        )
        root.addView(
            button(inflater, "Next destination") {
                // Whichever graph this fragment was loaded into. Both name their action the same
                // way, so one fragment serves both the conventional and the hybrid graph.
                val nav = findNavController()
                val action = nav.currentDestination
                    ?.getAction(R.id.toFragmentTwo)
                    ?.let { R.id.toFragmentTwo }
                    ?: R.id.toHybridCompose
                nav.navigate(action)
            },
        )
    }
}

/** The second View-based destination. Reached by a real navigation, not by being first. */
class XmlTwoFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = column(inflater) { root ->
        root.addView(heading(inflater, "Fragment two (XML views)"))
        root.addView(
            body(
                inflater,
                "Expected in the map as its own screen, with an edge from fragment one.",
            ),
        )
    }
}

/**
 * A destination that renders with Compose inside a fragment graph.
 *
 * This is the screen that used to take the whole Activity dark. Its `ComposeView` is attached under
 * the Activity's content view, so it makes "this Activity uses Compose" true for the Activity —
 * while the screens are still the fragment graph's destinations.
 */
class ComposeInsideFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            var colour by remember { mutableStateOf(Color(0xFF6200EE)) }
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Compose inside a fragment", fontSize = 24.sp)
                    Text(
                        "The Activity hosts a NavHostFragment and this destination is Compose. " +
                            "Both facts are true at once, which is the case that reported nothing.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    androidx.compose.material3.Button(
                        onClick = { colour = if (colour.red > 0.5f) Color(0xFF6200EE) else Color(0xFFB00020) },
                        modifier = Modifier.padding(top = 24.dp),
                    ) { Text("Repaint") }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .size(160.dp)
                            .background(colour, RoundedCornerShape(16.dp)),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- tiny view helpers

private fun column(inflater: LayoutInflater, build: (LinearLayout) -> Unit): View =
    LinearLayout(inflater.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 96, 48, 48)
        build(this)
    }

private fun heading(inflater: LayoutInflater, text: String) =
    TextView(inflater.context).apply {
        this.text = text
        textSize = 22f
    }

private fun body(inflater: LayoutInflater, text: String) =
    TextView(inflater.context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 32, 0, 32)
    }

private fun button(inflater: LayoutInflater, text: String, onClick: () -> Unit) =
    Button(inflater.context).apply {
        this.text = text
        setOnClickListener { onClick() }
    }
