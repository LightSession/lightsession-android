package com.lightsession.transport

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Batches waiting to be uploaded, on disk.
 *
 * This exists because the previous path could not survive either of the two
 * things that happen most often on a phone. `processBatch` drained the in-memory
 * buffers and *then* launched an upload; if the request failed, `sendBatch` logged
 * it and returned, and the frames and interactions were already gone from the
 * queues. And if the process died — which is what happens when the user swipes the
 * app away — everything buffered went with it. On mobile, a failed request is
 * normal: a tunnel, a lift, a handoff between cells.
 *
 * So a batch is written here first, uploaded from here, and removed only when the
 * server has answered 2xx. A batch that fails is still on disk for the next
 * attempt, and one that was pending when the process died is found again at
 * startup.
 *
 * ## Layout
 *
 * ```
 * <filesDir>/lightsession/spool/
 *   crumbs/1785060000000_a1b2.batch     ← breadcrumb form fields, JSON
 *   frames/1785060001000_c3d4/          ← one directory per frame batch
 *     meta.json
 *     000.jpg
 *     001.signal
 * ```
 *
 * Names begin with a millisecond timestamp so lexicographic order is chronological
 * and the oldest pending batch is simply the first one listed.
 *
 * ## Atomicity
 *
 * Nothing is ever uploaded half-written. Entries are built under a sibling
 * `.staging` directory and moved into place with a single `renameTo`, which is
 * atomic within a filesystem. A crash mid-write leaves a staging directory, which
 * [prune] removes.
 *
 * ## Bounds
 *
 * The spool is capped. Over budget, frame batches are deleted oldest-first and
 * breadcrumbs are kept: a tap is a hundred bytes and is the only record that it
 * happened, while a frame is tens of kilobytes and the one before it looks almost
 * identical. The previous code had a `MAX_BATCH_SIZE_MB = 50` constant that was
 * declared and never read, so there was no bound at all — a stalled network grew
 * the in-memory queue until the *host* app was killed for using too much memory.
 */
internal class BatchSpool(
    filesDir: File,
    /** Total bytes the spool may occupy before it starts dropping frame batches. */
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    /** Give up on an entry after this many failed uploads. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) {
    companion object {
        private const val TAG = "LightSession.Spool"

        /**
         * 40 MB. Enough for several minutes of capture on a bad connection, small
         * enough not to be conspicuous in the host app's storage listing.
         */
        const val DEFAULT_MAX_BYTES = 40L * 1024 * 1024

        /**
         * Six attempts. A transient failure recovers well inside that; anything
         * that fails six times is being rejected rather than lost, and retrying it
         * forever would mean the spool never drains and newer data gets evicted to
         * make room for a batch that will never be accepted.
         */
        const val DEFAULT_MAX_ATTEMPTS = 6

        private const val STAGING = ".staging"
        private const val ATTEMPTS_FILE = ".attempts"
    }

    private val root = File(filesDir, "lightsession/spool")
    private val framesDir = File(root, "frames")
    private val crumbsDir = File(root, "crumbs")
    private val stagingDir = File(root, STAGING)

    /** Serialises writes and deletes; uploads read entries that are already sealed. */
    private val lock = Any()

    init {
        framesDir.mkdirs()
        crumbsDir.mkdirs()
        stagingDir.mkdirs()
    }

    /**
     * One frame, as it goes to disk and comes back.
     *
     * No timestamp or sequence number: those were serialised into the per-frame
     * metadata at write time, and carrying zero-valued copies of them on the
     * recovered path would only mislead whoever reads this next.
     */
    data class SpooledFrame(
        val fileName: String,
        val bytes: ByteArray,
        val isRepeated: Boolean
    ) {
        // ByteArray gives structural equality by reference, which would make two
        // frames with identical content unequal and two aliases of the same array
        // equal. Identity is the file name here.
        override fun equals(other: Any?) =
            this === other || (other is SpooledFrame && fileName == other.fileName)

        override fun hashCode() = fileName.hashCode()
    }

    /** A frame batch, recovered from disk and ready to upload. */
    class FrameEntry(
        val dir: File,
        val metadataJson: String,
        val frames: List<SpooledFrame>
    )

    /** A breadcrumb batch, recovered from disk. */
    class CrumbEntry(
        val file: File,
        /** The multipart form fields, as name → value. */
        val fields: Map<String, String>
    )

    // ------------------------------------------------------------------ writes

    /**
     * Seals a frame batch onto disk.
     *
     * Returns the entry, or null if it could not be written — in which case the
     * caller should treat the batch as lost and say so, rather than pretending it
     * is queued.
     */
    fun writeFrames(
        batchId: String,
        metadataJson: String,
        frameMetadataJson: List<String>,
        frames: List<SpooledFrame>
    ): File? = synchronized(lock) {
        require(frameMetadataJson.size == frames.size) {
            "frame metadata and frame count disagree: ${frameMetadataJson.size} vs ${frames.size}"
        }

        val staging = File(stagingDir, "frames_$batchId")
        return try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("could not create $staging")

            File(staging, "meta.json").writeText(metadataJson)
            frames.forEachIndexed { index, frame ->
                File(staging, frame.fileName).writeBytes(frame.bytes)
                File(staging, "${frame.fileName}.meta").writeText(frameMetadataJson[index])
            }

            val target = File(framesDir, "${System.currentTimeMillis()}_$batchId")
            if (!staging.renameTo(target)) throw IOException("could not seal $target")

            enforceBudget()
            target
        } catch (e: Exception) {
            Log.e(TAG, "failed to spool frame batch $batchId; ${frames.size} frames dropped", e)
            staging.deleteRecursively()
            null
        }
    }

    /**
     * Seals a breadcrumb batch onto disk.
     *
     * Breadcrumbs are stored as their form fields rather than as an assembled
     * request body, so the transport can change without stranding entries written
     * by an older version of the SDK.
     */
    fun writeCrumbs(batchId: String, fields: Map<String, String>): File? = synchronized(lock) {
        val staging = File(stagingDir, "crumbs_$batchId")
        return try {
            staging.writeText(encodeFields(fields))
            val target = File(crumbsDir, "${System.currentTimeMillis()}_$batchId.batch")
            if (!staging.renameTo(target)) throw IOException("could not seal $target")
            enforceBudget()
            target
        } catch (e: Exception) {
            Log.e(TAG, "failed to spool breadcrumb batch $batchId; events dropped", e)
            staging.delete()
            null
        }
    }

    // ------------------------------------------------------------------- reads

    /** Pending breadcrumb batches, oldest first. */
    fun pendingCrumbs(): List<CrumbEntry> =
        (crumbsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".batch") } ?: emptyList())
            .sortedBy { it.name }
            .mapNotNull { file ->
                try {
                    CrumbEntry(file, decodeFields(file.readText()))
                } catch (e: Exception) {
                    // Unreadable means it will never upload. Removing it is the
                    // only way the spool drains.
                    Log.e(TAG, "discarding unreadable breadcrumb batch ${file.name}", e)
                    file.delete()
                    null
                }
            }

    /** Pending frame batches, oldest first. */
    fun pendingFrames(): List<FrameEntry> =
        (framesDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedBy { it.name }
            .mapNotNull { dir ->
                try {
                    val meta = File(dir, "meta.json").readText()
                    val frames = (dir.listFiles() ?: emptyArray())
                        // Skips the batch metadata, the per-frame metadata, and any
                        // bookkeeping dotfile. The attempt counter lives in here
                        // too, and it was being read back as a frame — so after a
                        // single failed upload the retry sent the server a
                        // 1-byte "frame" containing the retry count.
                        .filter {
                            it.isFile &&
                                !it.name.startsWith(".") &&
                                !it.name.endsWith(".meta") &&
                                it.name != "meta.json"
                        }
                        .sortedBy { it.name }
                        .map { file ->
                            SpooledFrame(
                                fileName = file.name,
                                bytes = file.readBytes(),
                                isRepeated = file.name.endsWith(".signal")
                            )
                        }
                    if (frames.isEmpty()) {
                        Log.w(TAG, "discarding frame batch ${dir.name} with no frames")
                        dir.deleteRecursively()
                        null
                    } else {
                        FrameEntry(dir, meta, frames)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "discarding unreadable frame batch ${dir.name}", e)
                    dir.deleteRecursively()
                    null
                }
            }

    /** The per-frame metadata that was written beside `fileName`. */
    fun frameMetadata(dir: File, fileName: String): String? =
        File(dir, "$fileName.meta").takeIf { it.exists() }?.readText()

    // ------------------------------------------------------- success / failure

    /** The server accepted it. */
    fun acknowledge(entry: File) = synchronized(lock) {
        if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
        Unit
    }

    /**
     * The upload failed. Records the attempt, and drops the entry once it has had enough.
     *
     * Returns nothing on purpose. It used to hand back "still worth retrying", which no
     * caller read and none could usefully act on: a failed upload stops the drain either
     * way, because the next entry would meet the same dead network. Whether *this* entry
     * survived is the spool's business and is already in the log.
     */
    fun recordFailure(entry: File): Unit = synchronized(lock) {
        val counter = if (entry.isDirectory) File(entry, ATTEMPTS_FILE) else File("${entry.path}$ATTEMPTS_FILE")
        val attempts = (counter.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0) + 1

        if (attempts >= maxAttempts) {
            Log.e(TAG, "giving up on ${entry.name} after $attempts attempts")
            acknowledge(entry)
            counter.delete()
        } else {
            try {
                counter.writeText(attempts.toString())
            } catch (e: IOException) {
                Log.w(TAG, "could not record attempt for ${entry.name}", e)
            }
        }
    }

    // ------------------------------------------------------------ housekeeping

    /**
     * Clears staging left behind by a crash, and any orphaned attempt counters.
     *
     * Called at startup. A staging entry is by definition half-written, so it can
     * never be uploaded — keeping it would only consume the budget.
     */
    fun prune() = synchronized(lock) {
        stagingDir.listFiles()?.forEach { it.deleteRecursively() }
        crumbsDir.listFiles()
            ?.filter { it.name.endsWith(ATTEMPTS_FILE) }
            ?.filter { !File(it.path.removeSuffix(ATTEMPTS_FILE)).exists() }
            ?.forEach { it.delete() }
        enforceBudget()
    }

    /** Bytes currently spooled. */
    fun sizeBytes(): Long = sizeOf(framesDir) + sizeOf(crumbsDir)

    fun pendingCount(): Int =
        (framesDir.listFiles()?.count { it.isDirectory } ?: 0) +
            (crumbsDir.listFiles()?.count { it.isFile && it.name.endsWith(".batch") } ?: 0)

    /**
     * Brings the spool back under budget by deleting the oldest frame batches.
     *
     * Breadcrumbs are never evicted here. They are two orders of magnitude smaller
     * and they are the only record that an interaction happened; a dropped frame
     * costs a few hundred milliseconds of a replay that the surrounding frames
     * already describe.
     */
    private fun enforceBudget() {
        var size = sizeBytes()
        if (size <= maxBytes) return

        val oldest = (framesDir.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedBy { it.name }

        var dropped = 0
        for (dir in oldest) {
            if (size <= maxBytes) break
            val freed = sizeOf(dir)
            if (dir.deleteRecursively()) {
                size -= freed
                dropped++
            }
        }

        if (dropped > 0) {
            // Logged loudly on purpose: silent truncation reads as "the replay was
            // always like that" rather than "frames were discarded here".
            Log.w(TAG, "spool over ${maxBytes / 1024 / 1024} MB; dropped $dropped oldest frame batch(es)")
        }
        if (size > maxBytes) {
            Log.w(TAG, "spool still over budget at ${size / 1024} KB with only breadcrumbs left")
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) (file.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else file.length()

    // ------------------------------------------------------------------ codec

    /**
     * Form fields as a flat text file: one `name` line, one length, then the bytes.
     *
     * Deliberately not JSON. Breadcrumb payloads are already JSON strings and one
     * of them is a whole document; nesting it would mean escaping it, which is
     * both wasteful and a place for a mismatch between writer and reader to hide.
     * Length-prefixing means no value can be misread no matter what it contains.
     */
    private fun encodeFields(fields: Map<String, String>): String = buildString {
        for ((name, value) in fields) {
            append(name).append('\n')
            append(value.toByteArray().size).append('\n')
            append(value).append('\n')
        }
    }

    private fun decodeFields(text: String): Map<String, String> {
        val bytes = text.toByteArray()
        val fields = LinkedHashMap<String, String>()
        var offset = 0

        fun readLine(): String? {
            if (offset >= bytes.size) return null
            val end = bytes.indexOfFrom('\n'.code.toByte(), offset)
            if (end < 0) return null
            val line = String(bytes, offset, end - offset)
            offset = end + 1
            return line
        }

        while (true) {
            val name = readLine() ?: break
            val length = readLine()?.toIntOrNull()
                ?: throw IOException("malformed spool entry: no length for field '$name'")
            if (offset + length > bytes.size) {
                throw IOException("malformed spool entry: field '$name' claims $length bytes")
            }
            fields[name] = String(bytes, offset, length)
            // Skip the value and the newline that terminates it.
            offset += length + 1
        }
        return fields
    }

    private fun ByteArray.indexOfFrom(needle: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == needle) return i
        return -1
    }
}
