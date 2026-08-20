package com.lightsession.network

/**
 * A URL path with its dynamic segments replaced, computed **on the device**.
 *
 * This is the piece that decides whether a customer's secret leaves their building, and it runs
 * here rather than on the server for exactly that reason: a token that reaches our ingest has
 * already left. The ingest refuses a query string it is handed anyway — see `api_call.rs` — but
 * that is a second line, and nothing it does un-leaks what already arrived.
 *
 * ## Which way to be wrong
 *
 * Two failures, and they are not symmetric.
 *
 * **Over-collapsing** merges two endpoints into one row. It is visible — a reader sees
 * `/v1/{id}` where they expected `/v1/status` — and it is recoverable, because the events keep
 * their screens and the rule can be revised.
 *
 * **Under-collapsing** stores an id, and often a token, in a column with a thirteen-month TTL.
 * It also mints one endpoint per value, so the list this feature exists to produce fills with
 * a million rows of one call each. Invisible until someone reads the table, and permanent.
 *
 * So a segment is kept only when it *looks like a word somebody typed into a route*, and
 * anything else becomes a placeholder. The same asymmetry `SubScreens.sanitize` settles the
 * same way for a label.
 *
 * ## What is never included
 *
 * The query string and the fragment, at all. They are where tokens and ids actually live —
 * `?token=`, `?api_key=`, `#access_token=` — and no rule about their *contents* is worth
 * trusting, so they are not read.
 */
internal object PathTemplate {

    /** Longest segment that can still be a word rather than a value. */
    private const val MAX_WORD = 24

    /**
     * Past this, a segment holding both letters and digits is an id or a hash. Below it,
     * `v2`, `api1` and `oauth2` are ordinary route words.
     */
    private const val MAX_MIXED_WORD = 12

    private val UUID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    /**
     * Collapses a path. Query and fragment are dropped; a blank or malformed path answers `""`,
     * which the caller reports as "no endpoint" rather than guessing.
     */
    fun of(rawPath: String): String {
        val path = rawPath.substringBefore('?').substringBefore('#').trim()
        if (path.isEmpty() || !path.startsWith("/")) return ""
        if (path == "/") return "/"

        val collapsed = path
            .split('/')
            .map { segment -> if (segment.isEmpty()) segment else collapse(segment) }
            .joinToString("/")

        // A path is a key in a column, not a place to put an essay. A template this long means
        // the collapsing did not recognise what it was looking at.
        return if (collapsed.length > 200) "" else collapsed
    }

    private fun collapse(segment: String): String = when {
        UUID.matches(segment) -> "{uuid}"
        segment.all { it.isDigit() } -> "{id}"
        // Before anything else, and the ordering is a fix rather than a preference: an email
        // has a dot in it, so letting the extension rule below look first turned
        // `maria@example.com` into `{id}.com` and published the domain. A segment written
        // outside the alphabet a route is written in is data, whatever shape it has.
        !isRouteAlphabet(segment) -> "{id}"
        else -> collapseWord(segment)
    }

    /**
     * The characters a hand-written route uses. `.` is here for file names and is why the rule
     * above has to run first; everything else — `@`, `+`, `,`, `%`, `=`, `:` — appears in a
     * value and not in a route.
     */
    private fun isRouteAlphabet(segment: String): Boolean =
        segment.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '~' || it == '.' }

    private fun collapseWord(segment: String): String {
        val extension = segment.substringAfterLast('.', "")
        // A real extension, not a domain suffix: short, alphanumeric, and with a name in front
        // of it. `/assets/logo.png` and `/assets/{id}.png` are different questions, and the
        // extension is not data.
        if (
            extension.isNotEmpty() &&
            extension.length <= 5 &&
            extension.all { it.isLetterOrDigit() } &&
            segment.length > extension.length + 1
        ) {
            return collapseWord(segment.substringBeforeLast('.')) + "." + extension
        }
        if (segment.length > MAX_WORD) return "{id}"
        if (
            segment.length > MAX_MIXED_WORD &&
            segment.any { it.isDigit() } &&
            segment.any { it.isLetter() }
        ) {
            return "{id}"
        }
        return segment
    }
}
