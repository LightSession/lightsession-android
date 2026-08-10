package com.lightsession.replay

import java.util.concurrent.atomic.AtomicLong

/**
 * How much of what the recorder produced was an actual picture.
 *
 * ## Why these had to become readable
 *
 * `ReplayIntegration` has counted these since it was written, and printed them in exactly one
 * place: `onTerminate()`, whose only caller would be `Application.onTerminate` — which Android does
 * not deliver on a real device. So the numbers were collected for the whole life of the process and
 * then thrown away.
 *
 * That is not a tidiness complaint. A recorder that emits [repeated] for every tick and [unique]
 * for none is producing a replay of a frozen screen, and it looks identical from the outside to one
 * that is working: frames still leave, batches still flush, the ingest still fills up. The only
 * thing that separates the two is this ratio, and it was unreadable.
 *
 * It was found by tracing rather than by counting, which took a system trace, a physical device and
 * several wrong turns. One log line would have said it.
 *
 * ## Reading it
 *
 * [unique] is a frame with pixels in it. [repeated] is the four-byte `RPTD` marker, which tells the
 * renderer "the screen did not change" — correct and cheap when the screen genuinely did not, and a
 * frozen replay when it did. On a screen being scrolled, every tick landing in [repeated] means no
 * picture of that scroll exists.
 */
object ReplayStats {

    private val totalAtomic = AtomicLong(0)
    private val uniqueAtomic = AtomicLong(0)
    private val repeatedAtomic = AtomicLong(0)

    /** Capture results delivered, of any kind. */
    val total: Long get() = totalAtomic.get()

    /** Results that carried an encoded image. */
    val unique: Long get() = uniqueAtomic.get()

    /** Results that were the `RPTD` marker instead of a picture. */
    val repeated: Long get() = repeatedAtomic.get()

    /** Share of delivered results that were a real picture, 0 when nothing has been delivered. */
    val uniqueShare: Double
        get() {
            val delivered = unique + repeated
            return if (delivered == 0L) 0.0 else unique.toDouble() / delivered
        }

    internal fun recordDelivery() = totalAtomic.incrementAndGet()
    internal fun recordUnique() = uniqueAtomic.incrementAndGet()
    internal fun recordRepeated() = repeatedAtomic.incrementAndGet()

    override fun toString(): String =
        "frames: $unique unique, $repeated repeated (${(uniqueShare * 100).toInt()}% real)"
}
