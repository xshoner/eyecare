package com.neurolens.eyecare

import android.content.ContentValues
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurolens.eyecare.data.*
import com.neurolens.eyecare.engine.ExposureStats
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportTest {
    @Test fun diagnosticsAndTimedFeedbackSurviveDatabaseAndExport() {
        val engine = com.neurolens.eyecare.engine.ExposureEngine(0)
        engine.frame(FloatArray(64) { .4f }, 50)
        engine.frame(FloatArray(64) { .8f }, 550)
        val r = SessionRecord(elapsedMs=1000,reason="screen_off",stats=engine.snapshot(1000,true),
            engineVersion="exposure-0.2.1",appVersion="0.2.1-alpha",revision=3,
            feedback=mapOf("afterUse" to Feedback(null,System.currentTimeMillis(),true),"afterRest" to Feedback(2,System.currentTimeMillis(),false)),
            restStartedAt=System.currentTimeMillis()-60000)
        val name="diagnostics-${java.util.UUID.randomUUID()}.db"
        val store=SessionStore(context,name)
        try {
            // Live-only currentChange/contrast are intentionally not persisted by the report codec.
            val persisted = r.copy(stats = r.stats.copy(currentChange = 0.0, contrast = 0.0))
            store.save(r); assertEquals(persisted,store.all().single())
            val doc=ReportExporter.document(listOf(r))
            assertEquals(2,doc.getInt("schemaVersion"))
            assertEquals(persisted,SessionCodec.decode(doc.getJSONArray("sessions").getJSONObject(0)))
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyDatabaseRecordSurvivesWithUnknownNewFields() {
        val name = "legacy-${java.util.UUID.randomUUID()}.db"
        val store = SessionStore(context, name)
        try {
            val id = java.util.UUID.randomUUID().toString()
            val raw = JSONObject("""{"id":"$id","started":100,"elapsed":1000,"reason":"user_stopped","discomfort":2,"engine":"exposure-0.1","valid":0,"high":0,"frames":1,"mean":0,"bins":[]}""")
            store.writableDatabase.insertOrThrow("sessions", null, ContentValues().apply { put("id", id); put("started",100); put("data",raw.toString()) })
            val r = store.all().single()
            assertNull(r.discomfortBefore); assertEquals("unknown",r.device); assertEquals("exposure-0.1",r.engineVersion)
            store.save(r.copy(discomfort=3,revision=1))
            assertEquals(3,store.all().single().discomfort)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun reportUsesScopedReadOnlyUriAndRoundtripsNewMetadata() {
        val r = SessionRecord(elapsedMs=1000,reason="user_stopped",stats=ExposureStats(excludedMs=mapOf("waiting" to 1000L)),
            discomfortBefore=1,discomfort=2,content="reading",device="test device",sdk=35,appVersion="0.2.0-alpha",engineVersion="exposure-0.2",revision=2)
        assertEquals(r,SessionCodec.decode(SessionCodec.encode(r)))
        val file = ReportExporter.prepare(context,listOf(r,SessionRecord()))
        try {
            val intent = ReportExporter.shareIntent(context,file)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val uri = intent.clipData!!.getItemAt(0).uri
            assertEquals("content",uri.scheme)
            val parsed = JSONObject(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertEquals(1,parsed.getInt("schemaVersion")); assertEquals(1,parsed.getJSONArray("sessions").length())
            assertEquals(1,parsed.getJSONArray("sessions").getJSONObject(0).getInt("before"))
            assertFalse(parsed.toString().contains("token"))
        } finally { file.delete() }
    }
}
