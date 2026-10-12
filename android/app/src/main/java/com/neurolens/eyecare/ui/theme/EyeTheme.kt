package com.neurolens.eyecare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurolens.eyecare.R
import com.neurolens.eyecare.engine.EyeCondition

/** Pretendard (SIL OFL 1.1, bundled in res/font with its license in res/raw). */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold)
)

/** App-specific tokens Material does not name. Text pairs are chosen for >= 4.5:1 on their surface. */
@Immutable
data class EyeColors(
    val canvas: Color,          // page background (warm linen by day, deep forest by night)
    val card: Color,
    val cardMuted: Color,       // tinted tile background
    val ink: Color,             // primary text
    val inkMuted: Color,        // secondary text
    val line: Color,            // dividers, ring tracks
    val brand: Color,           // forest green: CTA, brand dot, links
    val onBrand: Color,
    val brandSoft: Color,       // tonal button / selected chip
    val heroTop: Color,         // dashboard hero gradient
    val heroBottom: Color,
    val levelFill: Map<EyeCondition.Band, Color>,  // rings, bars, dots
    val levelText: Map<EyeCondition.Band, Color>,  // labels on card surface
    val levelSoft: Map<EyeCondition.Band, Color>,  // chip backgrounds
)

private val LightEye = EyeColors(
    canvas = Color(0xFFF6F5F0), card = Color(0xFFFFFFFF), cardMuted = Color(0xFFEFF4EF),
    ink = Color(0xFF15211E), inkMuted = Color(0xFF55655F), line = Color(0xFFE3E7E2),
    brand = Color(0xFF1F6F5C), onBrand = Color(0xFFFFFFFF), brandSoft = Color(0xFFDCEFE7),
    heroTop = Color(0xFFE3F2EA), heroBottom = Color(0xFFFFFFFF),
    levelFill = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFF2FA57F), EyeCondition.Band.GOOD to Color(0xFF7DC069),
        EyeCondition.Band.FAIR to Color(0xFFF0BE45), EyeCondition.Band.CAUTION to Color(0xFFF08F43),
        EyeCondition.Band.RISK to Color(0xFFE35D52), EyeCondition.Band.UNKNOWN to Color(0xFFB5BDB9)),
    levelText = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFF17694F), EyeCondition.Band.GOOD to Color(0xFF3B6E25),
        EyeCondition.Band.FAIR to Color(0xFF7D5A00), EyeCondition.Band.CAUTION to Color(0xFF9A4A0F),
        EyeCondition.Band.RISK to Color(0xFFAF2F25), EyeCondition.Band.UNKNOWN to Color(0xFF55655F)),
    levelSoft = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFFDDF3EA), EyeCondition.Band.GOOD to Color(0xFFE7F4DF),
        EyeCondition.Band.FAIR to Color(0xFFFBF0D2), EyeCondition.Band.CAUTION to Color(0xFFFCE6D3),
        EyeCondition.Band.RISK to Color(0xFFFADFDB), EyeCondition.Band.UNKNOWN to Color(0xFFECEFED)),
)

private val DarkEye = EyeColors(
    canvas = Color(0xFF0D1513), card = Color(0xFF16201D), cardMuted = Color(0xFF1C2925),
    ink = Color(0xFFE7EFEC), inkMuted = Color(0xFFA2B4AE), line = Color(0xFF2A3833),
    brand = Color(0xFF6FD1AE), onBrand = Color(0xFF06231A), brandSoft = Color(0xFF1E3B32),
    heroTop = Color(0xFF173129), heroBottom = Color(0xFF16201D),
    levelFill = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFF4CC79C), EyeCondition.Band.GOOD to Color(0xFF9AD47F),
        EyeCondition.Band.FAIR to Color(0xFFF2CB62), EyeCondition.Band.CAUTION to Color(0xFFF4A261),
        EyeCondition.Band.RISK to Color(0xFFF07A6F), EyeCondition.Band.UNKNOWN to Color(0xFF5E6E68)),
    levelText = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFF6FDDB4), EyeCondition.Band.GOOD to Color(0xFFADDD94),
        EyeCondition.Band.FAIR to Color(0xFFF4D27A), EyeCondition.Band.CAUTION to Color(0xFFF6B47F),
        EyeCondition.Band.RISK to Color(0xFFF59A90), EyeCondition.Band.UNKNOWN to Color(0xFFA2B4AE)),
    levelSoft = mapOf(
        EyeCondition.Band.EXCELLENT to Color(0xFF173A2F), EyeCondition.Band.GOOD to Color(0xFF243A1C),
        EyeCondition.Band.FAIR to Color(0xFF3A3218), EyeCondition.Band.CAUTION to Color(0xFF3D2A1A),
        EyeCondition.Band.RISK to Color(0xFF3E201D), EyeCondition.Band.UNKNOWN to Color(0xFF24302C)),
)

val LocalEyeColors = staticCompositionLocalOf { LightEye }

object Eye {
    val colors: EyeColors @Composable get() = LocalEyeColors.current
    val cardShape = RoundedCornerShape(26.dp)
    val tileShape = RoundedCornerShape(22.dp)
    val pillShape = RoundedCornerShape(50)
    val chipShape = RoundedCornerShape(14.dp)
}

// Korean wraps per syllable by default; phrase breaking keeps words like "어두움" together (API 33+).
private val KoreanPhrase = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Pretendard, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    letterSpacing = tracking.sp, lineBreak = KoreanPhrase)

/** One type scale for the whole app; body text never below 14sp, captions 12sp. */
private val EyeTypography = Typography(
    displayLarge = style(64, 68, FontWeight.Bold, -2.0),      // hero score
    displaySmall = style(36, 42, FontWeight.Bold, -1.0),
    headlineMedium = style(26, 34, FontWeight.Bold, -0.6),    // page titles
    headlineSmall = style(22, 30, FontWeight.Bold, -0.4),
    titleLarge = style(19, 26, FontWeight.SemiBold, -0.2),    // card titles
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(13, 19, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),          // buttons
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium, 0.2),
)

@Composable
fun EyeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val eye = if (dark) DarkEye else LightEye
    val scheme = if (dark) darkColorScheme(primary = eye.brand, onPrimary = eye.onBrand,
        primaryContainer = eye.brandSoft, onPrimaryContainer = eye.ink, secondary = eye.brand,
        background = eye.canvas, onBackground = eye.ink, surface = eye.card, onSurface = eye.ink,
        surfaceVariant = eye.cardMuted, onSurfaceVariant = eye.inkMuted, outline = eye.line,
        surfaceContainerHigh = eye.card, surfaceContainer = eye.card)
    else lightColorScheme(primary = eye.brand, onPrimary = eye.onBrand,
        primaryContainer = eye.brandSoft, onPrimaryContainer = eye.ink, secondary = eye.brand,
        background = eye.canvas, onBackground = eye.ink, surface = eye.card, onSurface = eye.ink,
        surfaceVariant = eye.cardMuted, onSurfaceVariant = eye.inkMuted, outline = eye.line,
        surfaceContainerHigh = eye.card, surfaceContainer = eye.card)
    CompositionLocalProvider(LocalEyeColors provides eye) {
        MaterialTheme(colorScheme = scheme, typography = EyeTypography,
            shapes = Shapes(small = Eye.chipShape, medium = Eye.tileShape, large = Eye.cardShape),
            content = content)
    }
}
