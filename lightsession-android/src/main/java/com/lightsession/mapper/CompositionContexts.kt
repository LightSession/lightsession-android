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
 * Descoberta por ESTRUTURA, não por nome de classe.
 *
 * O original fazia `Class.forName("androidx.compose.runtime.ComposerImpl$CompositionContextHolder")`.
 * Num release minificado o R8 renomeia essa classe, o `forName` lança
 * ClassNotFoundException, o catch zera REFLECTION_CONSTANTS e
 * [getCompositionContexts] passa a devolver sequência vazia — ou seja, toda
 * subcomposição (LazyColumn, LazyRow, bottom sheet) desaparece do skeleton, em
 * silêncio.
 *
 * Procurar um campo cujo TIPO é CompositionContext funciona depois da ofuscação,
 * porque a referência de tipo é resolvida pelo compilador. O resultado é
 * memoizado por classe, então o custo de reflection é pago uma vez.
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

    // `composers` é um Set<Composer>; o tipo declarado é genérico, então
    // identificamos pelo conteúdo.
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

@OptIn(UiToolingDataApi::class)
internal fun Group.getCompositionContexts(): Sequence<CompositionContext> =
    data.asSequence()
        .filterNotNull()
        .mapNotNull { holder ->
            holder.compositionContextField()
                ?.let { runCatching { it.get(holder) as? CompositionContext }.getOrNull() }
        }

@Suppress("UNCHECKED_CAST")
internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
    val field = composersField() ?: return emptyList()
    return runCatching { field.get(this) as? Iterable<Composer> }.getOrNull() ?: emptyList()
}
