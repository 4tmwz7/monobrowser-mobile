package com.voluene.monobrowser.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R

/** Rectangular text button: outlined, or filled when [inverted]. */
@Composable
fun MonoButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, inverted: Boolean = false, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (inverted) scheme.onBackground else scheme.background
    val fg = if (inverted) scheme.background else scheme.onBackground
    Box(
        modifier.background(bg).border(1.dp, scheme.outline, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg.copy(alpha = if (enabled) 1f else 0.4f), fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

/** Dimmed scrim; a tap on it calls [onDismiss] (null = the scrim does not dismiss). */
@Composable
fun Scrim(onDismiss: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = source, indication = null) { onDismiss?.invoke() },
    ) { content() }
}

/** Centered rectangular dialog with a hard border (no rounded corners, no shadow). */
@Composable
fun MonoDialog(onDismiss: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Scrim(onDismiss) {
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth()
                .background(scheme.background).border(2.dp, scheme.outline, RectangleShape)
                // Swallow taps so they do not reach the scrim.
                .clickable(interactionSource = source, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Full-screen page header with a title and a close button. */
@Composable
fun ScreenScaffold(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = scheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            actions()
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close), tint = scheme.onBackground)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
        content()
    }
}

/** Rectangular on/off switch: a hard-edged track with a square thumb. */
@Composable
fun MonoSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(width = 46.dp, height = 26.dp)
            .background(if (checked) scheme.onBackground else scheme.background)
            .border(2.dp, scheme.outline, RectangleShape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(3.dp),
    ) {
        Box(
            Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(16.dp)
                .background(if (checked) scheme.background else scheme.onBackground),
        )
    }
}

/** Square checkbox with a label. */
@Composable
fun MonoCheckbox(checked: Boolean, label: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, scheme.outline, RectangleShape).padding(4.dp)) {
            if (checked) Box(Modifier.fillMaxSize().background(scheme.onBackground))
        }
        Text(label, color = scheme.onBackground, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Single-line bordered text field. */
@Composable
fun MonoTextField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, scheme.outline, RectangleShape).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground),
            cursorBrush = SolidColor(scheme.onBackground),
            keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboardType),
            keyboardActions = KeyboardActions(onAny = { onDone() }),
            modifier = Modifier.fillMaxWidth().let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = scheme.onBackground.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1)
                inner()
            },
        )
    }
}

/** Dialog with one tappable row per option; the selected one is marked with a filled square. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss) {
        Text(title, color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(value); onDismiss() }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(18.dp).border(2.dp, scheme.outline, RectangleShape).padding(3.dp)) {
                    if (value == selected) Box(Modifier.fillMaxSize().background(scheme.onBackground))
                }
                Text(label, color = scheme.onBackground, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Dialog with one text field and OK / Cancel; [validate] enables OK. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    validate: (String) -> Boolean = { it.isNotBlank() },
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MonoDialog(onDismiss) {
        Text(title, color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        MonoTextField(text, { text = it }, hint, keyboardType = keyboardType, focusRequester = focus, onDone = { if (validate(text)) { onConfirm(text); onDismiss() } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
            MonoButton(confirmLabel, { onConfirm(text); onDismiss() }, Modifier.weight(1f), inverted = true, enabled = validate(text))
        }
    }
}

/** Confirmation dialog (message + Cancel / [confirmLabel]). */
@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss) {
        Text(title, color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(message, color = scheme.onBackground)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), onDismiss, Modifier.weight(1f))
            MonoButton(confirmLabel, { onConfirm(); onDismiss() }, Modifier.weight(1f), inverted = true)
        }
    }
}

/** Full-width hairline. */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
