package com.neurolens.eyecare.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.neurolens.eyecare.data.CareSettings
import com.neurolens.eyecare.engine.ReportScheduler
import com.neurolens.eyecare.ui.components.*
import com.neurolens.eyecare.ui.theme.Eye

data class SettingsState(
    val settings: CareSettings,
    val usageGranted: Boolean,
    val notificationsOn: Boolean,
    val overlayAllowed: Boolean,
    val versionName: String,
    val developerMode: Boolean,
    val canDelete: Boolean,
)

data class SettingsActions(
    val update: (CareSettings) -> Unit,
    val usageAccess: () -> Unit,
    val notifications: () -> Unit,
    val overlayPermission: () -> Unit,
    val exportCsv: () -> Unit,
    val exportJson: () -> Unit,
    val shareData: () -> Unit,
    val showLicenses: () -> Unit,
    val versionTapped: () -> Unit,
    val deleteAll: () -> Unit,
)

@Composable fun SettingsScreen(state: SettingsState, actions: SettingsActions,
    syncContent: @Composable () -> Unit, devContent: (@Composable () -> Unit)? = null) {
    val c = Eye.colors
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, color = c.ink)

        Group("리포트") {
            Text("얼마마다 눈 컨디션을 알려 드릴까요?".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            ChoiceRow(listOf(10, 20, 30, 60, 90, 120).map { it to if (it < 60) "${it}분" else "${it / 60}${if (it % 60 == 0) "시간" else ".5시간"}" },
                s.intervalMin.takeIf { it in listOf(10, 20, 30, 60, 90, 120) } ?: 30,
                { actions.update(s.copy(intervalMin = it)) })
            ChoiceRow(listOf(ReportScheduler.Basis.ACTIVE_SCREEN to "화면을 본 시간 기준", ReportScheduler.Basis.ELAPSED to "흐른 시간 기준"),
                s.basis, { actions.update(s.copy(basis = it)) })
            Text("화면을 5분 이상 끄면 새로 시작해요.".keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }

        Group("휴식 알림") {
            Text("오래 보거나, 어두운 곳에서 가까이 보거나, 자극적인 화면을 볼수록 더 일찍 알려 드려요. 평소엔 20분, 어두운 곳에서 가까이 볼 땐 10분마다예요.".keepAll(),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            ToggleRow("다른 앱 위에 카드로 보여 주기", "끄면 일반 알림으로 알려 드려요",
                s.overlayRestEnabled) { actions.update(s.copy(overlayRestEnabled = it)) }
            if (s.overlayRestEnabled && !state.overlayAllowed) ListRow("다른 앱 위 표시 허용하기",
                subtitle = "허용하지 않으면 일반 알림으로 알려 드려요", icon = EyeIcons.Bell, onClick = actions.overlayPermission)
            Text("점수가 내려간 뒤 이만큼 이어지면 알려 주세요".keepAll(), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            ChoiceRow(listOf(1, 2, 3, 5).map { it to "${it}분" }, s.restAlertMinutes,
                { actions.update(s.copy(restAlertMinutes = it)) })
            Text("같은 알림을 다시 알리기까지", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            ChoiceRow(listOf(10, 20, 30, 60).map { it to "${it}분" }, s.restCooldownMinutes,
                { actions.update(s.copy(restCooldownMinutes = it)) })
            ToggleRow("내 느낌 반영하기", "남겨 주신 피로 응답으로 점수를 내게 맞춰 참고값을 보여 드려요",
                s.personalCalibrationEnabled) { actions.update(s.copy(personalCalibrationEnabled = it)) }
        }

        Group("권한") {
            ListRow("화면 사용 시간", value = if (state.usageGranted) "연결됨" else "연결하기", icon = EyeIcons.Clock, onClick = actions.usageAccess)
            Hairline()
            ListRow("알림", value = if (state.notificationsOn) "켜짐" else "켜기", icon = EyeIcons.Bell, onClick = actions.notifications)
        }

        Group("내 데이터") {
            Text("기록은 이 폰에 저장돼요. 필요하면 파일로 꺼내 보관할 수 있어요.".keepAll(), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("CSV 저장", Modifier.weight(1f), onClick = actions.exportCsv)
                TonalButton("JSON 저장", Modifier.weight(1f), onClick = actions.exportJson)
            }
            ListRow("파일로 공유하기", subtitle = "메일·드라이브 등으로 보내기", icon = EyeIcons.Share, onClick = actions.shareData)
            Hairline()
            syncContent()
        }

        Group("앱 정보") {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = actions.versionTapped)
                .padding(vertical = 10.dp)) {
                Text("버전", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                Text(state.versionName + if (state.developerMode) " · 개발자 모드" else "", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
            Hairline()
            ListRow("오픈소스 라이선스", onClick = actions.showLicenses)
            Text("EYE·休는 의료기기가 아니며 질환을 진단·치료하지 않아요. 눈이 계속 불편하면 안과 전문의와 상담해 주세요.".keepAll(),
                style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }

        QuietButton("모든 기록 지우기", color = c.levelText.getValue(com.neurolens.eyecare.engine.EyeCondition.Band.RISK),
            enabled = state.canDelete, onClick = actions.deleteAll)
        if (!state.canDelete) Text("케어를 종료한 뒤 지울 수 있어요.".keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        devContent?.invoke()
    }
}

@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Eye.colors.inkMuted, modifier = Modifier.padding(start = 6.dp))
        EyeCard(content = content)
    }
}

@Composable private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Eye.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(subtitle.keepAll(), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = c.brand, checkedThumbColor = c.onBrand))
    }
}
