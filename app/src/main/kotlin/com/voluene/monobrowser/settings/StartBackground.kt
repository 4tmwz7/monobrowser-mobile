package com.voluene.monobrowser.settings

/** Pure rules for the start page's custom background image. Unit-tested. */
object StartBackground {
    /** Largest download accepted from a URL. */
    const val MAX_DOWNLOAD_BYTES = 15L * 1024 * 1024
    /** Largest photo accepted from the device (originals can be big; only the scaled copy is kept). */
    const val MAX_DEVICE_BYTES = 64L * 1024 * 1024
    /** Longest side of the stored copy in px. */
    const val MAX_SIDE = 2048
    const val FILE_NAME = "start-background.jpg"
    const val JPEG_QUALITY = 88

    /** Strength of the overlay in the theme's background colour, weakest to strongest (the setting stores the index). */
    val DIM_LEVELS = listOf(0.35f, 0.5f, 0.65f, 0.8f)
    const val DEFAULT_DIM = 2

    fun clampDim(level: Int): Int = level.coerceIn(0, DIM_LEVELS.lastIndex)

    fun overlayAlpha(level: Int): Float = DIM_LEVELS[clampDim(level)]

    /** http(s) address without whitespace, at most 2048 chars (the same rule as the homepage). */
    fun isValidUrl(text: String): Boolean = SettingsCodec.isValidHomepage(text)

    /** Size of the stored copy: the longer side shrinks to [max], the aspect ratio stays, nothing is enlarged. */
    fun targetSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val factor = max.toFloat() / longest
        return maxOf(1, Math.round(width * factor)) to maxOf(1, Math.round(height * factor))
    }

    /** Power-of-two `inSampleSize` for decoding: the biggest one that still leaves the longer side >= [max]. */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || max <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= max) sample *= 2
        return sample
    }
}
