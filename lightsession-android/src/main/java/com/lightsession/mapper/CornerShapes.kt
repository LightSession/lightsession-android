package com.lightsession.mapper

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * The corner radii an app actually asked for, read from the modifiers it applied.
 *
 * ## Why this is read rather than assumed
 *
 * The wireframe drew every modal as a hard-edged box, and the obvious fix — round a sheet at the
 * top and a dialog on all four corners by some default — looks right for the apps that use the
 * defaults and is confidently wrong for the app that squared its corners on purpose. Being
 * confidently wrong about somebody's UI is the failure this SDK keeps paying for, and here it is
 * avoidable, because the value is readable.
 *
 * `ModalCornerProbeTest` measured the two places an answer could live, against real modals:
 *
 * | modal | `ViewOutlineProvider` | the composition |
 * |---|---|---|
 * | `ModalBottomSheet` | nothing | `RoundedCornerShape(28dp, 28dp, 0, 0)` |
 * | `AlertDialog` | nothing | `RoundedCornerShape(28dp on all four)` |
 * | `Dialog { Surface { } }` | nothing | no rounded shape — it really is square |
 *
 * The View outline is the wrong place for a Compose modal: the rounding is drawn by a `Surface`,
 * not by a View background, so not one view in either window reports a rounded outline.
 *
 * ## Why the modifiers, and not the slot table
 *
 * The first version of this walked `Group.data` looking for a `Shape` parameter and matched it to
 * a rectangle by bounds. It worked for a modal and found nothing on an ordinary screen, because a
 * plain `group.children` walk stops at a subcomposition boundary — the thing
 * [ComposeLayoutInfo.computeLayoutInfos] exists to cross, and the reason `CompositionContexts` is
 * in this package at all.
 *
 * Reading the modifier chain of a layout node is both simpler and stronger: the scan already
 * carries `modifiers` per node, already crosses subcompositions, and the bounds are the node's own
 * — so a rectangle and its corners cannot disagree, and there is no matching rule to get wrong.
 *
 * ## The reflection, and its limit
 *
 * Compose's modifier elements are internal classes, so the shape is reached by reading a field.
 * Only a *field* — nothing here invokes a method on a live runtime object, which is the line
 * [NavControllerDiscovery] draws and the one that cost a release when it was crossed.
 *
 * The lookup keys on the field's **type**, never on its name: any `Shape`-typed field will do. A
 * rename inside Compose changes nothing here, and the fields are resolved once per modifier class
 * and cached, because this runs for every node of every wireframe.
 */
internal object CornerShapes {

    /** Shape-typed fields per modifier class. Weak, so an unloaded class does not pin its loader. */
    private val shapeFields: MutableMap<Class<*>, List<Field>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * The corners this node declares, or null when it declares none.
     *
     * A node commonly carries several shapes — a `Surface` sets one on its background element and
     * again on its graphics layer, and both a clip and a shadow may add `RectangleShape`. Only a
     * [CornerBasedShape] is of interest and `RectangleShape` is not one, so the square cases drop
     * out without a special case, and the first rounded shape found wins: they describe the same
     * surface and agree.
     *
     * @param bounds the node's own box, needed because a corner may be a percentage —
     *   `RoundedCornerShape(50)` is what makes a pill, and resolving it without the box would
     *   report fifty pixels for a corner that is actually half the height
     */
    fun radiiOf(
        modifiers: List<Modifier>,
        bounds: IntRect,
        density: Density,
        rightToLeft: Boolean,
    ): CornerRadii? {
        if (bounds.width <= 0 || bounds.height <= 0) return null

        for (modifier in modifiers) {
            val shape = modifier.cornerShape() ?: continue
            val radii = runCatching {
                shape.radiiFor(bounds, density, rightToLeft)
            }.getOrNull() ?: continue
            if (!radii.isSquare) return radii
        }
        return null
    }

    /** The first corner-based shape this modifier element holds in a field, if any. */
    private fun Modifier.cornerShape(): CornerBasedShape? {
        val fields = shapeFields.getOrPut(javaClass) {
            runCatching {
                javaClass.declaredFields
                    .filter { Shape::class.java.isAssignableFrom(it.type) }
                    .onEach { it.isAccessible = true }
            }.getOrDefault(emptyList())
        }
        for (field in fields) {
            val value = runCatching { field.get(this) }.getOrNull()
            if (value is CornerBasedShape) return value
        }
        return null
    }

    /**
     * A shape's four corners, in visual order.
     *
     * `topStart`/`topEnd` are mirrored here, where the layout direction is known. The renderer
     * draws pixels and has no start or end to resolve.
     */
    private fun CornerBasedShape.radiiFor(
        bounds: IntRect,
        density: Density,
        rightToLeft: Boolean,
    ): CornerRadii {
        val size = Size(bounds.width.toFloat(), bounds.height.toFloat())
        val start = topStart.toPx(size, density).toInt()
        val end = topEnd.toPx(size, density).toInt()
        val bottomEndPx = bottomEnd.toPx(size, density).toInt()
        val bottomStartPx = bottomStart.toPx(size, density).toInt()

        return if (rightToLeft) {
            CornerRadii(end, start, bottomStartPx, bottomEndPx)
        } else {
            CornerRadii(start, end, bottomEndPx, bottomStartPx)
        }
    }
}
