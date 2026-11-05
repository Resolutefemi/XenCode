package com.xencode.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zoom math for the image viewer. All pure functions, no android.* touched, no Robolectric needed.
 */
class ImageViewerZoomTest {

    private val eps = 0.001f

    @Test
    fun fitInside_wideImage_limitedByWidth() {
        val fitted = fitInside(IntSize(4000, 2000), IntSize(1000, 1000))
        assertEquals(1000f, fitted.width, eps)
        assertEquals(500f, fitted.height, eps)
    }

    @Test
    fun fitInside_tallImage_limitedByHeight() {
        val fitted = fitInside(IntSize(2000, 4000), IntSize(1000, 1000))
        assertEquals(500f, fitted.width, eps)
        assertEquals(1000f, fitted.height, eps)
    }

    @Test
    fun fitInside_zeroSize_isZero() {
        assertEquals(Size.Zero, fitInside(IntSize(0, 0), IntSize(1000, 1000)))
        assertEquals(Size.Zero, fitInside(IntSize(100, 100), IntSize.Zero))
    }

    @Test
    fun maxPan_notZoomed_isZero() {
        // When not zoomed the image is pinned dead center with zero pan allowance, otherwise it would drift on release
        assertEquals(0f, maxPan(contentPx = 1000f, containerPx = 1000f, scale = 1f), eps)
        // The short side never fills the container anyway, so no pan there either
        assertEquals(0f, maxPan(contentPx = 500f, containerPx = 1000f, scale = 1f), eps)
    }

    @Test
    fun maxPan_zoomed_isHalfOverflow() {
        assertEquals(500f, maxPan(contentPx = 1000f, containerPx = 1000f, scale = 2f), eps)
        assertEquals(250f, maxPan(contentPx = 500f, containerPx = 1000f, scale = 3f), eps)
    }

    @Test
    fun clampPan_outOfBounds_isClamped() {
        val clamped = clampPan(
            offset = Offset(9999f, -9999f),
            content = Size(1000f, 1000f),
            container = IntSize(1000, 1000),
            scale = 2f
        )
        assertEquals(500f, clamped.x, eps)
        assertEquals(-500f, clamped.y, eps)
    }

    @Test
    fun clampPan_notZoomed_isAlwaysZero() {
        val clamped = clampPan(
            offset = Offset(120f, -80f),
            content = Size(1000f, 1000f),
            container = IntSize(1000, 1000),
            scale = 1f
        )
        // Compare axis by axis instead of against Offset.Zero: when clamped to the zero boundary the negative axis gets -0.0f,
        // and since Offset is a value class packing two floats, -0.0f and 0.0f have different bit patterns so equals fails.
        assertEquals(0f, clamped.x, eps)
        assertEquals(0f, clamped.y, eps)
    }

    @Test
    fun zoomAround_pureTranslation_addsPan() {
        val moved = zoomAround(
            centroid = Offset(10f, 20f),
            containerCenter = Offset(500f, 500f),
            oldScale = 2f,
            newScale = 2f,
            oldOffset = Offset(30f, 40f),
            pan = Offset(5f, 6f)
        )
        assertEquals(35f, moved.x, eps)
        assertEquals(46f, moved.y, eps)
    }

    @Test
    fun zoomAround_centerAnchor_scalesOffset() {
        val moved = zoomAround(
            centroid = Offset(500f, 500f),
            containerCenter = Offset(500f, 500f),
            oldScale = 1f,
            newScale = 2f,
            oldOffset = Offset(10f, 20f),
            pan = Offset.Zero
        )
        assertEquals(20f, moved.x, eps)
        assertEquals(40f, moved.y, eps)
    }

    @Test
    fun zoomAround_cornerAnchor_keepsPixelUnderFinger() {
        val centroid = Offset(100f, 200f)
        val center = Offset(500f, 500f)
        val oldScale = 1.5f
        val newScale = 3f
        val oldOffset = Offset(40f, -30f)

        val moved = zoomAround(centroid, center, oldScale, newScale, oldOffset, Offset.Zero)

        // The content point under the centroid before the gesture should still be there after it
        val v = centroid - center
        val point = (v - oldOffset) / oldScale
        val after = point * newScale + moved
        assertEquals(v.x, after.x, 0.01f)
        assertEquals(v.y, after.y, 0.01f)
    }

    @Test
    fun zoomAround_zeroOldScale_returnsOldOffset() {
        val oldOffset = Offset(7f, 9f)
        val moved = zoomAround(Offset(1f, 2f), Offset.Zero, 0f, 2f, oldOffset, Offset(3f, 4f))
        assertEquals(oldOffset, moved)
    }

    @Test
    fun inSampleSize_longScreenshot_isDownsampled() {
        // Regression case: with && the check constrains the short edge, so 4000x300 would entirely escape sampling and decode at full size
        assertTrue(calculateInSampleSize(4000, 300, 1024) >= 2)
    }

    @Test
    fun inSampleSize_normalImage_fitsUnderMaxEdge() {
        val sample = calculateInSampleSize(2048, 1536, 1024)
        assertEquals(2, sample)
        assertTrue(2048 / sample <= 1024 && 1536 / sample <= 1024)
    }

    @Test
    fun inSampleSize_smallImage_isOne() {
        assertEquals(1, calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun inSampleSize_invalidBounds_isOne() {
        assertEquals(1, calculateInSampleSize(0, 0, 1024))
        assertEquals(1, calculateInSampleSize(100, 100, 0))
    }

    @Test
    fun inSampleSize_maxPixelsCap_applies() {
        // Max edge is within the limit, but total pixels are 24MP, so the pixel-cap fallback layer catches it
        val sample = calculateInSampleSize(6000, 4000, maxEdge = 8192, maxPixels = 4_000_000L)
        assertTrue(sample > 1)
        assertTrue((6000L / sample) * (4000L / sample) <= 4_000_000L)
    }
}
