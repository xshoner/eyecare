package com.neurolens.eyecare.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.ui.theme.Eye

/** Brand wordmark: EYE · 休 (rest). */
@Composable fun Wordmark(modifier: Modifier = Modifier) {
    val c = Eye.colors
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.Bold)) { append("EYE") }
        withStyle(SpanStyle(color = c.brand, fontWeight = FontWeight.Bold)) { append("·") }
        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append("休") }
    }, style = MaterialTheme.typography.headlineSmall, modifier = modifier.semantics { contentDescription = "아이휴" })
}

@Composable fun EyeCard(modifier: Modifier = Modifier, color: Color = Eye.colors.card,
    brush: Brush? = null, onClick: (() -> Unit)? = null, padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.fillMaxWidth().clip(Eye.cardShape)
        .let { if (brush != null) it.background(brush) else it.background(color) }
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(padding)
    Column(base, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = Eye.pillShape,
        colors = ButtonDefaults.buttonColors(containerColor = Eye.colors.brand, contentColor = Eye.colors.onBrand),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun TonalButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = Eye.pillShape,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Eye.colors.brandSoft, contentColor = Eye.colors.ink),
        contentPadding = PaddingValues(horizontal = 20.dp), modifier = modifier.heightIn(min = 48.dp)) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable fun QuietButton(text: String, modifier: Modifier = Modifier, color: Color = Eye.colors.brand,
    enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable fun LevelChip(band: EyeCondition.Band, modifier: Modifier = Modifier, text: String = band.label) {
    val c = Eye.colors
    Text(text, style = MaterialTheme.typography.labelMedium, color = c.levelText.getValue(band),
        modifier = modifier.clip(Eye.pillShape).background(c.levelSoft.getValue(band))
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Score ring with a soft track and the level colour; animates once from 0 on first show. */
@Composable fun ConditionRing(condition: Int?, band: EyeCondition.Band, modifier: Modifier = Modifier,
    size: Dp = 176.dp, stroke: Dp = 16.dp, numberStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayLarge,
    caption: String = "/ 100") {
    val c = Eye.colors
    val progress = remember { Animatable(0f) }
    LaunchedEffect(condition) {
        progress.animateTo((condition ?: 0) / 100f, tween(900, easing = FastOutSlowInEasing))
    }
    val fill = c.levelFill.getValue(band)
    Box(modifier.size(size).semantics {
        contentDescription = condition?.let { "눈 컨디션 ${it}점, ${band.label}" } ?: "눈 컨디션 판정 보류"
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(c.line, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (condition != null) drawArc(fill,
                135f, 270f * progress.value, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(condition?.toString() ?: "–", style = numberStyle, color = c.ink)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
        }
    }
}

/** Five-step scale 위험 → 매우 양호 with a marker at the current score. Colour is paired with labels. */
@Composable fun LevelScale(condition: Int?, modifier: Modifier = Modifier) {
    val c = Eye.colors
    val current = EyeCondition.band(condition)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(18.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val gap = 4.dp.toPx(); val h = 8.dp.toPx(); val y = (size.height - h) / 2
                val seg = (size.width - gap * 4) / 5
                EyeCondition.SCALE.forEachIndexed { i, band ->
                    drawRoundRect(c.levelFill.getValue(band).copy(alpha = if (band == current) 1f else .28f),
                        Offset(i * (seg + gap), y), Size(seg, h), CornerRadius(h / 2))
                }
                if (condition != null) {
                    val x = (condition.coerceIn(0, 100) / 100f) * size.width
                    drawCircle(c.card, 9.dp.toPx(), Offset(x.coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), size.height / 2))
                    drawCircle(c.levelFill.getValue(current), 6.dp.toPx(), Offset(x.coerceIn(9.dp.toPx(), size.width - 9.dp.toPx()), size.height / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            EyeCondition.SCALE.forEach { band ->
                Text(band.label, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (band == current) c.levelText.getValue(band) else c.inkMuted,
                    fontWeight = if (band == current) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** Bento tile for the dashboard grid. */
@Composable fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier,
    unit: String? = null, caption: String? = null, accent: Color = Eye.colors.brand,
    background: Color = Eye.colors.card, onClick: (() -> Unit)? = null, footer: @Composable (() -> Unit)? = null) {
    val c = Eye.colors
    Column(modifier.clip(Eye.tileShape).background(background)
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            unit?.let { Text(" $it", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        caption?.let { Text(it.keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted) }
        footer?.invoke()
    }
}

@Composable fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Eye.colors.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) QuietButton(action, onClick = onAction)
    }
}

/** Thin progress bar (0..1). */
@Composable fun ProgressLine(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Eye.colors.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height)).background(color))
    }
}

/** Seven-day mini bars of daily condition; a missing day is an empty slot, never zero. */
@Composable fun WeekBars(days: List<Pair<String, Int?>>, modifier: Modifier = Modifier) {
    val c = Eye.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom) {
            days.forEach { (label, value) ->
                val band = EyeCondition.band(value)
                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = value?.let { "$label ${it}점 ${band.label}" } ?: "$label 기록 없음"
                }, contentAlignment = Alignment.BottomCenter) {
                    if (value == null) Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line))
                    else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Text("$value", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().fillMaxHeight((value / 100f * .82f).coerceIn(.06f, .82f))
                            .clip(RoundedCornerShape(10.dp)).background(c.levelFill.getValue(band)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { (label, value) ->
                Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = if (value != null) c.ink else c.inkMuted)
            }
        }
    }
}

/** Selectable pill group for settings. */
@Composable fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Eye.colors
    Row(modifier.fillMaxWidth().clip(Eye.pillShape).background(c.cardMuted).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(Eye.pillShape)
                .background(if (on) c.card else Color.Transparent)
                .clickable(role = Role.RadioButton) { onSelect(value) }, contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = if (on) c.ink else c.inkMuted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

/** A settings/list row with optional value and chevron. */
@Composable fun ListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, value: String? = null,
    icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val c = Eye.colors
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon?.let { Icon(it, null, Modifier.size(22.dp), tint = c.inkMuted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            subtitle?.let { Text(it.keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.brand) }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(EyeIcons.Chevron, null, Modifier.size(18.dp), tint = c.inkMuted)
    }
}

/** Brand credit at the end of every page. */
@Composable fun PoweredBy(modifier: Modifier = Modifier) {
    val c = Eye.colors
    Text(buildAnnotatedString {
        append("© 2026 · Powered by ")
        withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append("AXopenlab") }
    }, style = MaterialTheme.typography.labelSmall, color = c.inkMuted, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable fun Hairline(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier, thickness = 1.dp, color = Eye.colors.line)

/** Text colour on a filled level swatch; the swatches are mid-light in both themes. */
val OnLevelFill = Color(0xFF10201B)

fun Modifier.semanticsTap(label: String, onClick: () -> Unit): Modifier =
    this.semantics { contentDescription = label }.clickable(role = Role.Button, onClick = onClick)

/**
 * Korean "keep-all": Android wraps Hangul between any two syllables, splitting words mid-way.
 * Joining the characters of each word with U+2060 WORD JOINER (default-ignorable, not drawn)
 * leaves spaces as the only break opportunities.
 */
fun String.keepAll(): String {
    if (none { it in '가'..'힣' }) return this
    val out = StringBuilder(length + length / 2)
    for (i in indices) {
        val ch = this[i]
        out.append(ch)
        val next = getOrNull(i + 1) ?: continue
        if (!ch.isWhitespace() && !next.isWhitespace() && next != '⁠' && ch != '⁠') out.append('⁠')
    }
    return out.toString()
}
