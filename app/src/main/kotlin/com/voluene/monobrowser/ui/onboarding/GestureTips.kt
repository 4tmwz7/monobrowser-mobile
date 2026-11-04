package com.voluene.monobrowser.ui.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R

/** A gesture or shortcut: a glyph, what to do, what happens. */
private data class Tip(val glyph: String, val title: Int, val text: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null)

private val TIPS = listOf(
    Tip("↔", R.string.tip_swipe_bar, R.string.tip_swipe_bar_text),
    Tip("↑", R.string.tip_swipe_up, R.string.tip_swipe_up_text),
    Tip("+", R.string.tip_counter, R.string.tip_counter_text),
    Tip("←", R.string.tip_back_hold, R.string.tip_back_hold_text),
    Tip("…", R.string.tip_address_hold, R.string.tip_address_hold_text),
    Tip("", R.string.tip_lock, R.string.tip_lock_text, androidx.compose.material.icons.Icons.Filled.Lock),
    Tip("↗", R.string.tip_link_hold, R.string.tip_link_hold_text),
    Tip("×", R.string.tip_swipe_tab, R.string.tip_swipe_tab_text),
    Tip("↩", R.string.tip_back, R.string.tip_back_text),
    Tip("↓", R.string.tip_pull, R.string.tip_pull_text),
)

/** The app's gestures and shortcuts (onboarding and Settings -> About). */
@Composable
fun GestureTips(modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onBackground
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TIPS.forEach { tip ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(32.dp).border(1.dp, fg, RectangleShape), contentAlignment = Alignment.Center) {
                    if (tip.icon != null) androidx.compose.material3.Icon(tip.icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                    else Text(tip.glyph, color = fg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 14.dp).fillMaxWidth()) {
                    Text(stringResource(tip.title), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(tip.text), color = fg.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
