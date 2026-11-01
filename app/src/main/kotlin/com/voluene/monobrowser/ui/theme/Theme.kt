package com.voluene.monobrowser.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.voluene.monobrowser.settings.ResolvedTheme
import com.voluene.monobrowser.settings.ThemeMode
import com.voluene.monobrowser.settings.Themes

/** Strictly flat and rectangular. No dynamic color / Material You. */
private val Square = RoundedCornerShape(0.dp) // zero radius == rectangle (Shapes needs CornerBasedShape)
private val MonoShapes = Shapes(
    extraSmall = Square, small = Square, medium = Square, large = Square, extraLarge = Square,
)

fun hexColor(hex: String): Color = Color(("FF" + hex.removePrefix("#")).toLong(16))

private fun ResolvedTheme.toColorScheme() =
    if (dark) {
        darkColorScheme(
            primary = hexColor(primary), onPrimary = hexColor(onPrimary),
            secondary = hexColor(onSurfaceVariant), onSecondary = hexColor(background),
            background = hexColor(background), onBackground = hexColor(onBackground),
            surface = hexColor(background), onSurface = hexColor(onBackground),
            surfaceVariant = hexColor(surfaceVariant), onSurfaceVariant = hexColor(onSurfaceVariant),
            outline = hexColor(outline), outlineVariant = hexColor(outlineVariant),
            error = hexColor(error), onError = hexColor(onError),
        )
    } else {
        lightColorScheme(
            primary = hexColor(primary), onPrimary = hexColor(onPrimary),
            secondary = hexColor(onSurfaceVariant), onSecondary = hexColor(background),
            background = hexColor(background), onBackground = hexColor(onBackground),
            surface = hexColor(background), onSurface = hexColor(onBackground),
            surfaceVariant = hexColor(surfaceVariant), onSurfaceVariant = hexColor(onSurfaceVariant),
            outline = hexColor(outline), outlineVariant = hexColor(outlineVariant),
            error = hexColor(error), onError = hexColor(onError),
        )
    }

/** Applies the chosen colour theme live (see `Themes.resolve`); also tints the system bars and the window. */
@Composable
fun MonoTheme(themeId: String = Themes.DEFAULT_ID, mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val resolved = remember(themeId, mode, systemDark) { Themes.resolve(themeId, mode, systemDark) }
    val scheme = remember(resolved) { resolved.toColorScheme() }
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !resolved.dark
            controller.isAppearanceLightNavigationBars = !resolved.dark
            window.decorView.setBackgroundColor(scheme.background.toArgb())
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = MonoShapes, content = content)
}
