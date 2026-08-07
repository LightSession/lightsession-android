package com.lightsession

import android.util.Log
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a subcomposition is reachable from the root composition, on whatever Compose is running.
 *
 * `getCompositionContexts` looks through a group's `data` for an object that *has a field* of
 * type `CompositionContext` — the shape of `ComposerImpl$CompositionContextHolder`, which is
 * what Compose 1.7 put there. That class does not exist on the 2026.02.01 BOM, and with it
 * gone the lookup finds nothing, `LazyColumn` and `Scaffold` contribute no nodes, and the
 * stored wireframe is a single rect. See [SubcompositionScanProbeTest] for the measurement.
 *
 * This prints what is in `data` instead of assuming a wrapper: for every group, any datum that
 * *is* a `CompositionContext` and any datum that *holds* one. The answer decides the fix.
 *
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest -Pls.composeBomUnderTest=<bom> \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.SubcompositionDiscoveryProbeTest
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UiToolingDataApi::class)
class SubcompositionDiscoveryProbeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun printHowSubcompositionsAreReachable() {
        rule.setContent {
            LazyColumn(Modifier.fillMaxSize()) { items((1..20).map { "item $it" }) { Text(it) } }
        }
        rule.waitForIdle()

        rule.runOnUiThread {
            val root = rule.activity.window.decorView.rootView
            val composeView = findComposeView(root)
            Log.i(TAG, "composeView=${composeView?.javaClass?.name}")
            val composition = composeView?.keyedTags()?.findComposition()?.unwrap()
            Log.i(TAG, "composition=${composition?.javaClass?.name}")
            val composer = composition?.composer()
            Log.i(TAG, "composer=${composer?.javaClass?.name}")

            val tree = composer?.compositionData?.asTree()
            if (tree == null) {
                Log.i(TAG, "NO TREE")
                return@runOnUiThread
            }

            var groups = 0
            var isContext = 0
            var holdsContext = 0
            walk(tree) { group ->
                groups++
                for (datum in group.data) {
                    if (datum == null) continue
                    if (datum is CompositionContext) {
                        isContext++
                        Log.i(TAG, "IS  CompositionContext: ${datum.javaClass.name} in group '${group.name}'")
                        val composers = datum.composersByType()
                        Log.i(TAG, "      composers found=${composers.size}")
                    }
                    val field = datum.javaClass.declaredFields.firstOrNull {
                        CompositionContext::class.java.isAssignableFrom(it.type)
                    }
                    if (field != null) {
                        holdsContext++
                        Log.i(TAG, "HOLDS via ${datum.javaClass.name}.${field.name} in group '${group.name}'")
                    }
                }
            }
            Log.i(TAG, "SUMMARY groups=$groups isContext=$isContext holdsContext=$holdsContext")
        }
        rule.waitForIdle()
    }

    private fun walk(group: Group, visit: (Group) -> Unit) {
        visit(group)
        group.children.forEach { walk(it, visit) }
    }

    private fun CompositionContext.composersByType(): List<Composer> {
        val field = javaClass.declaredFields.asSequence()
            .filter { Iterable::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .firstOrNull { candidate ->
                val value = runCatching { candidate.get(this) }.getOrNull() as? Iterable<*>
                value != null && value.all { it is Composer }
            } ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return (runCatching { field.get(this) as? Iterable<Composer> }.getOrNull() ?: emptyList())
            .toList()
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name.endsWith("AndroidComposeView")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findComposeView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun View.keyedTags(): SparseArray<*>? = runCatching {
        View::class.java.getDeclaredField("mKeyedTags").also { it.isAccessible = true }
            .get(this) as? SparseArray<*>
    }.getOrNull()

    private fun SparseArray<*>.findComposition(): Composition? {
        for (i in 0 until size()) (valueAt(i) as? Composition)?.let { return it }
        return null
    }

    private fun Composition.unwrap(): Composition {
        var current: Composition = this
        repeat(10) {
            val inner = current.javaClass.declaredFields.asSequence()
                .mapNotNull { f ->
                    runCatching { f.isAccessible = true; f.get(current) as? Composition }.getOrNull()
                }
                .firstOrNull { it !== current } ?: return current
            current = inner
        }
        return current
    }

    private fun Composition.composer(): Composer? = javaClass.declaredFields.asSequence()
        .mapNotNull { f ->
            runCatching { f.isAccessible = true; f.get(this) as? Composer }.getOrNull()
        }
        .firstOrNull()

    private companion object {
        const val TAG = "SubcompDiscovery"
    }
}
