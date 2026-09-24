package com.lightsession.masking

import android.graphics.Rect

/**
 * Mask rectangles handed in by an embedder, for screens this SDK cannot read.
 *
 * ## Why an embedder gets a say at all
 *
 * [MaskScanner] finds what to cover by walking the platform's own views, and a toolkit that
 * paints into a surface — Flutter is the one this was built for — gives that walk nothing to
 * find. The scan completes, returns empty, and the capture path reads empty as "nothing to
 * cover". `SurfaceContentMaskingTest` is the on-device proof. The only party that knows where
 * the text on such a screen is, is the toolkit that painted it — so it tells us, through this.
 *
 * ## The generation number
 *
 * Rectangles are only true of the frame they were measured on. The scanner solves that by
 * scanning at the instant of capture; an embedder cannot, because its report crosses a bridge
 * and arrives when it arrives. So each report carries a generation — a counter the embedder
 * bumps on every frame it paints — and the capture path records the generation it planned with
 * and asks, on completion, whether the rectangles moved since: [movedSince]. A frame painted
 * mid-capture with its masks somewhere else is a frame whose pixels and rectangles describe
 * different moments, and it is dropped. This is [replay.ScreenDrawing]'s `masksMoved` net
 * rebuilt for a painter whose draws the view system cannot see.
 *
 * Moved, not merely painted. A generation that advanced with the same rectangles is a frame whose
 * masks are exactly where the plan put them — a spinner turning, a map redrawing — and the answer
 * used to be to drop it anyway, which on a screen that paints every frame dropped every frame:
 * measured on the Flutter example's map screen, 19 of a session's 32 captures, every one with its
 * rectangles unchanged. So each report remembers how long its rectangles have stood unchanged, and
 * only a change drops.
 *
 * ## Invalid is not empty
 *
 * [set] with null rects means the embedder tried to measure its screen and failed. From that
 * moment every capture is dropped until a good report arrives, because the alternative is
 * shipping pixels whose masks are unknown. An *empty* list is different and legitimate: a screen
 * with nothing to cover.
 *
 * Thread-safe by a single volatile reference; readers get whole reports or nothing.
 */
public object SuppliedMasks {

    /** One report: what to cover, as of one painted frame. */
    internal class Report(
        val generation: Long,
        /** Screen pixels. Null means the embedder failed to measure — drop frames, see above. */
        val rects: List<Rect>?,
        /**
         * The first generation of the unbroken run of reports, ending with this one, that all
         * carried exactly these rectangles.
         */
        val sameSince: Long,
    )

    @Volatile
    private var current: Report? = null

    /**
     * Replaces the standing report.
     *
     * @param generation strictly increasing per painted frame on the embedder's side.
     * @param rects rectangles to cover in **screen pixels**, empty when the screen holds nothing
     *   coverable, or null when the embedder could not measure this frame.
     */
    public fun set(generation: Long, rects: List<Rect>?) {
        val previous = current
        val unchanged = previous != null && rects != null && previous.rects == rects &&
            generation > previous.generation
        current = Report(generation, rects, if (unchanged) previous!!.sameSince else generation)
    }

    /** Forgets the standing report; the scanner's own walk is authoritative again. */
    public fun clear() {
        current = null
    }

    /** The standing report, or null when no embedder has spoken. */
    internal fun snapshot(): Report? = current

    /** The generation of the standing report. */
    internal fun generationNow(): Long? = current?.generation

    /**
     * Whether the rectangles changed after the report of [planned] — the completion check.
     *
     * Moved when the embedder stopped reporting, when its latest report could not measure, when
     * its count went backwards (it restarted, and nothing ties the new count to the old one), and
     * when any report after [planned] carried different rectangles. Not moved only when every one
     * of them carried exactly the rectangles [planned] did.
     */
    internal fun movedSince(planned: Long): Boolean {
        val now = current ?: return true
        if (now.generation == planned) return false
        if (now.rects == null) return true
        if (now.generation < planned) return true
        return now.sameSince > planned
    }
}
