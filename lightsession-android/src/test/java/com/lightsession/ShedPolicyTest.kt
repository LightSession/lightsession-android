package com.lightsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * What the SDK gives up when the in-memory buffer hits its ceiling.
 *
 * Only reachable when the disk write is stalling, which cannot be provoked on an
 * emulator — so the policy is pinned here instead. The substantive choice is
 * *which* frames go, and inverting it would still compile, still pass every other
 * test, and quietly produce replays that stop before the moment worth watching.
 */
class ShedPolicyTest {

    private fun frame(seq: Int, size: Int) = SessionDataManager.FrameData(
        timestamp = 1_000L + seq,
        sequenceNumber = seq,
        frameSequenceNumber = seq,
        imageData = ByteArray(size),
        isRepeatedFrame = false
    )

    private fun buffer(vararg sizes: Int): Pair<ConcurrentLinkedQueue<SessionDataManager.FrameData>, AtomicLong> {
        val queue = ConcurrentLinkedQueue<SessionDataManager.FrameData>()
        sizes.forEachIndexed { index, size -> queue.offer(frame(index + 1, size)) }
        return queue to AtomicLong(sizes.sumOf { it.toLong() })
    }

    @Test
    fun `the oldest frames go and the newest are kept`() {
        // The decision this test exists for. A FIFO queue polled from the head
        // discards what came in first, so the replay keeps its ending.
        val (queue, bytes) = buffer(100, 100, 100, 100, 100)

        val shed = SessionDataManager.shedToFit(queue, bytes, target = 200)

        assertEquals(3, shed)
        assertEquals(200L, bytes.get())
        assertEquals(
            "kept the wrong frames",
            listOf(4, 5),
            queue.map { it.sequenceNumber }
        )
    }

    @Test
    fun `it stops as soon as it is under the target`() {
        val (queue, bytes) = buffer(500, 10, 10, 10)

        // One big frame is enough; the small ones must survive.
        val shed = SessionDataManager.shedToFit(queue, bytes, target = 100)

        assertEquals(1, shed)
        assertEquals(30L, bytes.get())
        assertEquals(3, queue.size)
    }

    @Test
    fun `a buffer already under the target is left alone`() {
        val (queue, bytes) = buffer(10, 20)
        assertEquals(0, SessionDataManager.shedToFit(queue, bytes, target = 1_000))
        assertEquals(2, queue.size)
        assertEquals(30L, bytes.get())
    }

    @Test
    fun `an empty queue does not spin`() {
        // The counter can disagree with the queue if a drain interleaved with a
        // push. Polling null has to end the loop, or this is an infinite loop on
        // the capture thread.
        val queue = ConcurrentLinkedQueue<SessionDataManager.FrameData>()
        val bytes = AtomicLong(999_999)

        assertEquals(0, SessionDataManager.shedToFit(queue, bytes, target = 0))
        assertTrue("the counter should be left as found", bytes.get() == 999_999L)
    }

    @Test
    fun `shedding everything is possible when one frame exceeds the target alone`() {
        val (queue, bytes) = buffer(5_000)
        assertEquals(1, SessionDataManager.shedToFit(queue, bytes, target = 100))
        assertTrue(queue.isEmpty())
        assertEquals(0L, bytes.get())
    }

    @Test
    fun `the byte count tracks what was actually removed`() {
        // The counter is maintained by hand rather than measured, so a drift here
        // would make every subsequent push believe the buffer is full.
        val (queue, bytes) = buffer(300, 50, 50, 50)
        SessionDataManager.shedToFit(queue, bytes, target = 150)
        assertEquals(
            queue.sumOf { it.imageData.size.toLong() },
            bytes.get()
        )
    }
}
