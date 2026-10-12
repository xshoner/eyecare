package com.neurolens.eyecare

import android.Manifest
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.neurolens.eyecare.capture.ProjectionService
import com.neurolens.eyecare.data.*
import com.neurolens.eyecare.engine.ExposureBin
import com.neurolens.eyecare.engine.ReportScheduler
import com.neurolens.eyecare.engine.MvpScorer
import com.neurolens.eyecare.engine.EyeCondition
import com.neurolens.eyecare.ui.components.*
import com.neurolens.eyecare.ui.screens.*
import com.neurolens.eyecare.ui.theme.Eye
import com.neurolens.eyecare.ui.theme.EyeTheme
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.content.ClipData
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val Ink = Color(0xFF173330)
private val Green = Color(0xFF0B695C)
private val Mist = Color(0xFFE2F3ED)
private val Paper = Color(0xFFF4F7F6)
private val Muted = Color(0xFF536B65)
private val Peach = Color(0xFFFFD8B9)
private val Warm = Color(0xFFAC631F)
private val Alert = Color(0xFFAA4938)

class MainActivity : ComponentActivity() {
    private val researchUploads by lazy { ResearchUploadManager.get(this) }
    private var researchNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var resumeTick by mutableIntStateOf(0)
    private var restUntil by mutableLongStateOf(0L)
    private var restRequestedId by mutableStateOf<String?>(null)
    private var restSessionId by mutableStateOf<String?>(null)
    private var launching by mutableStateOf(false)
    private var selectedContent by mutableStateOf("unspecified")
    private var beforeScore by mutableIntStateOf(-1)
    private var careReportId by mutableStateOf<String?>(null)
    private var pendingExportName: String? = null
    private var pendingEyeExportName: String? = null
    private var pendingResearchCsvName: String? = null
    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val name = pendingExportName
        if (uri != null && name != null) lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val source = File(File(cacheDir, "reports"), name)
                    require(source.exists()) { "보고서를 다시 내보내 주세요." }
                    contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("파일을 열 수 없어요.")
                }
            }.onSuccess { Toast.makeText(this@MainActivity, "파일을 저장했어요", Toast.LENGTH_LONG).show() }
                .onFailure { EyeCareRepository.error.value = "저장하지 못했어요. 다시 내보내 주세요." }
        }
    }
    private val saveEyeData = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val name = pendingEyeExportName
        if (uri != null && name != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val source = File(File(cacheDir, "reports"), name)
                require(source.exists())
                contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("파일을 열 수 없어요.")
            } }.onSuccess { Toast.makeText(this@MainActivity, "눈 원자료를 저장했어요", Toast.LENGTH_LONG).show() }
                .onFailure { EyeCareRepository.error.value = "눈 원자료를 저장하지 못했어요." }
        }
    }
    private val saveResearchCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val name = pendingResearchCsvName
        if (uri != null && name != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val source = File(File(cacheDir, "reports"), name)
                require(source.exists())
                contentResolver.openOutputStream(uri, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("파일을 열 수 없어요.")
            } }.onSuccess { Toast.makeText(this@MainActivity, "CSV 파일을 저장했어요", Toast.LENGTH_LONG).show() }
                .onFailure { EyeCareRepository.error.value = "CSV를 저장하지 못했어요. 다시 내보내 주세요." }
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askCapture() }
    private val motionPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCameraPermissionFlow() }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        launching = false
        if (result.resultCode == RESULT_OK && result.data != null) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java)
                    .setAction(ProjectionService.START).putExtra("code", result.resultCode).putExtra("data", result.data)
                    .putExtra("content", selectedContent).putExtra("before", beforeScore))
            }.onFailure { EyeCareRepository.error.value = "분석을 시작하지 못했어요. 앱을 열고 다시 시도해 주세요." }
        } else recordUnavailableAttempt("screen_capture_not_authorized")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EyeCareRepository.init(this)
        researchUploads
        ResearchSyncScheduler.schedulePeriodic(this)
        ResearchSyncScheduler.schedule(this)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        researchNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ResearchSyncScheduler.schedule(this@MainActivity)
                lifecycleScope.launch(Dispatchers.IO) { researchUploads.retryBrowserGrant() }
            }
        }.also { callback -> runCatching { connectivity.registerDefaultNetworkCallback(callback) } }
        restUntil = savedInstanceState?.getLong("restUntil") ?: 0L
        restRequestedId = savedInstanceState?.getString("restRequestedId")
        restSessionId = savedInstanceState?.getString("restSessionId")
        selectedContent = savedInstanceState?.getString("selectedContent") ?: "unspecified"
        beforeScore = savedInstanceState?.getInt("beforeScore", -1) ?: -1
        pendingExportName = savedInstanceState?.getString("pendingExportName")
        pendingResearchCsvName = savedInstanceState?.getString("pendingResearchCsvName")
        pendingEyeExportName = savedInstanceState?.getString("pendingEyeExportName")
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            EyeTheme { EyeCareApp() }
        }
    }
    override fun onResume() {
        super.onResume(); resumeTick++
        ResearchSyncScheduler.schedule(this)
        lifecycleScope.launch(Dispatchers.IO) { researchUploads.retryBrowserGrant() }
    }
    override fun onDestroy() {
        researchNetworkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("restRequestedId", restRequestedId); outState.putString("restSessionId", restSessionId)
        outState.putLong("restUntil", restUntil); outState.putString("selectedContent", selectedContent)
        outState.putInt("beforeScore", beforeScore); outState.putString("pendingExportName", pendingExportName)
        outState.putString("pendingResearchCsvName", pendingResearchCsvName)
        outState.putString("pendingEyeExportName", pendingEyeExportName)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("rest", false) == true) { startRest(); intent.removeExtra("rest") }
        intent?.getStringExtra("beginRestSessionId")?.let { beginRest(it); intent.removeExtra("beginRestSessionId") }
        intent?.getStringExtra("careReportId")?.let { careReportId = it; intent.removeExtra("careReportId") }
    }
    private fun action(value: String) {
        if (EyeCareRepository.live.value != null) startService(Intent(this, ProjectionService::class.java).setAction(value))
    }
    private fun startRest() { restRequestedId = EyeCareRepository.live.value?.record?.id; action(ProjectionService.REST) }
    private fun beginRest(id: String) {
        if (restSessionId == id && restUntil > SystemClock.elapsedRealtime()) return
        EyeCareRepository.beginRest(id); restRequestedId = null; restSessionId = id
        restUntil = SystemClock.elapsedRealtime() + 20_000
    }
    private fun askCapture() {
        launching = true
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            capture.launch(intent) // Android still asks for consent on every projection session.
        }
            .onFailure { launching = false; recordUnavailableAttempt("capture_launch_failed") }
    }
    private fun recordUnavailableAttempt(reason: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val session = SessionRecord(startedAt = now, reason = reason, content = selectedContent,
                    appVersion = "0.8.0-alpha", engineVersion = "exposure-0.2.1+efs-hypothesis-0.7")
                val report = CareReport.unavailable(session.id, now, reason)
                EyeCareRepository.store.save(session)
                EyeCareRepository.store.saveEvent(CareEvent(sessionId = session.id, at = now,
                    type = "capture_unavailable", detail = reason))
                EyeCareRepository.addReport(report)
                EyeCareRepository.refresh()
            }
            EyeCareRepository.error.value = "화면 캡처를 시작하지 못했습니다. 측정 불가 보고서를 확인해 주세요."
        }
    }
    private fun startCameraPermissionFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) askCapture()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    private fun startCare() {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            motionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else startCameraPermissionFlow()
    }
    private fun usageSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure { EyeCareRepository.error.value = "이 기기에서 사용 정보 설정을 열 수 없어요." }
    }
    private fun exportReport(records: List<SessionRecord>, share: Boolean) {
        lifecycleScope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val ids = records.map { it.id }.toSet()
                    ReportExporter.prepare(this@MainActivity, records,
                        EyeCareRepository.store.reports().filter { it.sessionId in ids },
                        EyeCareRepository.store.events(ids))
                }
                if (share) startActivity(Intent.createChooser(ReportExporter.shareIntent(this@MainActivity, file), "분석 보고서 공유"))
                else { pendingExportName = file.name; saveReport.launch(file.name) }
            }.onFailure { EyeCareRepository.error.value = "보고서를 내보내지 못했어요. 다시 시도해 주세요." }
        }
    }
    /** [mode]: "csv" or "json" opens a save dialog; "share" sends both files. */
    private fun exportResearchData(mode: String) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                ResearchDataExporter.prepare(this@MainActivity, EyeCareRepository.store,
                    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown")
            } }.onSuccess { (csv, json) ->
                runCatching {
                    when (mode) {
                        "csv" -> { pendingResearchCsvName = csv.name; saveResearchCsv.launch(csv.name) }
                        "json" -> { pendingExportName = json.name; saveReport.launch(json.name) }
                        else -> {
                            val uris = arrayListOf(csv, json).map {
                                FileProvider.getUriForFile(this@MainActivity, "$packageName.reports", it) }
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                clipData = ClipData.newRawUri("EYE·休 data", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "내 기록 공유"))
                        }
                    }
                }.onFailure { EyeCareRepository.error.value = "저장·공유 화면을 열 수 없습니다. 다시 시도해 주세요." }
            }.onFailure { EyeCareRepository.error.value = it.message ?: "기록을 내보내지 못했어요." }
        }
    }
    private fun exportEyeData(sessionId: String) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                EyeDenseExporter.prepare(this@MainActivity, EyeCareRepository.store, sessionId)
            } }.onSuccess {
                pendingEyeExportName = it.name
                runCatching { saveEyeData.launch(it.name) }
                    .onFailure { EyeCareRepository.error.value = "파일 저장 화면을 열 수 없습니다. 다시 시도해 주세요." }
            }
                .onFailure { EyeCareRepository.error.value = it.message ?: "눈 원자료를 내보내지 못했어요." }
        }
    }
    private fun exportResearchAggregate(report: CareReport) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val file = ResearchTransfer.prepare(this@MainActivity, report, researchUploads.devicePseudonym())
                file
            } }.onSuccess { file ->
                runCatching { startActivity(Intent.createChooser(
                    ResearchTransfer.shareIntent(this@MainActivity, file), "로컬 연구 집계 JSON 공유")) }
                    .onFailure { EyeCareRepository.error.value =
                        "로컬 연구 집계 파일의 공유 화면을 열 수 없습니다." }
            }.onFailure { EyeCareRepository.error.value = "연구용 집계를 준비하지 못했습니다. 다시 시도해 주세요." }
        }
    }
    private fun notificationSettings() {
        val choices = getSharedPreferences("choices", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            && !choices.getBoolean("notificationAsked", false)) {
            choices.edit().putBoolean("notificationAsked", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun overlaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"))) }
            .onFailure { EyeCareRepository.error.value = "다른 앱 위 표시 설정을 열지 못했습니다." }
    }

    @Composable private fun EyeCareApp() {
        val live by EyeCareRepository.live.collectAsStateWithLifecycle()
        val history by EyeCareRepository.history.collectAsStateWithLifecycle()
        val careReports by EyeCareRepository.reports.collectAsStateWithLifecycle()
        val subjectiveResponses by EyeCareRepository.subjectiveResponses.collectAsStateWithLifecycle()
        val researchState by researchUploads.status.collectAsStateWithLifecycle()
        val browserState by researchUploads.browserStatus.collectAsStateWithLifecycle()
        val error by EyeCareRepository.error.collectAsStateWithLifecycle()
        val c = Eye.colors
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var deleteDialog by remember { mutableStateOf(false) }
        var withdrawResearchDialog by remember { mutableStateOf(false) }
        var licenseDialog by remember { mutableStateOf(false) }
        var browserIdInput by remember { mutableStateOf("") }
        var browserCandidate by remember { mutableStateOf<String?>(null) }
        var browserRevokeDialog by remember { mutableStateOf(false) }
        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
        var activeId by rememberSaveable { mutableStateOf<String?>(null) }
        var exportIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
        var showSessions by rememberSaveable { mutableStateOf(false) }
        var syncExpanded by rememberSaveable { mutableStateOf(false) }
        var usage by remember { mutableStateOf(UsageSummary(false)) }
        var notifyEnabled by remember { mutableStateOf(false) }
        var careSettings by remember { mutableStateOf(CareSettingsRepository(this@MainActivity).read()) }
        var restEvents by remember { mutableStateOf<List<CareEvent>>(emptyList()) }
        val installedPackage = remember { packageManager.getPackageInfo(packageName, 0) }
        val choices = remember { getSharedPreferences("choices", MODE_PRIVATE) }
        var developerMode by remember { mutableStateOf(choices.getBoolean("developer_mode", false)) }
        var versionTaps by remember { mutableIntStateOf(0) }
        var updateChecking by remember { mutableStateOf(false) }
        var updateMessage by remember { mutableStateOf("확인 전") }
        var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
        var restRemaining by remember { mutableLongStateOf(0L) }
        val tick = resumeTick
        LaunchedEffect(tick) {
            while (true) {
                usage = withContext(Dispatchers.IO) { UsageReader.today(this@MainActivity) }
                notifyEnabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                delay(15000)
            }
        }
        // When a care session ends, open its newest report instead of a technical session page.
        LaunchedEffect(live == null, careReports, history) {
            if (live != null) activeId = live!!.record.id
            else activeId?.let { id ->
                val report = careReports.filter { it.sessionId == id }.maxByOrNull { it.createdAt }
                if (report != null) { if (restUntil == 0L) careReportId = report.id; activeId = null }
                else if (history.any { it.id == id }) activeId = null
            }
        }
        LaunchedEffect(tab, history) {
            if (tab == 1) restEvents = withContext(Dispatchers.IO) { EyeCareRepository.store.recentRestEvents() }
        }
        LaunchedEffect(restUntil) {
            while (restUntil != 0L) {
                restRemaining = max(0L, restUntil - SystemClock.elapsedRealtime())
                if (restRemaining == 0L) break
                delay(250)
            }
        }
        fun saveSettings(value: CareSettings) { careSettings = value; CareSettingsRepository(this@MainActivity).save(value) }
        fun latestNrs(report: CareReport): Int? = subjectiveResponses.asSequence().filter { it.reportId == report.id }
            .maxWithOrNull(compareBy<SubjectiveResponse> { it.answeredAt }.thenBy { it.id })?.nrs ?: report.nrs
        val answer: (String, Int) -> Unit = { id, value ->
            runCatching { EyeCareRepository.answerReport(id, value) }
                .onFailure { EyeCareRepository.error.value = "응답을 저장하지 못했어요. 다시 시도해 주세요." }
        }
        val detail = history.find { it.id == detailId }
        val openReport = careReports.find { it.id == careReportId }
        val trend = PersonalTrend.summarize(careReports)
        val now = System.currentTimeMillis()
        val week = remember(careReports) {
            val today = java.time.LocalDate.now()
            (6 downTo 0).map { back ->
                val day = today.minusDays(back.toLong())
                val values = careReports.filter { PersonalTrend.comparable(it) && Fmt.localDate(it.createdAt) == day }
                    .map { EyeCondition.fromBurden(it.score) }
                (if (back == 0) "오늘" else Fmt.weekday(day)) to values.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            }
        }
        val restEffect = remember(restEvents, careReports, subjectiveResponses, careSettings.restCooldownMinutes) {
            RestEffectAnalyzer.summarize(restEvents, careReports, subjectiveResponses, careSettings.restCooldownMinutes)
        }
        val fullScreen = restUntil != 0L || openReport != null || detail != null
        // System back closes an inner page first instead of leaving the app.
        BackHandler(enabled = fullScreen || tab != 0) {
            when {
                restUntil != 0L -> { restUntil = 0L; restSessionId = null }
                openReport != null -> careReportId = null
                detail != null -> detailId = null
                else -> tab = 0
            }
        }
        val pageScroll = rememberScrollState()
        LaunchedEffect(tab, careReportId, detailId, restUntil != 0L, live != null) { pageScroll.scrollTo(0) }
        val view = LocalView.current
        val dark = isSystemInDarkTheme()
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }

        Scaffold(containerColor = c.canvas, bottomBar = {
            if (!fullScreen) EyeNavBar(tab, live != null) { tab = it }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(pageScroll).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(4.dp))
                if (!fullScreen) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(Modifier.weight(1f))
                    if (live != null) LevelChip(EyeCondition.Band.EXCELLENT, text = "케어 중")
                }
                when {
                    restUntil != 0L -> RestScreen(restRemaining, { didRest ->
                        restSessionId?.let { EyeCareRepository.confirmRest(it, didRest) }
                        restUntil = 0L; restSessionId = null
                    }, { restUntil = 0L; restSessionId = null })
                    openReport != null -> {
                        val calibration = shownCalibration(PersonalFatigueCalibration.forReport(openReport, careReports,
                            subjectiveResponses), careSettings.personalCalibrationEnabled)
                        ReportDetailScreen(openReport, PersonalTrend.previous(openReport, careReports), latestNrs(openReport),
                            calibration.personalizedScore?.let { EyeCondition.fromBurden(it) },
                            back = { careReportId = null }, answer = { answer(openReport.id, it) },
                            devContent = if (developerMode) { { DevReportPanel(openReport) { exportResearchAggregate(openReport) } } } else null)
                    }
                    detail != null -> {
                        QuietButton("← 돌아가기") { detailId = null }
                        ResultPage(detail, { EyeCareRepository.report(detail.id, it) }, { exportIds = arrayListOf(detail.id) },
                            eyeExport = { exportEyeData(detail.id) },
                            feedback = { stage, score -> EyeCareRepository.feedback(detail.id, stage, score) },
                            rest = { beginRest(detail.id) })
                    }
                    tab == 0 && live != null -> {
                        val s = live!!
                        val strain = s.strain
                        LiveScreen(LiveState(elapsedMs = s.record.elapsedMs, nextReportMs = s.nextReportMs,
                            conditionBurden = strain?.let { 100 - it.condition } ?: s.efs?.takeIf { it.scoreAvailable }?.score,
                            partial = strain == null && s.efs?.confidence?.startsWith("보통") != true,
                            screenShared = s.screenCaptureActive, distanceCm = s.eye.distanceCm, lux = s.lux,
                            coaching = s.eye.qualityAction, reminderMuted = s.reminderMuted, notificationsOn = notifyEnabled,
                            factors = strain?.factors.orEmpty(), ratePerMin = strain?.ratePerMin ?: 0.0,
                            comboLabel = strain?.comboLabel,
                            forecast = strain?.nextBandMinutes?.let { "이대로면 약 ${it}분 뒤 '${strain.nextBandLabel}' 단계가 돼요." },
                            breakIntervalMin = strain?.breakIntervalMin ?: 20, continuousMin = strain?.continuousMin ?: 0.0),
                            LiveActions(stop = { action(ProjectionService.STOP) }, resumeShare = { askCapture() },
                                muteReminder = { action(ProjectionService.MUTE_REMINDER) },
                                calibrate = { action(ProjectionService.CALIBRATE_DISTANCE) }, rest = { startRest() }),
                            devContent = if (developerMode) { {
                                SectionLabel("DEVELOPER", "측정 진단")
                                ActivePage(s, notifyEnabled, { action(ProjectionService.STOP) }, { askCapture() },
                                    { action(ProjectionService.MUTE_REMINDER) }, { action(ProjectionService.CALIBRATE_DISTANCE) })
                            } } else null)
                    }
                    tab == 0 -> HomeScreen(HomeState(latest = trend.latest, previousDeltaBurden = trend.previousDelta,
                            usage = usage, distanceToday = DailyDistance.summarize(careReports, now),
                            distanceWeek = DailyDistance.recent(careReports, now), week = week,
                            notificationsOn = notifyEnabled, launching = launching,
                            latestNrs = trend.latest?.let { latestNrs(it) }),
                        HomeActions(start = { beforeScore = -1; selectedContent = "unspecified"; startCare() },
                            openReport = { careReportId = it }, openRecords = { tab = 1 },
                            usageAccess = { usageSettings() }, notifications = { notificationSettings() }, answer = answer))
                    tab == 1 -> RecordsScreen(RecordsState(careReports, restEffect.shown, restEffect.confirmed),
                        open = { careReportId = it }, startCare = { tab = 0; startCare() },
                        devContent = if (developerMode) { {
                            SectionLabel("DEVELOPER", "검증·세션")
                            val validation = CalibrationValidator.evaluate(careReports, subjectiveResponses)
                            SmallCard(background = Mist) {
                                Text("개인 보정 순차 검증", fontWeight = FontWeight.SemiBold)
                                Text("산식 ${validation.algorithmVersion ?: "없음"} · 비교 가능 ${validation.sameVersionReports}건 · 적격 응답 ${validation.eligibleLabels}건 · 검증 ${validation.evaluated}쌍", fontSize = 12.sp)
                                if (validation.rawMae != null && validation.personalMae != null)
                                    Text("MAE 원점수 ${"%.1f".format(validation.rawMae)} · 개인 참고 ${"%.1f".format(validation.personalMae)}", fontSize = 12.sp)
                                Text("휴식 표시 ${restEffect.shown} · 닫기 ${restEffect.dismissed} · 미루기 ${restEffect.snoozed} · 선택 ${restEffect.selected} · 확인 ${restEffect.confirmed}/${restEffect.declined} · NRS 짝 ${restEffect.pairedNrs}", fontSize = 12.sp)
                            }
                            TextButton(onClick = { showSessions = !showSessions }) {
                                Text(if (showSessions) "화면 세션 접기" else "화면 세션 ${history.size}개 보기") }
                            if (showSessions) history.forEach { r -> SessionCard(r) { detailId = r.id } }
                        } } else null)
                    else -> SettingsScreen(SettingsState(careSettings, usage.granted, notifyEnabled,
                            Settings.canDrawOverlays(this@MainActivity), installedPackage.versionName ?: "", developerMode, live == null),
                        SettingsActions(update = ::saveSettings, usageAccess = { usageSettings() },
                            notifications = { notificationSettings() }, overlayPermission = { overlaySettings() },
                            exportCsv = { exportResearchData("csv") }, exportJson = { exportResearchData("json") },
                            shareData = { exportResearchData("share") }, showLicenses = { licenseDialog = true },
                            versionTapped = {
                                versionTaps++
                                if (versionTaps >= 7) {
                                    versionTaps = 0; developerMode = !developerMode
                                    choices.edit().putBoolean("developer_mode", developerMode).apply()
                                    Toast.makeText(this@MainActivity, if (developerMode) "개발자 모드를 켰어요" else "개발자 모드를 껐어요", Toast.LENGTH_SHORT).show()
                                }
                            }, deleteAll = { deleteDialog = true }),
                        syncContent = {
                            ListRow("웹 대시보드 · 자동 백업", subtitle = if (researchState.enabled) "켜짐 · ${researchState.message}" else "꺼짐",
                                icon = EyeIcons.Chart, onClick = { syncExpanded = !syncExpanded })
                            if (syncExpanded) SyncPanel(researchState, browserState, browserIdInput, { browserIdInput = it },
                                onCandidate = { browserCandidate = it }, onWithdraw = { withdrawResearchDialog = true },
                                onRevoke = { browserRevokeDialog = true })
                        },
                        devContent = if (developerMode) { {
                            SectionLabel("DEVELOPER", "앱 진단")
                            SmallCard {
                                Text("설치 ${installedPackage.versionName} (${installedPackage.longVersionCode}) · 엔진 ${com.neurolens.eyecare.engine.StrainModel.VERSION}", fontSize = 12.sp)
                                Text(updateMessage, fontSize = 12.sp, color = Muted)
                                TextButton(enabled = !updateChecking, onClick = {
                                    updateChecking = true; availableRelease = null; updateMessage = "확인 중"
                                    lifecycleScope.launch {
                                        val release = withContext(Dispatchers.IO) { runCatching { ReleaseChecker.check() }.getOrNull() }
                                        updateChecking = false
                                        if (release == null) updateMessage = "확인 실패"
                                        else if (ReleaseManifest.isNewer(release, installedPackage.longVersionCode)) {
                                            availableRelease = release; updateMessage = "새 버전 ${release.versionName}"
                                        } else updateMessage = "최신 버전"
                                    }
                                }) { Text("업데이트 확인") }
                                availableRelease?.let { release ->
                                    Text("SHA-256 ${release.apkSha256}", fontSize = 11.sp, color = Muted)
                                    TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl))) } }) { Text("릴리스 페이지 열기") }
                                }
                                Text("동기화 ${researchState.sent}/${researchState.pending}/${researchState.failed} · ${researchState.reasonCode ?: "-"}", fontSize = 12.sp)
                            }
                        } } else null)
                }
                if (restUntil == 0L) PoweredBy()
                Spacer(Modifier.height(20.dp))
            }
        }

        if (exportIds != null) AlertDialog(onDismissRequest = { exportIds = null }, title = { Text("기록 내보내기") },
            text = { Text("${exportIds!!.size}개 기록을 파일로 저장하거나 공유해요.") },
            confirmButton = { TextButton(onClick = { val ids = exportIds!!; exportIds = null; exportReport(history.filter { it.id in ids }, false) }) { Text("파일로 저장") } },
            dismissButton = { TextButton(onClick = { val ids = exportIds!!; exportIds = null; exportReport(history.filter { it.id in ids }, true) }) { Text("공유하기") } })
        if (withdrawResearchDialog) AlertDialog(onDismissRequest = { withdrawResearchDialog = false },
            title = { Text("자동 백업을 끌까요?") },
            text = { Text("앞으로는 측정 요약을 서버로 보내지 않고, 보내지 않은 요약도 지워요. 이미 서버에 저장된 기록은 지워지지 않아요. 다시 켜려면 설정에서 동의해 주세요.") },
            confirmButton = { TextButton(onClick = {
                withdrawResearchDialog = false
                lifecycleScope.launch(Dispatchers.IO) { researchUploads.withdraw(); ResearchSyncScheduler.schedule(this@MainActivity, manual = true) }
            }) { Text("끄기") } },
            dismissButton = { TextButton(onClick = { withdrawResearchDialog = false }) { Text("취소") } })
        browserCandidate?.let { id ->
            AlertDialog(onDismissRequest = { browserCandidate = null }, title = { Text("이 브라우저를 연결할까요?") },
                text = { Text("브라우저 ${id.take(8)}…에서 저장된 기록과 앞으로 보낼 측정 요약을 최대 30일 동안 볼 수 있어요. 브라우저는 기록을 고치거나 지울 수 없고, 설정에서 언제든 해제할 수 있어요.") },
                confirmButton = { TextButton(onClick = { browserCandidate = null
                    lifecycleScope.launch(Dispatchers.IO) { researchUploads.connectBrowser(id) } }) { Text("연결") } },
                dismissButton = { TextButton(onClick = { browserCandidate = null }) { Text("취소") } })
        }
        if (browserRevokeDialog) AlertDialog(onDismissRequest = { browserRevokeDialog = false }, title = { Text("웹 연결 해제") },
            text = { Text("이 브라우저에서 더 이상 기록을 볼 수 없게 해요. 기록과 자동 백업 설정은 그대로예요.") },
            confirmButton = { TextButton(onClick = { browserRevokeDialog = false
                lifecycleScope.launch(Dispatchers.IO) { researchUploads.revokeBrowser(); researchUploads.retryBrowserGrant() } }) { Text("해제") } },
            dismissButton = { TextButton(onClick = { browserRevokeDialog = false }) { Text("취소") } })
        if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("모든 기록을 지울까요?") },
            text = { Text("이 폰에 저장된 측정 기록과 응답이 모두 지워지고 되돌릴 수 없어요.") },
            confirmButton = { TextButton(onClick = { EyeCareRepository.clear(); deleteDialog = false }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("취소") } })
        if (licenseDialog) AlertDialog(onDismissRequest = { licenseDialog = false }, title = { Text("오픈소스 라이선스") },
            text = { Text(remember { resources.openRawResource(R.raw.pretendard_ofl).bufferedReader().use { it.readText() } }.let {
                    "Pretendard 글꼴\n\n$it\n\nMediaPipe Face Landmarker — Apache License 2.0" },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { licenseDialog = false }) { Text("닫기") } })
        if (error != null) AlertDialog(onDismissRequest = { EyeCareRepository.error.value = null }, title = { Text("잠시 확인해 주세요") },
            text = { Text(error!!) }, confirmButton = { TextButton(onClick = { EyeCareRepository.error.value = null }) { Text("확인") } })
    }

    @Composable private fun SyncPanel(researchState: ResearchUploadState, browserState: BrowserGrantState,
        browserIdInput: String, onInput: (String) -> Unit, onCandidate: (String) -> Unit,
        onWithdraw: () -> Unit, onRevoke: () -> Unit) {
        val c = Eye.colors
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("측정 요약 수치만 보내요. 화면·카메라 영상, 화면 속 글자, 앱 이름은 보내지 않아요. 앱을 지우거나 데이터를 지우면 서버의 기록에 다시 접근할 수 없어요.",
                style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            Text("보낸 기록 ${researchState.sent}건 · 대기 ${researchState.pending}건" + (if (researchState.failed > 0) " · 실패 ${researchState.failed}건" else "") +
                (researchState.lastSyncedAtMs?.let { " · 마지막 ${Fmt.dateTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("지금 보내기", Modifier.weight(1f)) { ResearchSyncScheduler.schedule(this@MainActivity, manual = true) }
                if (researchState.enabled) TonalButton("자동 백업 끄기", Modifier.weight(1f), onClick = onWithdraw)
                else TonalButton("자동 백업 켜기", Modifier.weight(1f)) {
                    lifecycleScope.launch(Dispatchers.IO) { researchUploads.setAutoEnabled(true); ResearchSyncScheduler.schedule(this@MainActivity, manual = true) }
                }
            }
            Hairline()
            Text("웹에서 내 기록 보기", style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(browserState.message, style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            when (browserState.mode) {
                "linked" -> {
                    Text("연결된 브라우저 ${browserState.browserId?.take(8) ?: ""}… · ${browserState.expiresAt?.let { runCatching { Fmt.dateTime(Instant.parse(it).toEpochMilli()) }.getOrDefault(it) } ?: ""}까지",
                        style = MaterialTheme.typography.bodySmall, color = c.ink)
                    QuietButton("연결 해제", enabled = !browserState.busy, onClick = onRevoke)
                }
                "revoke_pending" -> QuietButton("해제 다시 시도") { lifecycleScope.launch(Dispatchers.IO) { researchUploads.retryBrowserGrant() } }
                else -> {
                    Text("웹 대시보드에 보이는 QR을 스캔해 연결해요.", style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                    TonalButton("QR 스캔", enabled = !browserState.busy) {
                        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                        GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                            .addOnSuccessListener { barcode ->
                                val candidate = barcode.rawValue?.trim()?.lowercase()
                                val parsed = runCatching { java.util.UUID.fromString(candidate) }.getOrNull()
                                if (parsed != null && parsed.toString() == candidate) onCandidate(candidate)
                                else EyeCareRepository.error.value = "웹 대시보드의 QR을 스캔해 주세요."
                            }
                            .addOnFailureListener { EyeCareRepository.error.value = "QR 스캔을 시작할 수 없어요. 아래에 ID를 직접 입력할 수 있어요." }
                    }
                    OutlinedTextField(value = browserIdInput, onValueChange = onInput, singleLine = true,
                        label = { Text("웹 대시보드의 브라우저 ID") }, modifier = Modifier.fillMaxWidth())
                    QuietButton("입력한 ID로 연결", enabled = browserIdInput.isNotBlank() && !browserState.busy) {
                        val candidate = browserIdInput.trim().lowercase()
                        val parsed = runCatching { java.util.UUID.fromString(candidate) }.getOrNull()
                        if (parsed != null && parsed.toString() == candidate) onCandidate(candidate)
                        else EyeCareRepository.error.value = "ID 형식을 확인해 주세요."
                    }
                }
            }
        }
    }
}

/** Developer-only report internals: the formula breakdown, raw signals and the aggregate export. */
@Composable private fun DevReportPanel(report: CareReport, exportAggregate: () -> Unit) {
    SectionLabel("DEVELOPER", "리포트 원자료")
    SmallCard {
        Text("산식 ${report.version} · 신뢰도 ${report.confidence} · 가용 비중 ${report.availableWeight}% · 저장 점수 ${report.score}", fontSize = 12.sp)
        report.mvpNote?.let { Text(it, fontSize = 11.sp, color = Muted) }
        ReportFactors(report)
        DistanceReportCard(report)
        if (report.version == MvpScorer.VERSION) ObservedContextLines(ObservedContext.fromReport(report), report.scoreAvailable)
        if (report.nominalWeights.isNotEmpty()) FatigueBreakdown(report)
        Text("화면 관측 ${"%.0f".format(report.screenCoverage * 100)}% · 눈 검출 ${report.eyeDetectionRatio?.let { "%.0f%%".format(it * 100) } ?: "결측"} · 카메라 ${"%.1f".format(report.cameraProcessedFps)}fps", fontSize = 12.sp)
        report.mvpSignals.toSortedMap().forEach { (id, signal) ->
            Text("$id: " + (signal.value?.let { "%.3f (q %.2f)".format(it, signal.quality) + if (signal.estimated) " ·추정" else "" }
                ?: "결측 (${signal.reason ?: "?"})"), fontSize = 11.sp, color = Muted)
        }
        if (report.version in setOf(com.neurolens.eyecare.engine.StrainModel.VERSION, com.neurolens.eyecare.engine.StrainModel.PREVIOUS_VERSION, MvpScorer.VERSION, MvpScorer.EXPOSURE_010_VERSION, MvpScorer.PREVIOUS_VERSION))
            TextButton(onClick = exportAggregate) { Text("업로드용 집계 JSON 공유") }
    }
}

/** Bottom navigation: three top-level destinations with icon + label. */
@Composable private fun EyeNavBar(tab: Int, live: Boolean, select: (Int) -> Unit) {
    val c = Eye.colors
    NavigationBar(containerColor = c.card, tonalElevation = 0.dp) {
        listOf(Triple("홈", EyeIcons.Home, 0), Triple("기록", EyeIcons.Chart, 1), Triple("설정", EyeIcons.Settings, 2))
            .forEach { (label, icon, index) ->
                NavigationBarItem(selected = tab == index, onClick = { select(index) },
                    icon = { Icon(icon, null, Modifier.size(24.dp)) },
                    label = { Text(if (index == 0 && live) "케어 중" else label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = c.brand, selectedTextColor = c.brand,
                        indicatorColor = c.brandSoft, unselectedIconColor = c.inkMuted, unselectedTextColor = c.inkMuted))
            }
    }
}

@Composable private fun ObservedContextLines(context: ObservedContextSummary, scoreAvailable: Boolean) {
    Text(if (scoreAvailable) "현재 유효 입력의 부분 추정" else "현재 점수 산출 보류",
        color = Alert, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Text(context.facts, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(context.included, color = Ink, fontSize = 12.sp)
    Text(context.environment, color = Muted, fontSize = 12.sp)
    Text(context.missing, color = Alert, fontSize = 12.sp)
    Text(context.accumulated, color = Muted, fontSize = 12.sp)
}

private fun shownCalibration(calibration: PersonalCalibration, enabled: Boolean) =
    if (enabled) calibration else calibration.copy(personalizedScore = null, offset = null)

@Composable private fun ReportFactors(report: CareReport) {
    val labels = mapOf("screen" to "화면 자극", "environment" to "주변 환경", "posture" to "자세·움직임",
        "usage" to "사용 습관", "eyes" to "눈 반응")
    val top = report.contributions.entries.filter { (id, points) ->
        points.isFinite() && points > 0 && report.mvpDomains[id] != null &&
            (report.domainQualities[id] ?: 0.0) > 0.0 }.sortedByDescending { it.value }.take(2)
    if (top.isEmpty()) Text("영역별 관측 근거가 부족해 주요 요인을 고르지 않았어요.", fontSize = 13.sp, color = Muted)
    else top.forEach { (id, points) ->
        Text("${labels[id] ?: id} · ${if (report.version == MvpScorer.VERSION) "분당 부담률" else "점수"} 기여 ${"%.1f".format(points)}${if (report.version == MvpScorer.VERSION) "%p" else "점"} · 품질 ${"%.0f".format((report.domainQualities[id] ?: 0.0) * 100)}%",
            fontSize = 13.sp, color = Ink)
    }
}

@Composable private fun DistanceReportCard(report: CareReport) {
    val cm = report.mvpSignals["D01.distanceEstimateCm"]?.value
    val quality = report.mvpSignals["D01.distanceEstimateCm"]?.quality ?: 0.0
    val coverage = report.mvpSignals["D04.coverage"]?.value ?: 0.0
    val near = report.mvpSignals["D03.nearRatio"]?.value
    SmallCard(background = Mist) {
        Text("근거리 관측", fontWeight = FontWeight.SemiBold, color = Ink)
        Text(cm?.let { "주로 보는 거리 약 ${"%.0f".format(it)} cm" } ?: "거리 추정 불가",
            fontSize = 17.sp, color = Green)
        Text("관측 시간 비율 ${"%.0f".format(coverage * 100)}%  ·  근거리 관측 ${near?.let { "${"%.0f".format(it * 100)}%" } ?: "자료 없음"}",
            fontSize = 12.sp, color = Muted)
        Text("기하 근사 품질 ${"%.0f".format(quality * 100)}%  ·  ${if (report.mvpSignals["D06.calibrated"]?.value == 1.0) "사용자 보정" else "미보정/측정 불가"}",
            fontSize = 12.sp, color = Muted)
        Text("홍채 지름과 카메라 정보를 이용한 눈–카메라축 거리 추정입니다. 시력이나 안경 도수는 산출하지 않습니다.",
            fontSize = 11.sp, color = Muted)
    }
}

@Composable private fun ActivePage(s: LiveSession, notifications: Boolean, stop: () -> Unit,
    resumeCapture: () -> Unit, muteReminder: () -> Unit, calibrateDistance: () -> Unit) {
    val r = s.record
    var showAdvanced by remember(r.id) { mutableStateOf(false) }
    SmallCard(background = Mist) {
        Text(if (s.screenCaptureActive) "●  측정 중" else "●  화면 공유가 끊겼어요", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(clock(r.elapsedMs), fontSize = 60.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.Light)
        Text("다음 리포트까지 ${duration(s.nextReportMs)}", color = Green, fontSize = 16.sp)
        Text("평소처럼 사용하다가 원할 때 종료하세요. 부족한 지표는 미측정으로 남습니다.", color = Muted, fontSize = 14.sp)
    }
    s.eye.qualityAction?.let { action -> SmallCard(background = Color(0xFFFFF2E5)) {
        Text("측정 품질 확인 · ${s.eye.qualityCode}", color = Alert, fontWeight = FontWeight.SemiBold)
        Text(action, fontSize = 14.sp)
        Text("최근 눈 유효 표본 ${s.eye.recentEyeCoverage?.let { "%.0f%%".format(it * 100) } ?: "미수집"} · 화면 유효 구간 ${"%.0f%%".format(r.stats.validMs * 100.0 / r.elapsedMs.coerceAtLeast(1))}", fontSize = 12.sp)
        Text("비교에 필요한 화면 coverage 기준은 30%입니다. 낮은 품질을 정상 점수로 해석하지 마세요.", fontSize = 12.sp, color = Muted)
    } }
    s.efs?.let { efs -> SmallCard {
        Text("현재 눈 컨디션 ${if (efs.scoreAvailable) EyeCondition.fromBurden(efs.score).toString() + " / 100" else "미측정"}", fontSize = 21.sp, color = Ink)
        if (efs.scoreAvailable) Text("현재 확인된 입력만 반영한 부분 추정입니다. 빠진 입력이 있으면 실제보다 높게 보일 수 있어요.", fontSize = 14.sp, color = Ink)
        s.observedContext?.let { observed ->
            ObservedContextLines(observed, efs.scoreAvailable)
        }
        Text("${efs.confidence} · 측정이 끝나면 결과를 확인해 주세요.", fontSize = 12.sp, color = Muted)
    } }
    if (!s.screenCaptureActive) PrimaryButton("화면 공유 다시 시작", onClick = resumeCapture)
    TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "측정 정보 접기" else "측정 상태 자세히") }
    if (showAdvanced) {
        SmallCard {
            Text("거리 추정 보정", fontWeight = FontWeight.SemiBold, color = Ink)
            Text("자로 눈과 전면 카메라 사이를 30 cm에 맞춘 뒤 화면을 똑바로 보고 보정하세요. 기기·해상도·회전이 바뀌면 다시 보정해야 합니다.",
                fontSize = 12.sp, color = Muted)
            TextButton(onClick = calibrateDistance) { Text("30 cm 기준으로 지금 보정") }
            Text("실측 점검: 자로 눈과 카메라 사이를 20·25·30·40·50·60 cm에 맞춰 각 1분씩 기록하세요. 밝음/어둠, 안경 유무, 고개 정면/좌우 각도를 따로 반복하고 실제값·추정값·결측·지터를 비교하세요. 이 화면의 수치는 기기별 정확도 합격을 뜻하지 않습니다.",
                fontSize = 12.sp, color = Muted)
        }
        SmallCard {
            Text("전면 카메라 · ${cameraLabel(s.eye.cameraState)}", fontWeight = FontWeight.SemiBold)
            Text("카메라 처리 ${s.eye.frames}프레임 · 눈 형태 유효 ${s.eye.eyeValidFrames}프레임", fontSize = 12.sp, color = Muted)
            val age = s.eye.lastFrameElapsedMs?.let { SystemClock.elapsedRealtime() - it }
            Text("최근 표본 ${age?.let { "${it}ms 전" } ?: "없음"} · 전달 ${"%.1f".format(s.eye.deliveredFps)}fps · 간격 ${s.eye.frameGapMs ?: "—"}ms · 처리 ${s.eye.processingMs?.let { "%.1f".format(it) } ?: "—"}ms",
                fontSize = 12.sp, color = Muted)
            Text("거리 ${s.eye.distanceCm?.let { "%.1f cm".format(it) } ?: "미측정"} · 품질 ${"%.0f".format(s.eye.distanceQuality * 100)}% · 최근 1분 coverage ${"%.0f".format(s.eye.distanceCoverage * 100)}% · 지터 ${s.eye.distanceJitterCm?.let { "%.1f cm".format(it) } ?: "—"}",
                fontSize = 12.sp, color = Muted)
            Text("거리 결측 이유: ${s.eye.distanceReason ?: "없음"} · 얼굴 밝기 지표 ${s.eye.faceIllumination?.let { "%.2f".format(it) } ?: "미측정"}",
                fontSize = 12.sp, color = Muted)
            Text("상대 시선 안정성: ${gazeLabel(s.eye.lastGaze?.state ?: "unavailable")}", fontSize = 13.sp)
            Text("열 상태 ${thermalLabel(s.eye.thermalStatus)} · 열 headroom ${s.eye.thermalHeadroom?.let { "%.2f".format(it) } ?: "지원/측정 불가"} (온도 °C가 아닌 위험 여유 지표)",
                fontSize = 12.sp, color = Muted)
            if (s.eye.cameraState == "thermal_paused")
                Text("열이 높아 분석이 일시 중지됐습니다. 기기가 뜨거우면 측정을 종료하고 식혀 주세요. 이 동안 점수는 미측정입니다.",
                    fontSize = 12.sp, color = Alert)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("유효 분석 구간", duration(r.stats.validMs), Modifier.weight(1f))
            MetricCard("변화가 큰 구간", duration(r.stats.highMs), Modifier.weight(1f))
        }
        Text(qualityText(r.stats.quality), fontSize = 13.sp, color = Muted)
        TrendChart(r.stats.bins)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = muteReminder, enabled = notifications && !s.reminderMuted) { Text(if (s.reminderMuted) "이번 알림을 껐어요" else "리포트 알림 끄기") }
        TextButton(onClick = stop) { Text("측정 종료") }
    }
    Text("점수는 화면·환경·자세·사용·눈 영역 중 가용한 지표를 반영하는 잠정 추정치예요.", color = Muted, fontSize = 12.sp)
}

@Composable private fun ResultPage(r: SessionRecord, report: (Int) -> Unit, export: () -> Unit,
    eyeExport: () -> Unit, feedback: (String, Int?) -> Unit, rest: () -> Unit) {
    SectionLabel("SESSION REPORT", "내 화면의 작은 기록")
    Text(date(r.startedAt), color = Muted, fontSize = 13.sp)
    r.diagnostics?.let { SessionDiagnosticCard(it) } ?: Text(
        "이전 버전 기록: 세션 진단 요약 미수집", color = Muted, fontSize = 12.sp)
    if (r.engineVersion == "exposure-0.2.1" && !r.appVersion.startsWith("0.3")) {
        val stage = if (r.restStartedAt != null) "afterRest" else "afterUse"
        val answer = r.feedback[stage]
        SmallCard {
            Text(if (stage == "afterRest") "휴식 시작 이후, 지금 눈은 어떤가요?" else "분석을 마쳤어요. 지금 눈은 어떤가요?", fontWeight = FontWeight.SemiBold)
            Text("0 전혀 불편하지 않음 · 4 많이 불편함. 응답 시각을 함께 저장해요.", fontSize = 12.sp)
            if (stage == "afterUse") Text("종료 후 약 " + duration((System.currentTimeMillis() - r.startedAt - r.elapsedMs).coerceAtLeast(0)) + " 경과 · 지연 응답은 별도 집계해요.", fontSize = 12.sp)
            else Text("타이머와 실제 휴식은 달라요. 일찍 돌아와도 응답 시각이 남아요.", fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (0..4).forEach { score -> FilterChip(selected = answer?.score == score, onClick = { feedback(stage, score) }, label = { Text("$score") }) }
            }
            TextButton(onClick = { feedback(stage, null) }) { Text("응답 건너뛰기") }
            if (answer != null) Text(if (answer.skipped) "건너뛰었어요 · 기록은 저장됐어요" else "응답 저장됨 · " + date(answer.at), fontSize = 12.sp)
            if (stage == "afterUse") OutlinedButton(onClick = { if (answer == null) feedback(stage, null); rest() }) { Text("지금 1분 쉬기") }
        }
    }
    OutlinedButton(onClick = export, modifier = Modifier.fillMaxWidth()) { Text("이 기록 내보내기") }
    if (EyeDenseExporter.supportsSession(r.appVersion)) OutlinedButton(onClick = eyeExport, modifier = Modifier.fillMaxWidth()) {
        Text("눈·시선 원자료 CSV.gz 저장")
    }
    SmallCard(background = Mist) {
        Text("${duration(r.elapsedMs)} 동안 살펴봤어요", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(reasonText(r.reason), color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) { Text("유효 분석", color = Muted, fontSize = 12.sp); Text(duration(r.stats.validMs), fontSize = 27.sp, color = Green) }
            Column(Modifier.weight(1f)) { Text("변화가 큰 구간", color = Muted, fontSize = 12.sp); Text(duration(r.stats.highMs), fontSize = 27.sp, color = Green) }
        }
    }
    TrendChart(r.stats.bins, r.elapsedMs)
    Text("약 2회/초로 화면을 표본 분석해요. 마지막 수신 상태와 세션 전체의 분석 범위는 달라요.", fontSize = 12.sp, color = Muted)
    if (r.elapsedMs > r.stats.validMs) Text("전체 중 " + duration(r.elapsedMs-r.stats.validMs) + "는 분석하지 못했어요. 관측한 구간의 결과이며 실제 응시 비율이 아니에요.", color = Muted)
    var qualityExpanded by remember(r.id) { mutableStateOf(false) }
    TextButton(onClick = { qualityExpanded = !qualityExpanded }) { Text(if (qualityExpanded) "구간별 분석 접기" else "구간별 분석 시간 보기") }
    if (qualityExpanded) {
        (0 until ((r.elapsedMs + 9999) / 10000).toInt()).forEach { i ->
            val b = r.stats.bins.find { it.seconds == i * 10 }
            val length = minOf(10000L, r.elapsedMs-i*10000L)
            Text("${i*10}초: ${"%.2f".format((b?.validMs ?: 0L)/1000.0)} / ${"%.2f".format(length/1000.0)}초 분석", fontSize = 12.sp)
            r.stats.qualityWindows.find { it.seconds == i*10 }?.excludedMs?.forEach { (reason, ms) ->
                Text(exclusionLabel(reason) + ": " + ms + "ms", fontSize = 11.sp, color = Muted)
            }
        }
    }
    Text("전체 시간 중 ${if (r.elapsedMs > 0) (100 * r.stats.validMs / r.elapsedMs).coerceIn(0,100) else 0}%를 분석했어요. 매우 어두운 화면·보호 화면·프레임이 없는 구간은 포함하지 않았어요.", fontSize = 13.sp, color = Muted)
    SmallCard {
        Text("결과를 읽는 방법", fontWeight = FontWeight.SemiBold)
        Text(if (r.stats.validMs == 0L) "분석 가능한 구간이 없어 변화량을 해석할 수 없어요. 공유 범위를 확인하고 다시 사용해 주세요."
            else "분석된 구간 중 ${100 * r.stats.highMs / r.stats.validMs}%에서 큰 밝기 변화가 관찰됐어요. 실제 응시나 눈 피로를 뜻하지 않아요.", fontSize = 14.sp, color = Muted)
        if (r.stats.excludedMs.isEmpty()) Text("이전 버전 기록에는 누락 원인이 없어요.", fontSize = 12.sp, color = Muted)
        r.stats.excludedMs.filterValues { it > 0 }.forEach { (reason, ms) -> Text("${exclusionLabel(reason)} · ${duration(ms)}", fontSize = 13.sp, color = Muted) }
        Text("표본 없음에는 정적인 화면도 포함될 수 있어요. 누락 구간의 변화량은 알 수 없어요.", fontSize = 12.sp, color = Muted)
        Text("콘텐츠: ${contentChoices[r.content] ?: "미선택"} · 시작 전 불편감: ${r.discomfortBefore?.toString() ?: "미응답"}", fontSize = 13.sp)
        if (r.discomfortBefore != null && r.discomfort != null) Text("전후 응답 변화: ${r.discomfort - r.discomfortBefore} (사용자 응답 차이)", fontSize = 13.sp)
    }
    if (r.engineVersion != "exposure-0.2.1") SmallCard {
        Text("이전 방식의 시점 미상 응답", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text("선택한 응답은 이 기록에만 저장돼요.", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..4).forEach { score ->
                FilterChip(selected = r.discomfort == score, onClick = { report(score) }, label = { Text("$score") })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0  전혀 없어요", fontSize = 11.sp, color = Muted); Text("4  많이 불편해요", fontSize = 11.sp, color = Muted)
        }
    }
    Text("화면 변화가 많다는 것만으로 눈의 피로·건강 상태를 판단할 수 없어요.", fontSize = 12.sp, color = Muted)
}

@Composable private fun SessionDiagnosticCard(d: SessionDiagnostics) {
    fun number(value: Double?, unit: String) = value?.let { "%.1f %s".format(it, unit) } ?: "미수집"
    fun percent(value: Double?) = value?.let { "%.0f%%".format(it * 100) } ?: "미수집"
    SmallCard(background = Mist) {
        Text("이 세션의 자동 진단", fontWeight = FontWeight.SemiBold)
        Text("관측 카메라 ${number(d.cameraFps, "fps")} · 화면 처리 ${number(d.screenFps, "fps")}", fontSize = 12.sp)
        Text("카메라 프레임 간격 평균 ${number(d.frameGapMeanMs, "ms")} · 최대 ${d.frameGapMaxMs?.let { "$it ms" } ?: "미수집"}", fontSize = 12.sp)
        Text("카메라 처리 평균 ${number(d.cameraProcessingMeanMs, "ms")} · 최대 ${number(d.cameraProcessingMaxMs, "ms")}", fontSize = 12.sp)
        Text("화면 처리 평균 ${number(d.screenProcessingMeanMs, "ms")} · 최대 ${number(d.screenProcessingMaxMs, "ms")}", fontSize = 12.sp)
        Text("세션 시간 대비 유효 눈 구간 ${percent(d.eyeCoverage)} · 거리 구간 ${percent(d.distanceCoverage)}", fontSize = 12.sp)
        Text("최고 열 상태 ${d.maxThermalStatus?.let(::thermalLabel) ?: "미수집"} · 열로 분석 중지 ${duration(d.thermalPausedMs)}", fontSize = 12.sp)
        Text("종료 시 동기화 대기 ${d.syncPending?.toString() ?: "미수집"}건 · 실패 ${d.syncFailed?.toString() ?: "미수집"}건 · 이유 코드 ${d.syncReasonCode ?: "없음"}", fontSize = 12.sp)
        Text("앱 ${d.appVersion ?: "미수집"} · 엔진 ${d.engineVersion ?: "미수집"}", color = Muted, fontSize = 11.sp)
        Text("FPS와 coverage는 해당 기기에서 관측된 값이며, 원시 영상·화면 내용은 이 요약에 저장하지 않습니다.", color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun SessionCard(r: SessionRecord, open: () -> Unit) {
    SmallCard(modifier = Modifier.clickable(onClick = open)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(date(r.startedAt), fontSize = 13.sp, color = Muted); Text("↗", color = Green, fontSize = 20.sp)
        }
        Text("${duration(r.elapsedMs)}의 화면 기록", fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text("유효 분석 ${duration(r.stats.validMs)} · 변화가 큰 구간 ${duration(r.stats.highMs)}", fontSize = 12.sp, color = Muted)
    }
}

@Composable private fun FatigueBreakdown(report: CareReport) {
    val labels = mapOf("screen" to "화면 자극", "environment" to "주변 환경",
        "posture" to "자세·움직임", "usage" to "사용 습관", "eyes" to "눈 반응")
    val current = report.version == MvpScorer.VERSION
    val examples = if (current) mapOf("screen" to "화면 변화·깜박임 중 가장 강한 관측 단서",
        "environment" to "조도와 상대 밝기 설정의 결합 추정",
        "posture" to "기울기·흔들림 중 강한 단서",
        "usage" to "활성 관측 시간 + 유효한 근거리 추정(같은 18% 안)",
        "eyes" to "깜빡임과 눈 개방의 서로 다른 단서") else mapOf(
        "screen" to "화면 변화·깜박임·색 비율·발광 추정",
        "environment" to "주변 밝기 대비·시간·열 상태",
        "posture" to "기울기·움직임·머리 움직임",
        "usage" to "연속 사용·앱 전환·잠금 해제·짧은 휴식",
        "eyes" to "깜빡임·눈 뜸·긴 감김·찡그림")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("100점 기준 지표별 명목 비중 · 부담률이 높을수록 눈 컨디션 점수를 낮춥니다", fontWeight = FontWeight.SemiBold, color = Ink)
        report.nominalWeights.forEach { (domain, weight) ->
            val measured = report.mvpDomains[domain]
            val contribution = report.contributions[domain]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${labels[domain] ?: domain} $weight%", fontSize = 13.sp)
                Text(if (measured == null) "미측정" else
                    "부담률 $measured/100 · 품질 ${"%.0f".format((report.domainQualities[domain] ?: 0.0) * 100)}% " +
                        "· ${if (current) "분당" else "점수"} 기여 ${"%.1f".format(contribution ?: 0.0)}${if (current) "%p" else "점"}", fontSize = 12.sp, color = Muted)
            }
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Mist)) {
                if (measured != null) Box(Modifier.fillMaxWidth((measured / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight().background(if (measured >= 75) Color(0xFFBE6857) else Green))
            }
            Text(examples[domain].orEmpty(), fontSize = 11.sp, color = Muted)
        }
        if (current) {
            Text("명목 영역 부담률 ${report.baseScore}%p + 어두움×화면변화 ${"%.1f".format(report.interactionRate * 100)}%p (최대 8%p)",
                fontSize = 13.sp, color = Ink)
            Text("앱이 관측한 활성 화면 ${"%.1f".format(report.observedMinutes)}분의 부담률을 한 번 적분 → 오늘 관측 부담 ${report.observedBurden?.let { "$it/100 (눈 컨디션 ${EyeCondition.fromBurden(it)}/100)" } ?: "미수집"}",
                fontSize = 12.sp, color = Ink)
            Text("표시값 = 100×(1−exp(−누적 부담분/40)). 40분 척도와 최대 8%p 교호작용은 검증된 의학 임계값이 아닌 제품 가설입니다. 좋은 관측이 강한 단서를 평균으로 지우지 않으며, 미측정은 0점·회복이 아닙니다.",
                fontSize = 11.sp, color = Muted)
            Text("관측 근거 비중 ${report.availableWeight}% · 품질과 누락은 부담률에서 감산하지 않고 별도로 표시합니다. 하루 표시 기간의 경계는 생리학적 회복을 뜻하지 않습니다.",
                fontSize = 11.sp, color = Muted)
        } else {
            Text("이전 산식 기본 ${report.baseScore}점 + 상황 보정 ${report.adjustments.sumOf { it.points }}점 → 당시 저장 점수 ${report.score}점",
                fontSize = 13.sp, color = Ink)
            Text("이전 산식은 가용 영역을 품질 가중 평균으로 다시 정규화했습니다. 원래 입력·점수·버전은 그대로 보존합니다.",
                fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable private fun TrendChart(bins: List<ExposureBin>, elapsedMs: Long = 0) {
    SmallCard {
        Text("시간에 따른 화면 변화", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (bins.isEmpty()) Text("분석 가능한 표본이 쌓이면 표시해요.", color = Muted, fontSize = 13.sp)
        else {
            val observed = bins.filter { it.validMs > 0 }
            val peak = observed.maxByOrNull { it.change +
                .05 * it.highMs.toDouble() / it.validMs.coerceAtLeast(1) }
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                val count = max(1, max((bins.last().seconds / 10) + 1, ((elapsedMs + 9999) / 10000).toInt()))
                val slot = size.width / count
                val ceiling = maxOf(.08, observed.maxOfOrNull { it.change } ?: .08)
                drawLine(Mist, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                bins.forEach { b ->
                    val height = if (b.validMs <= 0) 0f else
                        ((b.change / ceiling).coerceIn(0.0, 1.0) * (size.height - 12.dp.toPx()))
                            .toFloat().coerceAtLeast(4.dp.toPx())
                    val available = minOf(10000L, (elapsedMs - b.seconds * 1000L).coerceAtLeast(1))
                    val partial = elapsedMs > 0 && b.validMs < available
                    drawRoundRect(if (partial) Muted.copy(alpha = .4f) else if (b.seconds == peak?.seconds)
                        Color(0xFFBE6857) else if (b.highMs > 0) Color(0xFFCC9975) else Green,
                        topLeft = Offset(b.seconds / 10 * slot, size.height - height),
                        size = androidx.compose.ui.geometry.Size((slot * .72f).coerceAtLeast(1f), height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f))
                }
            }
            if (peak != null) Text("가장 변화가 큰 구간: 시작 후 ${peak.seconds / 60}분 ${peak.seconds % 60}초 " +
                "(화면 변화 ${"%.3f".format(peak.change)}, 고변화 ${peak.highMs}ms). 빨간 막대로 표시합니다.",
                fontSize = 12.sp, color = Color(0xFFBE6857))
            Text("고정 축 0~1 · 유효 시간 평균 · 회색은 부분 분석 · 빈 구간은 표본 없음", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 25.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun SmallCard(modifier: Modifier = Modifier, background: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(19.dp)) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}
@Composable private fun SectionLabel(eyebrow: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(eyebrow, color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp)
        Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}
private fun duration(ms: Long): String {
    if (ms in 1..999) return "1초 미만"
    val sec = ms.coerceAtLeast(0) / 1000
    return when { sec >= 3600 -> "${sec / 3600}시간 ${sec % 3600 / 60}분"; sec >= 60 -> "${sec / 60}분 ${sec % 60}초"; else -> "${sec}초" }
}
private fun clock(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M월 d일 · HH:mm"))
private fun sameDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
private fun qualityText(reason: String) = when (reason) {
    "measuring" -> "화면의 디지털 밝기 변화를 분석하고 있어요."
    "dark_or_protected" -> "매우 어둡거나 보호된 화면이에요. 이 구간은 분석에서 제외해요."
    "hidden" -> "공유한 앱이 보이지 않아요. 다시 보이면 분석을 이어가요."
    "waiting_frames" -> "새 화면 표본을 기다려요. 비어 있는 시간은 계산하지 않아요."
    "invalid" -> "화면 표본을 읽지 못했어요. 다음 표본을 기다려요."
    else -> "분석 가능한 화면 표본을 기다리고 있어요."
}
private val contentChoices = linkedMapOf("unspecified" to "미선택", "shorts" to "쇼츠", "video" to "영상", "game" to "게임", "reading" to "읽기", "mixed" to "혼합")
private fun exclusionLabel(reason: String) = when (reason) {
    "baseline_wait" -> "기준 표본 대기"
    "sample_gap" -> "1.5초 초과 표본 간격"
    "end_tail" -> "마지막 표본 이후 종료까지"
    "pending" -> "아직 다음 표본이 없는 시간"
    "continuity_reset" -> "공유 상태 전환 전 미관측"
    "dark_or_protected" -> "어둡거나 보호된 표본"
    "hidden" -> "공유 앱이 보이지 않음"
    "invalid" -> "표본 읽기 실패"
    "resized" -> "크기 변경 후 대기"
    "missing_frames" -> "새 표본 없음"
    else -> "분석 준비·기준 표본 대기"
}
private fun reasonText(reason: String) = when (reason) {
    "break_requested" -> "휴식을 요청하며 분석을 마쳤어요."
    "screen_off" -> "화면이 꺼져 분석을 종료했어요."
    "limit" -> "30분 분석을 마쳤어요."
    "system_stopped" -> "시스템에서 화면 공유가 종료됐어요."
    "thermal" -> "기기 온도가 높아 분석을 종료했어요."
    "interrupted" -> "분석이 중단됐어요. 마지막 저장 시점까지의 기록이에요."
    "capture_error", "start_failed" -> "화면 분석 중 오류가 발생했어요."
    else -> "직접 분석을 종료했어요."
}
private fun gazeLabel(state: String) = when (state) {
    "stable_relative" -> "상대 위치 안정"
    "moving_relative" -> "상대 위치 변화"
    "insufficient" -> "관측 누적 중"
    else -> "관측 불가"
}
private fun thermalLabel(status: Int?) = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "정상"
    PowerManager.THERMAL_STATUS_LIGHT -> "가벼운 부하"
    PowerManager.THERMAL_STATUS_MODERATE -> "주의 · 화면 분석 완화"
    PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL,
    PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "높음 · 분석 중지"
    else -> "확인 불가"
}

private fun cameraLabel(state: String) = when (state) {
    "thermal_paused" -> "열로 일시 중지"
    "observing" -> "관측 중"
    "opening" -> "연결 중"
    "camera_paused" -> "화면 꺼짐으로 일시 정지"
    "permission_missing" -> "카메라 권한 없음"
    "front_camera_missing" -> "전면 카메라 없음"
    "not_started" -> "시작 대기"
    else -> "사용 불가 ($state)"
}
