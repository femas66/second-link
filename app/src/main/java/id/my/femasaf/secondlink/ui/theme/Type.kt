package id.my.femasaf.secondlink.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// DESIGN.md calls for Inter (display/body) and JetBrains Mono (labels/technical data).
// Shipping a Google-Fonts-provider download needs a hand-verified certificate resource
// this project doesn't have; System sans / monospace are the faithful, verifiable
// substitutes the Android platform guidance itself recommends (Roboto is the system
// face; theme the brand voice in through the type scale, never invent a costume face).
private val DisplayFontFamily = FontFamily.SansSerif
private val MonoFontFamily = FontFamily.Monospace

private fun display(size: TextUnit, weight: FontWeight, lineHeight: TextUnit, tracking: TextUnit) =
    TextStyle(fontFamily = DisplayFontFamily, fontWeight = weight, fontSize = size, lineHeight = lineHeight, letterSpacing = tracking)

private fun mono(size: TextUnit, weight: FontWeight, lineHeight: TextUnit, tracking: TextUnit) =
    TextStyle(fontFamily = MonoFontFamily, fontWeight = weight, fontSize = size, lineHeight = lineHeight, letterSpacing = tracking)

val Typography = Typography(
    displayLarge = display(34.sp, FontWeight.Bold, 40.sp, (-0.02f).em),
    displayMedium = display(28.sp, FontWeight.Bold, 36.sp, (-0.02f).em),
    displaySmall = display(24.sp, FontWeight.Bold, 32.sp, (-0.01f).em),
    headlineLarge = display(24.sp, FontWeight.SemiBold, 30.sp, 0.sp),
    headlineMedium = display(22.sp, FontWeight.SemiBold, 28.sp, 0.sp),
    headlineSmall = display(20.sp, FontWeight.SemiBold, 26.sp, 0.sp),
    titleLarge = display(20.sp, FontWeight.SemiBold, 26.sp, 0.sp),
    titleMedium = display(16.sp, FontWeight.SemiBold, 22.sp, 0.15.sp),
    titleSmall = display(14.sp, FontWeight.SemiBold, 20.sp, 0.1.sp),
    bodyLarge = display(16.sp, FontWeight.Normal, 24.sp, 0.5.sp),
    bodyMedium = display(14.sp, FontWeight.Normal, 20.sp, 0.25.sp),
    bodySmall = display(12.sp, FontWeight.Normal, 17.sp, 0.4.sp),
    labelLarge = mono(14.sp, FontWeight.Medium, 20.sp, 0.05f.em),
    labelMedium = mono(12.sp, FontWeight.Medium, 16.sp, 0.05f.em),
    labelSmall = mono(11.sp, FontWeight.Medium, 15.sp, 0.05f.em)
)
