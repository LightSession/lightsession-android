package com.lightsession.errors

/**
 * One frame of an error this SDK did not see thrown, as the runtime that threw it describes it.
 *
 * For [com.lightsession.LightSession.recordError]. A `Throwable` carries JVM frames, and the
 * runtimes this exists for do not have any: a Dart exception in a Flutter app, a JavaScript one in
 * React Native. Forcing one into a synthetic `Throwable` would ruin the two fields the server
 * groups on — its type would be the wrapper's class name, so every such error would collapse into
 * one group, and no frame would ever be `in_app`, so the grouping would fall back to framework
 * frames and split or merge defects at random.
 *
 * So the embedder hands over what its own runtime knows, and it is serialised into exactly the
 * shape a JVM frame takes. Nothing downstream can tell the two apart, which is the point: the
 * grouping, the error dashboard and the screen attribution all work unchanged.
 */
public class ErrorFrame(
    /**
     * What the frame belongs to — the class of a method, or the library of a top-level function.
     *
     * Sent as `class`, the field a JVM frame fills with a fully qualified class name. The server
     * keys a group on `class.method` for the first in-app frames, so this is half of what decides
     * which errors are the same defect.
     */
    public val module: String,
    /** The function or method, sent as `method` — the other half of the grouping key. */
    public val function: String,
    /** The source file as the runtime names it, when it names one. */
    public val file: String? = null,
    /** The line, when the runtime knows it. Never part of the grouping key. */
    public val line: Int? = null,
    /**
     * Whether this frame is the app's own code rather than a framework's or a library's.
     *
     * The embedder decides, because it is the only side that knows what "the app's own code" means
     * in its runtime — a JVM package prefix answers the question for Kotlin and nothing else. The
     * server weighs it most: it keys a group on the first in-app frames, and falls back to frames
     * of any origin only when none is marked.
     */
    public val inApp: Boolean = false,
    /**
     * Where the frame was in the build, when the runtime can say nothing else about it.
     *
     * A release a runtime compiled without names — a Flutter app built with `--obfuscate` or
     * `--split-debug-info` — reports its stack as addresses. Pass the address with an empty
     * [module] and [function], and the build it belongs to as `symbols` on
     * [com.lightsession.LightSession.recordError]; the server names the frame from that build's
     * uploaded symbols before the error is grouped, and groups it on the address until they are
     * uploaded. Sent as `addr`, in hex.
     */
    public val address: Long? = null,
)
