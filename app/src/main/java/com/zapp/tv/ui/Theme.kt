package com.zapp.tv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.zapp.tv.R

/** One colour theme. The page colour, the two brand colours its gradients run between, and a few tints. */
data class ZappPalette(
    val id: String,
    val name: String,
    val bg: Color,
    val panel: Color,
    val brand1: Color,
    val brand2: Color,
    val signal: Color,
)

object Palettes {
    /** Default: deep ink with a blue-to-pink glow. */
    val Aurora = ZappPalette("aurora", "Aurora", Color(0xFF0A0A14), Color(0xFF15152A), Color(0xFF4D7CFF), Color(0xFFFF5FA2), Color(0xFF6E9BFF))
    val Ocean = ZappPalette("ocean", "Ocean", Color(0xFF0A0E1A), Color(0xFF121A2C), Color(0xFF7A5CFF), Color(0xFF2FD3F0), Color(0xFF39D0F0))
    val Sunset = ZappPalette("sunset", "Sunset", Color(0xFF120A0E), Color(0xFF22141B), Color(0xFFFF8A3D), Color(0xFFFF4F8B), Color(0xFFFF9A5C))
    val Emerald = ZappPalette("emerald", "Emerald", Color(0xFF07110E), Color(0xFF10201B), Color(0xFF22C58B), Color(0xFF2E9BF0), Color(0xFF3DDBA0))
    val Mono = ZappPalette("mono", "Monochrome", Color(0xFF000000), Color(0xFF0D0D0D), Color(0xFF5E5E5E), Color(0xFFE6E6E6), Color(0xFFDADADA))
    val all = listOf(Aurora, Ocean, Sunset, Emerald, Mono)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Aurora

    /** Colours to build your own theme from. */
    val Swatches = listOf(
        Color(0xFF4D7CFF), Color(0xFF2FD3F0), Color(0xFF22C58B), Color(0xFFB7F04D), Color(0xFFFFC94A),
        Color(0xFFFF8A3D), Color(0xFFFF4F6A), Color(0xFFFF5FA2), Color(0xFFB45CFF), Color(0xFF7A5CFF),
        Color(0xFFFFFFFF), Color(0xFF8A8F9C),
    )

    /** A custom theme from two swatches, on a near-black page. */
    fun custom(a: Int, b: Int): ZappPalette {
        val c1 = Swatches[a.mod(Swatches.size)]
        val c2 = Swatches[b.mod(Swatches.size)]
        return ZappPalette("custom", "Custom", Color(0xFF07070A), Color(0xFF131318), c1, c2, lerp(c1, Color.White, 0.2f))
    }
}

/**
 * Minimal and quiet: a dark page, thin type, and one loud thing, focus (the item under the remote
 * turns white). The theme's two colours appear only as a soft glow and in small accents.
 * Colours follow the chosen theme; everything reading them updates when it changes.
 */
object ZappColors {
    var palette by mutableStateOf(Palettes.Aurora)

    val Bg: Color get() = palette.bg
    val Panel: Color get() = palette.panel
    val PanelHigh: Color get() = lerp(palette.panel, Color.White, 0.06f)
    val Glass = Color(0x12FFFFFF)
    val GlassHigh = Color(0x22FFFFFF)
    val Line: Color get() = lerp(palette.panel, Color.White, 0.1f)
    val Text = Color(0xFFF1F2F6)
    val Dim = Color(0xFFA3A7B4)
    val Faint = Color(0xFF6B7080)
    /** Focus takes a light tint of the theme (plain white-grey for Monochrome). */
    val Focus: Color get() = lerp(palette.brand1, Color.White, 0.8f)
    val OnFocus: Color get() = palette.bg
    val OnFocusDim = Color(0xFF555A68)
    val Brand1: Color get() = palette.brand1
    val Brand2: Color get() = palette.brand2
    val Signal: Color get() = palette.signal
    val Live = Color(0xFFFF4F6A)
    val Star = Color(0xFFFFC94A)
    val Error = Color(0xFFFF8A9A)
    val Scrim: Color get() = palette.bg.copy(alpha = 0.9f)
}

/** Anton: tall, condensed poster lettering for film and series titles. */
val TitleFont = FontFamily(Font(R.font.anton, FontWeight.Normal))

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

@Composable
fun ZappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ZappColors.Brand1,
            onPrimary = Color.White,
            background = ZappColors.Bg,
            onBackground = ZappColors.Text,
            surface = ZappColors.Panel,
            onSurface = ZappColors.Text,
        ),
    ) {
        // Only animate while the glow is switched on.
        val pulse = if (!FocusStyle.glow) androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) } else androidx.compose.animation.core.rememberInfiniteTransition(label = "focusPulse").animateFloat(
            0f,
            1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Inter, color = ZappColors.Text),
            LocalFocusPulse provides pulse,
            content = content,
        )
    }
}
