package com.voluene.monobrowser.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's logo as in the launcher icon (`ic_launcher_foreground`): a square tile with an "M" cut out and a hatched
 * hard shadow cast down-right, on the same 64 x 64 grid. Drawn in the theme's foreground on its background, so it
 * is right in the light and the dark theme.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val fg = MaterialTheme.colorScheme.onBackground
    val bg = MaterialTheme.colorScheme.background
    val paths = remember {
        fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
        Triple(parse(SHADOW_BAND), parse(HATCH), parse(LETTER))
    }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 64f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            clipPath(paths.first) { drawPath(paths.second, fg, style = Stroke(width = 2.12f)) }
            drawRect(fg, topLeft = androidx.compose.ui.geometry.Offset(4f, 4f), size = androidx.compose.ui.geometry.Size(46f, 46f))
            drawPath(paths.third, bg)
        }
    }
}

private const val SHADOW_BAND = "M50,4L60,14L60,60L14,60L4,50L50,50Z"
private const val HATCH = "M0,42L22,64M0,36L28,64M0,30L34,64M0,24L40,64M0,18L46,64M0,12L52,64M0,6L58,64M0,0L64,64M6,0L64,58M12,0L64,52M18,0L64,46M24,0L64,40M30,0L64,34M36,0L64,28M42,0L64,22"
private const val LETTER = "M9,44V10h9l9,15l9,-15h9v34h-9V26l-9,15l-9,-15v18z"
