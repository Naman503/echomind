package app.echomind.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Calm, paper-like, low chroma with one indigo accent. Three semantic colours
 * never change with the theme or dynamic colour, because they carry meaning:
 * coral = recording, teal = stays on this phone, amber = leaves the phone.
 */
@Immutable
data class Semantic(
    val recording: Color,
    val onDevice: Color,
    val cloud: Color,
    val cloudContainer: Color,
    val positive: Color,
    val warning: Color,
)

private val LightSemantic = Semantic(Color(0xFFE5484D), Color(0xFF0F8A7E), Color(0xFFB26A00), Color(0xFFFFE7C2), Color(0xFF2E7D4F), Color(0xFFB45309))
private val DarkSemantic = Semantic(Color(0xFFFF8A80), Color(0xFF5FD4C4), Color(0xFFFFB74D), Color(0xFF4A3410), Color(0xFF7BD49A), Color(0xFFF5B266))

val LocalSemantic = staticCompositionLocalOf { LightSemantic }

private val Light = lightColorScheme(
    primary = Color(0xFF3D4A9E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF0E1A5C),
    secondary = Color(0xFF5B5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF7A5260), tertiaryContainer = Color(0xFFFFD9E3), onTertiaryContainer = Color(0xFF2F111D),
    background = Color(0xFFFBF8F4), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8F4), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE6E3DD), onSurfaceVariant = Color(0xFF5E5C58),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F2EC),
    surfaceContainer = Color(0xFFEFECE6), surfaceContainerHigh = Color(0xFFE9E6E0), surfaceContainerHighest = Color(0xFFE3E0DA),
    outline = Color(0xFF8D8A85), outlineVariant = Color(0xFFD8D4CD),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF1D2A6E),
    primaryContainer = Color(0xFF354185), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD), secondaryContainer = Color(0xFF434559), onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFEAB8C8), tertiaryContainer = Color(0xFF603B48),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF292A2F), surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF908F9A), outlineVariant = Color(0xFF46464F),
)

private val body = TextStyle(
    fontFamily = FontFamily.Default,
    // Devanagari matras need room above and below; no clipping, no extra padding.
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

private val Type = Typography(
    displaySmall = body.copy(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineMedium = body.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    headlineSmall = body.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    titleLarge = body.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = body.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = body.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = body.copy(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EchoTheme(theme: String = "system", dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalSemantic provides if (dark) DarkSemantic else LightSemantic) {
        MaterialExpressiveTheme(colorScheme = scheme, typography = Type, shapes = Shape, motionScheme = MotionScheme.standard(), content = content)
    }
}

/** Spacing on a 4 dp grid. */
object Sp {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = 16.dp
}

/** Colours for lists and notes; index 0 means "no colour". Readable in light and dark. */
object Palette {
    private val colors = listOf(
        Color(0xFFE5484D), Color(0xFFF08C00), Color(0xFFE2B007), Color(0xFF2F9E44),
        Color(0xFF0F8A7E), Color(0xFF3E7BFA), Color(0xFF6E56CF), Color(0xFFD6409F),
    )
    val size get() = colors.size
    fun color(i: Int): Color = if (i in 1..colors.size) colors[i - 1] else Color(0xFF8D8A85)
}
