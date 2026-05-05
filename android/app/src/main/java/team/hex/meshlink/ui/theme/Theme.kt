package team.hex.meshlink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * MeshLink theme.
 *
 * Two seed palettes — a deep aurora violet/teal for dark mode and a soft
 * frosted ice palette for light. Both are designed to sit underneath the
 * liquid-glass surfaces in [team.hex.meshlink.ui.theme.GlassSurface], where
 * translucent panels float over a saturated gradient background.
 *
 * On API 31+ we prefer the user's Material You dynamic palette so the app
 * picks up the current wallpaper colors; everything blends automatically
 * because the glass primitives only scale alpha, never hard-code RGB.
 */

private val MeshDark = darkColorScheme(
    primary       = Color(0xFFB6C8FF),
    onPrimary     = Color(0xFF0A1A4F),
    primaryContainer    = Color(0xFF243B8A),
    onPrimaryContainer  = Color(0xFFD9E2FF),
    secondary     = Color(0xFF7FE3D2),
    onSecondary   = Color(0xFF003733),
    tertiary      = Color(0xFFE9B0FF),
    onTertiary    = Color(0xFF430E5F),
    background    = Color(0xFF05060B),
    onBackground  = Color(0xFFE6E6F0),
    surface       = Color(0xFF0B0C16),
    onSurface     = Color(0xFFE6E6F0),
    surfaceVariant = Color(0xFF22243A),
    onSurfaceVariant = Color(0xFFC4C5DA),
    outline       = Color(0xFF7C7E97),
    error         = Color(0xFFFFB4AB),
    onError       = Color(0xFF690005),
)

private val MeshLight = lightColorScheme(
    primary       = Color(0xFF2B47C0),
    onPrimary     = Color(0xFFFFFFFF),
    primaryContainer    = Color(0xFFD9E2FF),
    onPrimaryContainer  = Color(0xFF001A4F),
    secondary     = Color(0xFF006A60),
    onSecondary   = Color(0xFFFFFFFF),
    tertiary      = Color(0xFF7C2BAF),
    onTertiary    = Color(0xFFFFFFFF),
    background    = Color(0xFFF7F8FF),
    onBackground  = Color(0xFF111122),
    surface       = Color(0xFFFFFFFF),
    onSurface     = Color(0xFF111122),
    surfaceVariant = Color(0xFFE2E2EE),
    onSurfaceVariant = Color(0xFF45465B),
    outline       = Color(0xFF767791),
    error         = Color(0xFFB3261E),
    onError       = Color(0xFFFFFFFF),
)

private val MeshShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val MeshTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 56.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun MeshLinkTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> MeshDark
        else -> MeshLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = MeshTypography,
        shapes = MeshShapes,
        content = content,
    )
}
