package com.lightsession

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightsession.session.Identity
import com.lightsession.session.Recording
import com.lightsession.session.SessionDataManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Who the data recorded *before* a rotation is attributed to, read off the spool.
 *
 * Rotation exists to keep two people's — or two sessions' — data apart, and both `rotateIfIdle`
 * and `startNewSession` say so in their own docs: "nothing recorded under the old identity is
 * attributed to the new one". This checks that claim against what actually reaches the disk,
 * because the disk is the last place the SDK controls and the first place the truth is visible.
 *
 * ## Why the spool and not a server
 *
 * A batch is sealed on disk before it is uploaded, with its metadata beside it — so the question
 * "which session id was stamped on these frames" is answerable without a network, a backend, or a
 * dashboard. It is also the same bytes the server would receive, which is what makes reading them
 * a proof rather than an approximation.
 *
 * ## What is being separated
 *
 * Two claims that look alike and fail for different reasons:
 *
 *  * **Frames.** `processBatch(deferFrames = true)` drains the buffer synchronously and then
 *    launches `spoolFrames` on the IO scope. `spoolFrames` builds its metadata from the *fields*
 *    `sessionId`/`userId`, and the caller replaces `sessionId` immediately after. So the stamp
 *    depends on which of the two runs first.
 *  * **Breadcrumbs.** Never deferred, so they are written inline — but `userId` is a computed
 *    property reading `identity.effectiveId` at write time, and `LightSession.reset()` resets the
 *    identity *before* rotating. So the stamp depends on the order of those two calls.
 *
 * The batch id is the control in both cases: it is built synchronously from the old session, so a
 * batch whose id says one session and whose metadata says another is self-contradicting evidence
 * rather than an ambiguous reading.
 */
@RunWith(AndroidJUnit4::class)
class RotationAttributionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val spoolRoot = File(context.filesDir, "lightsession/spool")

    private lateinit var manager: SessionDataManager
    private lateinit var identity: Identity

    @Before
    fun setUp() {
        spoolRoot.deleteRecursively()
        Recording.enabled = true
        identity = Identity.from(context)
        manager = SessionDataManager(context, LightSessionConfig("k", "http://x", "http://y"))
        manager.init(identity)
    }

    @After
    fun tearDown() {
        spoolRoot.deleteRecursively()
        identity.reset()
    }

    /** The `session_id` recorded inside each sealed frame batch, keyed by the batch's own id. */
    private fun frameBatchStamps(): List<Pair<String, String>> {
        val dir = File(spoolRoot, "frames")
        return (dir.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { batch ->
            val meta = File(batch, "meta.json").takeIf { it.exists() }?.readText() ?: return@mapNotNull null
            val stamped = Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
            val batchId = Regex("\"batch_id\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
                ?: batch.name
            if (stamped == null) null else batchId to stamped
        }
    }

    /** The `user_id` values recorded inside sealed breadcrumb batches. */
    private fun crumbUserIds(): List<String> {
        val dir = File(spoolRoot, "crumbs")
        return (dir.listFiles()?.filter { it.isFile } ?: emptyList()).flatMap { file ->
            Regex("\"user_id\"\\s*:\\s*\"([^\"]+)\"").findAll(file.readText())
                .map { it.groupValues[1] }
                .toList()
        }
    }

    private fun waitForSpool(what: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("nothing reached the spool for $what within 5s")
    }

    @Test
    fun frames_recorded_before_a_rotation_keep_the_old_session_id() {
        // Recorded under session A.
        repeat(3) { manager.addFrame(ByteArray(256) { 1 }, isRepeatedFrame = false, currentScreen = "before") }

        // The rotation the sign-out path performs.
        manager.startNewSession("test_rotation")

        waitForSpool("frames") { frameBatchStamps().isNotEmpty() }
        val stamps = frameBatchStamps()

        // The batch id is built synchronously from the session that was current when the batch was
        // sealed, so it names session A. The metadata should agree with it.
        val disagreeing = stamps.filter { (batchId, stamped) -> !batchId.startsWith(stamped) }

        println("[RotationAttribution] frame batches: ${stamps.size}")
        stamps.forEach { (batchId, stamped) ->
            println("[RotationAttribution]   batch_id=$batchId")
            println("[RotationAttribution]   session_id stamped=$stamped")
            println("[RotationAttribution]   agree=${batchId.startsWith(stamped)}")
        }

        assertTrue("no frame batch was sealed, so there is nothing to check", stamps.isNotEmpty())
        assertEquals(
            "a frame batch was stamped with a session id that disagrees with its own batch id — " +
                "the frames were recorded in one session and uploaded as another: $disagreeing",
            0,
            disagreeing.size,
        )
    }

    /**
     * The same rotation, with the IO dispatcher already busy.
     *
     * The first frame test passes, and understanding *why* is the point of this one. The spool
     * coroutine is launched before `sessionId` is replaced, but the replacement's right-hand side
     * is `UUID.randomUUID()` — a `SecureRandom` draw — so the calling thread spends real time
     * before the write lands, and an idle IO pool wins that head start every time. The ordering is
     * accidental, not enforced: nothing synchronises the coroutine's read of `sessionId` against
     * the caller's write, and no happens-before edge exists between them.
     *
     * So the question is whether the head start survives a dispatcher that is not idle — which is
     * the state the SDK is actually in whenever it is draining a backlog. If the stamp flips here,
     * the race is reachable in production rather than theoretical.
     */
    @Test
    fun frames_keep_the_old_session_id_even_when_IO_is_saturated() {
        val busy = java.util.concurrent.CountDownLatch(1)
        val occupied = java.util.concurrent.CountDownLatch(64)
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )
        // Enough blocked jobs to fill the IO pool, so a newly launched one has to wait for a slot.
        repeat(64) {
            scope.launch {
                occupied.countDown()
                busy.await()
            }
        }
        occupied.await(5, java.util.concurrent.TimeUnit.SECONDS)

        repeat(3) { manager.addFrame(ByteArray(256) { 2 }, isRepeatedFrame = false, currentScreen = "before") }
        manager.startNewSession("test_rotation_under_load")

        // Let the caller's write land well before the pool frees up.
        Thread.sleep(300)
        busy.countDown()

        waitForSpool("frames under load") { frameBatchStamps().isNotEmpty() }
        val stamps = frameBatchStamps()
        val disagreeing = stamps.filter { (batchId, stamped) -> !batchId.startsWith(stamped) }

        println("[RotationAttribution] UNDER LOAD, frame batches: ${stamps.size}")
        stamps.forEach { (batchId, stamped) ->
            println("[RotationAttribution]   batch_id=$batchId")
            println("[RotationAttribution]   session_id stamped=$stamped")
            println("[RotationAttribution]   agree=${batchId.startsWith(stamped)}")
        }
        scope.cancel()

        assertTrue("no frame batch was sealed under load", stamps.isNotEmpty())
        assertEquals(
            "under a busy IO dispatcher a frame batch was stamped with a session id that " +
                "disagrees with its own batch id: $disagreeing",
            0,
            disagreeing.size,
        )
    }

    @Test
    fun breadcrumbs_recorded_while_signed_in_keep_the_signed_in_user() {
        val signedIn = "user-under-test"
        identity.identify(signedIn)

        // An interaction recorded while that user was signed in.
        manager.addInteractionFromJson(
            """{"type":"TAP","start_time":1,"end_time":2,"screen_id":"s1",
               "points":[{"screen":"checkout","x":10.0,"y":20.0}]}""",
        )

        // Exactly what LightSession.reset() does, in the order it does it — rotation first,
        // identity second. This mirrors that function on purpose and must change with it: the
        // reversed order was a shipped bug, proven by this very test before the fix, because the
        // crumb spool reads `identity.effectiveId` at write time and a reset-first sequence
        // stamped the signed-in user's buffered actions with the next anonymous id.
        manager.startNewSession("identity_reset")
        identity.reset()

        waitForSpool("crumbs") { crumbUserIds().isNotEmpty() }
        val stamped = crumbUserIds()

        println("[RotationAttribution] signed-in user was: $signedIn")
        println("[RotationAttribution] anonymous id after reset: ${identity.effectiveId}")
        println("[RotationAttribution] user_id values on the spooled crumbs: $stamped")

        assertTrue("no breadcrumb reached the spool", stamped.isNotEmpty())
        assertTrue(
            "an interaction recorded while '$signedIn' was signed in was stamped with " +
                "$stamped instead — the signed-out user's last actions are attributed to the " +
                "next anonymous person",
            stamped.all { it == signedIn },
        )
    }
}
