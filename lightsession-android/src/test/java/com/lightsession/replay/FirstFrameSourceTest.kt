package com.lightsession.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a session's first frame is a real one.
 *
 * A source test, for the same reason [com.lightsession.ScreenGeometrySourceTest] is one: no device
 * demonstrates this fault. It needs a capture or an encode to *fail*, which is precisely what a
 * healthy emulator refuses to do on request, and `Recorder` cannot be built on a JVM — it takes a
 * main-thread `Handler` in its constructor. What went wrong both times was structural, so
 * structure is what this checks.
 *
 * ## What went wrong, twice
 *
 * `isFirstCapture` guards the guarantee that a recording opens with a picture rather than with a
 * marker meaning "the same as the one before". A marker with nothing before it cannot be rendered,
 * and once a still screen settles into markers every later tick emits another — the renderer then
 * rejects the whole session, permanently and correctly. Eighteen sessions reached the dead-letter
 * queue that way, one of them 78 frames with not a single real one among them.
 *
 * The first version cleared the flag where a capture was *requested*, on a path whose failure case
 * emits a marker. The second cleared it on delivery — better — but unconditionally, and
 * `ScreenDrawing.encodeToJpeg` returns null when encoding throws, which
 * `ReplayIntegration.handleCaptureResult` counts as a delivery while storing nothing. Same
 * outcome, one layer down.
 *
 * So the invariant is not "clear it later". It is: **the flag is cleared in exactly one place, and
 * that place has bytes in its hand.**
 */
class FirstFrameSourceTest {

    private val recorder = File("src/main/java/com/lightsession/replay/Recorder.kt")

    /** Source lines with comments stripped, so a sentence describing the rule is not a breach of it. */
    private fun codeLines(): List<Pair<Int, String>> {
        var inBlockComment = false
        return recorder.readText().lines().withIndex().map { (index, raw) ->
            var line = raw
            if (inBlockComment) {
                val end = line.indexOf("*/")
                line = if (end >= 0) line.substring(end + 2).also { inBlockComment = false } else ""
            }
            val blockStart = line.indexOf("/*")
            if (blockStart >= 0) {
                val end = line.indexOf("*/", blockStart)
                line = if (end >= 0) {
                    line.removeRange(blockStart, end + 2)
                } else {
                    inBlockComment = true
                    line.substring(0, blockStart)
                }
            }
            (index + 1) to line.substringBefore("//")
        }
    }

    @Test
    fun the_first_capture_flag_is_cleared_in_exactly_one_place() {
        val sites = codeLines().filter { (_, code) -> "isFirstCapture.set(false)" in code }

        assertEquals(
            "the flag that guarantees a real first frame is cleared in more than one place; each " +
                "extra one is a path that can clear it without a frame having been delivered:\n" +
                sites.joinToString("\n") { (line, code) -> "  Recorder.kt:$line: ${code.trim()}" },
            1,
            sites.size,
        )
    }

    /**
     * And that the one place is guarded by the bytes existing.
     *
     * Looks for a null check in the few lines above the clear, rather than parsing Kotlin: the
     * point is to fail loudly if someone deletes the guard, not to prove the file compiles.
     */
    @Test
    fun the_flag_is_cleared_only_when_there_are_bytes() {
        val code = codeLines()
        val site = code.first { (_, line) -> "isFirstCapture.set(false)" in line }.first

        val preceding = code
            .filter { (line, _) -> line in (site - 4) until site }
            .joinToString("\n") { it.second }

        assertTrue(
            "Recorder.kt:$site clears the first-frame flag with no null check above it. " +
                "`encodeToJpeg` returns null when encoding throws, and a null is not a frame:\n" +
                preceding,
            "bytes != null" in preceding,
        )
    }

    /**
     * The budget that keeps a failing capture from being retried forever exists and is consulted.
     *
     * Making the flag mean "delivered" removed a bound that used to come for free: while it meant
     * "attempted", one capture was ever dispatched for it. A window that can never be read — a
     * secure one, say — would otherwise pay for a full capture on every tick of the session.
     */
    @Test
    fun the_first_frame_attempts_are_bounded() {
        val code = codeLines().map { it.second }.joinToString("\n")

        assertTrue(
            "MAX_FIRST_FRAME_ATTEMPTS is gone; nothing bounds the retry of a capture that never " +
                "lands, and a window that cannot be read pays a capture per tick forever",
            "MAX_FIRST_FRAME_ATTEMPTS" in code,
        )
        assertTrue(
            "the attempt budget is declared but never consulted when deciding to capture",
            "firstFrameAttempts.get() < MAX_FIRST_FRAME_ATTEMPTS" in code,
        )
    }
}
