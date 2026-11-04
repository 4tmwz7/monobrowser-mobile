package com.voluene.monobrowser.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voluene.monobrowser.R
import com.voluene.monobrowser.settings.AppSettings
import com.voluene.monobrowser.ui.common.MonoButton
import com.voluene.monobrowser.ui.common.MonoDialog

/** Next/previous step of [AppSettings.TEXT_ZOOM_STEPS] from [current] (which may be between steps). */
fun textZoomStep(current: Int, up: Boolean): Int {
    val steps = AppSettings.TEXT_ZOOM_STEPS
    return if (up) steps.firstOrNull { it > current } ?: steps.last() else steps.lastOrNull { it < current } ?: steps.first()
}

/** Page text size (applies to every tab right away): A- / value / A+, a sample line and Reset. */
@Composable
fun TextSizeDialog(zoom: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val steps = AppSettings.TEXT_ZOOM_STEPS
    MonoDialog(onDismiss) {
        Text(stringResource(R.string.settings_text_size), color = scheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoButton("A−", { onChange(textZoomStep(zoom, up = false)) }, enabled = zoom > steps.first())
            Text(
                stringResource(R.string.text_size_value, zoom), color = scheme.onBackground, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            MonoButton("A+", { onChange(textZoomStep(zoom, up = true)) }, enabled = zoom < steps.last())
        }
        Text(stringResource(R.string.text_size_sample), color = scheme.onBackground, fontSize = (16f * zoom / 100f).sp, lineHeight = (22f * zoom / 100f).sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(stringResource(R.string.text_size_reset), { onChange(100) }, Modifier.weight(1f), enabled = zoom != 100)
            MonoButton(stringResource(R.string.dialog_ok), onDismiss, Modifier.weight(1f), inverted = true)
        }
    }
}
