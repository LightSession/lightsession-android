package com.lightsession

/**
 * The platform this SDK runs on, as the server names it.
 *
 * Every write carries it, and the server checks it against the project the API key belongs to. A
 * project created for Android refuses `ios`, a project created for React Native or Flutter accepts
 * both — one codebase, two platforms — and a project whose SDK is too old to send anything is
 * accepted as before.
 *
 * It exists because of a mistake that has already happened: an Android project's key was pasted
 * into an iOS integration and nothing objected. Both apps reported into the same project, the screen
 * map filled with the screens of two different apps, and the only symptom was somebody looking at
 * the graph and not recognising it. The key alone cannot tell the two apart — it identifies a
 * project, not a platform — so the report has to say.
 *
 * A constant rather than something detected at runtime: this artifact only ever runs on Android, so
 * anything else it could report would be a lie. The React Native SDK is the one that decides between
 * the two at runtime, because it is the one that can be either.
 */
internal const val PLATFORM = "android"
