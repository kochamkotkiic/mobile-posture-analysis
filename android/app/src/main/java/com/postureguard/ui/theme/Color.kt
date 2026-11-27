package com.postureguard.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome base: black, white and a few greys. Colour is used only to carry meaning (status).
val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)

val Grey50 = Color(0xFFF6F6F6)   // light: panels, tiles
val Grey100 = Color(0xFFEEEEEE)  // light: secondary buttons, track of progress bars
val Grey300 = Color(0xFFCBCBCB)  // light: outlines, disabled
val Grey500 = Color(0xFF757575)  // secondary text (both themes)
val Grey700 = Color(0xFF333333)  // dark: secondary buttons, outlines
val Grey800 = Color(0xFF1F1F1F)  // dark: panels, tiles
val Grey900 = Color(0xFF141414)  // dark: background behind the camera

/** Status colours, the same in light and dark mode (white text on top is readable on both). */
object StatusColors {
    val Good = Color(0xFF05A357)
    val Bad = Color(0xFFE11900)
    val Warning = Color(0xFFFFA600)
}
