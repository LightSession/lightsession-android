package com.lightsession.replay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a surface-backed window is treated as having painted.
 *
 * This is the recorder's change detector for content the draw listener cannot see, and it decides
 * whether a tick costs a four-byte marker or a full capture and a JPEG encode. Getting it wrong in
 * one direction loses frames of a replay; in the other it bills every customer whose app happens
 * to contain a video player for captures it then throws away.
 */
class SurfacePaintSignalTest {

    @Test
    fun `a window with no surface is left to the draw listener`() {
        // The ordinary Android case, and the one that must not change: the listener is accurate
        // there, and a second opinion here could only override it wrongly.
        assertFalse(
            surfaceMayHavePainted(
                hostsSurface = false,
                reportedGeneration = 7,
                generationAtLastCapture = 3,
            ),
        )
    }

    @Test
    fun `a generation that moved means the embedder painted`() {
        assertTrue(
            surfaceMayHavePainted(
                hostsSurface = true,
                reportedGeneration = 8,
                generationAtLastCapture = 7,
            ),
        )
    }

    @Test
    fun `a generation that held still means the screen did`() {
        // The whole point of reading it. Without this a Flutter app on a still screen paid a
        // capture and an encode every tick to discover the pixels had not changed.
        assertFalse(
            surfaceMayHavePainted(
                hostsSurface = true,
                reportedGeneration = 7,
                generationAtLastCapture = 7,
            ),
        )
    }

    @Test
    fun `the first capture happens before there is anything to compare against`() {
        assertTrue(
            surfaceMayHavePainted(
                hostsSurface = true,
                reportedGeneration = 1,
                generationAtLastCapture = null,
            ),
        )
    }

    @Test
    fun `with nothing reporting the surface is assumed to have painted`() {
        // A video player, a map, a camera preview: no public API gives a SurfaceView's frame
        // signal, so the only safe assumption is that it moved. isRepeatOfLastFrame settles it
        // afterwards from the bytes.
        assertTrue(
            surfaceMayHavePainted(
                hostsSurface = true,
                reportedGeneration = null,
                generationAtLastCapture = null,
            ),
        )
    }
}
