package com.lightsession.mapper

import android.graphics.Rect

/**
 * A screen described by an embedder, for a screen this SDK cannot read.
 *
 * ## Why an embedder describes its own screen
 *
 * [SkeletonGenerator] builds a wireframe by walking the platform's own view hierarchy. A toolkit
 * that paints into a surface — Flutter above all — gives that walk one `FlutterView` and nothing
 * inside it, so the wireframe of any such screen is a single rectangle the size of the display.
 * It is not an error and nothing logs: the walk succeeds, finds one node, and the picture that
 * reaches the dashboard is a grey page.
 *
 * The party that knows what is on such a screen is the toolkit that painted it. This is where it
 * says so, and it is the same shape of seam as [com.lightsession.masking.SuppliedMasks] — one
 * describes what to cover, this one describes what to draw.
 *
 * ## Why staleness is handled differently here
 *
 * A mask report has to match the frame it is drawn on, to the frame, because a rectangle in the
 * wrong place leaves text showing. A wireframe has no such coupling: it is taken once per screen,
 * after the screen settles, and it is a picture of the layout rather than of an instant. So there
 * is no generation counter here — the standing description is used, and an embedder that reports
 * on every settled screen keeps it current.
 *
 * What does matter is that a description belongs to the screen it is filed under. The embedder
 * reports a new one whenever its screen changes, and the SDK reads the standing one when it builds
 * a wireframe; between those, a navigation the embedder has not yet reported would file the old
 * layout under the new name. The window is the settle delay — several seconds — against a report
 * sent on the first frame after a screen change, so the description is normally in place long
 * before the wireframe is asked for.
 *
 * Thread-safe by a single volatile reference; readers get a whole description or nothing.
 */
public object SuppliedScreen {

    /**
     * One rectangle of a described screen, and everything drawn inside it.
     *
     * Deliberately close to what `SkeletonGenerator` already builds from a `View`, so the two
     * sources meet at the same place and everything downstream — the flattening, the paint order,
     * the recolour, the renderer — is shared rather than duplicated.
     */
    public class Node(
        /** Where this sits, in **screen pixels**. */
        public val bounds: Rect,
        /**
         * What it is. One of the names the server's `NodeKind` knows: `CONTAINER`, `TEXT`,
         * `IMAGE`, `INPUT`, `BUTTON`, `WEBVIEW`, `CARD`, `UNKNOWN`. An unknown name degrades to
         * `UNKNOWN` rather than failing — the server has the same rule, and a wireframe with one
         * odd rectangle is worth more than no wireframe.
         */
        public val kind: String,
        /**
         * The background this node paints, as ARGB, or null when it paints none.
         *
         * Null is the common case and the useful one: it means "colour this the way you colour a
         * node of this kind", which is what keeps a supplied wireframe looking like a native one.
         */
        public val color: Int? = null,
        public val children: List<Node> = emptyList(),
    )

    /** A whole screen, as the embedder last described it. */
    public class Screen(
        /** The described surface, in screen pixels. */
        public val width: Int,
        public val height: Int,
        public val root: Node,
    )

    @Volatile
    private var current: Screen? = null

    /**
     * How many descriptions have arrived, ever.
     *
     * The mapper records which revision a wireframe was built from, so it can tell a description
     * it has already drawn from one it has not. Without that it could only ask "is there a
     * description", which is true forever after the first one and says nothing about whether the
     * picture on file is made of it.
     */
    @Volatile
    private var revision: Long = 0


    /**
     * Told when a new description arrives, so the SDK can take the wireframe again.
     *
     * The mapper decides when a screen's picture is worth replacing, and on a Compose app it
     * learns that a screen changed from a snapshot apply. An app with no Compose in it never
     * produces one — so a Flutter screen's wireframe was taken once, on arrival, before this
     * object had been told anything, and nothing ever came along to say it was worth taking
     * again.
     *
     * A report is that app's version of the same news, so it is announced rather than left to be
     * polled.
     */
    @Volatile
    internal var onDescribed: (() -> Unit)? = null

    /**
     * Replaces the standing description.
     *
     * Reported per settled screen rather than per frame: this is structure, not geometry that has
     * to match a particular frame's pixels.
     */
    public fun set(screen: Screen) {
        current = screen
        revision++
        onDescribed?.invoke()
    }

    /**
     * Forgets the standing description; the view walk is authoritative again.
     *
     * For an embedder going away — an engine detaching.
     */
    public fun clear() {
        current = null
    }


    /** The standing description, or null when no embedder has spoken. */
    internal fun snapshot(): Screen? = current

    /** Which description is standing, for a caller deciding whether it has already used it. */
    internal fun revisionNow(): Long = revision

}
