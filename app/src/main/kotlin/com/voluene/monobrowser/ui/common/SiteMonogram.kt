package com.voluene.monobrowser.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.data.TopSites
import com.voluene.monobrowser.ui.start.monogram

/** The site's monogram in a square: the app's monochrome stand-in for a favicon (lists, start page, tab grid). */
@Composable
fun SiteMonogram(url: String, modifier: Modifier = Modifier, size: Dp = 32.dp, color: Color = MaterialTheme.colorScheme.onBackground) {
    val key = TopSites.hostKey(url)
    Box(modifier.size(size).border(1.dp, color, RectangleShape), contentAlignment = Alignment.Center) {
        Text(
            if (key != null) monogram(TopSites.label(key)) else "•", color = color,
            fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}
