package com.neurolens.eyecare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neurolens.eyecare.data.SessionRecord
import com.neurolens.eyecare.data.SessionStore
import com.neurolens.eyecare.engine.ExposureBin
import com.neurolens.eyecare.engine.ExposureStats
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTest {
    @Test fun durableCheckpointAndSelfReportRoundtrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "storage-test-${java.util.UUID.randomUUID()}.db"
        val store = SessionStore(context, databaseName)
        val r = SessionRecord(elapsedMs = 1500, stats = ExposureStats(validMs = 1000, highMs = 500,
            frames = 3, meanChange = .2, bins = listOf(ExposureBin(0, 1000, .2, 500))), reason = "user_stopped", discomfort = 2)
        store.save(r)
        store.close()
        val reopened = SessionStore(context, databaseName)
        val loaded = reopened.all().first { it.id == r.id }
        assertEquals(r.id, loaded.id)
        assertEquals(r.stats.bins, loaded.stats.bins)
        assertEquals(2, loaded.discomfort)
        assertEquals(1000, loaded.stats.validMs)
        reopened.clear()
        reopened.close()
        context.deleteDatabase(databaseName)
    }
}
