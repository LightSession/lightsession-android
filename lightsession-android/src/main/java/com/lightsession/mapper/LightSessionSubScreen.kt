package com.lightsession.mapper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.lightsession.LightSession

/**
 * Marks its part of the composition as a screen for as long as it is composed.
 *
 * Put it inside the thing it names:
 *
 * ```
 * if (showFilters) {
 *     Box(Modifier.fillMaxSize()) {
 *         LightSessionSubScreen("filter-sheet")
 *         FilterPanel()
 *     }
 * }
 * ```
 *
 * Composition is exactly the right lifetime, which is what makes this better than a pair of
 * manual calls: the panel and its tracking cannot fall out of step, and there is no exit
 * path — a back press, a dismiss, a navigation, a process death — that leaves a screen the
 * reader has left still marked as the one they are on.
 *
 * For dialogs and modal bottom sheets this is unnecessary. Those are real windows and the
 * SDK sees them by itself.
 */
@Composable
public fun LightSessionSubScreen(name: String) {
    DisposableEffect(name) {
        LightSession.getInstance().setSubScreen(name)
        onDispose {
            // Cleared through the same name it was set with. Two of these can overlap while
            // one panel animates out under another, and the leaving one must not cancel the
            // arriving one's claim.
            LightSession.getInstance().clearSubScreen(name)
        }
    }
}
