package com.voluene.monobrowser.settings

import kotlin.math.pow
import kotlin.math.roundToInt

/** Colour tokens of the desktop theme (`theme.ts`), as `#rrggbb`. */
data class ThemeTokens(
    val dark: Boolean,
    val bg: String, val surface: String, val surface2: String,
    val fg: String, val muted: String,
    val accent: String, val onAccent: String,
    val border: String, val danger: String,
)

data class ThemePreset(val id: String, val name: String, val tokens: ThemeTokens)

/** What the UI actually paints with (all `#rrggbb`); mapped onto a Material colour scheme in `MonoTheme`. */
data class ResolvedTheme(
    val dark: Boolean,
    val background: String, val onBackground: String,
    val surfaceVariant: String, val onSurfaceVariant: String,
    val primary: String, val onPrimary: String,
    val outline: String, val outlineVariant: String,
    val error: String, val onError: String,
)

/**
 * Port of the desktop colour themes (`theme.ts` / `color-themes.ts`; palette values from the themes' official
 * repositories, see THIRD-PARTY-NOTICES). Colours stay flat; only the palette changes. The default theme is the
 * strict monochrome look and follows the light/dark mode setting.
 */
object Themes {
    const val DEFAULT_ID = "default"

    private class Palette(
        val id: String, val name: String, val base: String, val bar: String, val surface: String,
        val text: String, val muted: String, val accent: String, val danger: String,
    )

    private val PALETTES = listOf(
        Palette("rose-pine", "Rosé Pine", "#191724", "#191724", "#26233a", "#e0def4", "#908caa", "#ebbcba", "#eb6f92"),
        Palette("rose-pine-moon", "Rosé Pine Moon", "#232136", "#232136", "#393552", "#e0def4", "#908caa", "#c4a7e7", "#eb6f92"),
        Palette("rose-pine-dawn", "Rosé Pine Dawn", "#faf4ed", "#f2e9e1", "#dfdad9", "#575279", "#797593", "#907aa9", "#b4637a"),
        Palette("everforest-dark", "Everforest Dark", "#2d353b", "#272e33", "#3d484d", "#d3c6aa", "#9da9a0", "#a7c080", "#e67e80"),
        Palette("everforest-light", "Everforest Light", "#fdf6e3", "#efebd4", "#f4f0d9", "#5c6a72", "#4b585e", "#8da101", "#f85552"),
        Palette("gruvbox-dark", "Gruvbox Dark", "#282828", "#1d2021", "#3c3836", "#ebdbb2", "#bdae93", "#fabd2f", "#fb4934"),
        Palette("gruvbox-light", "Gruvbox Light", "#fbf1c7", "#ebdbb2", "#d5c4a1", "#3c3836", "#504945", "#b57614", "#9d0006"),
        Palette("nord", "Nord", "#2e3440", "#2e3440", "#3b4252", "#eceff4", "#d8dee9", "#88c0d0", "#bf616a"),
        Palette("dracula", "Dracula", "#282a36", "#21222c", "#44475a", "#f8f8f2", "#bfc3d9", "#bd93f9", "#ff5555"),
        Palette("tokyo-night", "Tokyo Night", "#1a1b26", "#16161e", "#292e42", "#a9b1d6", "#9aa5ce", "#7aa2f7", "#f7768e"),
        Palette("solarized-dark", "Solarized Dark", "#002b36", "#002b36", "#073642", "#93a1a1", "#93a1a1", "#b58900", "#dc322f"),
        Palette("solarized-light", "Solarized Light", "#fdf6e3", "#eee8d5", "#e3dcc5", "#073642", "#586e75", "#b58900", "#dc322f"),
        Palette("kanagawa-wave", "Kanagawa Wave", "#1f1f28", "#16161d", "#363646", "#dcd7ba", "#c8c093", "#7e9cd8", "#e46876"),
        Palette("one-dark", "One Dark", "#282c34", "#21252b", "#3e4451", "#abb2bf", "#abb2bf", "#61afef", "#e06c75"),
    )

    // ---- colour math (ports of the helpers in theme.ts) ----

    private fun channel(hex: String, index: Int): Double {
        val v = hex.substring(index, index + 2).toInt(16) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(hex: String): Double = 0.2126 * channel(hex, 1) + 0.7152 * channel(hex, 3) + 0.0722 * channel(hex, 5)

    fun contrastRatio(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Linear mix of two `#rrggbb` colours; [amount] = share of [b]. */
    fun mixHex(a: String, b: String, amount: Double): String {
        fun part(i: Int): String {
            val x = a.substring(i, i + 2).toInt(16)
            val y = b.substring(i, i + 2).toInt(16)
            return (x + (y - x) * amount).roundToInt().toString(16).padStart(2, '0')
        }
        return "#${part(1)}${part(3)}${part(5)}"
    }

    fun pickOnAccent(accent: String, bg: String, fg: String): String {
        val own = if (contrastRatio(bg, accent) >= contrastRatio(fg, accent)) bg else fg
        if (contrastRatio(own, accent) >= 4.5) return own
        return if (contrastRatio("#0a0a0a", accent) >= contrastRatio("#ffffff", accent)) "#0a0a0a" else "#ffffff"
    }

    fun ensureMutedContrast(muted: String, fg: String, surfaces: List<String>, min: Double = 4.5): String {
        var current = muted
        fun worst(color: String) = surfaces.minOf { contrastRatio(color, it) }
        var step = 0
        while (step <= 20 && worst(current) < min) {
            val next = mixHex(current, fg, 0.1)
            if (next == current) break
            current = next
            step++
        }
        return current
    }

    private fun isDark(bg: String, fg: String) = luminance(bg) < luminance(fg)

    val PRESETS: List<ThemePreset> = PALETTES.map { p ->
        ThemePreset(
            p.id, p.name,
            ThemeTokens(
                dark = isDark(p.base, p.text),
                bg = p.base, surface = p.bar, surface2 = p.surface, fg = p.text,
                muted = ensureMutedContrast(p.muted, p.text, listOf(p.base, p.bar, p.surface)),
                accent = p.accent, onAccent = pickOnAccent(p.accent, p.base, p.text),
                border = mixHex(p.base, p.text, 0.28), danger = p.danger,
            ),
        )
    }

    fun find(id: String): ThemePreset? = PRESETS.firstOrNull { it.id == id }

    fun isKnown(id: String) = id == DEFAULT_ID || find(id) != null

    private val DEFAULT_LIGHT = ResolvedTheme(
        dark = false, background = "#ffffff", onBackground = "#000000",
        surfaceVariant = "#f2f2f2", onSurfaceVariant = "#333333",
        primary = "#000000", onPrimary = "#ffffff",
        outline = "#000000", outlineVariant = "#e6e6e6", error = "#000000", onError = "#ffffff",
    )
    private val DEFAULT_DARK = ResolvedTheme(
        dark = true, background = "#000000", onBackground = "#ffffff",
        surfaceVariant = "#1a1a1a", onSurfaceVariant = "#e6e6e6",
        primary = "#ffffff", onPrimary = "#000000",
        outline = "#ffffff", outlineVariant = "#333333", error = "#ffffff", onError = "#000000",
    )

    /** Settings -> concrete colours. The default theme follows [mode] (system = [systemDark]); presets are fixed. */
    fun resolve(themeId: String, mode: ThemeMode, systemDark: Boolean): ResolvedTheme {
        val preset = find(themeId)
        if (preset == null) {
            val dark = when (mode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            return if (dark) DEFAULT_DARK else DEFAULT_LIGHT
        }
        val t = preset.tokens
        return ResolvedTheme(
            dark = t.dark, background = t.bg, onBackground = t.fg,
            surfaceVariant = t.surface, onSurfaceVariant = t.muted,
            primary = t.accent, onPrimary = t.onAccent,
            outline = mixHex(t.bg, t.fg, 0.55), outlineVariant = t.border,
            error = t.danger, onError = t.bg,
        )
    }
}
