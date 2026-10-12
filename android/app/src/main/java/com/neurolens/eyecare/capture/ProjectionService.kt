package com.neurolens.eyecare.capture

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.neurolens.eyecare.MainActivity
import com.neurolens.eyecare.R
import com.neurolens.eyecare.data.*
import com.neurolens.eyecare.engine.*
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class ProjectionService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    @Volatile private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frontCamera: FrontCameraSource? = null
    private var sensorHub: SensorHub? = null
    private var eyeLive = EyeLive()
    private val eyeSignals = EyeSignalEngine()
    private val screenSignals = ScreenSignalEngine()
    private val burdenStore by lazy { ObservedBurdenStore(this) }
    private var burdenLedger: ObservedBurdenLedger? = null
    private val strainStore by lazy { StrainStore(this) }
    private var strainLedger: StrainLedger? = null
    private val strainPolicy = StrainAlertPolicy()
    private var latestAssessment: StrainModel.Assessment? = null
    private var habitualCm: Double? = null
    private var reportTicks = 0
    private var reportRichTicks = 0
    private val distanceTracker = DistanceTracker()
    private val distanceCalibration by lazy { DistanceCalibrationRepository(this) }
    private var lastDistanceReportAt = 0L
    private var lastDistanceSampleAt = -1L
    private var lastDistanceAttemptAt = -1L
    private var lastCameraGeometryId: String? = null
    private var latestMvp: MvpResult? = null
    private val recentEyes = ArrayDeque<EyeObservation>()
    private val eyeWriteBuffer = EyeWriteBuffer()
    private var diagnostics: SessionDiagnosticsAccumulator? = null
    private val eyePostPending = AtomicBoolean(false)
    private val restPolicy = RestAlertPolicy()
    private var qualityGuide = QualityGuide()
    @Volatile private var overlayVisible = false
    private val restOverlay by lazy { RestOverlay(this,
        rest = { worker.post {
            val sessionId = record?.id
            event("rest_alert_selected")
            finish("break_requested")
            if (sessionId != null) main.post { runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("beginRestSessionId", sessionId))
            }.onFailure { EyeCareRepository.error.value = "휴식 화면을 열지 못했습니다. 앱에서 기록을 확인해 주세요." } }
        } },
        snooze = { worker.post { event("rest_alert_snoozed"); restPolicy.snooze(SystemClock.elapsedRealtime()); strainPolicy.snooze(SystemClock.elapsedRealtime()); hideRestOverlay(); notices.cancel(3) } },
        dismiss = { worker.post { event("rest_alert_dismissed"); restPolicy.dismiss(SystemClock.elapsedRealtime(), settings.restCooldownMinutes); hideRestOverlay() } },
        disable = { worker.post {
            event("rest_alert_disabled")
            settings = settings.copy(overlayRestEnabled = false)
            CareSettingsRepository(this).save(settings)
            restPolicy.clear(); hideRestOverlay(); notices.cancel(3)
        } }) }
    private var cameraAllowed = false
    private var engine: ExposureEngine? = null
    private var record: SessionRecord? = null
    private var origin = 0L
    private var lastFrame = -1000L
    private var lastSave = 0L
    private var lastDiagnosticSave = 0L
    private var lastScoreAt = 0L
    private var lastThermalCheck = -10_000L
    private var lastSettingsCheck = -5_000L
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalHeadroom: Float? = null
    private var thermalPaused = false
    private var thermalRecoveryAt = 0L
    private var stopping = false
    private var visible = true
    private var screenIsOn = true
    private var reminderMuted = false
    private var scheduler: ReportScheduler? = null
    private var settings = CareSettings()
    private var latestEfs: EfsResult? = null
    private var lastEfsMinute = -1L
    private var lastScoreRefresh = -1L
    private var lastReportMicroBreaks = 0
    private var lastReportInteractionObservedMs = 0L
    private var pendingContent = "unspecified"
    private var pendingBefore: Int? = null
    private var sampleBuffer = FloatArray(0)
    private var rgbBuffer = IntArray(0)
    private val captureCounters = mutableMapOf<String, Long>()
    private fun countCapture(key: String) { captureCounters[key] = (captureCounters[key] ?: 0) + 1 }
    private var processingCount = 0
    private var processingTotalNs = 0L
    private var processingMaxNs = 0L
    private val main = Handler(Looper.getMainLooper())
    private val notices get() = getSystemService(NotificationManager::class.java)
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> worker.post {
                    val now = SystemClock.elapsedRealtime()
                    screenIsOn = false
                    scheduler?.update(now, false)
                    sensorHub?.stop()
                    frontCamera?.stop()
                    flushEyeBuffer()
                    restPolicy.clear(); hideRestOverlay()
                    updateCameraState("camera_paused")
                    event("screen_off")
                    engine?.resetContinuity("hidden", now)
                    screenSignals.reset("screen_off")
                    eyeSignals.reset()
                    lastFrame = -1L
                    publish()
                }
                Intent.ACTION_SCREEN_ON -> worker.post {
                    val now = SystemClock.elapsedRealtime()
                    screenIsOn = true
                    sensorHub?.start()
                    val restsBefore = scheduler?.restCount ?: 0
                    scheduler?.update(now, true)
                    if (!thermalPaused) frontCamera?.start()
                    event("screen_on")
                    if ((scheduler?.restCount ?: 0) > restsBefore) event("recognized_rest", "5m_screen_off")
                    engine?.resetContinuity("waiting", now)
                    screenSignals.reset("screen_on")
                    lastFrame = -1L
                    publish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        EyeCareRepository.init(this)
        thread = HandlerThread("eyecare-features").apply { start() }
        worker = Handler(thread.looper)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        notices.createNotificationChannel(NotificationChannel(CHANNEL, "눈 케어 진행", NotificationManager.IMPORTANCE_LOW))
        notices.createNotificationChannel(NotificationChannel(REPORT_CHANNEL, "눈 컨디션 리포트", NotificationManager.IMPORTANCE_DEFAULT))
        notices.createNotificationChannel(NotificationChannel(REST_ALERT_CHANNEL, "잠깐 휴식 안내", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> worker.post { finish("user_stopped") }
            REST -> worker.post { finish("break_requested") }
            MUTE_REMINDER -> worker.post {
                reminderMuted = true
            }
            SNOOZE_REST -> worker.post { event("rest_alert_snoozed"); restPolicy.snooze(SystemClock.elapsedRealtime()); strainPolicy.snooze(SystemClock.elapsedRealtime()); hideRestOverlay(); notices.cancel(3) }
            DISABLE_REST -> worker.post {
                event("rest_alert_disabled")
                settings = settings.copy(overlayRestEnabled = false)
                CareSettingsRepository(this).save(settings); restPolicy.clear(); hideRestOverlay(); notices.cancel(3)
            }
            CALIBRATE_DISTANCE -> worker.post { calibrateDistance() }
            START -> {
                if (projection != null || stopping) return START_NOT_STICKY
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("data")
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                try {
                    pendingContent = intent.getStringExtra("content")?.takeIf { it in listOf("shorts", "video", "game", "reading", "mixed") } ?: "unspecified"
                    pendingBefore = intent.getIntExtra("before", -1).takeIf { it in 0..4 }
                    cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        (if (cameraAllowed && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                    startForeground(1, ongoing(0), types)
                    val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
                    projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                    worker.post { if (record == null) begin() else resumeCapture() }
                } catch (_: Exception) {
                    EyeCareRepository.error.value = "화면 분석을 시작하지 못했어요. 공유 동의를 다시 확인해 주세요."
                    if (record == null) worker.post { finish("start_failed") }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        try {
            origin = SystemClock.elapsedRealtime()
            burdenLedger = ObservedBurdenLedger(burdenStore.load(java.time.LocalDate.now().toEpochDay()))
            lastReportInteractionObservedMs = burdenLedger?.state()?.interactionObservedMs ?: 0L
            val today = java.time.LocalDate.now().toEpochDay()
            val wallNow = System.currentTimeMillis()
            strainLedger = StrainLedger(strainStore.load(today)).also { ledger ->
                // Rest between sessions counts only when Android recorded the screen as off.
                ledger.state().lastWallMs?.let { last ->
                    UsageReader.screenOffMillis(this, last, wallNow)?.let { off -> ledger.recoverGap(off, wallNow, today) }
                }
            }
            habitualCm = DailyDistance.recent(EyeCareRepository.reports.value.filter { it.createdAt < wallNow }, wallNow).meanCm
            reportTicks = 0; reportRichTicks = 0
            diagnostics = SessionDiagnosticsAccumulator(origin)
            qualityGuide = QualityGuide()
            lastDiagnosticSave = 0L
            lastDistanceReportAt = origin
            distanceTracker.clear()
            lastDistanceSampleAt = -1L
            lastDistanceAttemptAt = -1L
            lastCameraGeometryId = null
            screenIsOn = getSystemService(PowerManager::class.java).isInteractive
            settings = CareSettingsRepository(this).read()
            scheduler = ReportScheduler(settings.intervalMin, settings.basis, startMs = origin,
                screenOn = screenIsOn)
            record = SessionRecord(discomfortBefore = pendingBefore, content = pendingContent,
                device = "${Build.MANUFACTURER} ${Build.MODEL}", sdk = Build.VERSION.SDK_INT,
                appVersion = "0.15.0-alpha", engineVersion = "exposure-0.2.1+${StrainModel.VERSION}")
            record?.let { EyeCareRepository.store.save(it) }
            event("care_started")
            engine = ExposureEngine(origin)
            sensorHub = SensorHub(this, worker).also { if (screenIsOn) it.start() }
            EyeCareRepository.error.value = null
            attachCapture()
            ensureCameraSource()
            publish()
            worker.post(tick)
        } catch (_: Exception) {
            EyeCareRepository.error.value = "이 기기에서 화면을 읽지 못했어요. 다시 시작해 주세요."
            finish("capture_error")
        }
    }

    private fun resumeCapture() {
        try {
            attachCapture()
            ensureCameraSource()
            event("screen_capture_resumed")
            publish()
        } catch (_: Exception) {
            detachCapture("capture_error")
            EyeCareRepository.error.value = "화면 공유를 다시 시작하지 못했어요. 다시 시도해 주세요."
        }
    }

    private fun ensureCameraSource() {
        if (!cameraAllowed) { frontCamera?.stop(); updateCameraState("permission_missing"); return }
        if (frontCamera == null) frontCamera = FrontCameraSource(this,
            { observation ->
                if (!eyePostPending.compareAndSet(false, true)) false else {
                    val queued = worker.post {
                        try { handleEyeObservation(observation) } finally { eyePostPending.set(false) }
                    }
                    if (!queued) eyePostPending.set(false)
                    queued
                }
            },
            { state -> worker.post { updateCameraState(state) } })
        if (screenIsOn && !thermalPaused) frontCamera?.start()
    }

    private fun attachCapture() {
            visible = true
            val sourceProjection = projection ?: throw IllegalStateException("Missing projection")
            sourceProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (projection === sourceProjection) detachCapture("system_stopped") }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (!stopping && projection === sourceProjection && display != null) runCatching { resize(width, height) }.onFailure { detachCapture("capture_error") }
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (projection !== sourceProjection) return
                    countCapture(if (isVisible) "visible_events" else "hidden_events")
                    if (visible == isVisible) return // Duplicate callbacks are not a new capture gap.
                    visible = isVisible
                    if (!isVisible) { restPolicy.clear(); hideRestOverlay() }
                    engine?.resetContinuity(if (isVisible) "waiting" else "hidden", SystemClock.elapsedRealtime())
                    screenSignals.reset(if (isVisible) "visible" else "hidden")
                    lastFrame = -1L
                }
            }, worker)
            val metrics = resources.displayMetrics
            val (w, h) = scaled(metrics.widthPixels, metrics.heightPixels)
            reader = makeReader(w, h)
            display = sourceProjection.createVirtualDisplay("EyeCare local analysis", w, h, resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, worker)
            if (display == null) throw IllegalStateException("Missing display")
    }

    private fun detachCapture(reason: String) {
        if (stopping || (projection == null && display == null)) return
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        projection = null
        visible = false
        flushEyeBuffer()
        restPolicy.clear(); hideRestOverlay()
        engine?.resetContinuity("hidden", SystemClock.elapsedRealtime())
        screenSignals.reset(reason)
        lastFrame = -1L
        event("screen_capture_stopped", reason)
        publish()
    }

    private fun scaled(width: Int, height: Int): Pair<Int, Int> {
        val scale = 256.0 / maxOf(width, height).coerceAtLeast(1)
        return Pair((width * scale).roundToInt().coerceAtLeast(2), (height * scale).roundToInt().coerceAtLeast(2))
    }

    private fun makeReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ source ->
            countCapture("callbacks")
            val frame = try { source.acquireLatestImage() } catch (_: Exception) { countCapture("acquire_errors"); return@setOnImageAvailableListener }
            if (frame == null) { countCapture("empty_acquires"); return@setOnImageAvailableListener }
            countCapture("acquired")
            frame.use { image ->
                val at = SystemClock.elapsedRealtime()
                if (source !== reader) { countCapture("stale_reader"); return@use }
                if (stopping) { countCapture("stopping_skips"); return@use }
                if (!screenIsOn) { countCapture("screen_off_skips"); return@use }
                if (!visible) { countCapture("hidden_skips"); return@use }
                 if (thermalPaused) { countCapture("thermal_skips"); return@use }
                 if (at - lastFrame < if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) 1000 else 500) {
                     countCapture("throttled"); return@use
                 }
                countCapture("selected")
                val processingStart = SystemClock.elapsedRealtimeNanos()
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    if (sampleBuffer.size != image.width * image.height) sampleBuffer = FloatArray(image.width * image.height)
                    if (rgbBuffer.size != image.width * image.height) rgbBuffer = IntArray(image.width * image.height)
                    val luma = sampleBuffer
                    var offset = 0
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        val i = y * plane.rowStride + x * plane.pixelStride
                        val r = buffer.get(i).toInt() and 255
                        val g = buffer.get(i + 1).toInt() and 255
                        val b = buffer.get(i + 2).toInt() and 255
                        luma[offset++] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                        rgbBuffer[offset - 1] = -0x1000000 or (r shl 16) or (g shl 8) or b
                    }
                    if (screenSignals.frame(rgbBuffer, image.width, image.height, at)) {
                        engine?.frame(luma, at)
                        lastFrame = at // Only successfully decoded and accepted pixels refresh capture.
                    } else engine?.resetContinuity("invalid", at)
                } catch (_: Exception) { countCapture("read_errors"); engine?.resetContinuity("invalid", at) }
                finally {
                    val elapsedNs = SystemClock.elapsedRealtimeNanos() - processingStart
                    processingCount++; processingTotalNs += elapsedNs; processingMaxNs = maxOf(processingMaxNs, elapsedNs)
                }
            }
        }, worker)
    }

    private fun resize(width: Int, height: Int) {
        val (w, h) = scaled(width, height)
        if (reader?.width == w && reader?.height == h) return
        countCapture("resize_events")
        val old = reader
        val next = makeReader(w, h)
        display?.resize(w, h, resources.configuration.densityDpi)
        display?.surface = next.surface
        reader = next
        old?.close()
        engine?.resetContinuity("resized", SystemClock.elapsedRealtime())
        screenSignals.reset("resized")
        lastFrame = -1L
    }

    private fun monitorThermal(now: Long) {
        if (now - lastThermalCheck < 10_000) return
        lastThermalCheck = now
        val power = getSystemService(PowerManager::class.java)
        thermalStatus = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else PowerManager.THERMAL_STATUS_NONE
        thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) runCatching { power.getThermalHeadroom(10) }
            .getOrNull()?.takeIf { it.isFinite() } else null
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && !thermalPaused) {
            thermalPaused = true; thermalRecoveryAt = 0L
            frontCamera?.stop(); updateCameraState("thermal_paused")
            engine?.resetContinuity("thermal_paused", now)
            screenSignals.reset("thermal_paused")
            eyeSignals.reset()
            lastFrame = -1L
            restPolicy.clear(); hideRestOverlay()
            latestMvp = latestMvp?.copy(scoreAvailable = false, confidence = "열로 측정 일시 중지")
            event("thermal_pause", "status=$thermalStatus")
            EyeCareRepository.error.value = "기기 열 상태가 높아 분석을 잠시 멈췄습니다. 뜨겁다면 측정을 종료하고 식혀 주세요."
        } else if (thermalPaused) {
            if (thermalStatus <= PowerManager.THERMAL_STATUS_LIGHT) {
                if (thermalRecoveryAt == 0L) thermalRecoveryAt = now
                if (now - thermalRecoveryAt >= 30_000) {
                    thermalPaused = false; thermalRecoveryAt = 0L
                    if (screenIsOn) frontCamera?.start()
                    event("thermal_resumed", "status=$thermalStatus")
                }
            } else thermalRecoveryAt = 0L
        }
        diagnostics?.thermal(now, thermalStatus, thermalPaused)
    }

    private fun diagnosticSnapshot(now: Long): SessionDiagnostics? = record?.let { session ->
        diagnostics?.snapshot(now, processingCount, processingTotalNs / 1e6,
            processingMaxNs / 1e6, ResearchUploadManager.get(this).status.value,
            session.appVersion, session.engineVersion)
    }

    private fun hideRestOverlay() {
        if (!overlayVisible) return
        overlayVisible = false
        main.post { restOverlay.hide() }
    }

    /** At least two of light, distance and screen stimulation measured: enough to trust a deduction. */
    private fun richInputs(a: StrainModel.Assessment): Boolean {
        val signals = latestMvp?.signals ?: return false
        val lux = signals["E01.lux"]?.let { it.value != null && it.quality >= .3 } == true
        val distance = signals["D01.distanceEstimateCm"]?.let { it.value != null && it.quality >= .2 } == true
        return listOf(lux, distance, a.stimulus != null).count { it } >= 2
    }

    private fun evaluateRestAlert(now: Long) {
        if (overlayVisible && !Settings.canDrawOverlays(this)) {
            event("rest_alert_permission_lost"); hideRestOverlay()
        }
        val ledger = strainLedger
        val assessment = latestAssessment
        val active = !stopping && screenIsOn && visible && display != null && !thermalPaused && !reminderMuted &&
            ledger != null && assessment != null && ObservationFreshness.current(now, lastScoreAt)
        if (!active) return
        val alert = strainPolicy.evaluate(StrainAlertPolicy.Tick(now, ledger!!.condition(), assessment!!,
            (scheduler?.continuousMillis() ?: 0L) / 60_000.0,
            latestMvp?.signals?.get("D01.distanceEstimateCm")?.takeIf { it.quality >= .2 }?.value,
            ledger.darkMinutes, settings.restCooldownMinutes, settings.restAlertMinutes)) ?: return
        event("strain_alert", alert.kind.name)
        main.post {
            if (stopping || !screenIsOn) return@post
            hideRestOverlay()
            // Overlay when the user chose it and Android allows it; otherwise a heads-up notification.
            overlayVisible = settings.overlayRestEnabled && restOverlay.show(alert.title, alert.body)
            if (overlayVisible) worker.post { event("rest_alert_shown", "overlay:${alert.kind}") }
            else if (notices.areNotificationsEnabled()) {
                runCatching { notices.notify(3, NotificationCompat.Builder(this, REST_ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_eye)
                    .setContentTitle(alert.title).setContentText(alert.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                    .setPriority(if (alert.kind == StrainAlertPolicy.Kind.RISK) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(activityPending()).setAutoCancel(true)
                    .addAction(0, "10분 뒤에", servicePending(SNOOZE_REST, 11))
                    .addAction(0, "이번 케어 알림 끄기", servicePending(MUTE_REMINDER, 12)).build()) }
                    .onSuccess { worker.post { event("rest_alert_shown", "notification:${alert.kind}") } }
            } else worker.post { event("rest_alert_unavailable", "permission") }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (stopping) {
                return
            }
            val elapsed = SystemClock.elapsedRealtime() - origin
            val now = SystemClock.elapsedRealtime()
            monitorThermal(now)
            val selected = if (now - lastSettingsCheck >= 5_000) {
                lastSettingsCheck = now; CareSettingsRepository(this@ProjectionService).read()
            } else settings
            val schedule = scheduler
            if (schedule != null) {
                val due = if (selected != settings) {
                    settings = selected
                    schedule.settings(selected.intervalMin, selected.basis, now)
                } else schedule.update(now)
                if (latestMvp == null || lastScoreRefresh < 0 || elapsed - lastScoreRefresh >= 10_000) {
                    latestEfs = calculateEfs(now)
                    lastScoreRefresh = elapsed
                }
                burdenLedger?.observe(now, java.time.LocalDate.now().toEpochDay(),
                    screenIsOn && !thermalPaused && latestMvp?.scoreAvailable == true &&
                        ObservationFreshness.current(now, lastScoreAt),
                    latestMvp?.burdenRate, latestMvp?.interactionRate ?: 0.0)
                val viewing = screenIsOn && !thermalPaused && display != null && ObservationFreshness.current(now, lastScoreAt)
                strainLedger?.observe(now, System.currentTimeMillis(), java.time.LocalDate.now().toEpochDay(),
                    viewing, resting = !screenIsOn, assessment = latestAssessment)
                if (viewing) { reportTicks++; if (latestAssessment?.let { richInputs(it) } == true) reportRichTicks++ }
                if (elapsed / 60_000L != lastEfsMinute) {
                    lastEfsMinute = elapsed / 60_000L
                    latestMvp?.let { result ->
                        val detail = JSONObject().put("version", MvpScorer.VERSION)
                            .put("score", if (result.scoreAvailable) result.score else JSONObject.NULL)
                            .put("observedBurden", result.observedBurden ?: JSONObject.NULL)
                            .put("confidence", result.confidence)
                            .put("domains", JSONObject(result.domains))
                            .put("signals", JSONObject().apply { result.signals.forEach { (id, signal) ->
                                put(id, JSONObject().put("value", signal.value ?: JSONObject.NULL)
                                    .put("quality", signal.quality).put("reason", signal.reason ?: JSONObject.NULL)
                                    .put("estimated", signal.estimated))
                            } })
                        event("mvp_minute", detail.toString())
                    }
                }
                if (due) createReport(now)
            }
            evaluateRestAlert(now)
            eyeWriteBuffer.due(now)?.let { batch -> record?.let { EyeCareRepository.store.saveEyeObservations(it.id, batch) } }
            publish()
            if (elapsed - lastSave >= 5000) {
                if (elapsed - lastDiagnosticSave >= 30_000) {
                    record = record?.copy(diagnostics = diagnosticSnapshot(now))
                    lastDiagnosticSave = elapsed
                }
                record?.let { EyeCareRepository.store.save(it) }
                burdenLedger?.let { burdenStore.save(it.state()) }
                strainLedger?.let { strainStore.save(it.state()) }
                notices.notify(1, ongoing(elapsed))
                lastSave = elapsed
            }
            worker.postDelayed(this, 1000)
        }
    }

    private fun publish(finalized: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        var recentEyeCoverage: Double? = null
        if (record != null) {
            val distance = distanceTracker.snapshot(now, 60_000)
            val recent = recentEyes.filter { now - it.capturedAtMs in 0L..10_000L }
            val fps = if (recent.size < 2) 0.0 else recent.size * 1000.0 /
                (now - recent.first().capturedAtMs).coerceAtLeast(1000L)
            recentEyeCoverage = recent.takeIf { it.size >= 5 }?.let { samples ->
                samples.count { it.geometry != null }.toDouble() / samples.size }
            eyeLive = eyeLive.copy(distanceCoverage = distance.coverage,
                distanceJitterCm = distance.jitterCm, deliveredFps = fps,
                thermalStatus = thermalStatus, thermalHeadroom = thermalHeadroom,
                recentEyeCoverage = recentEyeCoverage)
        }
        val stats = (engine?.snapshot(now, finalized) ?: ExposureStats()).copy(processingCount = processingCount,
            processingTotalMs = processingTotalNs / 1e6, processingMaxMs = processingMaxNs / 1e6,
            captureCounters = captureCounters.toMap())
        val advice = qualityGuide.update(now, QualityInput(eyeLive.cameraState,
            eyeLive.lastObservationStatus, eyeLive.faceIllumination, eyeLive.distanceReason,
            recentEyeCoverage, record?.let { stats.validMs.toDouble() /
                (now - origin).coerceAtLeast(1) }, eyeLive.deliveredFps,
            (now - origin).coerceAtLeast(0), thermalPaused, screenIsOn))
        eyeLive = eyeLive.copy(qualityCode = advice?.code, qualityAction = advice?.action)
        record = record?.copy(elapsedMs = (now - origin).coerceAtLeast(0), stats = stats)
        record?.let { EyeCareRepository.live.value = LiveSession(it, true, reminderMuted,
            scheduler?.remainingMs(now) ?: 0, latestEfs, display != null, eyeLive,
            latestMvp?.let { result -> ObservedContext.fromSignals(result.signals, result.domains,
                result.scoreAvailable, result.observedBurden, result.observedMinutes,
                result.inputs) },
            latestMvp?.signals?.get("E01.lux")?.takeIf { it.quality >= .3 }?.value?.takeIf { it.isFinite() && it >= 0 },
            strainLive()) }
    }

    private fun strainLive(): StrainLive? {
        val ledger = strainLedger ?: return null
        val a = latestAssessment
        val condition = ledger.condition()
        val minutes = a?.let { strainPolicy.minutesToNextBand(condition, it.totalRate) }
        val nextBand = when { condition >= 80 -> 79; condition >= 60 -> 59; condition >= 40 -> 39; condition >= 20 -> 19; else -> null }
            ?.let { EyeCondition.band(it).label }
        return StrainLive(condition, a?.totalRate ?: 0.0,
            a?.active?.map { it.label to (a.rates[it] ?: 0.0) }.orEmpty(),
            a?.combo?.takeIf { it != StrainModel.Combo.NONE }?.label, a?.stimulus?.label,
            minutes?.takeIf { (a?.totalRate ?: 0.0) >= .3 }, nextBand,
            a?.let { strainPolicy.breakInterval(it) } ?: 20,
            (scheduler?.continuousMillis() ?: 0L) / 60_000.0,
            a?.active?.take(2)?.map { it.tip }.orEmpty())
    }

    private fun calculateEfs(now: Long, distanceWindowMs: Long = 300_000): EfsResult {
        val elapsedSec = ((now - origin) / 1000).toInt()
        val windowSec = minOf(300, settings.intervalMin * 30)
        val stats = engine?.snapshot(now) ?: ExposureStats()
        val old = EfsScorer.score((scheduler?.continuousMillis() ?: 0L) / 60_000.0,
            stats.bins, (elapsedSec - windowSec).coerceAtLeast(0), elapsedSec)
        val signals = linkedMapOf<String, Signal>()
        val captureReason = when {
            stopping -> "session_stopped"
            thermalPaused -> "thermal_paused"
            !screenIsOn -> "screen_off"
            display == null -> "capture_stopped"
            !ObservationFreshness.current(now, lastFrame) -> "stale_screen_sample"
            else -> null
        }
        signals.putAll(screenSignals.snapshot(now, captureReason))
        signals.putAll(sensorHub?.snapshot(now).orEmpty())
        val eyeReason = when {
            stopping -> "session_stopped"
            thermalPaused -> "thermal_paused"
            !cameraAllowed -> "camera_permission_denied"
            !screenIsOn -> "screen_off"
            eyeLive.cameraState != "observing" -> eyeLive.cameraState
            else -> null
        }
        signals.putAll(eyeSignals.snapshot(now, eyeReason))
        val distance = distanceTracker.snapshot(now, distanceWindowMs)
        val distanceQuality = (distance.quality * distance.coverage).coerceIn(0.0, 1.0)
        val distanceFresh = ObservationFreshness.current(now, lastDistanceSampleAt)
        signals["D01.distanceEstimateCm"] = if (distanceFresh && distance.medianCm != null && distanceQuality >= .05)
            Signal.observed(distance.medianCm, distanceQuality, estimated = true)
        else Signal.missing("distance_coverage_or_geometry_unavailable")
        signals["D02.nearExposureRisk"] = if (distanceFresh && distance.closeRisk != null && distanceQuality >= .05)
            Signal.observed(distance.closeRisk, distanceQuality, estimated = true)
        else Signal.missing("distance_coverage_or_geometry_unavailable")
        signals["D03.nearRatio"] = distance.nearRatio?.takeIf { distanceFresh }
            ?.let { Signal.observed(it, distanceQuality, estimated = true) }
            ?: Signal.missing("distance_coverage_or_geometry_unavailable")
        signals["D04.coverage"] = Signal.observed(distance.coverage)
        signals["D05.validMinutes"] = Signal.observed(distance.observedMs / 60_000.0)
        signals["D08.windowMinutes"] = Signal.observed(distanceWindowMs.coerceAtMost(7_200_000) / 60_000.0)
        signals["D06.calibrated"] = Signal.observed(if (distance.calibrated) 1.0 else 0.0)
        signals["D07.source"] = Signal.missing(distance.source) // Source is local report metadata, not a numeric measure.
        signals["S17.captureActive"] = if (captureReason == null) Signal.observed(1.0)
            else Signal.missing(captureReason)
        signals["U01.continuousMin"] = Signal.observed((scheduler?.continuousMillis() ?: 0) / 60_000.0)
        signals["U07.microBreaks"] = Signal.observed(((scheduler?.microRestCount ?: 0) - lastReportMicroBreaks).toDouble())
        signals["U07.recognizedRests"] = Signal.observed((scheduler?.restCount ?: 0).toDouble())
        val apl = signals["S01.apl"]?.value
        val brightness = signals["S15.brightness"]?.value
        signals["S16.emissionProxy"] = if (apl != null && brightness != null)
            Signal.observed(apl * brightness, minOf(signals["S01.apl"]!!.quality, .7), estimated = true)
        else Signal.missing("screen_or_brightness_unavailable")
        val lux = signals["E01.lux"]?.value
        val emission = signals["S16.emissionProxy"]?.value
        signals["E08.darkBrightRisk"] = ContextRisk.darkBright(lux, brightness)?.let {
            Signal.observed(it, minOf(signals["E01.lux"]?.quality ?: 0.0,
                signals["S15.brightness"]?.quality ?: 0.0, .6), estimated = true)
        } ?: Signal.missing("lux_or_brightness_unavailable")
        signals["E02.mismatch"] = if (lux != null && emission != null)
            Signal.observed(kotlin.math.log10((emission + .01) / (lux / 500.0 + .01)), .5, estimated = true)
        else Signal.missing("light_sensor_or_emission_unavailable")
        val interactionObservedMs = burdenLedger?.state()?.interactionObservedMs ?: 0L
        if (interactionObservedMs < lastReportInteractionObservedMs) lastReportInteractionObservedMs = 0L
        val jointMs = (interactionObservedMs - lastReportInteractionObservedMs).coerceAtLeast(0L)
        val jointInputsNow = signals["E08.darkBrightRisk"]?.value != null &&
            signals["S06.change"]?.value != null
        signals["X01.darkStimulusMin"] = if (jointMs > 0 || jointInputsNow)
            Signal.observed((jointMs / 60_000.0).coerceAtMost(10.0), .6, estimated = true)
        else Signal.missing("joint_dark_screen_observation_unavailable")
        signals["S17.validMs"] = Signal.observed(stats.validMs.toDouble())
        signals["S17.excludedMs"] = Signal.observed(stats.excludedMs.values.sum().toDouble())
        val observedWindowMs = (now - origin).coerceIn(1L, 300_000L)
        val windowStartSec = ((now - origin - observedWindowMs) / 1000).toInt()
        signals["S18.timeCoverage"] = Signal.observed((stats.bins.filter { it.seconds >= windowStartSec }
            .sumOf { it.validMs }.toDouble() / observedWindowMs).coerceIn(0.0, 1.0))
        val baseline = PersonalBaselineRepository(this).read()
        fun compare(id: String, source: String, metric: BaselineMetric) {
            val signal = signals[source]
            signals[id] = when {
                signal?.value == null -> Signal.missing(signal?.reason ?: "source_unavailable")
                !metric.ready -> Signal.missing("baseline_gathering")
                else -> Signal.observed(signal.value - metric.mean!!,
                    minOf(signal.quality, metric.qualitySum / metric.count), estimated = true)
            }
            signals["$id.validSamples"] = Signal.observed(metric.count.toDouble())
            signals["$id.baselineQuality"] = if (metric.count > 0)
                Signal.observed(metric.qualitySum / metric.count) else Signal.missing("baseline_gathering")
        }
        compare("H01.usageVsBaseline", "U01.continuousMin", baseline.usage)
        compare("H02.blinkVsBaseline", "F02.blinksPerMin", baseline.blink)
        compare("H03.openVsBaseline", "F06.openMean", baseline.open)
        compare("H04.switchesVsBaseline", "U03.switchesPerMin", baseline.switches)
        compare("H05.unlocksVsBaseline", "U06.unlocks", baseline.unlocks)
        compare("H06.microBreaksVsBaseline", "U07.microBreaks", baseline.microBreaks)
        // Deduction model inputs: only reasonably reliable values; missing ones deduct nothing.
        fun v(id: String, minQuality: Double = .30) = signals[id]?.takeIf { it.quality >= minQuality }
            ?.value?.takeIf { it.isFinite() }
        val ledger = strainLedger
        val assessment = StrainModel.assess(StrainModel.Inputs(
            lux = v("E01.lux"), brightness = v("S15.brightness", .2),
            distanceCm = v("D01.distanceEstimateCm", .2), habitualCm = habitualCm,
            continuousMin = v("U01.continuousMin", .5), screenChange = v("S06.change"),
            largeRatio = v("S08.largeRatio"), flashPerMin = v("S09.flashPerMin"), scenePerMin = v("S10.scenePerMin"),
            blinksPerMin = v("F02.blinksPerMin", .35), closure = v("F07.closure80", .35), squint = v("F09.squint", .35),
            tiltDeg = v("M01.tiltDeg"), darkMinutes = ledger?.darkMinutes ?: 0.0, nearMinutes = ledger?.nearMinutes ?: 0.0))
        latestAssessment = assessment.takeIf { captureReason == null }
        signals["K01.strainRatePerMin"] = Signal.observed(assessment.totalRate, .6, estimated = true)
        ledger?.state()?.let { state ->
            signals["K02.condition"] = Signal.observed(state.condition.toDouble(), .6, estimated = true)
            signals["K03.darkMinutes"] = Signal.observed(state.darkMs / 60_000.0, .6)
            signals["K04.nearMinutes"] = Signal.observed(state.nearMs / 60_000.0, .6)
            signals["K07.viewingMinutesToday"] = Signal.observed(state.viewingMs / 60_000.0)
            signals["K08.restMinutesToday"] = Signal.observed(state.restMs / 60_000.0)
        }
        signals["K05.nearSeverity"] = Signal.observed(assessment.nearSeverity, .6, estimated = true)
        signals["K06.stimulusLevel"] = assessment.stimulus?.let { Signal.observed(it.ordinal.toDouble(), .5, estimated = true) }
            ?: Signal.missing("screen_change_unavailable")
        signals["K09.comboLevel"] = Signal.observed(assessment.combo.ordinal.toDouble(), .6, estimated = true)
        signals["K10.habitualDistanceCm"] = habitualCm?.let { Signal.observed(it, .5, estimated = true) }
            ?: Signal.missing("no_prior_distance")
        latestMvp = MvpScorer.score(signals, baseline).let {
            val accumulated = burdenLedger?.score()
            val observedMinutes = (burdenLedger?.state()?.observedMs ?: 0L) / 60_000.0
            it.copy(observedBurden = accumulated?.takeIf { observedMinutes > 0 },
                observedMinutes = observedMinutes,
                scoreAvailable = it.scoreAvailable && !thermalPaused && screenIsOn,
                confidence = if (thermalPaused) "열로 측정 일시 중지" else it.confidence)
        }
        lastScoreAt = now
        val result = latestMvp!!
        return EfsResult(result.score, if (!result.scoreAvailable) "측정 불가" else "입력 기여",
            result.confidence, result.inputs, result.domains["usage"] ?: old.usageScore,
            result.domains["screen"], signals["S17.coverage"]?.value ?: old.coverage,
            result.scoreAvailable)
    }

    private fun handleEyeObservation(observation: EyeObservation) {
        if (stopping || !screenIsOn) return
        diagnostics?.camera(observation.capturedAtMs, observation.geometry != null,
            observation.processingMs)
        if (eyeLive.cameraState != "observing") updateCameraState("observing")
        eyeLive = eyeLive.copy(frames = eyeLive.frames + 1,
            eyeValidFrames = eyeLive.eyeValidFrames + if (observation.geometry != null) 1 else 0,
            gazeValidFrames = eyeLive.gazeValidFrames + if (observation.geometry?.meanU != null) 1 else 0,
            lastOpen = observation.geometry?.meanOpen,
            lastGaze = observation.gaze, lastFrameElapsedMs = observation.capturedAtMs,
            frameGapMs = observation.frameGapMs, processingMs = observation.processingMs,
            faceIllumination = observation.faceIllumination, lastObservationStatus = observation.status,
            distanceCm = observation.distance.cm, distanceQuality = observation.distance.quality,
            distanceReason = observation.distance.reason,
            lowLight = observation.lowLight, lowLightGain = observation.lowLightGain,
            rawLumaP50 = observation.rawLumaP50, cameraEv100 = observation.cameraEv100)
        // Retain summary geometry for reports, not repeated landmark/blendshape arrays.
        recentEyes.addLast(observation.copy(landmarks = emptyList(), blendshapes = emptyMap(), headMatrix = emptyList()))
        if (lastCameraGeometryId != null && lastCameraGeometryId != observation.cameraGeometryId) {
            distanceTracker.clear(); lastDistanceSampleAt = -1L; lastDistanceAttemptAt = -1L
        }
        lastCameraGeometryId = observation.cameraGeometryId
        val calibrated = distanceCalibration.apply(observation.distance, observation.cameraGeometryId)
        if (lastDistanceAttemptAt < 0 || observation.capturedAtMs - lastDistanceAttemptAt >= 200) {
            lastDistanceAttemptAt = observation.capturedAtMs
            distanceTracker.add(observation.capturedAtMs, calibrated)
            diagnostics?.distance(observation.capturedAtMs, calibrated.cm != null && calibrated.quality >= .35)
            if (calibrated.cm?.let { it.isFinite() && it in 15.0..80.0 } == true &&
                calibrated.quality >= .35) lastDistanceSampleAt = observation.capturedAtMs
        }
        eyeLive = eyeLive.copy(distanceCm = calibrated.cm, distanceQuality = calibrated.quality,
            distanceReason = calibrated.reason)
        eyeSignals.add(observation.capturedAtMs, observation.geometry?.eyeAOpen,
            observation.geometry?.eyeBOpen, observation.blendshapes,
            observation.gaze?.let { g ->
                if (g.stdU != null && g.stdV != null) kotlin.math.hypot(g.stdU, g.stdV) else null
            }, observation.status, observation.headMatrix, observation.faceIllumination,
            CameraFrame(observation.lowLightGain, LowLightPolicy.enhancementQuality(observation.lowLightGain),
                observation.rawLumaP50, observation.cameraEv100, observation.lowLight, observation.aeFpsMin))
        while (recentEyes.isNotEmpty() && observation.capturedAtMs - recentEyes.first().capturedAtMs > 300_000) recentEyes.removeFirst()
        record?.let { session -> eyeWriteBuffer.offer(observation)?.let { batch ->
            EyeCareRepository.store.saveEyeObservations(session.id, batch)
        } }
    }

    private fun flushEyeBuffer() {
        record?.let { session -> EyeCareRepository.store.saveEyeObservations(session.id, eyeWriteBuffer.drain()) }
    }

    private fun calibrateDistance() {
        if (record == null || !screenIsOn) return
        val recent = recentEyes.filter { SystemClock.elapsedRealtime() - it.capturedAtMs <= 5_000 &&
            it.distance.diameterPx != null && it.distance.reason in setOf(null,
                "camera_geometry_or_calibration_needed", "outside_estimation_range") }
        val geometry = recent.lastOrNull()?.cameraGeometryId
        val diameters = recent.filter { it.cameraGeometryId == geometry }.mapNotNull { it.distance.diameterPx }.sorted()
        if (geometry == null || diameters.size < 3 || diameters.last() / diameters.first() > 1.15) {
            EyeCareRepository.error.value = "거리 보정에 쓸 안정적인 홍채 표본이 부족합니다. 밝은 곳에서 화면을 보고 다시 시도하세요."
            return
        }
        distanceCalibration.calibrate(30.0, diameters[diameters.size / 2], geometry)
        EyeCareRepository.error.value = "30 cm 기준 보정을 저장했습니다. 자로 눈과 카메라 사이를 맞춘 경우에만 참고하세요."
    }

    private fun updateCameraState(state: String) {
        if (stopping || (!screenIsOn && state != "camera_paused") || state == eyeLive.cameraState) return
        if (state != "observing") {
            eyeSignals.reset(); distanceTracker.clear(); lastDistanceSampleAt = -1L
            lastDistanceAttemptAt = -1L; recentEyes.clear()
        }
        eyeLive = eyeLive.copy(cameraState = state,
            lastOpen = if (state == "observing") eyeLive.lastOpen else null,
            lastGaze = if (state == "observing") eyeLive.lastGaze else null,
            distanceCm = if (state == "observing") eyeLive.distanceCm else null,
            distanceReason = if (state == "observing") eyeLive.distanceReason else state)
        event("camera_state", state)
        publish()
    }

    private fun createReport(now: Long, notify: Boolean = true, updateBaseline: Boolean = true) {
        val schedule = scheduler ?: return
        val session = record ?: return
        val result = calculateEfs(now, (now - lastDistanceReportAt).coerceAtLeast(1))
        latestEfs = result
        val created = System.currentTimeMillis()
        val windowMs = minOf(now - origin, minOf(300L, settings.intervalMin * 30L) * 1000L)
        val start = created - windowMs
        val eyes = recentEyes.filter { it.capturedAtMs >= now - windowMs }
        val validEyes = eyes.mapNotNull { it.geometry }
        val gaze = eyes.lastOrNull()?.takeIf { now - it.capturedAtMs < 2_000 }
            ?.gaze?.state ?: "unavailable"
        val report = CareReport.from(session.id, created, start, settings.intervalMin, result,
            eyeDetectionRatio = if (eyes.isEmpty()) null else validEyes.size.toDouble() / eyes.size,
            cameraProcessedFps = eyes.size * 1000.0 / windowMs.coerceAtLeast(1),
            eyeOpenMean = validEyes.takeIf { it.isNotEmpty() }?.map { it.meanOpen }?.average(),
            gazeState = gaze, mvp = latestMvp).let { base ->
            // The deduction model replaces the per-minute rate as the stored score from 0.14 on.
            val strain = strainLedger?.state()
            if (strain == null) base else {
                val coverage = if (reportTicks > 0) (100 * reportRichTicks / reportTicks) else 0
                base.copy(version = StrainModel.VERSION, score = 100 - strain.condition,
                    scoreAvailable = strain.viewingMs > 0 && !thermalPaused,
                    level = EyeCondition.band(strain.condition).label,
                    confidence = if (coverage >= 50) "보통 · 감점 모델" else "낮음 · 부분 관측",
                    availableWeight = coverage,
                    contributions = strain.byFactor,
                    observedBurden = 100 - strain.condition, observedMinutes = strain.viewingMs / 60_000.0,
                    mvpNote = "감점 모델 ${StrainModel.VERSION}: 오늘 누적 감점 ${"%.1f".format(strain.deduction)}점, " +
                        "관측 시청 ${strain.viewingMs / 60_000}분, 관측 휴식 ${strain.restMs / 60_000}분, 입력 충분 비율 $coverage%.")
            }
        }
        reportTicks = 0; reportRichTicks = 0
        EyeCareRepository.addReport(report)
        if (updateBaseline) latestMvp?.let { mvp -> PersonalBaselineRepository(this).add(
            mvp.signals["F06.openMean"], mvp.signals["F02.blinksPerMin"],
            mvp.signals["U01.continuousMin"], mvp.signals["U03.switchesPerMin"],
            mvp.signals["U06.unlocks"], mvp.signals["U07.microBreaks"],
            if (PersonalTrend.comparable(report)) Signal.observed(mvp.score.toDouble(), .7) else null) }
        event("report", report.id)
        lastReportMicroBreaks = schedule.microRestCount
        lastReportInteractionObservedMs = burdenLedger?.state()?.interactionObservedMs ?: 0L
        lastDistanceReportAt = now
        schedule.reported(now)
        if (notify && report.scoreAvailable && !reminderMuted && notices.areNotificationsEnabled())
            reportNotification(report)
    }

    private fun finish(reason: String) {
        if (stopping) return
        restPolicy.clear(); hideRestOverlay()
        worker.removeCallbacks(tick)
        flushEyeBuffer()
        if (record != null && scheduler != null) createReport(SystemClock.elapsedRealtime(),
            notify = false, updateBaseline = false)
        stopping = true // Final report must snapshot fresh evidence before the stop gate hides it.
        burdenLedger?.let { burdenStore.save(it.state()) }
        strainLedger?.let { strainStore.save(it.state()) }
        frontCamera?.close(); frontCamera = null
        sensorHub?.stop(); sensorHub = null
        if (engine != null) publish(true)
        event("care_stopped", reason)
        record?.copy(reason = reason, revision = 1,
            diagnostics = diagnosticSnapshot(SystemClock.elapsedRealtime()))?.let { EyeCareRepository.store.save(it) }
        EyeCareRepository.live.value = null
        EyeCareRepository.refresh()
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun event(type: String, detail: String = "") {
        record?.let { EyeCareRepository.store.saveEvent(CareEvent(sessionId = it.id,
            at = System.currentTimeMillis(), type = type, detail = detail)) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        worker.post { finish("interrupted"); thread.quitSafely() }
        super.onDestroy()
    }

    private fun activityPending(rest: Boolean = false): PendingIntent = PendingIntent.getActivity(this, if (rest) 2 else 1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (rest) putExtra("rest", true)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun servicePending(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, ProjectionService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun reportPending(reportId: String): PendingIntent = PendingIntent.getActivity(this, reportId.hashCode(),
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("careReportId", reportId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun ongoing(elapsed: Long): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_eye).setContentTitle("EYE·休 · 눈 케어 ${elapsed / 60000}분째")
        .setContentText(if (display == null && record != null) "화면 공유가 멈췄어요 · 앱에서 다시 시작해 주세요" else "화면·얼굴 영상은 저장하지 않아요")
        .setContentIntent(activityPending()).setOngoing(true).setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(0, "케어 종료", servicePending(STOP, 10)).build()

    private fun reportNotification(report: CareReport) {
        notices.notify(report.id.hashCode(), NotificationCompat.Builder(this, REPORT_CHANNEL)
            .setSmallIcon(R.drawable.ic_eye)
            .setContentTitle(if (PersonalTrend.comparable(report))
                "눈 컨디션 ${EyeCondition.fromBurden(report.score)}점 · ${EyeCondition.band(EyeCondition.fromBurden(report.score)).label}"
                else "눈 컨디션 리포트가 도착했어요")
            .setContentText(if (PersonalTrend.comparable(report)) EyeCondition.sentence(EyeCondition.fromBurden(report.score))
                else "일부만 측정됐어요. 눌러서 자세히 보기")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                (if (PersonalTrend.comparable(report)) EyeCondition.sentence(EyeCondition.fromBurden(report.score))
                else "일부 항목만 측정돼 참고용 리포트예요.") + "\n지금 눈이 얼마나 피곤한지 남겨 주세요."))
            .setContentIntent(reportPending(report.id)).setAutoCancel(true)
            .addAction(0, "지금 눈 상태 남기기", reportPending(report.id))
            .build())
    }

    companion object {
        const val START = "com.neurolens.eyecare.START"
        const val STOP = "com.neurolens.eyecare.STOP"
        const val REST = "com.neurolens.eyecare.REST"
        const val MUTE_REMINDER = "com.neurolens.eyecare.MUTE_REMINDER"
        const val SNOOZE_REST = "com.neurolens.eyecare.SNOOZE_REST"
        const val DISABLE_REST = "com.neurolens.eyecare.DISABLE_REST"
        const val CALIBRATE_DISTANCE = "com.neurolens.eyecare.CALIBRATE_DISTANCE"
        private const val CHANNEL = "analysis"
        private const val REPORT_CHANNEL = "care_reports"
        private const val REST_ALERT_CHANNEL = "rest_alerts"
    }
}
