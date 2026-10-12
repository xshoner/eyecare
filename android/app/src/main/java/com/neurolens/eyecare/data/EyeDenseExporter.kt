package com.neurolens.eyecare.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPOutputStream

object EyeDenseExporter {
    /** Eye observations have been persisted with sessions since 0.3.1. */
    fun supportsSession(appVersion: String): Boolean {
        val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""").matchEntire(appVersion) ?: return false
        val (major, minor, patch) = match.destructured
        val version = Triple(major.toIntOrNull() ?: return false,
            minor.toIntOrNull() ?: return false, patch.toIntOrNull() ?: return false)
        return version.first > 0 || version.second > 3 ||
            (version.second == 3 && version.third >= 1)
    }

    private val columns = listOf("at", "capturedElapsedMs", "cameraTimestampNs", "status",
        "openA", "openB", "uA", "vA", "uB", "vB", "gazeState", "stdU", "stdV",
        "rapidMovesPerMin", "landmarks", "blendshapes", "headMatrix", "faceIllumination")

    fun prepare(context: Context, store: SessionStore, sessionId: String): File {
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(folder, "eyecare-eyes-${sessionId}.csv.gz")
        try {
            var count = 0
            GZIPOutputStream(file.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(columns.joinToString(","))
                count = store.eachEyeObservation(sessionId) { raw ->
                    val row = JSONObject(raw)
                    writer.appendLine(columns.joinToString(",") { name -> csvCell(row.opt(name).takeUnless { it == JSONObject.NULL }?.toString() ?: "") })
                }
            }
            require(count > 0) { "저장된 눈 관측 자료가 없어요." }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ")}\""
}
