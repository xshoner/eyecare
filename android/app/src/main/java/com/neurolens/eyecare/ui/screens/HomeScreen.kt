package com.neurolens.eyecare.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.neurolens.eyecare.data.CareReport
import com.neurolens.eyecare.data.DailyDistanceSummary
import com.neurolens.eyecare.data.DistanceGuide
import com.neurolens.eyecare.data.UsageSummary
import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.ui.components.*
import com.neurolens.eyecare.ui.theme.Eye

data class HomeState(
    val latest: CareReport?,
    val previousDeltaBurden: Int?,
    val usage: UsageSummary,
    val distanceToday: DailyDistanceSummary,
    val distanceWeek: DailyDistanceSummary,
    val week: List<Pair<String, Int?>>,
    val notificationsOn: Boolean,
    val launching: Boolean,
    val latestNrs: Int?,
)

data class HomeActions(
    val start: () -> Unit,
    val openReport: (String) -> Unit,
    val openRecords: () -> Unit,
    val usageAccess: () -> Unit,
    val notifications: () -> Unit,
    val answer: (String, Int) -> Unit,
)

@Composable fun HomeScreen(state: HomeState, actions: HomeActions) {
    val c = Eye.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(Fmt.today(), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        HeroCondition(state, actions)
        PrimaryButton(if (state.launching) "준비하고 있어요…" else "눈 케어 시작", icon = EyeIcons.Play,
            enabled = !state.launching, onClick = actions.start)
        Text("시작하면 화면 공유를 한 번 허락해 주세요. 화면·얼굴 영상은 저장하지 않아요.".keepAll(),
            style = MaterialTheme.typography.bodySmall, color = c.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))

        state.latest?.let { report ->
            FeedbackCard(report.id, state.latestNrs) { actions.answer(report.id, it) }
        }

        SectionTitle("오늘의 눈 습관")
        val advice = DistanceGuide.advise(state.distanceWeek.meanCm ?: state.distanceToday.meanCm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(EyeIcons.Clock, "화면 사용", if (state.usage.granted && state.usage.hasEvents)
                Fmt.duration(state.usage.millis) else "–", Modifier.weight(1f),
                caption = if (state.usage.granted) "오늘 누적" else "탭해서 연결",
                onClick = if (state.usage.granted) null else actions.usageAccess)
            val avg = state.distanceToday.meanCm ?: state.distanceWeek.meanCm
            StatTile(EyeIcons.Distance, "평균 보는 거리", avg?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f),
                unit = avg?.let { "cm" }, caption = when (advice.level) {
                    DistanceGuide.Level.GOOD -> "좋은 거리"
                    DistanceGuide.Level.CLOSE, DistanceGuide.Level.TOO_CLOSE -> "${advice.moveCm}cm 더 멀리"
                    else -> "측정 후 표시" },
                accent = when (advice.level) {
                    DistanceGuide.Level.TOO_CLOSE -> c.levelFill.getValue(EyeCondition.Band.RISK)
                    DistanceGuide.Level.CLOSE -> c.levelFill.getValue(EyeCondition.Band.CAUTION)
                    else -> c.brand })
        }
        val light = Fmt.light(state.latest?.signal("E01.lux"))
        val blink = state.latest?.signal("F02.blinksPerMin", .35)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(if ((state.latest?.signal("E01.lux") ?: 999.0) < 50) EyeIcons.Moon else EyeIcons.Sun,
                "주변 밝기", light?.first ?: "–", Modifier.weight(1f), caption = if (light == null) "측정 후 표시" else "최근 측정")
            StatTile(EyeIcons.Blink, "눈 깜빡임", blink?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f),
                unit = blink?.let { "회/분" }, caption = blink?.let { if (it < 8) "자주 깜빡여 주세요" else "최근 측정" } ?: "측정 후 표시")
        }
        if (advice.level == DistanceGuide.Level.CLOSE || advice.level == DistanceGuide.Level.TOO_CLOSE)
            DistanceCoach(advice)
        state.latest?.takeIf { com.neurolens.eyecare.engine.StrainModel.isDeduction(it.version) &&
            Fmt.localDate(it.createdAt) == java.time.LocalDate.now() }?.let { DeductionCard(it) }

        SectionTitle("최근 7일", action = "기록 보기", onAction = actions.openRecords)
        EyeCard {
            if (state.week.none { it.second != null })
                Text("측정을 하면 하루 평균 눈 컨디션이 여기에 쌓여요.".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            WeekBars(state.week)
        }

        if (!state.usage.granted || !state.notificationsOn) {
            EyeCard(color = c.cardMuted, padding = 8.dp) {
                if (!state.usage.granted) ListRow("화면 사용 시간 연결", Modifier.padding(horizontal = 12.dp),
                    subtitle = "오늘 얼마나 봤는지 함께 보여 드려요", icon = EyeIcons.Clock, onClick = actions.usageAccess)
                if (!state.notificationsOn) ListRow("휴식 알림 켜기", Modifier.padding(horizontal = 12.dp),
                    subtitle = "쉬어야 할 때 알려 드려요", icon = EyeIcons.Bell, onClick = actions.notifications)
            }
        }
        Text("EYE·休는 눈 사용 습관을 돕는 앱이며, 의료 진단을 대신하지 않아요.".keepAll(),
            style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

@Composable private fun HeroCondition(state: HomeState, actions: HomeActions) {
    val c = Eye.colors
    val report = state.latest
    val view = report?.conditionView()
    EyeCard(brush = Brush.verticalGradient(listOf(c.heroTop, c.heroBottom)), padding = 22.dp,
        onClick = report?.let { { actions.openReport(it.id) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지금 내 눈 컨디션", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            if (view != null) LevelChip(view.band, text = if (view.partial) "측정 부족" else view.band.label)
        }
        if (report == null || view == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(c.brandSoft), contentAlignment = Alignment.Center) {
                    Icon(EyeIcons.Leaf, null, Modifier.size(40.dp), tint = c.brand)
                }
                Text("첫 눈 케어를 시작해 보세요".keepAll(), style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("평소처럼 폰을 쓰는 동안 화면·거리·밝기·깜빡임을 살펴 눈 컨디션을 100점 만점으로 알려 드려요.".keepAll(),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            return@EyeCard
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConditionRing(view.value, view.band)
            Text(when {
                view.value == null -> "측정 정보가 부족해 점수를 계산하지 못했어요."
                view.partial -> "일부만 측정됐어요. 얼굴과 화면이 더 오래 보이면 정확해져요."
                else -> EyeCondition.sentence(view.value)
            }.keepAll(), style = MaterialTheme.typography.bodyLarge, color = c.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(listOfNotNull(Fmt.dateTime(report.createdAt),
                state.previousDeltaBurden?.let { EyeCondition.deltaSentence(it) }).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
        }
        LevelScale(if (view.partial) null else view.value)
    }
}

@Composable private fun DistanceCoach(advice: DistanceGuide.Advice) {
    val c = Eye.colors
    val band = if (advice.level == DistanceGuide.Level.TOO_CLOSE) EyeCondition.Band.RISK else EyeCondition.Band.CAUTION
    EyeCard(color = c.levelSoft.getValue(band)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(EyeIcons.Distance, null, Modifier.size(28.dp), tint = c.levelText.getValue(band))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${advice.headline} · ${advice.moveCm}cm 더 멀리", style = MaterialTheme.typography.titleMedium,
                    color = c.levelText.getValue(band))
                Text(advice.detail.keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
    }
}

/** One-tap "how do your eyes feel" (0 편안 … 10 매우 피곤). */
@Composable fun FeedbackCard(reportId: String, saved: Int?, onSave: (Int) -> Unit) {
    val c = Eye.colors
    EyeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("지금 눈은 어떤가요?", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            saved?.let { Text("내 응답 $it", style = MaterialTheme.typography.labelMedium, color = c.brand) }
        }
        Text("남겨 주시면 내 느낌에 더 잘 맞게 알려 드려요.".keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        NrsPicker(saved, onSave)
    }
}

@Composable fun NrsPicker(saved: Int?, onSave: (Int) -> Unit) {
    val c = Eye.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0..5, 6..10).forEach { values ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { value ->
                    val on = saved == value
                    val tint = c.levelFill.getValue(EyeCondition.band(100 - value * 10))
                    Box(Modifier.weight(1f).height(48.dp).clip(Eye.chipShape)
                        .background(if (on) tint else c.cardMuted)
                        .semanticsTap("눈 피로 ${value}점") { onSave(value) },
                        contentAlignment = Alignment.Center) {
                        Text("$value", style = MaterialTheme.typography.titleSmall,
                            color = if (on) OnLevelFill else c.ink)
                    }
                }
                if (values.last == 10) Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0 편안해요", style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.weight(1f))
            Text("10 매우 피곤해요", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
        }
    }
}
