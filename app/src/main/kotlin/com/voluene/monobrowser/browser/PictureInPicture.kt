package com.voluene.monobrowser.browser

/** Picture-in-picture helpers (pure, unit-tested). */
object PictureInPicture {
    /** Android accepts aspect ratios between 1:2.39 and 2.39:1. */
    private const val MAX = 2.39

    /** The window's aspect ratio (numerator, denominator) for a [width] x [height] video; 16:9 when unknown. */
    fun ratio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val r = width.toDouble() / height
        return when {
            r > MAX -> 239 to 100
            r < 1 / MAX -> 100 to 239
            else -> width to height
        }
    }
}
