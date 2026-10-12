package com.neurolens.eyecare

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.neurolens.eyecare.capture.ProjectionService
import com.neurolens.eyecare.data.EyeCareRepository
import com.neurolens.eyecare.data.ReportExporter
import com.neurolens.eyecare.data.CollectorClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    private fun text(value: String): UiObject2 = device.wait(Until.findObject(By.text(value)), 10000)
        ?: run {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "failure.xml"))
            error("Missing UI text: $value")
        }
    private fun launchHome() {
        EyeCareRepository.init(context)
        InstrumentationRegistry.getArguments().getString("collectorAddress")?.let {
            context.getSharedPreferences("collector", android.content.Context.MODE_PRIVATE).edit().putString("address", it).commit()
        }
        device.wakeUp()
        device.pressMenu()
        if (EyeCareRepository.live.value != null) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ProjectionService.STOP))
            waitFor("Previous session stops") { EyeCareRepository.live.value == null }
        }
        EyeCareRepository.history.value.filter { it.engineVersion == "exposure-0.2.1" }.forEach {
            if (it.feedback["afterUse"] == null) EyeCareRepository.feedback(it.id, "afterUse", null)
            if (it.restStartedAt != null && it.feedback["afterRest"] == null) EyeCareRepository.feedback(it.id, "afterRest", null)
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("화면 변화 분석 시작").click()
        text("영상").click()
        text("1").click()
        text("동의하고 시작").click()
        assertNotNull(device.wait(Until.findObject(By.res("com.android.systemui", "screen_share_mode_spinner")), 10000))
    }
    private fun shareEntireScreen() {
        launchHome()
        device.findObject(By.res("com.android.systemui", "screen_share_mode_spinner")).click()
        text("Entire screen").click()
        device.waitForIdle()
        device.findObject(By.res("android", "button1")).click()
        waitFor("Projection running after explicit system consent") { EyeCareRepository.live.value != null }
    }
    private fun screenshot(name: String) {
        assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), name)))
    }
    private fun reveal(value: String) {
        device.waitForIdle()
        repeat(8) {
            if (device.hasObject(By.text(value))) return
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 30)
            device.waitForIdle()
        }
        text(value)
    }

    @Test fun captureAcrossAppsRotationStopAndSelfReport() {
        shareEntireScreen()
        val id = EyeCareRepository.live.value!!.record.id
        val fixture = Intent().setClassName(instrumentation.context.packageName, FixtureActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fixture)
        assertNotNull(device.wait(Until.findObject(By.desc("EyeCare color fixture")), 10000))
        waitFor("Real MediaProjection frames produce measurable changes") {
            val stats = EyeCareRepository.live.value?.record?.stats
            stats != null && stats.frames >= 8 && stats.validMs >= 2500 && stats.highMs >= 1000
        }
        device.setOrientationLeft()
        SystemClock.sleep(1800)
        assertNotNull(EyeCareRepository.live.value)
        device.setOrientationNatural()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        text("●  화면 변화 분석 중")
        device.waitForIdle()
        screenshot("active.png")
        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("분석 종료")
        text("분석 종료").click()
        waitFor("Session persisted after stopping") { EyeCareRepository.history.value.any { it.id == id } }
        val saved = EyeCareRepository.history.value.first { it.id == id }
        assertEquals("user_stopped", saved.reason)
        assertTrue(saved.stats.validMs <= saved.elapsedMs)
        assertTrue(saved.stats.highMs <= saved.stats.validMs)
        assertEquals("video", saved.content)
        assertEquals(1, saved.discomfortBefore)
        assertEquals(saved.elapsedMs, saved.stats.validMs + saved.stats.excludedMs.values.sum())
        text("분석을 마쳤어요. 지금 눈은 어떤가요?")
        text("2").click()
        waitFor("Timed self-report saved") { EyeCareRepository.history.value.first { it.id == id }.feedback["afterUse"]?.score == 2 }
        val latest = EyeCareRepository.history.value.first { it.id == id }
        assertTrue(latest.feedback["afterUse"]!!.at >= latest.startedAt + latest.elapsedMs)
        assertTrue(latest.stats.captureCounters.getValue("callbacks") >= latest.stats.captureCounters.getValue("selected"))
        screenshot("self-report.png")
        val exported = ReportExporter.document(listOf(EyeCareRepository.history.value.first { it.id == id }))
        File(context.getExternalFilesDir(null), "exported-report.json").writeText(exported.toString(2))
        InstrumentationRegistry.getArguments().getString("collectorAddress")?.let { address ->
            val count = CollectorClient.send(address, exported)
            assertEquals(count, CollectorClient.send(address, exported))
        }
        UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(10)
        screenshot("result.png")
        text("이 기록 내보내기").click()
        text("분석 보고서 내보내기")
        screenshot("export.png")
        if (InstrumentationRegistry.getArguments().getString("collectorAddress") != null) {
            text("집계로 보내기").click()
            assertNotNull(device.wait(Until.findObject(By.textContains("전송 완료")), 15000))
        } else device.pressBack()
    }

    @Test fun cancelConsentDoesNotStartCapture() {
        launchHome()
        device.findObject(By.res("android", "button2")).click()
        text("화면 변화 분석 시작")
        assertNull(EyeCareRepository.live.value)
    }

    @Test fun partialWindowShowsObservedTimeAndExclusions() {
        EyeCareRepository.init(context)
        val e = com.neurolens.eyecare.engine.ExposureEngine(0)
        e.frame(FloatArray(64) { .2f },9000)
        e.frame(FloatArray(64) { .8f },9507)
        val r = com.neurolens.eyecare.data.SessionRecord(startedAt=System.currentTimeMillis(),elapsedMs=10000,
            stats=e.snapshot(10000,true),reason="user_stopped",engineVersion="exposure-0.2.1",revision=1)
        EyeCareRepository.store.save(r); EyeCareRepository.refresh()
        device.wakeUp(); device.pressMenu()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("분석을 마쳤어요. 지금 눈은 어떤가요?")
        text("응답 건너뛰기").click()
        reveal("구간별 분석 시간 보기")
        text("구간별 분석 시간 보기").click()
        reveal("0초: 0.51 / 10.00초 분석")
        text("0초: 0.51 / 10.00초 분석")
        screenshot("partial-quality.png")
    }

    @Test fun restRequestSkipsUseResponseAndStoresSeparateRestResponse() {
        shareEntireScreen()
        val id = EyeCareRepository.live.value!!.record.id
        UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("분석 마치고 1분 쉬기")
        text("분석 마치고 1분 쉬기").click()
        text("분석을 마쳤어요. 지금 눈은 어떤가요?")
        text("응답 건너뛰기").click()
        text("결과로 돌아가기").click()
        text("휴식 시작 이후, 지금 눈은 어떤가요?")
        text("3").click()
        waitFor("Rest response saved independently") { EyeCareRepository.history.value.first { it.id == id }.feedback["afterRest"]?.score == 3 }
        val r = EyeCareRepository.history.value.first { it.id == id }
        assertTrue(r.feedback["afterUse"]!!.skipped)
        assertNull(r.discomfort)
        assertTrue(r.feedback["afterRest"]!!.at >= r.restStartedAt!!)
        screenshot("rest-feedback.png")
    }

    @Test fun screenOffStopsCaptureAndSavesRecord() {
        shareEntireScreen()
        val id = EyeCareRepository.live.value!!.record.id
        device.sleep()
        waitFor("Screen off terminates capture") { EyeCareRepository.history.value.any { it.id == id } }
        assertTrue(EyeCareRepository.history.value.first { it.id == id }.reason in listOf("screen_off", "system_stopped"))
        assertNull(EyeCareRepository.live.value)
        device.wakeUp(); device.pressMenu()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("분석을 마쳤어요. 지금 눈은 어떤가요?")
        text("응답 건너뛰기").click()
        waitFor("Screen-off response skip persisted") { EyeCareRepository.history.value.first { it.id == id }.feedback["afterUse"]?.skipped == true }
    }
}
