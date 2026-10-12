package com.neurolens.eyecare.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ReportExporter {
    fun document(records: List<SessionRecord>, careReports: List<CareReport> = emptyList(),
        events: List<CareEvent> = emptyList()): JSONObject = JSONObject().apply {
        put("format", "neurolens-eyecare")
        put("schemaVersion", if (careReports.isNotEmpty() || events.isNotEmpty()) 3 else if (records.any { it.engineVersion == "exposure-0.2.1" }) 2 else 1)
        put("reportId", UUID.randomUUID().toString())
        put("exportedAt", System.currentTimeMillis())
        put("sessions", JSONArray().apply { records.filter { it.reason != "active" }.take(100).forEach { put(SessionCodec.encode(it)) } })
        if (careReports.isNotEmpty() || events.isNotEmpty()) {
            put("careReports", JSONArray().apply { careReports.forEach { put(CareReportCodec.encode(it)) } })
            put("careEvents", JSONArray().apply { events.forEach { put(CareEventCodec.encode(it)) } })
        }
    }

    fun prepare(context: Context, records: List<SessionRecord>, careReports: List<CareReport> = emptyList(),
        events: List<CareEvent> = emptyList()): File {
        require(records.any { it.reason != "active" }) { "내보낼 기록이 없어요." }
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000 }?.forEach { it.delete() }
        val file = File(folder, "eyecare-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        file.writeText(document(records, careReports, events).toString(2), Charsets.UTF_8)
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("EyeCare report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
