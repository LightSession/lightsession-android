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
import kotlin.run
import kotlin.sequences.filter
import kotlin.sequences.mapNotNull

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
 * `asSet()` is named because it is the public, documented way to view a `ScatterSet` as one, and
 * the search falls through to *any* zero-argument method returning something iterable — the
 * result is validated by its contents either way, so a wrong guess cannot be mistaken for a
 * right one. Naming it is a hint, not a dependency: this file's whole argument is that a walk
 * over another library's internals must recognise shapes rather than names.
 */
private fun elementsOf(value: Any?): Iterable<*>? {
    if (value == null) return null
    if (value is Iterable<*>) return value

    val cls = value.javaClass
    elementsViewByClass[cls]?.let { view ->
        return runCatching { view.invoke(value) as? Iterable<*> }.getOrNull()
    }
    if (cls in classesWithoutElementsView) return null

    val view = runCatching {
        cls.methods
            .asSequence()
            .filter { it.parameterCount == 0 && Iterable::class.java.isAssignableFrom(it.returnType) }
            .sortedBy { if (it.name == "asSet") 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    if (view == null) {
        classesWithoutElementsView += cls
        return null
    }
    elementsViewByClass[cls] = view
    return runCatching { view.invoke(value) as? Iterable<*> }.getOrNull()
}

private fun CompositionContext.composersField(): Field? {
    val cls = javaClass
    composersFieldByClass[cls]?.let { return it }
    if (cls in classesWithoutComposers) return null

    // The field is identified by what it *holds*, not by its declared type: the type is generic,
    // and since 1.11 it is not even a collection interface.
    var sawEmptyCandidate = false
    val field = runCatching {
        cls.declaredFields
            .asSequence()
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
