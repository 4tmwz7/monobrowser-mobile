package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureInPictureTest {
    @Test fun keepsTheVideoShape() {
        assertEquals(1920 to 1080, PictureInPicture.ratio(1920, 1080))
        assertEquals(1080 to 1920, PictureInPicture.ratio(1080, 1920))
    }

    @Test fun clampsToWhatAndroidAccepts() {
        assertEquals(239 to 100, PictureInPicture.ratio(3000, 1000))
        assertEquals(100 to 239, PictureInPicture.ratio(100, 1000))
    }

    @Test fun unknownSizeIsWidescreen() {
        assertEquals(16 to 9, PictureInPicture.ratio(0, 0))
        assertEquals(16 to 9, PictureInPicture.ratio(640, -1))
    }
}
