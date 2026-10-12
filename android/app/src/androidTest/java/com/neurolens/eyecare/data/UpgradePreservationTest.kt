package com.neurolens.eyecare.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpgradePreservationTest {
    @Test fun versionFourDataSurvivesVersionFiveOpenAndNewOptionalDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upgrade-test-${UUID.randomUUID()}.db"
        val session = SessionRecord(id = "preserved-session", startedAt = 1_000L,
            reason = "user_stopped", appVersion = "0.8.0-alpha")
        val report = CareReport(id = "preserved-report", sessionId = session.id,
            createdAt = 2_000L, windowStartAt = 1_000L, intervalMin = 10, score = 42,
            level = "test", confidence = "partial", inputs = emptyList(), usageScore = 20,
            screenScore = 30, screenCoverage = .5)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, started INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE care_reports (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE care_events (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE TABLE eye_dense (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, created INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX eye_dense_session_time ON eye_dense(session_id, created)")
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id); put("started", session.startedAt)
            put("data", SessionCodec.encode(session).toString())
        })
        db.insertOrThrow("care_reports", null, ContentValues().apply {
            put("id", report.id); put("session_id", session.id); put("created", report.createdAt)
            put("data", CareReportCodec.encode(report).toString())
        })
        db.insertOrThrow("care_events", null, ContentValues().apply {
            put("id", "event"); put("session_id", session.id); put("created", 3_000L)
            put("data", CareEventCodec.encode(CareEvent("event", session.id, 3_000L, "test")).toString())
        })
        db.version = 4
        db.close()
        try {
            SessionStore(context, name).use { store ->
                assertEquals(5, store.writableDatabase.version)
                assertEquals(session.id, store.all().single().id)
                assertNull(store.all().single().diagnostics)
                assertEquals(report.id, store.reports().single().id)
                assertEquals("test", store.events(setOf(session.id)).single().type)
                store.addSubjectiveResponse(SubjectiveResponse("answer", report.id, 5, 4_000L))
                assertEquals(5, store.subjectiveResponses().single().nrs)
            }
        } finally { context.deleteDatabase(name) }
    }
}
