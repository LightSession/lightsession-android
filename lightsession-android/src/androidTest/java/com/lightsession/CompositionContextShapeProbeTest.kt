package com.lightsession

import android.util.Log
import androidx.compose.runtime.CompositionContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a `CompositionContext` implementation actually holds, on whatever Compose is running.
 *
 * The SDK reaches subcompositions by reflection, and it identifies the relevant fields by
 * *type* rather than by name so that R8 renaming cannot break it. That defence is sound and
 * insufficient: a type-based lookup still encodes an assumption about the type, and Compose
 * has been moving its internal collections to `androidx.collection` scatter sets, which are
 * not `Iterable`. On the 2026.02.01 BOM every subcomposition vanished from the wireframe
 * while every test passed, because the tests ran on 2024.11.00.
 *
 * So this prints the shape instead of assuming it. Run it against a new Compose before
 * trusting the scan on that Compose:
 *   ./gradlew :lightsession-android:connectedDebugAndroidTest -Pls.composeBomUnderTest=<bom> \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lightsession.CompositionContextShapeProbeTest
 */
@RunWith(AndroidJUnit4::class)
class CompositionContextShapeProbeTest {

    @Test
    fun printCompositionContextShape() {
        for (name in CANDIDATES) {
            val cls = runCatching { Class.forName(name) }.getOrNull()
            if (cls == null) {
                Log.i(TAG, "ABSENT $name")
                continue
            }
            Log.i(TAG, "FOUND $name")
            for (f in cls.declaredFields) {
                val iterable = Iterable::class.java.isAssignableFrom(f.type)
                Log.i(TAG, "    ${f.name}: ${f.type.name} iterable=$iterable")
            }
        }

        // Every field anywhere in the runtime whose type is CompositionContext — the other
        // half of the lookup, and the half that has to keep finding the holder at all.
        for (name in HOLDERS) {
            val cls = runCatching { Class.forName(name) }.getOrNull() ?: continue
            Log.i(TAG, "HOLDER $name")
            for (f in cls.declaredFields) {
                val isCtx = CompositionContext::class.java.isAssignableFrom(f.type)
                Log.i(TAG, "    ${f.name}: ${f.type.name} isCompositionContext=$isCtx")
            }
        }
    }

    private companion object {
        const val TAG = "CompositionShape"

        val CANDIDATES = listOf(
            "androidx.compose.runtime.ComposerImpl\$CompositionContextImpl",
            // Compose 1.11 renamed the composer: `ComposerImpl` became `GapComposer`, and its
            // `composers` field became a `MutableScatterSet` rather than a `Set`.
            "androidx.compose.runtime.GapComposer\$CompositionContextImpl",
            "androidx.compose.runtime.CompositionContextImpl",
            "androidx.compose.runtime.Recomposer",
        )

        val HOLDERS = listOf(
            "androidx.compose.runtime.ComposerImpl\$CompositionContextHolder",
        )
    }
}
