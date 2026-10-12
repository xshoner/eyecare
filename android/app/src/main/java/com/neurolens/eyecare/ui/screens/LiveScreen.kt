package com.neurolens.eyecare.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neurolens.eyecare.data.DistanceGuide
import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.ui.components.*
import com.neurolens.eyecare.ui.theme.Eye

data class LiveState(
    val elapsedMs: Long,
    val nextReportMs: Long,
    val conditionBurden: Int?,      // stored-burden scale; null when not measurable
    val partial: Boolean,
    val screenShared: Boolean,
    val distanceCm: Double?,
    val lux: Double?,
    val coaching: String?,          // plain-language capture tip (low light, face not visible …)
    val reminderMuted: Boolean,
    val notificationsOn: Boolean,
    val factors: List<Pair<String, Double>> = emptyList(),  // active deduction factors, points per minute
    val ratePerMin: Double = 0.0,
    val comboLabel: String? = null,
    val forecast: String? = null,
    val breakIntervalMin: Int = 20,
    val continuousMin: Double = 0.0,
)

data class LiveActions(val stop: () -> Unit, val resumeShare: () -> Unit, val muteReminder: () -> Unit,
    val calibrate: () -> Unit, val rest: () -> Unit)

@Composable fun LiveScreen(state: LiveState, actions: LiveActions, devContent: (@Composable () -> Unit)? = null) {
    val c = Eye.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EyeCard(brush = Brush.verticalGradient(listOf(c.heroTop, c.heroBottom)), padding = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulseDot(if (state.screenShared) c.brand else c.levelFill.getValue(EyeCondition.Band.CAUTION))
                Text(if (state.screenShared) "눈 케어 중" else "화면 공유가 멈췄어요", style = MaterialTheme.typography.titleMedium, color = c.ink)
            }
            Text(Fmt.clock(state.elapsedMs), style = MaterialTheme.typography.displayLarge, color = c.ink)
            Text("다음 리포트까지 ${Fmt.duration(state.nextReportMs)}", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            if (!state.screenShared) PrimaryButton("화면 공유 다시 시작", onClick = actions.resumeShare)
        }
        state.coaching?.let { tip ->
            EyeCard(color = c.levelSoft.getValue(EyeCondition.Band.FAIR)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(EyeIcons.Info, null, Modifier.size(24.dp), tint = c.levelText.getValue(EyeCondition.Band.FAIR))
                    Text(tip.keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        }
        val condition = EyeCondition.fromBurden(state.conditionBurden)
        val band = if (condition == null || state.partial) EyeCondition.Band.UNKNOWN else EyeCondition.band(condition)
        val advice = DistanceGuide.advise(state.distanceCm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).clip(Eye.tileShape).background(c.card).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("지금 컨디션", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                ConditionRing(condition, band, size = 104.dp, stroke = 10.dp, numberStyle = MaterialTheme.typography.displaySmall, caption = "")
                LevelChip(band, text = if (state.partial && condition != null) "측정 중" else band.label)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(EyeIcons.Distance, "지금 거리", state.distanceCm?.let { "${it.toInt()}" } ?: "–", unit = state.distanceCm?.let { "cm" },
                    caption = when (advice.level) { DistanceGuide.Level.GOOD -> "좋아요"; DistanceGuide.Level.UNKNOWN -> "얼굴을 비춰 주세요"
                        else -> "${advice.moveCm}cm 더 멀리" },
                    accent = when (advice.level) { DistanceGuide.Level.TOO_CLOSE -> c.levelFill.getValue(EyeCondition.Band.RISK)
                        DistanceGuide.Level.CLOSE -> c.levelFill.getValue(EyeCondition.Band.CAUTION); else -> c.brand })
                StatTile(if ((state.lux ?: 999.0) < 50) EyeIcons.Moon else EyeIcons.Sun, "주변 밝기",
                    Fmt.light(state.lux)?.first ?: "–")
            }
        }
        StrainNowCard(state)
        if (advice.level == DistanceGuide.Level.TOO_CLOSE) Text(advice.detail.keepAll(), style = MaterialTheme.typography.bodyMedium,
            color = c.levelText.getValue(EyeCondition.Band.RISK), modifier = Modifier.padding(horizontal = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalButton("20초 쉬기", Modifier.weight(1f), icon = EyeIcons.Leaf, onClick = actions.rest)
            TonalButton("케어 종료", Modifier.weight(1f), icon = EyeIcons.Stop, onClick = actions.stop)
        }
        EyeCard(padding = 8.dp) {
            ListRow("거리 맞추기", Modifier.padding(horizontal = 12.dp), subtitle = "자로 눈과 카메라 사이를 30cm에 맞춘 뒤 눌러 주세요",
                icon = EyeIcons.Distance, onClick = actions.calibrate)
            if (state.notificationsOn) { Hairline(Modifier.padding(horizontal = 12.dp))
                ListRow(if (state.reminderMuted) "이번 케어 알림 꺼짐" else "이번 케어 알림 끄기", Modifier.padding(horizontal = 12.dp),
                    icon = EyeIcons.Bell, onClick = if (state.reminderMuted) null else actions.muteReminder) }
        }
        Text("평소처럼 폰을 사용하세요. 화면과 얼굴 영상은 저장하지 않고 기기 안에서만 분석해요.".keepAll(),
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
        devContent?.invoke()
    }
}

@Composable private fun PulseDot(color: androidx.compose.ui.graphics.Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 1.6f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).scale(s).clip(CircleShape).background(color.copy(alpha = .3f)))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

/** Guided 20-20-20 style rest: a slow breathing circle, the remaining seconds, then a check-in. */
@Composable fun RestScreen(remainingMs: Long, confirm: (Boolean) -> Unit, done: () -> Unit) {
    val c = Eye.colors
    val t = rememberInfiniteTransition(label = "breath")
    val breath by t.animateFloat(.82f, 1f, infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (remainingMs > 0) "먼 곳을 바라보며\n천천히 숨 쉬어 보세요" else "잘 쉬었어요",
            style = MaterialTheme.typography.headlineMedium, color = c.ink, textAlign = TextAlign.Center)
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize().scale(if (remainingMs > 0) breath else 1f)) {
                drawCircle(c.brandSoft)
                drawCircle(c.brand.copy(alpha = .18f), radius = size.minDimension * .36f)
            }
            Text(if (remainingMs > 0) "${(remainingMs + 999) / 1000}" else "완료",
                style = MaterialTheme.typography.displayLarge, color = c.brand)
        }
        Text("6m(20피트) 이상 떨어진 곳을 20초 바라보면 눈 근육이 쉬어요.\n케어는 잠시 멈췄어요. 다시 하려면 홈에서 시작해 주세요.".keepAll(),
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, textAlign = TextAlign.Center)
        if (remainingMs == 0L) {
            Text("실제로 쉬었나요?", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TonalButton("쉬었어요", Modifier.weight(1f)) { confirm(true) }
                TonalButton("못 쉬었어요", Modifier.weight(1f)) { confirm(false) }
            }
        }
        QuietButton(if (remainingMs > 0) "그만하고 돌아가기" else "돌아가기", onClick = done)
    }
}

/** What is lowering the score right now, how fast, and when the next break reminder comes. */
@Composable private fun StrainNowCard(state: LiveState) {
    val c = Eye.colors
    EyeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지금 감점 요인", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(if (state.ratePerMin >= .01) "분당 −${"%.1f".format(state.ratePerMin)}점" else "감점 없음",
                style = MaterialTheme.typography.labelMedium,
                color = c.levelText.getValue(when { state.ratePerMin >= 1.0 -> EyeCondition.Band.RISK
                    state.ratePerMin >= .5 -> EyeCondition.Band.CAUTION; state.ratePerMin >= .2 -> EyeCondition.Band.FAIR
                    else -> EyeCondition.Band.GOOD }))
        }
        state.comboLabel?.let {
            LevelChip(EyeCondition.Band.RISK, text = "$it 보는 중 · 휴식 알림 ${state.breakIntervalMin}분마다")
        }
        if (state.factors.isEmpty()) Text("지금은 눈에 부담을 주는 요인이 거의 없어요.".keepAll(),
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        val top = state.factors.maxOfOrNull { it.second }?.coerceAtLeast(.1) ?: 1.0
        state.factors.take(4).forEach { (label, rate) ->
            val band = when { rate >= .5 -> EyeCondition.Band.CAUTION; rate >= .2 -> EyeCondition.Band.FAIR; else -> EyeCondition.Band.GOOD }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row { Text(label, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    Text("−${"%.2f".format(rate)}/분", style = MaterialTheme.typography.labelMedium, color = c.levelText.getValue(band)) }
                ProgressLine((rate / top).toFloat(), c.levelFill.getValue(band), height = 6.dp)
            }
        }
        state.forecast?.let { Text(it.keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.ink) }
        val nextBreak = (state.breakIntervalMin - state.continuousMin % state.breakIntervalMin).coerceAtLeast(0.0)
        Text("연속 ${state.continuousMin.toInt()}분째 · 다음 휴식 알림까지 약 ${kotlin.math.ceil(nextBreak).toInt()}분".keepAll(),
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}
