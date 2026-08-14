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
 * Original File: radiography/src/main/java/radiography/internal/ComposeLayoutInfo.kt
 */

@file:OptIn(UiToolingDataApi::class)
package com.lightsession.mapper

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composer
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.node.InteroperableComposeUiNode
import androidx.compose.ui.node.Ref
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.tooling.data.CallGroup
import androidx.compose.ui.tooling.data.Group
import androidx.compose.ui.tooling.data.NodeGroup
import androidx.compose.ui.tooling.data.SourceLocation
import androidx.compose.ui.tooling.data.UiToolingDataApi
import androidx.compose.ui.tooling.data.asTree
import androidx.compose.ui.unit.IntRect
import com.lightsession.mapper.ComposeLayoutInfo.AndroidViewInfo
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.collections.asSequence
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.plus
import kotlin.collections.single
import kotlin.let
import kotlin.sequences.flatMap
import kotlin.sequences.map
import kotlin.sequences.partition
import kotlin.sequences.plus
import kotlin.text.orEmpty

/**
 * Information about a Compose `LayoutNode`, extracted from a [Group] tree via [Group.computeLayoutInfos].
 *
 * This is a useful layer of indirection from directly handling Groups because it allows us to
 * define our own notion of what an atomic unit of "composable" is independently from how Compose
 * actually represents things under the hood. When this changes in some future dev version, we
 * only need to update the "parsing" logic in this file.
 * It's also helpful since we actually gather data from multiple Groups for a single LayoutInfo,
 * so parsing them ahead of time into these objects means the visitor can be stateless.
 */
internal sealed class ComposeLayoutInfo {
  data class LayoutNodeInfo(
      val name: String,
      val callChain: List<CallGroupInfo>,
      val bounds: IntRect,
      val modifiers: List<Modifier>,
      val children: Sequence<ComposeLayoutInfo>,
      val semanticsNodes: List<SemanticsNode>,
  ) : ComposeLayoutInfo()

  data class SubcompositionInfo(
    val name: String,
    val callChain: List<CallGroupInfo>,
    val bounds: IntRect,
    val children: Sequence<ComposeLayoutInfo>
  ) : ComposeLayoutInfo()

  data class AndroidViewInfo(
    val view: View
  ) : ComposeLayoutInfo()
}

/**
 * Recursively parses [ComposeLayoutInfo]s from a [Group]. Groups form a tree and can contain different
 * type of nodes which represent function calls, arbitrary data stored directly in the slot table,
 * or just subtrees.
 *
 * This function walks the tree and collects only Groups which represent emitted values
 * ([NodeGroup]s). These either represent `LayoutNode`s (Compose's internal primitive for layout
 * algorithms) or classic Android views that the composition emitted. This function collapses all
 * the groups in between each of these nodes, but uses the top-most Group under the previous node
 * to derive the "name" of the [ComposeLayoutInfo]. The other [ComposeLayoutInfo] properties come directly off
 * [NodeGroup] values.
 *
 * To preserve details about the Call Groups between Layout Nodes, the call chain is preserved in order
 * to provide granular detail about the hierarchy if desired.
 */
/**
 * Semantics indexed by node id, built once.
 *
 * The original called `getAllSemanticsNodes()` inside the loop, once per LayoutNode — on a screen
 * with 200 nodes that is 200 full walks of the semantics tree, on the main thread, during a
 * navigation. Grouping by id once makes each lookup O(1).
 */
internal class SemanticsIndex(owner: SemanticsOwner?) {
  private val byId: Map<Int, List<SemanticsNode>> =
    owner?.getAllSemanticsNodes(mergingEnabled = false)?.groupBy { it.id } ?: emptyMap()

  val isEmpty: Boolean get() = byId.isEmpty()

  fun nodesFor(semanticsId: Int?): List<SemanticsNode> =
    semanticsId?.let { byId[it] } ?: emptyList()

  companion object {
    val EMPTY = SemanticsIndex(null)
  }
}

internal fun Group.computeLayoutInfos(
    parentCallChain: List<CallGroupInfo> = emptyList(),
    /**
   * The semantics owner for this Group. This is used to look up the semantics nodes for each
   * layout node.
   */
  semantics: SemanticsIndex = SemanticsIndex.EMPTY,
  /**
   * Composers already walked in this scan, so each subcomposition is reported once.
   *
   * A subcomposition is reachable by two routes and Compose 1.7 publishes both: the
   * `CompositionContextHolder` wrapper and the context itself, in *different* groups.
   * Deduplicating inside `getCompositionContexts` cannot see that — it is called per group — so
   * the same `LazyColumn` was walked twice and every item drawn on top of itself, 185 rects
   * where 58 were right.
   *
   * Scan-scoped: the default argument makes it once per entry, and every recursive call below
   * passes the same set along. A fresh one per level would defeat it entirely.
   */
  visited: MutableSet<Composer> = Collections.newSetFromMap(IdentityHashMap()),
): Sequence<ComposeLayoutInfo> {
  val callChain = this.name?.let { parentCallChain + CallGroupInfo(it, this.location) } ?: parentCallChain

  // Things that we want to consider children of the current node, but aren't actually child nodes
  // as reported by Group.children.
  val irregularChildren = subComposedChildren(callChain, semantics, visited) + androidViewChildren()

  // Certain composables produce an internal structure that is hard to read if we report it exactly.
  // Instead, we use heuristics to recognize subtrees that match certain expected structures and
  // aggregate them somewhat before reporting.
  tryParseSubcomposition(callChain, irregularChildren, semantics, visited)
    ?.let { return it }
  tryParseAndroidView(callChain, irregularChildren, semantics, visited)
    ?.let { return it }

  // This is an intermediate group that doesn't represent a LayoutNode, so we flatten by just
  // reporting its children without reporting a new subtree.
  if (this !is NodeGroup) {
    return children.asSequence()
      .flatMap { it.computeLayoutInfos(callChain, semantics, visited) } + irregularChildren
  }

  val children = children.asSequence()
    // This node will "consume" the name, so reset it name to empty for children.
    .flatMap { it.computeLayoutInfos(semantics = semantics, visited = visited) }

  val semanticsId = (this.node as? LayoutInfo)?.semanticsId
  val semanticsNodes = semantics.nodesFor(semanticsId)

  val layoutInfo = ComposeLayoutInfo.LayoutNodeInfo(
    name = callChain.firstOrNull()?.name.orEmpty(),
    callChain = callChain,
    bounds = box,
    modifiers = modifierInfo.map { it.modifier },
    semanticsNodes = semanticsNodes,
    children = children + irregularChildren,
  )
  return sequenceOf(layoutInfo)
}

/**
 * Look for any `CompositionContext`s stored in this group. These will be rolled up into the
 * `SubcomposeLayout` if present, otherwise they will just be shown as regular children.
 * The compositionData val is marked as internal, and not intended for public consumption.
 * The returned [SubcompositionInfo]s should be collated by [tryParseSubcomposition].
 */
private fun Group.subComposedChildren(
  callChain: List<CallGroupInfo>,
  semantics: SemanticsIndex,
  visited: MutableSet<Composer>,
): Sequence<ComposeLayoutInfo.SubcompositionInfo> =
  getCompositionContexts()
    .flatMap { it.tryGetComposers().asSequence() }
    // The composer, not the context, is what identifies a subcomposition: two routes to the
    // same composition hand back two context objects but the same composer.
    .filter { visited.add(it) }
    .map { subcomposer ->
      ComposeLayoutInfo.SubcompositionInfo(
        name = callChain.firstOrNull()?.name.orEmpty(),
        callChain = callChain,
        bounds = box,
        children = subcomposer.compositionData.asTree()
          .computeLayoutInfos(semantics = semantics, visited = visited)
      )
    }

/**
 * The `AndroidView` composable remembers a [Ref] to a special internal subclass of [ViewGroup] that
 * manages the wiring between the hosting android view and the child view. This function looks for
 * refs to views and returns them as [AndroidViewInfo]s to be collated with [tryParseAndroidView].
 *
 * Note that [Ref] is a public type – any third-party composable could also remember a ref to a
 * view, and it would be reported by this function. That would almost certainly be a code smell for
 * a number of reasons though, so we don't try to ignore those cases.
 */
@OptIn(InternalComposeUiApi::class)
private fun Group.androidViewChildren(): List<ComposeLayoutInfo.AndroidViewInfo> {
  return data.mapNotNull { datum ->
    (datum as? InteroperableComposeUiNode)
      ?.getInteropView()
      ?.let(::AndroidViewInfo)
  }
}

@OptIn(UiToolingDataApi::class)
internal data class CallGroupInfo(
    val name: String,
    val location: SourceLocation?,
)

/**
 * SubcomposeLayouts need to be handled specially, because all their subcompositions are always
 * logical children of their single LayoutNode. In order to render them so that the rendering
 * actually matches that logical structure, we need to reorganize the subtree a bit so
 * subcompositions are children of the layout node and not siblings of it.
 *
 * Note that there's no sure-fire way to actually detect a SubcomposeLayout. The best we can do is
 * use a heuristic. If any part of the heuristics don't match, then we fall back to treating the
 * group like any other.
 *
 * The heuristic we use is:
 * - Name of the group is "SubcomposeLayout".
 * - Has one or more subcompositions under it.
 * - Has exactly one LayoutNode child.
 * - That LayoutNode has no children of its own.
 */
private fun Group.tryParseSubcomposition(
  callChain: List<CallGroupInfo>,
  irregularChildren: Sequence<ComposeLayoutInfo>,
  semantics: SemanticsIndex,
  visited: MutableSet<Composer>,
): Sequence<ComposeLayoutInfo>? {
  if (this.name != "SubcomposeLayout") return null

  val (subcompositions, regularChildren) =
    (children.asSequence().flatMap { it.computeLayoutInfos(callChain, semantics, visited) } + irregularChildren)
      .partition { it is ComposeLayoutInfo.SubcompositionInfo }
      .let {
        // There's no type-safe partition operator so we just cast.
        @Suppress("UNCHECKED_CAST")
        it as Pair<List<ComposeLayoutInfo.SubcompositionInfo>, List<ComposeLayoutInfo>>
      }

  if (subcompositions.isEmpty()) return null
  if (regularChildren.size != 1) return null

  val mainNode = regularChildren.single()
  if (mainNode !is ComposeLayoutInfo.LayoutNodeInfo) return null
  if (!mainNode.children.isEmpty()) return null

  // We can be pretty confident at this point that this is an actual SubcomposeLayout, so
  // expose its layout node as the parent of all its subcompositions.
  val subcompositionName = "<subcomposition of ${mainNode.name}>"
  return sequenceOf(
      mainNode.copy(
          children = subcompositions.asSequence()
              .map { it.copy(name = subcompositionName) }
      )
  )
}

/**
 * The AndroidView composable also needs to be special-cased. The actual android view is stored
 * in a Ref deep inside the hierarchy somewhere, but we want to expose it as the immediate child
 * of nearest common parent node that contains both the android view and the LayoutNode that is
 * used as a proxy to measure and lay it out in the composable.
 *
 * We can't rely on just the composable name, since any composable could be called "AndroidView",
 * so if any of the subtree parsing fails to match our expectations, we fallback to treating it
 * like any other group. Note that this heuristic isn't as strict as the subcomposition one, since
 * there's only one way to get an android view into a composition, so we can rely more heavily on
 * the presence of an actual android view. We still require there to be only one LayoutNode child,
 * otherwise it would be ambiguous which node we should report as the parent of the view.
 * We also require the common parent to be a CallGroup, since that is a valid assumption as of the
 * time of this writing and it saves us the additional logic of having to decide whether to return
 * this or the mainNode as the root of the subtree if this is a NodeGroup for some reason.
 *
 * Note that while this looks very similar to the [tryParseSubcomposition], that is probably
 * mostly coincidental, so it's probably not a good idea to factor out any abstractions. Since
 * they both rely on internal-only implementation details of how the Compose runtime happens to
 * work, either of them could change independently in the future, and it will be easier to update
 * the logic of both if that happens if they're completely independent.
 */
private fun Group.tryParseAndroidView(
  callChain: List<CallGroupInfo>,
  irregularChildren: Sequence<ComposeLayoutInfo>,
  semantics: SemanticsIndex,
  visited: MutableSet<Composer>,
): Sequence<ComposeLayoutInfo>? {
  if (this.name != "AndroidView") return null
  if (this !is CallGroup) return null

  val (androidViews, regularChildren) =
    (children.asSequence().flatMap { it.computeLayoutInfos(callChain, semantics, visited) } + irregularChildren)
      .partition { it is AndroidViewInfo }
      .let {
        // There's no type-safe partition operator so we just cast.
        @Suppress("UNCHECKED_CAST")
        it as Pair<List<AndroidViewInfo>, List<ComposeLayoutInfo>>
      }

  if (androidViews.isEmpty()) return null
  if (regularChildren.size != 1) return null

  val mainNode = regularChildren.single()
  if (mainNode !is ComposeLayoutInfo.LayoutNodeInfo) return null

  // We can be pretty confident at this point that this is an actual AndroidView composable,
  // so expose its layout node as the parent of its actual view.
  return sequenceOf(mainNode.copy(children = mainNode.children + androidViews))
}

private fun Sequence<*>.isEmpty(): Boolean = !iterator().hasNext()
