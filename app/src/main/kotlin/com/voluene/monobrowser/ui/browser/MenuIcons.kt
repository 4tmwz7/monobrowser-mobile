package com.voluene.monobrowser.ui.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private fun icon(name: String, data: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
}.build()

/** Small hand-drawn icons (material-icons-extended is not a dependency). */
object MenuIcons {
    val Download: ImageVector by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val Bookmark: ImageVector by lazy { icon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z") }
    val StarOutline: ImageVector by lazy {
        icon("StarOutline", "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z")
    }
    val Article: ImageVector by lazy {
        icon("Article", "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3zM14,17H7v-2h7V17zM17,13H7v-2h10V13zM17,9H7V7h10V9z")
    }
    val Desktop: ImageVector by lazy {
        icon("Desktop", "M21,2H3C1.9,2 1,2.9 1,4v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4C23,2.9 22.1,2 21,2zM21,16H3V4h18V16z")
    }
    val Print: ImageVector by lazy {
        icon("Print", "M19,8H5c-1.66,0 -3,1.34 -3,3v6h4v4h12v-4h4v-6C22,9.34 20.66,8 19,8zM16,19H8v-5h8V19zM19,12c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1S19.55,12 19,12zM18,3H6v4h12V3z")
    }
    val TextSize: ImageVector by lazy {
        icon("TextSize", "M9,4v3h5v12h3V7h5V4H9zM3,12h3v7h3v-7h3V9H3V12z")
    }
    val Restore: ImageVector by lazy {
        icon("Restore", "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9S17.97,3 13,3zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z")
    }
    val BookmarkOutline: ImageVector by lazy { icon("BookmarkOutline", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z") }
}
