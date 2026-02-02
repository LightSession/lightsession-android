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
import kotlin.LazyThreadSafetyMode.PUBLICATION
import kotlin.apply
import kotlin.collections.asSequence
import kotlin.jvm.java
import kotlin.let
import kotlin.run
import kotlin.sequences.filter
import kotlin.sequences.mapNotNull

private val REFLECTION_CONSTANTS by lazy(PUBLICATION) {
    try {
        object {
            val CompositionContextHolderClass =
                Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextHolder")
            val CompositionContextImplClass =
                Class.forName("androidx.compose.runtime.ComposerImpl\$CompositionContextImpl")
            val CompositionContextHolderRefField =
                CompositionContextHolderClass.getDeclaredField("ref")
                    .apply { isAccessible = true }
            val CompositionContextImplComposersField =
                CompositionContextImplClass.getDeclaredField("composers")
                    .apply { isAccessible = true }
        }
    } catch (e: Throwable) {
        null
    }
}

@OptIn(UiToolingDataApi::class)
internal fun Group.getCompositionContexts(): Sequence<CompositionContext> {
  return REFLECTION_CONSTANTS?.run {
    data.asSequence()
      .filter { it != null && it::class.java == CompositionContextHolderClass }
      .mapNotNull { holder -> holder.tryGetCompositionContext() }
  } ?: emptySequence()
}

@Suppress("UNCHECKED_CAST")
internal fun CompositionContext.tryGetComposers(): Iterable<Composer> {
  return REFLECTION_CONSTANTS?.let {
    if (!it.CompositionContextImplClass.isInstance(this)) return emptyList()
    it.CompositionContextImplComposersField.get(this) as? Iterable<Composer>
  } ?: emptyList()
}

private fun Any?.tryGetCompositionContext() = REFLECTION_CONSTANTS?.let {
  it.CompositionContextHolderRefField.get(this) as? CompositionContext
}
