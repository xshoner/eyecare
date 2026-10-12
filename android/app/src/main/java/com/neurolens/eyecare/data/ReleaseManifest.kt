package com.neurolens.eyecare.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class ReleaseInfo(val versionCode: Int, val versionName: String,
    val apkSha256: String, val downloadUrl: String)

object ReleaseManifest {
    const val URL = "https://eyecare-one.vercel.app/android-release.json"

    fun parse(text: String): ReleaseInfo? = runCatching {
        if (text.length !in 2..8192) return@runCatching null
        val j = JSONObject(text)
        val code = j.getInt("versionCode")
        val name = j.getString("versionName")
        val sha = j.getString("apkSha256")
        val link = j.getString("downloadUrl")
        validate(code, name, sha, link)
    }.getOrNull()

    fun validate(code: Int, name: String, sha: String, link: String): ReleaseInfo? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (code < 1 || name.length !in 3..40 || !sha.matches(Regex("[0-9a-fA-F]{64}")) ||
            uri.scheme != "https" || uri.host != "drive.google.com" ||
            !uri.path.matches(Regex("/file/d/[A-Za-z0-9_-]+/view"))) return null
        return ReleaseInfo(code, name, sha.lowercase(), link)
    }

    fun isNewer(release: ReleaseInfo, installedVersionCode: Long) =
        release.versionCode.toLong() > installedVersionCode
}

/** Invoked only from the user's Update check button; never downloads or installs an APK. */
object ReleaseChecker {
    fun check(): ReleaseInfo? {
        val connection = URL(ReleaseManifest.URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return null
            val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ReleaseManifest.parse(payload)
        } finally { connection.disconnect() }
    }
}
