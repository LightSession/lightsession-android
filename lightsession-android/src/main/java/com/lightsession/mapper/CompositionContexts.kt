/*
 * Copyright (c) 2020-2025 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ------------------------------------------------------------------
 *
 * This file contains code adapted from the Radiography library.
 * Original Source: https://github.com/block/radiography
 * Original File: radiography/src/main/java/radiography/internal/CompositionContexts.kt
 */
package com.lightsession.mapper

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.UiToolingDataApi
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.apply
import kotlin.collections.asSequence
import kotlin.jvm.java
import kotlin.let
import kotlin.sequences.filter

/**
 * Found by **structure**, not by class name.
 *
 * The original did `Class.forName("androidx.compose.runtime.ComposerImpl$CompositionContextHolder")`.
 * In a minified release R8 renames that class, the `forName` throws ClassNotFoundException, the catch
 * clears REFLECTION_CONSTANTS, and [getCompositionContexts] starts returning an empty sequence — so
 * every subcomposition disappears from the skeleton. A `LazyColumn`, a `LazyRow`, a bottom sheet: on
 * a screen that is a list, what survives is the frame around it. Silently, and only in release, which
 * is the combination that makes it expensive to find.
 *
 * Looking for a field whose *type* is CompositionContext works after obfuscation, because the type
 * reference is resolved by the compiler rather than by a string. The result is memoised per class, so
 * the reflection is paid for once.
 */
private val contextFieldByClass = ConcurrentHashMap<Class<*>, Field>()
private val classesWithoutContext: MutableSet<Class<*>> =
    Collections.newSetFromMap(ConcurrentHashMap())

private fun Any.compositionContextField(): Field? {
    val cls = javaClass
    contextFieldByClass[cls]?.let { return it }
    if (cls in classesWithoutContext) return null

    val field = runCatching {
        cls.declaredFields.firstOrNull { CompositionContext::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
    }.getOrNull()

    if (field == null) classesWithoutContext += cls else contextFieldByClass[cls] = field
    return field
}

private val composersFieldByClass = ConcurrentHashMap<Class<*>, Field>()
private val classesWithoutComposers: MutableSet<Class<*>> =
    Collections.newSetFromMap(ConcurrentHashMap())

/** Memoised per collection class; absence is memoised as the class's own entry mapping to itself. */
private val elementsViewByClass = ConcurrentHashMap<Class<*>, Method>()
private val classesWithoutElementsView: MutableSet<Class<*>> =
    Collections.newSetFromMap(ConcurrentHashMap())

/**
 * The elements of a value that holds a collection, whatever collection type Compose used.
 *
 * `composers` was a `Set<Composer>` until Compose 1.11, where it became an
 * `androidx.collection.MutableScatterSet` — which is a set in every sense except the one this
 * code depended on: it does not implement `Iterable`. Nothing threw. The field simply stopped
 * matching, `LazyColumn` and `Scaffold` contributed no nodes again, and the wireframe of a list
 * screen became the frame around an empty page. Bracketed to `ui-tooling-data` 1.11.1: 1.10.5
 * walks 27 rects for twenty items, 1.11.1 walks 7.
 *
 * Exactly two shapes are read, and the narrowness is the lesson of this function's first
 * version, learned the expensive way. That version fell through to invoking *any*
 * zero-argument method returning something iterable, on the theory that the result would be
 * validated by its contents anyway. Validating the result does not undo the invocation: the
 * field walk reaches values like the context's own `this$0` — a live `GapComposer`,
 * mid-composition — and calling an arbitrary method on that is running arbitrary runtime code
 * at the worst possible moment. On Compose 1.12 one such call left the host's `ComposeView`
 * measured at 0×0 permanently: every screen of the app blank, no exception anywhere, bisected
 * on a real app to precisely this fallback. Reading a field is passive; *invoking* is not, and
 * a walk over another library's internals only gets to do the first.
 *
 * `asSet()` on a `ScatterSet` is the one invocation kept, because it is the documented,
 * side-effect-free view Compose itself provides — and it is called only on values whose class
 * name says that is what they are.
 */
private fun elementsOf(value: Any?): Iterable<*>? {
    if (value == null) return null
    if (value is Iterable<*>) return value

    val cls = value.javaClass
    if (!isScatterSet(cls)) return null

    elementsViewByClass[cls]?.let { view ->
        return runCatching { view.invoke(value) as? Iterable<*> }.getOrNull()
    }
    if (cls in classesWithoutElementsView) return null

    val view = runCatching {
        cls.methods.firstOrNull {
            it.name == "asSet" && it.parameterCount == 0 &&
                Iterable::class.java.isAssignableFrom(it.returnType)
        }
    }.getOrNull()

    if (view == null) {
        classesWithoutElementsView += cls
        return null
    }
    elementsViewByClass[cls] = view
    return runCatching { view.invoke(value) as? Iterable<*> }.getOrNull()
}

/**
 * Whether this is `androidx.collection`'s ScatterSet family, by name up the hierarchy.
 *
 * By name rather than by `Class.forName`: the SDK does not depend on androidx.collection
 * directly, and resolving the class through this module's classloader would tie the check to
 * classloader topology when all it needs is to recognise what it is looking at.
 */
private fun isScatterSet(cls: Class<*>): Boolean {
    var current: Class<*>? = cls
    while (current != null) {
        if (current.name == "androidx.collection.ScatterSet" ||
            current.name == "androidx.collection.MutableScatterSet"
        ) {
            return true
        }
        current = current.superclass
    }
    return false
}

private fun CompositionContext.composersField(): Field? {
    val cls = javaClass
    composersFieldByClass[cls]?.let { return it }
    if (cls in classesWithoutComposers) return null

    // The field is identified by what it *holds* — the declared type is generic — but only
    // fields whose declared type already looks like a collection are read at all. The first
    // version read every field and probed every value, which put a live `GapComposer` (the
    // context's `this$0`) through the probe; see [elementsOf] for what that cost.
    var sawEmptyCandidate = false
    val field = runCatching {
        cls.declaredFields
            .asSequence()
            .filter { Iterable::class.java.isAssignableFrom(it.type) || isScatterSet(it.type) }
            .onEach { it.isAccessible = true }
            .firstOrNull { candidate ->
                val elements = elementsOf(runCatching { candidate.get(this) }.getOrNull())
                    ?: return@firstOrNull false
                // Non-empty, and that is not fussiness. `all` on an empty collection is
                // vacuously true, so the old test accepted the first empty one it met — and
                // `GapComposer$CompositionContextImpl` ships `inspectionTables`, an ordinary
                // empty `Set`, right beside the field actually wanted. An empty decoy would win
                // and every subcomposition would be silently absent.
                val usable = elements.any() && elements.all { it is Composer }
                if (!usable && elements.none()) sawEmptyCandidate = true
                usable
            }
    }.getOrNull()

    if (field != null) {
        composersFieldByClass[cls] = field
        return field
    }
    // Not remembered when the only disqualification was emptiness. A context asked before it has
    // any subcompositions has an empty `composers`, and blacklisting the class on that would
    // blind this scan to it for the life of the process — the failure being fixed here, arrived
    // at from the other direction.
    if (!sawEmptyCandidate) classesWithoutComposers += cls
    return null
}

/**
 * Both shapes a slot table stores a [CompositionContext] in, because Compose changed which.
 *
 * Asking only "which datum *holds* one in a field" was right for Compose 1.7, where the
 * `ComposerImpl$CompositionContextHolder.ref` wrapper was there to find. That class is gone on
 * the 2026.02.01 BOM and the context sits in `data` unwrapped, so the question matched nothing:
 * `LazyColumn` and `Scaffold` contributed no nodes, and a screen built from them stored a
 * wireframe of one rect — the frame around an empty page. Silently, and only against a Compose
 * newer than the one this module compiles against, which is every real consumer.
 *
 * Both are asked, and neither is preferred: a reflective walk over another library's internals
 * cannot know which shape it is looking at from a version number, only from what is in front of
 * it. That means 1.7 answers twice, once per route to the same composition — deduplicated by
 * `Composer` in `computeLayoutInfos`, which is the level that can see a whole scan. Doing it
 * here would not work; this function is called per group, and the two routes sit in different
 * ones.
 */
@OptIn(UiToolingDataApi::class)
internal fun Group.getCompositionContexts(): Sequence<CompositionContext> =
    data.asSequence()
        .filterNotNull()
        .flatMap { datum ->
            sequenceOf(
                // The context itself, stored directly.
                datum as? CompositionContext,
                // Or a wrapper around it.
                datum.compositionContextField()
                    ?.let { runCatching { it.get(datum) as? CompositionContext }.getOrNull() },
            )
        }
        .filterNotNull()

internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
    val field = composersField() ?: return emptyList()
    val elements = elementsOf(runCatching { field.get(this) }.getOrNull()) ?: return emptyList()
    // Filtered rather than cast: the shape was recognised from a sample, and a collection that
    // grew a non-Composer since would otherwise reach the walk as one.
    return elements.filterIsInstance<Composer>()
}
