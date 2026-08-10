package com.lightsession.bench.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightsession.bench.probe.Sample

private val PSS = Color(0xFF4E79A7)
private val NATIVE = Color(0xFFE15759)
private val JAVA = Color(0xFF59A14F)

/**
 * The three memory series over a rolling window, on one shared vertical scale.
 *
 * Shared on purpose: the interesting comparison is which of them moves when recording starts, and
 * three independently normalised charts make a 40 KB wobble look exactly like a 40 MB one. The
 * scale's ceiling is the largest PSS seen in the window, so the lines keep their proportions.
 *
 * Native is drawn in its own colour because it is the one to watch here — a capture's bitmap has
 * lived in native memory rather than on the Java heap since API 26, so the recorder's biggest single
 * allocation moves the red line, not the green one.
 */
@Composable
fun MemoryChart(samples: List<Sample>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Legend("PSS", PSS)
            Legend("native", NATIVE)
            Legend("java", JAVA)
            val peak = samples.maxOfOrNull { it.mem.pssTotalKb } ?: 0
            Text("ceiling ${peak / 1024} MB", fontSize = 9.sp, color = Color.Gray)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(Color(0xFF14171C)),
        ) {
            if (samples.size < 2) return@Canvas
            val ceiling = samples.maxOf { it.mem.pssTotalKb }.coerceAtLeast(1)
            val dx = size.width / (samples.size - 1).toFloat()

            fun series(color: Color, pick: (Sample) -> Int) {
                var previous: Offset? = null
                samples.forEachIndexed { index, sample ->
                    val value = pick(sample).coerceAtLeast(0)
                    val point = Offset(
                        x = index * dx,
                        y = size.height - (value.toFloat() / ceiling) * size.height,
                    )
                    previous?.let { drawLine(color, it, point, strokeWidth = 2f) }
                    previous = point
                }
            }

            series(PSS) { it.mem.pssTotalKb }
            series(NATIVE) { it.mem.nativeAllocKb }
            series(JAVA) { it.mem.javaUsedKb }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) { drawRect(color) }
        Text(" $label", fontSize = 9.sp, color = Color.Gray)
    }
}
