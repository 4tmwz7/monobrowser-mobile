package com.voluene.monobrowser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import kotlinx.coroutines.delay

/** How long a message stays; messages with an action stay a little longer. */
const val MESSAGE_MS = 3_500L
const val MESSAGE_ACTION_MS = 5_000L

/**
 * A message above the toolbar (inverted bar): text and an optional action ("Open", "Undo", "Switch").
 * Goes away by itself, on the action, or when swiped sideways.
 */
@Composable
fun MessageBar(message: TabManager.UiMessage, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(message.id) {
        delay(if (message.action != null) MESSAGE_ACTION_MS else MESSAGE_MS)
        onDismiss(message.id)
    }
    val corners = rememberScreenAwareShape()
    Row(
        modifier.fillMaxWidth().then(corners.modifier).clip(corners.shape).background(scheme.onBackground, corners.shape)
            .pointerInput(message.id) { detectHorizontalDragGestures { _, _ -> onDismiss(message.id) } }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message.text, color = scheme.background, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        val label = message.actionLabel
        val action = message.action
        if (label != null && action != null) {
            Text(
                label.uppercase(), color = scheme.background, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clickable { onDismiss(message.id); action() }.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 16.dp))
        }
    }
}
