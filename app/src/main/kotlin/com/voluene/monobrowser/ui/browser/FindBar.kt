package com.voluene.monobrowser.ui.browser

import com.voluene.monobrowser.ui.common.rememberScreenAwareShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.FindState

/** Find-in-page bar. It is drawn over the page (bottom-left); it never shrinks the page. */
@Composable
fun FindBar(
    state: FindState,
    onQuery: (String) -> Unit,
    onNext: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val corners = rememberScreenAwareShape()
    Row(
        modifier.then(corners.modifier).clip(corners.shape).background(scheme.background, corners.shape).border(1.dp, scheme.outline, corners.shape).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = text,
                onValueChange = { text = it; onQuery(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground),
                cursorBrush = SolidColor(scheme.onBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text(stringResource(R.string.find_hint), color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1)
                    inner()
                },
            )
        }
        if (text.isNotEmpty()) {
            Text(
                stringResource(R.string.find_count, state.active, state.total),
                color = scheme.onBackground, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        FindButton(Icons.Default.KeyboardArrowUp, R.string.find_previous) { onNext(false) }
        FindButton(Icons.Default.KeyboardArrowDown, R.string.find_next) { onNext(true) }
        FindButton(Icons.Default.Close, R.string.find_close, onClose)
    }
}

@Composable
private fun FindButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, stringResource(label), tint = MaterialTheme.colorScheme.onBackground)
    }
}
