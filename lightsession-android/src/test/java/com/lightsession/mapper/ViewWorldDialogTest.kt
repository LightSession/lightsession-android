package com.lightsession.mapper

import android.view.WindowManager.LayoutParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dialog built out of Views, which is most dialogs outside a Compose app.
 *
 * Everything else in [SubScreenReader] reads Compose semantics, and for a long time that was all
 * it read — so a dialog with no Compose in it was answered `null` and never became a node. Measured
 * on the React Native sample: RN's `Modal` is a plain `Dialog` holding `ReactViewGroup`s, its
 * window reached `readModal` exactly as a Compose dialog does, and the read came back
 *
 *     readModal: view=com.android.internal.policy.DecorView result=null
 *
 * `Alert.alert` is an AppCompat `AlertDialog` and failed identically. Neither ever appeared in the
 * map, on any version. The iOS SDK has no such gap because it recognises a modal by controller
 * class, which is blind to the UI framework.
 *
 * These are the two decisions that fix carries, pulled out of the View they are read off so they
 * can be checked without an Android framework — the reader itself needs a real view tree and a
 * real WindowManager, and neither exists in a JVM unit test.
 */
class ViewWorldDialogTest {

    // --------------------------------------------------- is this window a dialog

    /**
     * The measured case. RN's `Modal` reported `type=2` — `TYPE_APPLICATION` — and takes focus.
     */
    @Test
    fun `an application window that takes focus is a dialog`() {
        assertTrue(SubScreenReader.isDialogWindow(LayoutParams.TYPE_APPLICATION, 0))
        assertTrue(SubScreenReader.isDialogWindow(LayoutParams.TYPE_BASE_APPLICATION, 0))
    }

    /**
     * The expensive wrong answer, and the reason this is a decision rather than an `if` inline.
     * `identifyModal` records the measurement: a dropdown, a menu and a tooltip each add a root
     * view exactly like a dialog does, so calling every new window a screen mints one every time
     * somebody opens a combo box. Every `PopupWindow` is a sub-window, which is what separates it.
     */
    @Test
    fun `a popup window is not a dialog`() {
        assertFalse(SubScreenReader.isDialogWindow(LayoutParams.TYPE_APPLICATION_PANEL, 0))
        assertFalse(SubScreenReader.isDialogWindow(LayoutParams.TYPE_APPLICATION_SUB_PANEL, 0))
        assertFalse(
            SubScreenReader.isDialogWindow(LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG, 0)
        )
    }

    @Test
    fun `a toast or the keyboard is not a dialog`() {
        assertFalse(SubScreenReader.isDialogWindow(LayoutParams.TYPE_TOAST, 0))
        assertFalse(SubScreenReader.isDialogWindow(LayoutParams.TYPE_INPUT_METHOD, 0))
    }

    /**
     * A window that cannot take focus is not something a person is looking *at* — it is chrome over
     * what they were already looking at. An overlay that dims or annotates the screen without
     * taking focus would otherwise replace the screen's own name in the map.
     */
    @Test
    fun `an application window that cannot take focus is not a dialog`() {
        assertFalse(
            SubScreenReader.isDialogWindow(
                LayoutParams.TYPE_APPLICATION,
                LayoutParams.FLAG_NOT_FOCUSABLE
            )
        )
    }

    /** Flags other than focus say nothing about this. */
    @Test
    fun `dimming behind does not decide it either way`() {
        assertTrue(
            SubScreenReader.isDialogWindow(
                LayoutParams.TYPE_APPLICATION,
                LayoutParams.FLAG_DIM_BEHIND
            )
        )
    }

    // --------------------------------------------------- what it is called

    @Test
    fun `the same shape always gets the same name`() {
        val shape = listOf(
            SubScreenReader.KIND_GROUP to 0,
            SubScreenReader.KIND_TEXT to 1,
            SubScreenReader.KIND_TEXT to 1,
            SubScreenReader.KIND_GROUP to 1,
            SubScreenReader.KIND_OTHER to 2,
        )
        assertEquals(SubScreenReader.shapeHashOf(shape), SubScreenReader.shapeHashOf(shape))
    }

    /**
     * Two dialogs the app never named must not land on one node — the failure the iOS side hit with
     * un-named sheets, arriving here as two dialogs of different shape.
     */
    @Test
    fun `a different shape gets a different name`() {
        val confirm = listOf(
            SubScreenReader.KIND_GROUP to 0,
            SubScreenReader.KIND_TEXT to 1,
            SubScreenReader.KIND_TEXT to 1,
        )
        val form = listOf(
            SubScreenReader.KIND_GROUP to 0,
            SubScreenReader.KIND_TEXT to 1,
            SubScreenReader.KIND_EDITABLE to 1,
            SubScreenReader.KIND_GROUP to 1,
        )
        assertNotEquals(SubScreenReader.shapeHashOf(confirm), SubScreenReader.shapeHashOf(form))
    }

    /** Depth is part of the shape: the same nodes nested differently are a different dialog. */
    @Test
    fun `nesting is part of the shape`() {
        val flat = listOf(SubScreenReader.KIND_GROUP to 0, SubScreenReader.KIND_TEXT to 1)
        val nested = listOf(SubScreenReader.KIND_GROUP to 0, SubScreenReader.KIND_TEXT to 2)
        assertNotEquals(SubScreenReader.shapeHashOf(flat), SubScreenReader.shapeHashOf(nested))
    }

    /**
     * The half that cannot be tested by what goes in, only by what does not: text is absent from
     * the input entirely. A dialog reading "Delete Dr. Silva?" and the same dialog reading "Delete
     * Dr. Souza?" produce the same list of kinds and depths, so they cannot help but hash alike —
     * which is the whole reason the input is shaped this way rather than taken from the tree.
     */
    @Test
    fun `two readings of one dialog differ only in text and so share a name`() {
        val silva = listOf(SubScreenReader.KIND_GROUP to 0, SubScreenReader.KIND_TEXT to 1)
        val souza = listOf(SubScreenReader.KIND_GROUP to 0, SubScreenReader.KIND_TEXT to 1)
        assertEquals(SubScreenReader.shapeHashOf(silva), SubScreenReader.shapeHashOf(souza))
    }

    @Test
    fun `an unnamed dialog is named for its shape`() {
        assertEquals("dialog-abc123", SubScreenReader.modalLabel(null, "abc123"))
    }

    /** A `View.tag` is the View-world `testTag`, and the developer naming the thing wins. */
    @Test
    fun `the apps own tag wins over the shape`() {
        assertEquals("Trocar empresa", SubScreenReader.modalLabel("Trocar empresa", "abc123"))
    }

    /**
     * And a "tag" that is really a record is refused, the same trap `nameModal` documents: a name
     * taken from data mints a node per row.
     */
    @Test
    fun `a tag that is really data falls back to the shape`() {
        val fromARecord = "Pedido 84321 de Maria Aparecida Souza ".repeat(8)
        assertEquals("dialog-abc123", SubScreenReader.modalLabel(fromARecord, "abc123"))
    }
}
