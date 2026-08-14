package com.lightsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.lightsession.transport.BatchSpool

/**
 * The spool's whole reason to exist is that data survives a failed upload and a
 * killed process. That is a claim about durability, so it gets tested rather than
 * asserted in a comment.
 */
class BatchSpoolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun spool(maxBytes: Long = BatchSpool.DEFAULT_MAX_BYTES, maxAttempts: Int = 3) =
        BatchSpool(folder.root, maxBytes, maxAttempts)

    private fun frame(name: String, size: Int = 64, repeated: Boolean = false) =
        BatchSpool.SpooledFrame(name, ByteArray(size) { it.toByte() }, repeated)

    private fun writeFrames(
        s: BatchSpool,
        id: String,
        frames: List<BatchSpool.SpooledFrame> = listOf(frame("frame_1_100.jpg"))
    ) = s.writeFrames(
        batchId = id,
        metadataJson = """{"batch_id":"$id"}""",
        frameMetadataJson = frames.map { """{"file":"${it.fileName}"}""" },
        frames = frames
    )

    @Test
    fun `a frame batch survives being written and read back`() {
        val s = spool()
        val frames = listOf(frame("frame_1_100.jpg", 128), frame("repeated_2_200.signal", 1, true))
        assertNotNull(writeFrames(s, "b1", frames))

        val pending = s.pendingFrames()
        assertEquals(1, pending.size)
        val entry = pending.single()
        assertEquals("""{"batch_id":"b1"}""", entry.metadataJson)
        assertEquals(2, entry.frames.size)
        assertEquals(128, entry.frames.first { !it.isRepeated }.bytes.size)
        assertTrue(entry.frames.any { it.isRepeated })
        // Per-frame metadata is recovered from beside the frame, so a batch written
        // by an older run still uploads with the metadata it was created with.
        assertEquals(
            """{"file":"frame_1_100.jpg"}""",
            s.frameMetadata(entry.dir, "frame_1_100.jpg")
        )
    }

    @Test
    fun `a breadcrumb batch round trips through the codec`() {
        val s = spool()
        // Values that would break a naive line-based format: embedded newlines,
        // quotes, a whole JSON document, and an empty string.
        val fields = mapOf(
            "type" to "breadcrumb_batch",
            "session_id" to "1785060029729",
            "breadcrumbs" to """[{"type":"interaction","screen":"Main\nActivity"}]""",
            "multiline" to "one\ntwo\nthree",
            "quoted" to """he said "hi"""",
            "empty" to ""
        )
        assertNotNull(s.writeCrumbs("c1", fields))

        val recovered = s.pendingCrumbs().single()
        assertEquals(fields, recovered.fields)
    }

    @Test
    fun `acknowledging removes the entry and failing keeps it`() {
        val s = spool()
        writeFrames(s, "keep")
        s.writeCrumbs("drop", mapOf("type" to "breadcrumb_batch"))
        assertEquals(2, s.pendingCount())

        s.acknowledge(s.pendingCrumbs().single().file)
        assertEquals(1, s.pendingCount())

        // This is the case the whole design exists for: an upload failed and the
        // data is still there.
        s.recordFailure(s.pendingFrames().single().dir)
        assertEquals(1, s.pendingCount())
        assertEquals(1, s.pendingFrames().single().frames.size)
    }

    @Test
    fun `an entry is abandoned once it has failed too often`() {
        val s = spool(maxAttempts = 3)
        writeFrames(s, "doomed")
        val dir = s.pendingFrames().single().dir

        // Asserted through the spool rather than through a return value: what matters is
        // whether the entry is still there to be retried, and that is what a caller can
        // actually see.
        s.recordFailure(dir)
        assertEquals("first failure should keep it", 1, s.pendingCount())
        s.recordFailure(dir)
        assertEquals("second failure should keep it", 1, s.pendingCount())
        // Retrying forever would mean the spool never drains and newer data gets
        // evicted to make room for a batch nobody will ever accept.
        s.recordFailure(dir)
        assertEquals("third failure should give up", 0, s.pendingCount())
    }

    @Test
    fun `going over budget drops frame batches and keeps breadcrumbs`() {
        // A tap is a hundred bytes and is the only record it happened; a frame is
        // large and the one beside it looks almost identical.
        val s = spool(maxBytes = 4_096)

        s.writeCrumbs("crumbs", mapOf("breadcrumbs" to """[{"type":"interaction"}]"""))
        repeat(6) { i ->
            writeFrames(s, "big$i", listOf(frame("frame_${i}_0.jpg", size = 2_048)))
        }

        assertTrue("spool should be back under budget", s.sizeBytes() <= 4_096)
        assertEquals("breadcrumbs must never be evicted", 1, s.pendingCrumbs().size)
        assertTrue("some frame batches should survive", s.pendingFrames().isNotEmpty())
        assertTrue("older frame batches should have gone", s.pendingFrames().size < 6)
    }

    @Test
    fun `the oldest frame batch is the one that goes`() {
        val s = spool(maxBytes = 3_000)
        writeFrames(s, "oldest", listOf(frame("frame_1_1.jpg", 2_000)))
        Thread.sleep(2) // distinct millisecond, so the names order correctly
        writeFrames(s, "newest", listOf(frame("frame_2_2.jpg", 2_000)))

        val names = s.pendingFrames().map { it.dir.name }
        assertEquals(1, names.size)
        assertTrue("kept the wrong one: $names", names.single().endsWith("_newest"))
    }

    @Test
    fun `pending entries are returned oldest first`() {
        val s = spool()
        writeFrames(s, "first")
        Thread.sleep(2)
        writeFrames(s, "second")
        Thread.sleep(2)
        writeFrames(s, "third")

        val order = s.pendingFrames().map { it.dir.name.substringAfter('_') }
        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `a batch left half written is not offered for upload`() {
        val s = spool()
        // What a crash mid-write leaves behind. It must never be uploaded: a
        // truncated multipart is worse than a missing one.
        val staging = folder.root.resolve("lightsession/spool/.staging/frames_crashed")
        staging.mkdirs()
        staging.resolve("meta.json").writeText("""{"partial":true}""")

        assertEquals(0, s.pendingCount())
        assertEquals(0, s.pendingFrames().size)

        s.prune()
        assertFalse("staging should be cleared on prune", staging.exists())
    }

    @Test
    fun `an unreadable entry is discarded rather than blocking the queue`() {
        val s = spool()
        writeFrames(s, "good")
        // A frame directory with no meta.json, however it got that way.
        val broken = folder.root.resolve("lightsession/spool/frames/9999999999999_broken")
        broken.mkdirs()
        broken.resolve("frame_1_1.jpg").writeBytes(ByteArray(10))

        val pending = s.pendingFrames()
        assertEquals("only the good batch should be offered", 1, pending.size)
        assertFalse("the broken batch should be gone", broken.exists())
    }

    @Test
    fun `a frame batch with no frames is not uploaded as an empty request`() {
        val s = spool()
        val empty = folder.root.resolve("lightsession/spool/frames/1000000000000_hollow")
        empty.mkdirs()
        empty.resolve("meta.json").writeText("{}")

        assertEquals(0, s.pendingFrames().size)
        assertFalse(empty.exists())
    }

    @Test
    fun `a fresh spool reports nothing pending`() {
        val s = spool()
        assertEquals(0, s.pendingCount())
        assertEquals(0L, s.sizeBytes())
        assertEquals(emptyList<BatchSpool.FrameEntry>(), s.pendingFrames())
        assertEquals(emptyList<BatchSpool.CrumbEntry>(), s.pendingCrumbs())
    }

    @Test
    fun `a second spool over the same directory finds what the first wrote`() {
        // Standing in for process death: the object is gone, the files are not.
        writeFrames(spool(), "survivor")
        spool().writeCrumbs("survivor-crumbs", mapOf("breadcrumbs" to "[]"))

        val next = spool()
        next.prune()
        assertEquals(2, next.pendingCount())
        assertEquals("survivor", next.pendingFrames().single().dir.name.substringAfter('_'))
    }

    @Test
    fun `mismatched frame and metadata counts fail loudly`() {
        val s = spool()
        // A programming error, not a runtime condition — so it throws rather than
        // writing a batch whose frames and metadata do not line up.
        try {
            s.writeFrames("bad", "{}", listOf("{}"), listOf(frame("a.jpg"), frame("b.jpg")))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("disagree"))
        }
    }
}
