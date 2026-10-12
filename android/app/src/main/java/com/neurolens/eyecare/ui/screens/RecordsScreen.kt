package com.neurolens.eyecare.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.neurolens.eyecare.data.CareReport
import com.neurolens.eyecare.data.DistanceGuide
import com.neurolens.eyecare.data.PersonalTrend
import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.engine.StrainModel
import kotlin.math.roundToInt
import com.neurolens.eyecare.ui.components.*
import com.neurolens.eyecare.ui.theme.Eye

data class RecordsState(val reports: List<CareReport>, val restShown: Int, val restTaken: Int)

@Composable fun RecordsScreen(state: RecordsState, open: (String) -> Unit, startCare: () -> Unit,
    devContent: (@Composable () -> Unit)? = null) {
    val c = Eye.colors
    var showAll by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("기록", style = MaterialTheme.typography.headlineMedium, color = c.ink)
        if (state.reports.isEmpty()) {
            EyeCard {
                Text("아직 기록이 없어요", style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text("눈 케어를 시작하면 설정한 주기마다 리포트가 쌓여요.".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                PrimaryButton("눈 케어 시작", icon = EyeIcons.Play, onClick = startCare)
            }
            devContent?.invoke()
        } else {
        TrendCard(state.reports)
        DistanceTrendCard(state.reports)
        if (state.restShown > 0) EyeCard(color = c.cardMuted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(EyeIcons.Leaf, null, Modifier.size(26.dp), tint = c.brand)
                Column(Modifier.weight(1f)) {
                    Text("휴식 알림 ${state.restShown}번 · 실제로 쉰 횟수 ${state.restTaken}번",
                        style = MaterialTheme.typography.titleSmall, color = c.ink)
                    Text("알림이 오면 20초만 먼 곳을 바라봐 주세요.".keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                }
            }
        }
        SectionTitle("리포트")
        val grouped = state.reports.sortedByDescending { it.createdAt }
            .take(if (showAll) 200 else 12).groupBy { Fmt.localDate(it.createdAt) }
        grouped.forEach { (day, items) ->
            Text(Fmt.dayHeader(day), style = MaterialTheme.typography.labelMedium, color = c.inkMuted,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            EyeCard(padding = 6.dp) {
                items.forEachIndexed { i, report ->
                    if (i > 0) Hairline(Modifier.padding(horizontal = 14.dp))
                    ReportRow(report) { open(report.id) }
                }
            }
        }
        if (state.reports.size > 12) QuietButton(if (showAll) "접기" else "전체 ${state.reports.size}개 보기") { showAll = !showAll }
        devContent?.invoke()
        }
    }
}

@Composable private fun ReportRow(report: CareReport, onClick: () -> Unit) {
    val c = Eye.colors
    val view = report.conditionView()
    val minutes = report.signal("U01.continuousMin", .5)
    val cm = report.signal("D01.distanceEstimateCm", .2)
    val light = Fmt.light(report.signal("E01.lux"))
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(Eye.tileShape).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.levelSoft.getValue(view.band)), contentAlignment = Alignment.Center) {
            Text(view.value?.toString() ?: "–", style = MaterialTheme.typography.titleMedium, color = c.levelText.getValue(view.band))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${Fmt.time(report.createdAt)}  ${if (view.partial) "측정 부족" else view.band.label}",
                style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(listOfNotNull(minutes?.let { "사용 ${it.toInt()}분" }, cm?.let { "거리 ${it.toInt()}cm" }, light?.first)
                .joinToString(" · ").ifEmpty { "측정 요약 없음" }, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Icon(EyeIcons.Chevron, null, Modifier.size(18.dp), tint = c.inkMuted)
    }
}

/** Condition over the last reports of the newest scoring version, with the five bands behind the line. */
@Composable private fun TrendCard(reports: List<CareReport>) {
    val c = Eye.colors
    val newest = reports.maxByOrNull { it.createdAt }?.version
    val points = reports.filter { it.version == newest && PersonalTrend.comparable(it) }
        .sortedByDescending { it.createdAt }.take(10).reversed()
    EyeCard {
        Text("눈 컨디션 흐름", style = MaterialTheme.typography.titleLarge, color = c.ink)
        if (points.size < 2) {
            Text("충분히 측정된 리포트가 2개 이상 쌓이면 흐름을 보여 드려요.".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@EyeCard
        }
        val values = points.map { EyeCondition.fromBurden(it.score) }
        Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {
            contentDescription = "최근 ${values.size}개 리포트의 눈 컨디션: ${values.joinToString()}"
        }) {
            val h = size.height; val w = size.width
            EyeCondition.SCALE.forEach { band ->
                val r = EyeCondition.range(band)!!
                val top = h * (1f - (r.last + if (band == EyeCondition.Band.EXCELLENT) 0 else 1) / 100f)
                val bottom = h * (1f - r.first / 100f)
                drawRect(c.levelSoft.getValue(band), Offset(0f, top), Size(w, bottom - top))
            }
            fun x(i: Int) = if (values.size == 1) w / 2 else 12.dp.toPx() + (w - 24.dp.toPx()) * i / (values.size - 1)
            fun y(v: Int) = h * (1f - v / 100f)
            for (i in 1 until values.size) drawLine(c.ink.copy(alpha = .55f), Offset(x(i - 1), y(values[i - 1])),
                Offset(x(i), y(values[i])), 2.5.dp.toPx())
            values.forEachIndexed { i, v ->
                drawCircle(c.card, 6.dp.toPx(), Offset(x(i), y(v)))
                drawCircle(c.levelFill.getValue(EyeCondition.band(v)), 4.5.dp.toPx(), Offset(x(i), y(v)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Fmt.dateTime(points.first().createdAt), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.weight(1f))
            Text("최근 ${values.last()}점", style = MaterialTheme.typography.labelSmall, color = c.ink)
        }
    }
}

@Composable private fun DistanceTrendCard(reports: List<CareReport>) {
    val c = Eye.colors
    val points = reports.sortedByDescending { it.createdAt }.take(10).reversed()
        .map { it.signal("D01.distanceEstimateCm", .2)?.takeIf { cm -> cm in 15.0..80.0 } }
    if (points.count { it != null } < 2) return
    EyeCard {
        Text("보는 거리 흐름", style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text("점선 ${DistanceGuide.TARGET_CM}cm보다 아래면 조금 가깝게 본 거예요.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "리포트별 보는 거리: ${points.filterNotNull().joinToString { "${it.toInt()}cm" }}"
        }) {
            val h = size.height; val w = size.width
            val seen = points.filterNotNull()
            val top = maxOf(seen.max(), DistanceGuide.TARGET_CM + 5.0) + 3
            val bottom = minOf(seen.min(), DistanceGuide.TARGET_CM - 10.0) - 3
            fun y(cm: Double) = ((top - cm) / (top - bottom) * h).toFloat().coerceIn(0f, h)
            fun x(i: Int) = if (points.size == 1) w / 2 else 10.dp.toPx() + (w - 20.dp.toPx()) * i / (points.size - 1)
            drawLine(c.inkMuted.copy(alpha = .6f), Offset(0f, y(DistanceGuide.TARGET_CM.toDouble())),
                Offset(w, y(DistanceGuide.TARGET_CM.toDouble())), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            for (i in 1 until points.size) { val a = points[i - 1]; val b = points[i]
                if (a != null && b != null) drawLine(c.brand, Offset(x(i - 1), y(a)), Offset(x(i), y(b)), 2.5.dp.toPx()) }
            points.forEachIndexed { i, cm -> if (cm != null) drawCircle(
                if (cm < DistanceGuide.TARGET_CM) c.levelFill.getValue(EyeCondition.Band.CAUTION) else c.brand,
                5.dp.toPx(), Offset(x(i), y(cm))) }
        }
    }
}

/** Full-screen report detail. [devContent] is shown only in developer mode. */
@Composable fun ReportDetailScreen(report: CareReport, previous: CareReport?, savedNrs: Int?,
    personalReference: Int?, back: () -> Unit, answer: (Int) -> Unit, devContent: (@Composable () -> Unit)? = null) {
    val c = Eye.colors
    val view = report.conditionView()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(Eye.pillShape)
            .clickable(role = Role.Button, onClick = back).padding(end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Icon(EyeIcons.Back, "뒤로", Modifier.size(24.dp), tint = c.ink)
            Text("기록", style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
        EyeCard(brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.heroTop, c.heroBottom))) {
            Text(Fmt.dateTime(report.createdAt), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConditionRing(view.value, view.band)
                LevelChip(view.band, text = if (view.partial) "측정 부족" else view.band.label)
                Text(when {
                    view.value == null -> "측정 정보가 부족해 점수를 계산하지 못했어요."
                    view.partial -> "일부 항목만 측정돼 참고용 점수예요."
                    else -> EyeCondition.sentence(view.value)
                }.keepAll(), style = MaterialTheme.typography.bodyLarge, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (previous != null && !view.partial) Text("지난 리포트보다 ${EyeCondition.deltaSentence(report.score - previous.score)}",
                    style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                personalReference?.let { Text("내 느낌 기준으로 보면 ${it}점이에요", style = MaterialTheme.typography.labelMedium, color = c.brand) }
            }
            LevelScale(if (view.partial) null else view.value)
        }
        val missing = DOMAIN_WORDS.filterKeys { it !in report.mvpDomains }.values
        if (missing.isNotEmpty() && report.scoreAvailable) EyeCard(color = c.cardMuted) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(EyeIcons.Info, null, Modifier.size(22.dp), tint = c.inkMuted)
                Text("이번에 측정되지 않은 항목: ${missing.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        FactorsCard(report)
        SummaryTiles(report)
        FeedbackCard(report.id, savedNrs, answer)
        devContent?.invoke()
    }
}

/** Deduction-model reports: which factors took how many points today, largest first. */
@Composable internal fun DeductionCard(report: CareReport, title: String = "오늘 감점된 이유") {
    val c = Eye.colors
    val rows = report.contributions.mapNotNull { (id, points) ->
        StrainModel.Factor.of(id)?.let { it to points } }.filter { it.second >= .5 }.sortedByDescending { it.second }
    EyeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text("총 −${rows.sumOf { it.second }.roundToInt()}점", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        if (rows.isEmpty()) {
            Text("아직 감점된 항목이 없어요. 지금처럼 보면 좋아요.".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            return@EyeCard
        }
        val top = rows.first().second
        rows.forEach { (factor, points) ->
            val band = when { points >= 10 -> EyeCondition.Band.CAUTION; points >= 4 -> EyeCondition.Band.FAIR; else -> EyeCondition.Band.GOOD }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text(factor.label, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    Text("−${points.roundToInt()}점", style = MaterialTheme.typography.labelMedium, color = c.levelText.getValue(band)) }
                ProgressLine((points / top).toFloat(), c.levelFill.getValue(band))
            }
        }
        rows.firstOrNull { it.first != StrainModel.Factor.BASE }?.let { (factor, _) ->
            Text("· ${factor.tip}".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
    }
}

@Composable private fun FactorsCard(report: CareReport) {
    if (StrainModel.isDeduction(report.version)) { DeductionCard(report, "감점된 이유"); return }
    val c = Eye.colors
    val rows = DOMAIN_WORDS.mapNotNull { (id, label) -> report.mvpDomains[id]?.let { Triple(id, label, it) } }
    if (rows.isEmpty()) return
    EyeCard {
        Text("컨디션에 영향을 준 요인".keepAll(), style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text("막대가 길수록 눈에 부담이 컸던 항목이에요.".keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        rows.sortedByDescending { it.third }.forEach { (_, label, risk) ->
            val band = when { risk >= 60 -> EyeCondition.Band.CAUTION; risk >= 30 -> EyeCondition.Band.FAIR; else -> EyeCondition.Band.GOOD }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text(label, style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    Text(when { risk >= 60 -> "높음"; risk >= 30 -> "보통"; else -> "낮음" },
                        style = MaterialTheme.typography.labelMedium, color = c.levelText.getValue(band)) }
                ProgressLine(risk / 100f, c.levelFill.getValue(band))
            }
        }
    }
}

@Composable private fun SummaryTiles(report: CareReport) {
    val c = Eye.colors
    val cm = report.signal("D01.distanceEstimateCm", .2)
    val advice = DistanceGuide.advise(cm)
    val lux = report.signal("E01.lux")
    val blink = report.signal("F02.blinksPerMin", .35)
    val minutes = report.signal("U01.continuousMin", .5)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(EyeIcons.Clock, "연속 사용", minutes?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f), unit = minutes?.let { "분" })
        StatTile(EyeIcons.Distance, "보는 거리", cm?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f), unit = cm?.let { "cm" },
            caption = when (advice.level) { DistanceGuide.Level.GOOD -> "좋은 거리"; DistanceGuide.Level.UNKNOWN -> null
                else -> "${advice.moveCm}cm 더 멀리" })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(if ((lux ?: 999.0) < 50) EyeIcons.Moon else EyeIcons.Sun, "주변 밝기", Fmt.light(lux)?.first ?: "–", Modifier.weight(1f))
        StatTile(EyeIcons.Blink, "눈 깜빡임", blink?.let { "${it.toInt()}" } ?: "–", Modifier.weight(1f), unit = blink?.let { "회/분" })
    }
    val tips = listOfNotNull(advice.detail.takeIf { advice.level == DistanceGuide.Level.CLOSE || advice.level == DistanceGuide.Level.TOO_CLOSE },
        Fmt.light(lux)?.second?.takeIf { (lux ?: 999.0) < 50 },
        blink?.takeIf { it < 8 }?.let { "깜빡임이 적었어요. 의식적으로 천천히 깜빡여 주세요." })
    if (tips.isNotEmpty()) EyeCard(color = c.brandSoft) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(EyeIcons.Leaf, null, Modifier.size(22.dp), tint = c.brand)
            Text("이렇게 해 보세요", style = MaterialTheme.typography.titleSmall, color = c.ink)
        }
        tips.forEach { Text("· $it".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.ink) }
    }
}
