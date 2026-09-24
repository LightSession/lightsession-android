package com.lightsession.errors

/**
 * The build whose symbols name an error's addresses, for [com.lightsession.LightSession.recordError].
 *
 * A runtime that compiles its release builds without names — Dart, with `--obfuscate` or
 * `--split-debug-info` — reports an error's frames as addresses, and prints a build id beside them.
 * The names are in a file that build left on the machine that made it, uploaded to the server once
 * per build. This says which one: the server looks the file up by [buildId], names the frames with
 * it, and only then groups the error, so a defect keeps its group from one release to the next.
 */
public class ErrorSymbols(
    /** What kind of symbols they are. `dart` is the kind the server reads. */
    public val kind: String,
    /** The build id the runtime printed with the stack, in hex. */
    public val buildId: String,
    /** The architecture the runtime printed — `arm64`, `arm`, `x64`. For the record only. */
    public val arch: String? = null,
    /**
     * The app's own package, when the embedder knows it. The server marks a named frame in-app by
     * it; without it, it uses the package the upload named.
     */
    public val appPackage: String? = null,
)
