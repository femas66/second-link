package id.my.femasaf.secondlink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Status semantics MaterialTheme's ColorScheme has no roles for (success/warning).
 * Error reuses the standard Material error roles.
 */
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

private val LightStatusColors = StatusColors(
    success = Success,
    onSuccess = Color.White,
    successContainer = SuccessContainerLight,
    onSuccessContainer = OnSuccessContainerLight,
    warning = Warning,
    onWarning = Color.White,
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight
)

private val DarkStatusColors = StatusColors(
    success = Color(0xFF8BD48D),
    onSuccess = Color(0xFF00390A),
    successContainer = SuccessContainerDark,
    onSuccessContainer = OnSuccessContainerDark,
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF472A00),
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

private val DarkColorScheme = darkColorScheme(
    primary = IndigoTooling90,
    onPrimary = IndigoTooling20,
    primaryContainer = IndigoTooling30,
    onPrimaryContainer = IndigoTooling90,
    secondary = MutedDark,
    onSecondary = Color(0xFF322D41),
    background = SurfaceDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = Color(0xFF272530),
    surfaceVariant = Color(0xFF322F37),
    onSurfaceVariant = MutedDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF322F37),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680400),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoTooling,
    onPrimary = Color.White,
    primaryContainer = IndigoTooling90,
    onPrimaryContainer = IndigoTooling20,
    secondary = Muted,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = Color(0xFFF0F0F2),
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = Muted,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = Error,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight
)

/**
 * The "Mission Control Desk" identity is a committed brand, not a category default:
 * dynamic (wallpaper-derived) color is opt-in only, never the default, so the indigo
 * anchor and the Rarity Rule hold on every device.
 */
@Composable
fun SecondLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = SecondLinkShapes,
            content = content
        )
    }
}

/** Convenience accessor: `MaterialTheme.status` inside any `@Composable`. */
val MaterialTheme.status: StatusColors
    @Composable get() = LocalStatusColors.current
