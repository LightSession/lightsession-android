package com.lightsession

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

class ViewMasker(
    private val maskText: Boolean,
    private val maskImages: Boolean,
    private val debugDrawMasks: Boolean
) {

    /**
     * Collects all maskable rectangles within the given root view hierarchy.
     * @param rootView The root view to start the collection from.
     * @return A list of Rect objects representing the areas to be masked.
     */
    fun collectMaskableRects(rootView: View): List<Rect> {
        val maskableRects = mutableListOf<Rect>()
        findMaskableWidgets(rootView, maskableRects)
        return maskableRects
    }

    /**
     * Recursively traverses the view hierarchy to identify and add maskable widgets'
     * visible bounds to the provided list.
     * @param view The current view being inspected.
     * @param maskableRects The list to which maskable rectangles will be added.
     */
    private fun findMaskableWidgets(view: View?, maskableRects: MutableList<Rect>) {
        if (view == null || view.visibility != View.VISIBLE) {
            return
        }

        if (view is TextView && maskText) {
            if (!TextUtils.isEmpty(view.text) || !TextUtils.isEmpty(view.hint)) {
                val layout = view.layout
                if (layout != null) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val textViewLeft = location[0]
                    val textViewTop = location[1]

                    for (i in 0 until layout.lineCount) {
                        val lineBounds = Rect()
                        layout.getLineBounds(i, lineBounds)

                        val lineLeft = layout.getLineLeft(i)
                        val lineRight = layout.getLineRight(i)

                        val globalLeft = textViewLeft + view.paddingLeft + lineLeft.toInt()
                        val globalTop = textViewTop + view.paddingTop + lineBounds.top
                        val globalRight = textViewLeft + view.paddingLeft + lineRight.toInt()
                        val globalBottom = textViewTop + view.paddingTop + lineBounds.bottom

                        val textRect = Rect(globalLeft, globalTop, globalRight, globalBottom)
                        if (!textRect.isEmpty) {
                            maskableRects.add(textRect)
                        }
                    }
                } else {
                    Log.w(
                        "ViewMasker",
                        "TextView layout is null, masking entire view for debug: ${view.javaClass.simpleName}"
                    )
                    val rect = Rect()
                    if (view.getGlobalVisibleRect(rect)) {
                        if (!rect.isEmpty) {
                            maskableRects.add(rect)
                        }
                    }
                }
            }
        } else if (view is ImageView && maskImages) {
            val rect = Rect()
            if (view.getGlobalVisibleRect(rect)) {
                if (!rect.isEmpty) {
                    maskableRects.add(rect)
                }
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                findMaskableWidgets(child, maskableRects)
            }
        }
    }

    /**
     * Draws mask rectangles and their borders onto the given bitmap for debugging purposes.
     * @param bitmap The bitmap to draw the masks on. Must be mutable.
     * @param maskRects The list of Rect objects representing the areas to be masked.
     */
    fun drawMaskRectsOnBitmap(bitmap: Bitmap, maskRects: List<Rect>) {
        if (!bitmap.isMutable) {
            Log.e("ViewMaskerDebug", "Bitmap must be mutable to draw masks.")
            return
        }
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.argb(128, 0, 255, 0) // Semi-transparent green
            style = Paint.Style.FILL
            strokeWidth = 2f
        }

        val borderPaint = Paint().apply {
            color = Color.RED // Red border
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        for (rect in maskRects) {
            canvas.drawRect(rect, paint)
            canvas.drawRect(rect, borderPaint)
        }
    }
}