package com.lightsession

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightsession.mapper.SkeletonGenerator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the wireframe sees through a `NavHost` nested inside a `Scaffold`.
 *
 * ## Why this exists beside `SubcompositionScanProbeTest`
 *
 * That probe covers one `SubcomposeLayout` — a `Scaffold`, a `LazyColumn`, or the two of them
 * stacked. It never puts a `NavHost` between them, and a real app almost always does: the shell is
 * a `Scaffold` with a bottom bar, the `NavHost` sits in its content slot, and each destination is
 * *itself* a `Scaffold`. That is three composition boundaries deep, and `NavHost` adds a fourth
 * thing the other probe never exercised — its destinations live in a `SubcomposeLayout`-backed
 * crossfade container with a saved-state holder around them.
 *
 * Written to diagnose one screen. A production `Métricas` screen came back far simpler than the
 * screen really is, on Compose 1.7.6, where subcomposition discovery is known to work — the
 * `SubcompositionScanProbeTest` numbers are identical before and after the discovery fix at that
 * version. So the cause is somewhere this repository had never measured.
 *
 * ## How to read it
 *
 * [Case.EAGER] is the control: the same leaves with no subcomposition anywhere. Every other case
 * adds one layer of nesting. A count that collapses at a particular layer names that layer as the
 * one that loses content. [Case.LOADING] is the same tree with its body replaced by a spinner —
 * what the screen looks like before its data arrives, and what a wireframe captured too early
 * would be.
 *
 * Run with:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.NestedScaffoldNavProbeTest
 */
@RunWith(AndroidJUnit4::class)
class NestedScaffoldNavProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Case { EAGER, SCREEN, IN_NAVHOST, FULL_SHELL, LOADING }

    private companion object {
        const val TAG = "NestedScaffoldNav"
    }

    /** Rect count and kind census for whatever is currently composed. */
    private fun scan(label: String): Int {
        var count = 0
        rule.runOnUiThread {
            val root = rule.activity.window.decorView.rootView
            val rects = SkeletonGenerator().frameFrom(root, backgroundColor = 0)?.rects.orEmpty()
            val census = rects.groupingBy { it.kind }.eachCount()
            Log.i(TAG, "$label -> ${rects.size} rects $census")
            count = rects.size
        }
        rule.waitForIdle()
        return count
    }

    /** The body of the production screen: a scrolling Column of cards, no lazy list. */
    @Composable
    private fun Body() {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Mensal", "Trimestral", "Anual", "Personalizado").forEach {
                    FilterChip(selected = it == "Mensal", onClick = {}, label = { Text(it) })
                }
            }
            repeat(3) { index ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Cobertura $index")
                                Spacer(Modifier.height(2.dp))
                                Text("72,5%")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { 0.725f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("29 de 40 médicos visitados")
                    }
                }
            }
        }
    }

    /** The destination as the app writes it: its own Scaffold with a top bar. */
    @Composable
    private fun Screen(loading: Boolean = false) {
        Scaffold(topBar = { Text("Minhas métricas") }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    Body()
                }
            }
        }
    }

    @Composable
    private fun InNavHost(loading: Boolean = false) {
        val nav = rememberNavController()
        NavHost(nav, startDestination = "performance") {
            composable("performance") { Screen(loading) }
        }
    }

    /** Outer shell: Scaffold with a bottom bar, NavHost in its content slot. */
    @Composable
    private fun FullShell(loading: Boolean = false) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Rotas", "Médicos", "Métricas", "Perfil").forEach {
                        NavigationBarItem(
                            selected = it == "Métricas",
                            onClick = {},
                            icon = { Text(it.take(1)) },
                            label = { Text(it) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) { InNavHost(loading) }
        }
    }

    @Test
    fun contentSurvivesAScaffoldWrappingANavHostWrappingAScaffold() {
        var case by mutableStateOf(Case.EAGER)

        rule.setContent {
            when (case) {
                // Same leaves, zero subcompositions. The ceiling everything else is judged against.
                Case.EAGER -> Body()
                Case.SCREEN -> Screen()
                Case.IN_NAVHOST -> InNavHost()
                Case.FULL_SHELL -> FullShell()
                Case.LOADING -> FullShell(loading = true)
            }
        }

        val counts = Case.entries.associateWith { c ->
            case = c
            rule.waitForIdle()
            scan(c.name)
        }

        Log.i(TAG, "SUMMARY $counts")
        // Deliberately no assertion. This is a probe: it is here to produce the numbers that say
        // which layer loses the content, not to encode an answer nobody has yet.
    }
}
