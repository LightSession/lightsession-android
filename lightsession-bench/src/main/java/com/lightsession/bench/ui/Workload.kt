package com.lightsession.bench.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The screen being recorded. The only difference between the arms is whether it is being recorded.
 *
 * It does not scroll itself — [com.lightsession.bench.run.TouchDriver] drags it with synthetic
 * `MotionEvent`s, for the reason given there: a scroll driven from code produces no touch, and
 * without a touch the recorder never leaves its slow capture schedule.
 *
 * ## Why the rows look like this
 *
 * `MaskScanner` reads the Compose semantics tree rather than composable names: text becomes a
 * rectangle through `SemanticsProperties.Text`, an image through `Role.Image`. A row's cost to the
 * masker is therefore decided by how many semantics nodes it publishes, which is why each carries
 * three `Text`s and one node that declares itself an image.
 *
 * That image is a coloured `Box` with the role set, not a real bitmap. To the scanner — the thing
 * being measured — a rect carrying `Role.Image` is indistinguishable from a photograph, while a real
 * bitmap would add decode and upload cost that belongs to the app, not to the library.
 *
 * A still screen was rejected as the workload: frame deduplication upstream turns repeated captures
 * of an unchanging screen into a repeat signal rather than a frame, so it would measure the cheap
 * path and call it the cost.
 */
@Composable
fun WorkloadList(
    state: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    rows: Int = ROWS,
) {
    LazyColumn(state = state, modifier = modifier) {
        items(rows) { index -> WorkloadRow(index) }
    }
}

@Composable
private fun WorkloadRow(index: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(SWATCHES[index % SWATCHES.size])
                // What makes the masker treat this as an image. See the file comment.
                .semantics { role = Role.Image },
        )
        Column(Modifier.fillMaxWidth()) {
            Text("Row $index", fontSize = 15.sp, style = MaterialTheme.typography.titleSmall)
            Text(
                "sensitive-looking body text that masking has to cover on every capture",
                fontSize = 12.sp,
            )
            Text("id ${index * 7919}  ·  updated ${index % 60}m ago", fontSize = 11.sp)
        }
    }
}

/** Long enough that a whole arm of dragging never runs out of new rows to compose. */
private const val ROWS = 600

private val SWATCHES = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
)
