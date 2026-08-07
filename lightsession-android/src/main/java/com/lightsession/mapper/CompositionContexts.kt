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

private fun CompositionContext.composersField(): Field? {
    val cls = javaClass
    composersFieldByClass[cls]?.let { return it }
    if (cls in classesWithoutComposers) return null

    // `composers` is a Set<Composer>, and the declared type is generic — so the field is
    // identified by what it holds rather than by its type.
    val field = runCatching {
        cls.declaredFields
            .asSequence()
            .filter { Iterable::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .firstOrNull { candidate ->
                val value = runCatching { candidate.get(this) }.getOrNull() as? Iterable<*>
                value != null && value.all { it is Composer }
            }
    }.getOrNull()

    if (field == null) classesWithoutComposers += cls else composersFieldByClass[cls] = field
    return field
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

@Suppress("UNCHECKED_CAST")
internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
    val field = composersField() ?: return emptyList()
    return runCatching { field.get(this) as? Iterable<Composer> }.getOrNull() ?: emptyList()
}
