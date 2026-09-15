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
 * and compares on completion. A frame whose generation moved mid-capture is a frame whose pixels
 * and rectangles describe different moments, and it is dropped. This is [replay.ScreenDrawing]'s
 * `drewDuringCapture` net rebuilt for a painter whose draws the view system cannot see.
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
        current = Report(generation, rects)
    }

    /** Forgets the standing report; the scanner's own walk is authoritative again. */
    public fun clear() {
        current = null
    }

    /** The standing report, or null when no embedder has spoken. */
    internal fun snapshot(): Report? = current

    /** The generation of the standing report, for the completion check. */
    internal fun generationNow(): Long? = current?.generation
}
