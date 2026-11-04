package com.voluene.monobrowser.ui

import androidx.compose.ui.geometry.Rect
import com.voluene.monobrowser.ui.common.ConcentricCorners
import com.voluene.monobrowser.ui.common.CornerRadii
import com.voluene.monobrowser.ui.common.DisplayCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcentricCornersTest {
    // A 1000 x 2000 px screen with 100 px corners.
    private val corners = listOf(
        DisplayCorner(100f, 100f, 100f), DisplayCorner(900f, 100f, 100f),
        DisplayCorner(900f, 1900f, 100f), DisplayCorner(100f, 1900f, 100f),
    )

    @Test fun fullScreenElementTakesTheScreenRadius() {
        assertEquals(CornerRadii(100f, 100f, 100f, 100f), ConcentricCorners.radii(Rect(0f, 0f, 1000f, 2000f), corners))
    }

    @Test fun insetElementIsConcentric() {
        // A button 30 px from the left and 40 px from the bottom: 100 - max(30, 40) = 60 at its bottom-left only.
        val r = ConcentricCorners.radii(Rect(30f, 1800f, 400f, 1960f), corners)
        assertEquals(CornerRadii(bottomLeft = 60f), r)
    }

    @Test fun bottomSheetRoundsOnlyItsBottomCorners() {
        val r = ConcentricCorners.radii(Rect(0f, 1200f, 1000f, 2000f), corners)
        assertEquals(CornerRadii(bottomRight = 100f, bottomLeft = 100f), r)
    }

    @Test fun elementsAwayFromTheCornersStaySquare() {
        assertTrue(ConcentricCorners.radii(Rect(300f, 900f, 700f, 1100f), corners).isSquare)
        // Far enough from the edges that the remaining radius is negligible.
        assertTrue(ConcentricCorners.radii(Rect(98f, 1700f, 500f, 1902f), corners).isSquare)
        // No rounded corners reported (older Android, square screen).
        assertTrue(ConcentricCorners.radii(Rect(0f, 0f, 1000f, 2000f), emptyList()).isSquare)
        assertTrue(ConcentricCorners.radii(Rect(0f, 0f, 1000f, 2000f), listOf(null, null, null, null)).isSquare)
    }

    @Test fun radiusNeverExceedsHalfTheElement() {
        // A 40 px tall bar touching the bottom-right corner.
        val r = ConcentricCorners.radii(Rect(600f, 1960f, 1000f, 2000f), corners)
        assertEquals(20f, r.bottomRight, 0f)
    }

    @Test fun barContentMovesOutOfTheCurve() {
        // A bar along the bottom edge: at 10 px above the bottom the glass is far in from both sides.
        val (left, right) = ConcentricCorners.horizontalInsets(Rect(0f, 1900f, 1000f, 2000f), corners, 1990f, bottom = true)
        // Circle centre y 1900, dy = 90 -> half chord sqrt(100^2 - 90^2) = 43.6 -> glass at x = 100 - 43.6 = 56.4.
        assertEquals(56.4f, left, 0.1f)
        assertEquals(56.4f, right, 0.1f)
        // Above the corner zone the edge is straight: nothing to do.
        assertEquals(0f to 0f, ConcentricCorners.horizontalInsets(Rect(0f, 1700f, 1000f, 1800f), corners, 1790f, bottom = true))
        // A top bar under a 100 px status area.
        assertEquals(0f to 0f, ConcentricCorners.horizontalInsets(Rect(0f, 100f, 1000f, 200f), corners, 115f, bottom = false))
        val (topLeft, _) = ConcentricCorners.horizontalInsets(Rect(0f, 0f, 1000f, 100f), corners, 15f, bottom = false, margin = 4f)
        assertTrue(topLeft > 40f)
        // Already inset enough.
        assertEquals(0f to 0f, ConcentricCorners.horizontalInsets(Rect(80f, 1900f, 920f, 2000f), corners, 1990f, bottom = true))
    }
}
