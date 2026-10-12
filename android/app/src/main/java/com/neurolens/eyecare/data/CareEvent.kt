package com.neurolens.eyecare.data

import org.json.JSONObject
import java.util.UUID

data class CareEvent(val id: String = UUID.randomUUID().toString(), val sessionId: String,
    val at: Long, val type: String, val detail: String = "")

object CareEventCodec {
    fun encode(e: CareEvent) = JSONObject().put("id", e.id).put("sessionId", e.sessionId)
        .put("at", e.at).put("type", e.type).put("detail", e.detail)
    fun decode(j: JSONObject) = CareEvent(j.getString("id"), j.getString("sessionId"),
        j.getLong("at"), j.getString("type"), j.optString("detail", ""))
}
