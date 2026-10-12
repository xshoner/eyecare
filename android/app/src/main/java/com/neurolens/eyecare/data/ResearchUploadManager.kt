package com.neurolens.eyecare.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ResearchUploadState(val enabled: Boolean, val pending: Int, val sent: Int,
    val failed: Int, val lastSyncedAtMs: Long?, val activationPending: Boolean,
    val message: String, val reasonCode: String? = null) {
    val consented: Boolean get() = enabled
}
data class BrowserGrantState(val mode: String, val browserId: String?, val expiresAt: String?,
    val busy: Boolean, val message: String)

/** All stored tokens, consent state, and queued aggregates are protected by Android Keystore AES-GCM. */
class ResearchUploadManager private constructor(context: Context) {
    companion object {
        @Volatile private var instance: ResearchUploadManager? = null
        fun get(context: Context): ResearchUploadManager = instance ?: synchronized(this) {
            instance ?: ResearchUploadManager(context.applicationContext).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("research_upload_v1", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val api = SupabaseResearchTransport(
        "https://fdwmhfruvdyrxidwuvqx.supabase.co",
        "sb_publishable_23y3Jc2Jj1Ko5FiKChB1oA_9sriwmby"
    )
    private var locked = false
    private var state: JSONObject = try { load() } catch (_: Exception) {
        locked = true
        JSONObject()
    }
    init { if (!locked) runCatching { initializeAutoMode() }.onFailure { locked = true } }
    private val mutableStatus = MutableStateFlow(snapshot(if (locked) "보호된 기록을 열 수 없어 동기화를 멈췄어요." else "동기화 대기 중"))
    val status = mutableStatus.asStateFlow()
    private val mutableBrowserStatus = MutableStateFlow(browserSnapshot(if (locked)
        "보호된 계정 정보를 열 수 없어 웹 연결을 확인할 수 없습니다." else "웹 연결 확인 전"))
    val browserStatus = mutableBrowserStatus.asStateFlow()

    private fun pendingResponses(): List<SubjectiveResponse> {
        val local = SessionStore(app)
        return try { local.pendingSubjectiveResponses() } finally { local.close() }
    }
    private fun snapshot(message: String): ResearchUploadState = ResearchUploadState(
        state.optBoolean("auto_enabled"), (state.optJSONArray("queue")?.length() ?: 0) +
            if (state.optBoolean("auto_enabled")) runCatching { pendingResponses().size }.getOrDefault(0) else 0,
        state.optJSONArray("sent_report_ids")?.length() ?: 0, state.optInt("failed_count", 0),
        state.optLong("last_synced_at_ms").takeIf { it > 0L },
        state.optBoolean("activation_check_pending"), message,
        when {
            locked -> "KEY_UNAVAILABLE"
            state.optBoolean("activation_check_pending") -> "CONSENT_CHECK_PENDING"
            state.optString("last_error_code").isNotBlank() -> state.optString("last_error_code")
            (state.optJSONArray("queue")?.length() ?: 0) > 0 -> "PENDING_SEND"
            else -> null
        })

    private fun initializeAutoMode() {
        if (state.optBoolean("auto_sync_initialized")) return
        val oldConsent = state.optBoolean("consent")
        val hasSession = state.optJSONObject("session") != null
        val hasPseudonym = state.has("device_pseudonym")
        val pendingWithdrawal = state.optBoolean("pending_withdrawal")
        // A fresh installation is opted in by this deployment's explicit approval.
        // A previous withdrawal has no marker in 0.5.3, so uncertain identities stay off
        // until a read of the old owner consent row proves no prior remote withdrawal.
        val initial = ResearchAutoModePolicy.initial(oldConsent, hasSession,
            hasPseudonym, pendingWithdrawal)
        state.put("auto_enabled", initial.enabled)
        state.put("auto_sync_initialized", true)
        state.put("activation_check_pending", initial.checkPriorConsent)
        if (initial.enabled) state.put("consent", true)
        save()
    }

    private fun browserSnapshot(message: String, busy: Boolean = false): BrowserGrantState {
        val row = state.optJSONObject("browser_grant")
        val pending = state.optBoolean("pending_browser_revoke")
        val expires = row?.optString("expires_at")?.takeIf { it.isNotBlank() }
        val mode = when {
            locked -> "error"
            pending -> "revoke_pending"
            row == null -> "disconnected"
            !row.isNull("revoked_at") -> "revoked"
            expires != null && runCatching { java.time.Instant.parse(expires).isBefore(java.time.Instant.now()) }.getOrDefault(false) -> "expired"
            else -> "linked"
        }
        return BrowserGrantState(mode, row?.optString("browser_id"), expires, busy, message)
    }

    private fun readBrowserGrant(owner: UUID, token: String): JSONObject? {
        val rows = JSONArray(api.request("GET",
            "/rest/v1/research_browser_grants?owner_id=eq.$owner&select=browser_id,expires_at,revoked_at", token))
        return if (rows.length() == 0) null else rows.getJSONObject(0)
    }

    suspend fun connectBrowser(rawBrowserId: String) = mutex.withLock {
        checkUnlocked()
        val browserId = runCatching { UUID.fromString(rawBrowserId.trim()) }.getOrNull()
        if (browserId == null || browserId.toString() != rawBrowserId.trim().lowercase()) {
            mutableBrowserStatus.value = browserSnapshot("올바른 브라우저 QR 또는 UUID가 아닙니다.")
            return@withLock
        }
        val current = state.optJSONObject("browser_grant")
        if (current?.optString("browser_id") == browserId.toString() &&
            browserSnapshot("").mode == "linked") {
            mutableBrowserStatus.value = browserSnapshot("이 브라우저는 이미 연결되어 있습니다.")
            return@withLock
        }
        mutableBrowserStatus.value = browserSnapshot("브라우저 연결 승인 중", busy = true)
        try {
            val active = session()
            val owner = UUID.fromString(active.getString("owner_id"))
            val token = active.getString("access_token")
            if (readBrowserGrant(owner, token) == null) {
                try {
                    api.request("POST", "/rest/v1/research_browser_grants", token,
                        JSONObject().put("owner_id", owner.toString()).put("browser_id", browserId.toString()))
                } catch (e: ResearchHttpException) {
                    if (e.status != 409) throw e
                    val expiry = java.time.Instant.now().plus(java.time.Duration.ofDays(29)).toString()
                    api.request("PATCH", "/rest/v1/research_browser_grants?owner_id=eq.$owner", token,
                        JSONObject().put("browser_id", browserId.toString())
                            .put("expires_at", expiry).put("revoked_at", JSONObject.NULL))
                }
            } else {
                // Server enforces <=30 days. Allow clock skew by requesting 29 days.
                val expiry = java.time.Instant.now().plus(java.time.Duration.ofDays(29)).toString()
                api.request("PATCH", "/rest/v1/research_browser_grants?owner_id=eq.$owner", token,
                    JSONObject().put("browser_id", browserId.toString())
                        .put("expires_at", expiry).put("revoked_at", JSONObject.NULL))
            }
            val confirmed = readBrowserGrant(owner, token) ?: error("연결 상태를 확인할 수 없습니다.")
            check(confirmed.getString("browser_id") == browserId.toString())
            state.put("browser_grant", confirmed)
            state.put("pending_browser_revoke", false)
            save()
            mutableBrowserStatus.value = browserSnapshot("이 브라우저에서 내 기록을 볼 수 있어요.")
        } catch (e: Exception) {
            mutableBrowserStatus.value = browserSnapshot(if (e is ResearchHttpException)
                "서버 응답 ${e.status}. 연결되지 않았습니다. 다시 시도해 주세요."
                else "연결 또는 인증 오류. 네트워크를 확인해 주세요.")
        }
    }

    suspend fun revokeBrowser() = mutex.withLock {
        checkUnlocked()
        if (state.optJSONObject("browser_grant") == null) return@withLock
        state.put("pending_browser_revoke", true)
        save()
        mutableBrowserStatus.value = browserSnapshot("철회 요청 중. 오프라인이면 서버 연결 권한은 아직 남을 수 있습니다.")
    }

    suspend fun retryBrowserGrant() = mutex.withLock {
        if (locked || state.optJSONObject("session") == null) return@withLock
        try {
            val active = session()
            val owner = UUID.fromString(active.getString("owner_id"))
            val token = active.getString("access_token")
            if (state.optBoolean("pending_browser_revoke")) {
                api.request("PATCH", "/rest/v1/research_browser_grants?owner_id=eq.$owner", token,
                    JSONObject().put("revoked_at", java.time.Instant.now().toString()))
                state.put("pending_browser_revoke", false)
            }
            val grant = readBrowserGrant(owner, token)
            if (grant == null) state.remove("browser_grant") else state.put("browser_grant", grant)
            save()
            mutableBrowserStatus.value = browserSnapshot(when (browserSnapshot("").mode) {
                "linked" -> "이 브라우저에서 내 기록을 볼 수 있어요."
                "expired" -> "웹 연결이 만료됐습니다. 다시 승인할 수 있습니다."
                "revoked" -> "웹 연결이 해제됐습니다."
                else -> "연결된 브라우저가 없습니다."
            })
        } catch (e: Exception) {
            mutableBrowserStatus.value = browserSnapshot(if (state.optBoolean("pending_browser_revoke"))
                "철회 요청 대기 중. 오프라인이면 서버 연결 권한이 아직 남을 수 있습니다."
                else "웹 연결을 확인할 수 없습니다. 네트워크를 확인해 주세요.")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("neurolens_research_v1", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("neurolens_research_v1",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }

    private fun load(): JSONObject {
        val value = prefs.getString("state", null) ?: return JSONObject().put("queue", JSONArray())
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    private fun save() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("state", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    private fun checkUnlocked() { check(!locked) { "보호된 기록을 열 수 없어 동기화할 수 없어요." } }

    fun devicePseudonym(): UUID {
        checkUnlocked()
        state.optString("device_pseudonym").takeIf { it.isNotBlank() }?.let { return UUID.fromString(it) }
        return UUID.randomUUID().also { state.put("device_pseudonym", it.toString()); save() }
    }

    suspend fun resolveLegacyActivation(): Boolean = mutex.withLock {
        if (locked) return@withLock false
        if (!state.optBoolean("activation_check_pending")) return@withLock true
        try {
            val active = session() // Reuses the existing owner; never signs up here.
            val owner = UUID.fromString(active.getString("owner_id"))
            val rows = JSONArray(api.request("GET",
                "/rest/v1/research_consent?owner_id=eq.$owner&select=consent_version,withdrawn_at",
                active.getString("access_token")))
            if (rows.length() == 0) {
                state.put("auto_enabled", true)
                state.put("consent", true)
            }
            state.put("activation_check_pending", false)
            save()
            mutableStatus.value = snapshot(if (state.optBoolean("auto_enabled"))
                "기존 계정 확인 완료 · 자동 동기화 켜짐"
                else "이전 동의 기록 확인 완료 · 자동 동기화 꺼짐")
            true
        } catch (_: Exception) {
            mutableStatus.value = snapshot("이전 동의 상태를 확인하지 못했습니다. 확인 전에는 자동 전송하지 않습니다.")
            false
        }
    }

    suspend fun setAutoEnabled(enabled: Boolean) = mutex.withLock {
        checkUnlocked()
        state.put("auto_sync_initialized", true)
        state.put("activation_check_pending", false)
        state.put("auto_enabled", enabled)
        state.put("consent", enabled)
        if (!enabled) {
            state.put("queue", JSONArray())
            state.put("failed_count", 0)
            state.put("pending_withdrawal", state.optJSONObject("session") != null)
        }
        save()
        mutableStatus.value = snapshot(if (enabled)
            "자동 웹 동기화 켜짐 · 기존 보고서를 확인합니다."
            else "자동 웹 동기화 꺼짐 · 새 자료를 보내지 않습니다. 이미 저장된 서버 기록은 유지됩니다.")
    }

    suspend fun withdraw() = setAutoEnabled(false)

    /** Persist aggregate-only reports before any network attempt. Also refresh an unsent NRS. */
    suspend fun enqueueReports(reports: List<CareReport>): Int = mutex.withLock {
        if (locked || !state.optBoolean("auto_enabled")) return@withLock 0
        val pseudonym = devicePseudonym()
        enqueueObservations(reports.mapNotNull {
            ResearchObservationFactory.from(it, pseudonym, "research-v1") })
    }

    /** Historical sessions without a care report contribute measured aggregates only. */
    suspend fun enqueueLegacySessions(sessions: List<SessionRecord>): Int = mutex.withLock {
        if (locked || !state.optBoolean("auto_enabled")) return@withLock 0
        val pseudonym = devicePseudonym()
        enqueueObservations(sessions.mapNotNull {
            ResearchObservationFactory.fromLegacySession(it, pseudonym, "research-v1") })
    }

    /** The caller holds mutex; the persisted queue and sent IDs are the dedup journal. */
    private fun enqueueObservations(items: List<ResearchObservation>): Int {
        val queue = state.optJSONArray("queue") ?: JSONArray().also { state.put("queue", it) }
        val sent = state.optJSONArray("sent_report_ids") ?: JSONArray().also { state.put("sent_report_ids", it) }
        val sentIds = (0 until sent.length()).mapTo(hashSetOf()) { sent.getString(it) }
        val queued = (0 until queue.length()).associate {
            queue.getJSONObject(it).getString("report_id") to it }.toMutableMap()
        var changed = false
        var added = 0
        for (item in items) {
            val id = item.reportId.toString()
            if (id in sentIds) continue
            val row = ResearchTransfer.row(item)
            val index = queued[id]
            if (index == null) {
                queued[id] = queue.length()
                queue.put(row)
                added++
                changed = true
            } else if (queue.getJSONObject(index).opt("subjective_fatigue_nrs") != row.opt("subjective_fatigue_nrs")) {
                queue.put(index, row)
                changed = true
            }
        }
        if (changed) save()
        mutableStatus.value = snapshot(if (queue.length() > 0)
            "자동 동기화 대기 중 · 인터넷 연결 시 전송합니다." else "자동 동기화 중")
        return added
    }

    suspend fun consentAndQueue(report: CareReport) {
        enqueueReports(listOf(report)) // Never overrides an explicit OFF.
    }

    /** Returns false for WorkManager's exponential retry. Rows stay encrypted until confirmed. */
    suspend fun retry(): Boolean = mutex.withLock {
        if (locked) return@withLock false
        if (!state.optBoolean("pending_withdrawal") &&
            (!state.optBoolean("auto_enabled") ||
                ((state.optJSONArray("queue")?.length() ?: 0) == 0 && pendingResponses().isEmpty())))
            return@withLock true
        try {
            val active = session()
            val owner = UUID.fromString(active.getString("owner_id"))
            val token = active.getString("access_token")
            if (state.optBoolean("pending_withdrawal")) {
                val rows = JSONArray(api.request("GET",
                    "/rest/v1/research_consent?owner_id=eq.$owner&select=owner_id", token))
                if (rows.length() > 0) api.request("PATCH",
                    "/rest/v1/research_consent?owner_id=eq.$owner", token,
                    JSONObject().put("withdrawn_at", java.time.Instant.now().toString()))
                state.put("pending_withdrawal", false)
                save()
                if (!state.optBoolean("auto_enabled")) {
                    mutableStatus.value = snapshot("자동 동기화 꺼짐 · 서버 동의 철회 완료")
                    return@withLock true
                }
            }
            if (!state.optBoolean("auto_enabled")) return@withLock true
            val consentRows = JSONArray(api.request("GET",
                "/rest/v1/research_consent?owner_id=eq.$owner&select=consent_version,withdrawn_at", token))
            if (consentRows.length() == 0) {
                api.request("POST", "/rest/v1/research_consent", token,
                    JSONObject().put("owner_id", owner.toString()).put("consent_version", "research-v1"))
            } else {
                val consent = consentRows.getJSONObject(0)
                if (!consent.isNull("withdrawn_at") || consent.getString("consent_version") != "research-v1")
                    api.request("PATCH", "/rest/v1/research_consent?owner_id=eq.$owner", token,
                        JSONObject().put("consent_version", "research-v1").put("withdrawn_at", JSONObject.NULL))
            }
            while (state.optBoolean("auto_enabled")) {
                val queue = state.optJSONArray("queue") ?: break
                if (queue.length() == 0) break
                val row = queue.getJSONObject(0)
                val reportId = UUID.fromString(row.getString("report_id"))
                try {
                    api.request("POST", "/rest/v1/research_observations", token,
                        JSONObject(row.toString()).put("owner_id", owner.toString()))
                } catch (e: ResearchHttpException) {
                    if (e.status != 409 || JSONArray(api.request("GET",
                        "/rest/v1/research_observations?owner_id=eq.$owner&report_id=eq.$reportId&select=id",
                        token)).length() == 0) throw e
                }
                val sent = state.optJSONArray("sent_report_ids") ?: JSONArray()
                if ((0 until sent.length()).none { sent.getString(it) == reportId.toString() })
                    sent.put(reportId.toString())
                state.put("sent_report_ids", sent)
                val remaining = JSONArray()
                for (i in 1 until queue.length()) remaining.put(queue.getJSONObject(i))
                state.put("queue", remaining)
                state.put("failed_count", 0)
                state.remove("last_error_code")
                state.put("last_synced_at_ms", System.currentTimeMillis())
                save()
                mutableStatus.value = snapshot("자동 동기화 중")
            }
            syncPendingResponses(owner, token)
            state.remove("last_error_code")
            save()
            mutableStatus.value = snapshot("웹 동기화 완료")
            true
        } catch (e: Exception) {
            state.put("failed_count", if ((state.optJSONArray("queue")?.length() ?: 0) > 0 ||
                runCatching { pendingResponses().isNotEmpty() }.getOrDefault(false)) 1 else 0)
            state.put("last_error_code", when (e) {
                is ResearchHttpException -> "HTTP_${e.status}"
                is java.io.IOException -> "NETWORK_IO"
                else -> "LOCAL_OR_AUTH"
            })
            save()
            val reason = if (e is ResearchHttpException) "서버 응답 ${e.status}" else "네트워크 또는 계정 오류"
            mutableStatus.value = snapshot("연결이 원활하지 않아요. 기록은 보관하고 잠시 후 다시 보낼게요.")
            false
        }
    }

    private fun syncPendingResponses(owner: UUID, token: String) {
        val local = SessionStore(app)
        try {
            while (state.optBoolean("auto_enabled")) {
                val page = local.pendingSubjectiveResponses()
                if (page.isEmpty()) break
                for (answer in page) {
                    if (!state.optBoolean("auto_enabled")) break
                    val reportId = UUID.fromString(answer.reportId)
                    val known = state.optJSONArray("sent_report_ids")?.let { ids ->
                        (0 until ids.length()).any { ids.getString(it) == answer.reportId }
                    } == true
                    if (!known && JSONArray(api.request("GET",
                            "/rest/v1/research_observations?owner_id=eq.$owner&report_id=eq.$reportId&select=id",
                            token)).length() == 0) error("Response report upload is pending")
                    try {
                        api.request("POST", "/rest/v1/research_subjective_responses", token,
                            ResearchTransfer.responseRow(answer, owner))
                    } catch (e: ResearchHttpException) {
                        if (e.status != 409) throw e
                    }
                    val confirmed = JSONArray(api.request("GET",
                        "/rest/v1/research_subjective_responses?owner_id=eq.$owner&id=eq.${answer.id}&select=id,report_id,received_at",
                        token))
                    if (confirmed.length() != 1 || confirmed.getJSONObject(0).getString("report_id") != answer.reportId)
                        error("Response receipt not confirmed")
                    val received = java.time.Instant.parse(confirmed.getJSONObject(0).getString("received_at")).toEpochMilli()
                    local.markSubjectiveReceived(answer.id, received)
                    state.put("last_synced_at_ms", System.currentTimeMillis())
                    state.put("failed_count", 0)
                    save()
                }
            }
        } finally { local.close() }
        runCatching { EyeCareRepository.refreshSubjective() }
    }

    private fun session(): JSONObject {
        val old = state.optJSONObject("session")
        if (old != null && old.optLong("expires_at_ms") > System.currentTimeMillis() + 60_000) return old
        val response = if (old == null) {
            JSONObject(api.request("POST", "/auth/v1/signup", null,
                JSONObject().put("data", JSONObject()).put("gotrue_meta_security", JSONObject())))
        } else {
            try {
                JSONObject(api.request("POST", "/auth/v1/token?grant_type=refresh_token", null,
                    JSONObject().put("refresh_token", old.getString("refresh_token"))))
            } catch (e: ResearchHttpException) {
                if (e.status in 400..499) throw IllegalStateException("익명 세션을 복구할 수 없습니다.")
                throw e
            }
        }
        val owner = response.getJSONObject("user").getString("id")
        if (old != null && owner != old.getString("owner_id")) error("익명 계정이 일치하지 않습니다.")
        val fresh = JSONObject().put("owner_id", owner)
            .put("access_token", response.getString("access_token"))
            .put("refresh_token", response.getString("refresh_token"))
            .put("expires_at_ms", System.currentTimeMillis() + response.getLong("expires_in") * 1000)
        state.put("session", fresh)
        save() // Refresh tokens rotate; save the replacement immediately.
        return fresh
    }
}
