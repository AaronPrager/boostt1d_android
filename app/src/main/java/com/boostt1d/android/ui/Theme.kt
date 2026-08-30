package com.boostt1d.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The palette from the iOS app's BoostTheme, value for value.
 *
 * iOS resolves light/dark inside each `Color.adaptive(light:dark:)`. Compose has no
 * equivalent, so the two palettes are built separately and chosen once at the top of
 * the tree — which is why every colour has to be read through [BoostTheme.colors]
 * rather than referenced as a global constant.
 */
@Immutable
data class BoostColors(
    val primary: Color,
    val primaryDeep: Color,
    val accent: Color,
    val accentDeep: Color,
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val inRange: Color,
    val high: Color,
    val veryHigh: Color,
    val low: Color,
    val neutral: Color,
    val insulin: Color,
    val carbs: Color,
    val food: Color,
    val insight: Color,
    val report: Color,
    val clinical: Color,
)

private val LightBoostColors = BoostColors(
    primary = Color(0xFF1F6FEB),
    primaryDeep = Color(0xFF1552B8),
    accent = Color(0xFF0FB5A6),
    accentDeep = Color(0xFF0B8B80),
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFEDF0F6),
    border = Color(0xFFE2E6EE),
    textPrimary = Color(0xFF0E1521),
    textSecondary = Color(0xFF5C6779),
    textTertiary = Color(0xFF8A93A3),
    inRange = Color(0xFF16A34A),
    high = Color(0xFFE8890C),
    veryHigh = Color(0xFFDC5A17),
    low = Color(0xFFDC2626),
    neutral = Color(0xFF7A8496),
    insulin = Color(0xFF7C4DFF),
    carbs = Color(0xFFF08A24),
    food = Color(0xFF0FB5A6),
    insight = Color(0xFFE8890C),
    report = Color(0xFF4F46E5),
    clinical = Color(0xFF0891B2),
)

private val DarkBoostColors = BoostColors(
    primary = Color(0xFF4C8DFF),
    primaryDeep = Color(0xFF2F6BD8),
    accent = Color(0xFF2DD4BF),
    accentDeep = Color(0xFF14A79A),
    background = Color(0xFF0B0E13),
    surface = Color(0xFF161B23),
    surfaceMuted = Color(0xFF1E242E),
    border = Color(0xFF2A313C),
    textPrimary = Color(0xFFF2F5FA),
    textSecondary = Color(0xFF9AA5B5),
    textTertiary = Color(0xFF6E7887),
    inRange = Color(0xFF35CE6C),
    high = Color(0xFFFFAA33),
    veryHigh = Color(0xFFFF7A3D),
    low = Color(0xFFFF5A5A),
    neutral = Color(0xFF8B95A6),
    insulin = Color(0xFFA07BFF),
    carbs = Color(0xFFFFA94D),
    food = Color(0xFF2DD4BF),
    insight = Color(0xFFFFAA33),
    report = Color(0xFF818CF8),
    clinical = Color(0xFF22C3E0),
)

/** iOS BoostTheme.Spacing. */
object BoostSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
    val xxl = 40.dp
}

/** iOS BoostTheme.Radius. */
object BoostRadius {
    val sm = 7.dp
    val md = 10.dp
    val lg = 14.dp
    val xl = 18.dp
    val pillLike = 999.dp
}

private val LocalBoostColors = staticCompositionLocalOf { LightBoostColors }

object BoostTheme {
    val colors: BoostColors
        @Composable get() = LocalBoostColors.current
}

private val BoostTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun BoostT1DTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val boost = if (darkTheme) DarkBoostColors else LightBoostColors

    // Material's own scheme is mapped onto the Boost palette so the stock components
    // (text fields, checkboxes, dropdown menus) land on brand without being restyled
    // one by one at every call site.
    val material = if (darkTheme) {
        darkColorScheme(
            primary = boost.primary,
            onPrimary = Color.White,
            background = boost.background,
            onBackground = boost.textPrimary,
            surface = boost.surface,
            onSurface = boost.textPrimary,
            surfaceVariant = boost.surfaceMuted,
            onSurfaceVariant = boost.textSecondary,
            outline = boost.border,
            error = boost.low,
        )
    } else {
        lightColorScheme(
            primary = boost.primary,
            onPrimary = Color.White,
            background = boost.background,
            onBackground = boost.textPrimary,
            surface = boost.surface,
            onSurface = boost.textPrimary,
            surfaceVariant = boost.surfaceMuted,
            onSurfaceVariant = boost.textSecondary,
            outline = boost.border,
            error = boost.low,
        )
    }

    CompositionLocalProvider(LocalBoostColors provides boost) {
        MaterialTheme(colorScheme = material, typography = BoostTypography, content = content)
    }
}
