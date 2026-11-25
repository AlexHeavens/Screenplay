/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Blue on white. */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF), onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF3F6FB5), onSecondary = Color.White,
    background = Color.White, onBackground = Color(0xFF111B2B),
    surface = Color.White, onSurface = Color(0xFF111B2B),
    surfaceVariant = Color(0xFFE4ECF7), onSurfaceVariant = Color(0xFF3B4A60),
    outline = Color(0xFF6E7D94), outlineVariant = Color(0xFFC5D0E0),
)

/** Lighter blue on near-black blue-grey. */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB8FF), onPrimary = Color(0xFF06214A),
    primaryContainer = Color(0xFF1F3F73), onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF9DB9E3), onSecondary = Color(0xFF0E2547),
    background = Color(0xFF0E1420), onBackground = Color(0xFFE3E8F2),
    surface = Color(0xFF0E1420), onSurface = Color(0xFFE3E8F2),
    surfaceVariant = Color(0xFF263044), onSurfaceVariant = Color(0xFFB8C3D6),
    outline = Color(0xFF8793A8), outlineVariant = Color(0xFF3A4660),
)

/** The greys of the import progress (scrubber and labels, unspoken text), one set per mode. */
class ProgressGreys(val read: Color, val readLabel: Color, val spokenText: Color, val speech: Color)

val LightGreys = ProgressGreys(
    read = Color(0xFFD6D6D6), readLabel = Color(0xFF8C8C8C), spokenText = Color(0xFF383838), speech = Color(0xFF7A7A7A),
)
val DarkGreys = ProgressGreys(
    read = Color(0xFF3A4660), readLabel = Color(0xFF8793A8), spokenText = Color(0xFFE3E8F2), speech = Color(0xFFA7B1C6),
)

/** The user's choice in the options pane. */
enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark");

    fun dark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
}

/** The mode in force. Set from the options pane, so the whole UI follows at once. */
object ThemeChoice {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
}

@Composable
fun ScreenplayTheme(dark: Boolean = ThemeChoice.mode.dark(isSystemInDarkTheme()), content: @Composable () -> Unit) {
    // The window is themed by XML, which only follows the device; match it to the chosen mode.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val bg = (if (dark) DarkColors else LightColors).background.toArgb()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        // Deprecated for edge-to-edge windows; this app draws its own background behind the bars, so the colours still apply.
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

private val dark: Boolean @Composable get() = MaterialTheme.colorScheme.background.red < 0.5f

/** Pages read but not yet spoken: light grey on white. */
val ReadGrey: Color @Composable get() = if (dark) DarkGreys.read else LightGreys.read

/** [ReadGrey] as text: a little darker so it stays legible on the surface. */
val ReadLabelGrey: Color @Composable get() = if (dark) DarkGreys.readLabel else LightGreys.readLabel

/** Spoken sentences while the book is still being made: stands apart from [ReadLabelGrey]. */
val SpokenTextGrey: Color @Composable get() = if (dark) DarkGreys.spokenText else LightGreys.spokenText

/** Text that has audio: over [ReadGrey]. */
val SpeechGrey: Color @Composable get() = if (dark) DarkGreys.speech else LightGreys.speech

/** The corner radius of every button-like control (theme choice, play, speed, notes), so they cannot drift apart. */
val ControlShape = RoundedCornerShape(8.dp)

/** What a theme-choice chip shows, taken from the scheme in force: the default chip colours come from the stock palette, not ours. */
class ChipPalette(val selectedContainer: Color, val selectedLabel: Color, val label: Color, val outline: Color)

fun chipPalette(c: ColorScheme) = ChipPalette(c.primaryContainer, c.onPrimaryContainer, c.onSurface, c.outline)

@Composable
fun themeChipColors() = chipPalette(MaterialTheme.colorScheme).let {
    FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent, labelColor = it.label,
        selectedContainerColor = it.selectedContainer, selectedLabelColor = it.selectedLabel,
    )
}
