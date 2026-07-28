package com.lightsession

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/**
 * What gets covered before a captured screen leaves the device.
 *
 * ## Why on the device
 *
 * The alternative was to send the sensitive rectangles to the server and let it draw
 * over the image — the same trick that makes wireframes cheap. It does not work here,
 * and the reason is worth stating plainly: **the unmasked pixels would already be on
 * the server.** Masking would be a rendering choice applied to content that had already
 * been transmitted and stored. Any bug that served the original, any bucket that leaked,
 * any backup that outlived the policy, and the protection is gone. Masking on the device
 * is the only version where the sensitive pixels never exist anywhere else.
 *
 * ## What it costs
 *
 * The objection to on-device masking was processor time, since the SDK competes on
 * being lighter than the alternatives. Measured on a 1080×2400 frame
 * (`MaskingCostTest`), against a JPEG encode the SDK already pays:
 *
 * ```
 * JPEG encode (q=80)   8.254 ms
 * draw 10 mask rects   0.166 ms   =  2.01% of the encode
 * draw 50 mask rects   0.353 ms   =  4.28% of the encode
 * ```
 *
 * Two percent. The premise survives; the argument against does not.
 *
 * ## Why a policy object
 *
 * Three separate places construct a `ScreenDrawing`, none of which sees the config, and
 * a capture path that forgets to mask is the only failure that really matters here. So
 * the policy is process-wide and consulted at capture time rather than passed to each
 * constructor: a new capture path inherits it instead of having to remember it. The
 * defaults are the masking ones, so a policy nobody configured still covers text.
 */
object Masking {

    /**
     * Cover text and input fields. On by default.
     *
     * Text is where the sensitive content is — names, balances, addresses, message
     * bodies, a recovery phrase. Defaulting this off would mean the SDK leaks by
     * omission, which is the wrong direction for a default to fail in.
     */
    @Volatile
    var text: Boolean = true
        internal set

    /**
     * Cover images. Off by default.
     *
     * Different trade from text. Photos and avatars can be sensitive, but so is every
     * icon and logo, and masking all of them turns a replay into a page of grey boxes
     * that says nothing about what the user did. Worth turning on for an app that shows
     * documents, receipts or user uploads.
     */
    @Volatile
    var images: Boolean = false
        internal set

    /**
     * Draw masks translucent, with a border, instead of opaque.
     *
     * For checking *placement*, which is the thing that silently goes wrong: a mask
     * offset by the status bar height still looks like a mask and still leaves the text
     * readable above it. With this on you can see both the mask and what is under it.
     *
     * Never for production — it defeats the masking it is verifying.
     */
    @Volatile
    var debugHighlight: Boolean = false
        internal set

    internal fun configure(config: LightSessionConfig) {
        text = config.maskText
        images = config.maskImages
        debugHighlight = config.maskDebugHighlight
    }

    /** Whether anything would be covered, so callers can skip the traversal. */
    internal val enabled: Boolean get() = text || images

    /**
     * Paints over the given rectangles.
     *
     * Expects a canvas in **screen coordinates** and rectangles to match, which is what
     * `ScreenDrawing.captureToBitmap` provides: it scales the canvas once and draws
     * every window into that transform, so a screen-space rectangle lands on the right
     * pixels at any `CaptureQuality`. Converting by hand against the scale factor is the
     * same mistake that made the replay's touch blob four times too big.
     */
    internal fun draw(canvas: Canvas, rects: List<Rect>) {
        if (rects.isEmpty()) return

        val fill = Paint().apply {
            style = Paint.Style.FILL
            // Opaque and neutral: a mask has to be unmistakably a mask, and it has to
            // actually hide. The dead `ViewMasker` this replaces drew 50%-alpha green,
            // which left the text underneath perfectly legible — a mask that looked
            // like one without being one.
            color = if (debugHighlight) Color.argb(110, 0, 200, 90) else MASK_COLOR
        }

        for (rect in rects) {
            canvas.drawRect(rect, fill)
        }

        if (debugHighlight) {
            val border = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.RED
            }
            for (rect in rects) {
                canvas.drawRect(rect, border)
            }
        }
    }

    /**
     * Mid-grey rather than black.
     *
     * Black reads as a rendering failure — a dead region, a view that did not draw. Grey
     * reads as a deliberate redaction, which is what it is, and it stays visible against
     * both light and dark app themes.
     */
    private val MASK_COLOR = Color.rgb(0x9E, 0x9E, 0x9E)
}
