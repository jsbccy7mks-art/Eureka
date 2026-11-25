package com.zapp.tv.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.zapp.tv.R

/**
 * Ink-navy glass. Surfaces are quiet translucent panels; the one loud thing is focus:
 * the item under the remote turns bright, like Apple TV and Google TV. Brand gradient
 * (violet to cyan) is reserved for the logo and the primary action.
 */
object ZappColors {
    val Bg = Color(0xFF0A0E1A)
    val Panel = Color(0xFF121A2C)
    val PanelHigh = Color(0xFF1B2540)
    val Glass = Color(0x14FFFFFF)
    val GlassHigh = Color(0x24FFFFFF)
    val Line = Color(0xFF26314F)
    val Text = Color(0xFFEDF1FA)
    val Dim = Color(0xFF94A0BD)
    val Faint = Color(0xFF5E6A88)
    val Focus = Color(0xFFF3F6FC)
    val OnFocus = Color(0xFF0A0E1A)
    val OnFocusDim = Color(0xFF4C5672)
    val Brand1 = Color(0xFF7A5CFF)
    val Brand2 = Color(0xFF2FD3F0)
    val Signal = Color(0xFF39D0F0)
    val Live = Color(0xFFFF4F6A)
    val Star = Color(0xFFFFC94A)
    val Error = Color(0xFFFF8A9A)
    val Scrim = Color(0xE6070A14)
}

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
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Inter, color = ZappColors.Text),
            content = content,
        )
    }
}
