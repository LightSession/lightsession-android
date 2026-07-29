package com.lightsession

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That the SDK asks one object how big the screen is.
 *
 * A source test rather than a behavioural one, because the fault it guards against is not
 * something a device can demonstrate: on a phone in a single fullscreen window the three sources
 * agree, and the disagreement only appears where a system bar or a split window takes a slice.
 * What went wrong was that three call sites *existed*, so that is what this counts.
 *
 * Measured before the change: a capture row said 1080×2337 while the JPEG it pointed at decoded to
 * 1080×2400, and touches were normalised by 2337 and drawn on 2400 — every heatmap shifted down by
 * 1.3% of the height, always in the same direction.
 */
class ScreenGeometrySourceTest {

    private val sources = File("src/main/java/com/lightsession")

    private fun kotlinFiles(): List<File> =
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun only_ScreenGeometry_reads_the_platform_metrics() {
        val offenders = kotlinFiles()
            .filter { it.name != "ScreenGeometry.kt" }
            .mapNotNull { file ->
                val lines = file.readText().lines()
                    .withIndex()
                    .filter { (_, line) ->
                        val code = line.substringBefore("//")
                        "displayMetrics" in code &&
                            // A parameter named `displayMetrics` is a caller handing one in; the
                            // fault is *fetching* one, which is what these three spellings do.
                            ("resources" in code || "getSystem()" in code)
                    }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                lines.takeIf { it.isNotEmpty() }
            }
            .flatten()

        assertEquals(
            "these fetch their own screen metrics; they must ask ScreenGeometry instead, or the " +
                "number recorded beside a capture stops describing the capture:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }
}
