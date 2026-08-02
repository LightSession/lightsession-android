package com.lightsession.mapper

/**
 * A part of a screen the NavController does not know about.
 *
 * The screen map is built from `NavController.addOnDestinationChangedListener`, so it sees
 * exactly what the app declared as a route. Everything else that changes what a person is
 * looking at — a tab, a dialog, a bottom sheet — happens inside one destination and is
 * invisible to it. In the View world a dialog was its own window and got counted for free;
 * in Compose the destination simply never changes.
 *
 * Modelling these as a *suffix* on the destination rather than as a new concept is what
 * makes them cost nothing downstream: `dashboard › History` is a screen name like any
 * other, so it gets an id, a capture, a heatmap and its own node in the flow graph without
 * a single change on the server.
 */
internal data class SubScreen(val kind: Kind, val label: String) {
    enum class Kind {
        TAB,

        /**
         * A dialog or modal sheet in its own window.
         *
         * Its lifetime is known exactly — the window is added and removed — so while one is
         * up there is nothing to re-read, and the removal is what ends it.
         */
        MODAL,

        /**
         * A part of the screen the app declared itself.
         *
         * The escape hatch for everything the SDK cannot see: a sheet drawn inside the
         * composition, a full-screen panel behind `AnimatedVisibility`, a wizard step. None
         * of those open a window, and none are distinguishable in semantics from ordinary
         * content — so the app names them, through `LightSession.setSubScreen`.
         *
         * Ranked above a tab, because something the app went out of its way to declare is
         * the thing being looked at.
         */
        DECLARED,
    }
}

internal object SubScreens {

    /**
     * Between the destination and the part of it being viewed.
     *
     * A character no route contains, so the composed name can always be split back apart,
     * and one that reads as "inside" rather than as a path separator — `/` would make
     * `dashboard › History` look like a route the app declares.
     */
    const val SEPARATOR = " › "

    /**
     * Longer than this and it is not a label.
     *
     * A tab reads "Overview"; a dialog's testTag reads "confirm-delete". Anything much
     * longer means the reader grabbed body text, and body text is per-user — "Delete Dr.
     * Silva?" would mint a screen per doctor. Truncating would keep that bug and hide it,
     * so an over-long label is rejected outright and the caller falls back.
     */
    const val MAX_LABEL = 32

    /**
     * A label fit to become part of a screen name, or null.
     *
     * Screen names are keys: the server rows a screen by (name, version), and the device
     * caches by a hash of the name. So the same tab has to produce a byte-identical string
     * every time it is read, which is why whitespace is collapsed rather than trusted —
     * a label wrapped across two lines arrives with a newline in it, and `Overview\n` and
     * `Overview` would be two screens.
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val collapsed = raw.replace(SEPARATOR, " ").replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty() || collapsed.length > MAX_LABEL) return null
        return collapsed
    }

    /**
     * The full screen name for a destination and the parts of it in view, innermost last.
     *
     * Plural, and it was the fix for a measured hole in the map. The layers used to be one
     * slot: opening a dialog *replaced* the tab it was raised from, so a dialog opened from
     * each of three tabs was one node — `dashboard › dialog-4fab23` three times — and the
     * heatmaps of three different screens piled onto one wireframe. The tab was even saved
     * and restored around the modal, so navigation looked right while the identity was
     * wrong. Now the layers nest: `dashboard › History › dialog-4fab23`.
     *
     * A suffix is dropped when it merely repeats the name built so far — see [isRedundant].
     * That rule working per-layer is also what keeps a modal named like the tab it covers
     * from stuttering: `dashboard › Filter › Filter` folds to `dashboard › Filter`.
     *
     * Which tab counts as a sub-screen at all is settled before this, by diffing what is
     * selected now against what was selected on arrival: the tab a screen opens on *is* that
     * screen rather than a part of it, and a bottom navigation item never differs from
     * arrival. This is the belt to that brace — a reading taken before the arrival state was
     * known can still produce `home › Home`, and that is noise.
     */
    fun compose(base: String, subs: List<SubScreen>): String {
        var name = base
        for (sub in subs) {
            if (!isRedundant(name, sub.label)) name += SEPARATOR + sub.label
        }
        return name
    }

    /** One part, for the callers and tests that have at most one. */
    fun compose(base: String, sub: SubScreen?): String = compose(base, listOfNotNull(sub))

    private fun isRedundant(base: String, label: String): Boolean {
        val leaf = base.substringAfterLast('/').substringAfterLast(SEPARATOR)
        return leaf.equals(label, ignoreCase = true) ||
            leaf.replace("_", "").replace("-", "").equals(
                label.replace(" ", "").replace("_", "").replace("-", ""),
                ignoreCase = true,
            )
    }

}
