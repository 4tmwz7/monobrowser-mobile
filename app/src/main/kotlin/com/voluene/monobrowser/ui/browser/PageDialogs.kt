package com.voluene.monobrowser.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.PageDialog
import com.voluene.monobrowser.browser.SitePermission
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoDialog

/** Shows the permission prompt or the JS alert/confirm/prompt of a page, in the monochrome style. */
@Composable
fun PageDialogView(dialog: PageDialog) {
    BackHandler { dialog.cancel() }
    when (dialog) {
        is PageDialog.Permission -> PermissionDialog(dialog)
        is PageDialog.Alert -> MessageDialog(dialog.origin, dialog.message, onOk = dialog.close, onCancel = null)
        is PageDialog.Confirm -> MessageDialog(dialog.origin, dialog.message, onOk = { dialog.resolve(true) }, onCancel = { dialog.resolve(false) })
        is PageDialog.Prompt -> PromptDialog(dialog)
    }
}

@Composable
private fun PermissionDialog(dialog: PageDialog.Permission) {
    val scheme = MaterialTheme.colorScheme
    val what = stringResource(
        when {
            SitePermission.LOCATION in dialog.wants -> R.string.perm_location
            SitePermission.CAMERA in dialog.wants && SitePermission.MICROPHONE in dialog.wants -> R.string.perm_camera_microphone
            SitePermission.CAMERA in dialog.wants -> R.string.perm_camera
            else -> R.string.perm_microphone
        },
    )
    MonoDialog(onDismiss = { dialog.resolve(false) }) {
        Text(stringResource(R.string.perm_dialog_title), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.perm_message, dialog.origin, what), color = scheme.onBackground, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.perm_duration), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.perm_deny), { dialog.resolve(false) }, Modifier.weight(1f))
            MonoButton(stringResource(R.string.perm_allow), { dialog.resolve(true) }, Modifier.weight(1f), inverted = true)
        }
    }
}

@Composable
private fun MessageDialog(origin: String, message: String, onOk: () -> Unit, onCancel: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    MonoDialog(onDismiss = null) {
        Text(stringResource(R.string.js_dialog_title, origin), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(message, color = scheme.onBackground)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null) MonoButton(stringResource(R.string.dialog_cancel), onCancel, Modifier.weight(1f))
            MonoButton(stringResource(R.string.dialog_ok), onOk, Modifier.weight(1f), inverted = true)
        }
    }
}

@Composable
private fun PromptDialog(dialog: PageDialog.Prompt) {
    val scheme = MaterialTheme.colorScheme
    var text by remember(dialog.id) { mutableStateOf(dialog.default) }
    MonoDialog(onDismiss = null) {
        Text(stringResource(R.string.js_dialog_title, dialog.origin), color = scheme.onBackground.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(dialog.message, color = scheme.onBackground)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onBackground),
            cursorBrush = SolidColor(scheme.onBackground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().border(1.dp, scheme.outline, RectangleShape).padding(10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.dialog_cancel), { dialog.resolve(null) }, Modifier.weight(1f))
            MonoButton(stringResource(R.string.dialog_ok), { dialog.resolve(text) }, Modifier.weight(1f), inverted = true)
        }
    }
}
