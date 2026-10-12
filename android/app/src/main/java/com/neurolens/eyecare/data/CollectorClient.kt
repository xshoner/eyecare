package com.neurolens.eyecare.data

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

object CollectorClient {
    data class Connection(val endpoint: String, val token: String)
    fun parse(address: String): Connection {
        val uri = URI(address.trim())
        require(uri.scheme == "http" && uri.userInfo == null && uri.query == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "수집기에서 표시한 로컬 연결 주소를 그대로 넣어 주세요." }
        val parts = uri.host?.split('.')?.map { it.toIntOrNull() ?: -1 } ?: emptyList()
        require(parts.size == 4 && parts.all { it in 0..255 } &&
            (parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))) {
            "같은 네트워크의 사설 IPv4 주소만 연결할 수 있어요."
        }
        require(uri.host == parts.joinToString(".")) { "사설 IPv4 주소를 그대로 넣어 주세요." }
        require(uri.port in 1..65535) { "수집기의 포트 번호를 확인해 주세요." }
        val token = uri.fragment ?: ""
        require(token.matches(Regex("[a-f0-9]{48}"))) { "주소 끝의 연결 키까지 복사해 주세요." }
        return Connection("http://${uri.host}:${uri.port}/api/import", token)
    }

    fun send(address: String, document: JSONObject): Int {
        val settings = parse(address)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 10 * 1024 * 1024) { "보고서가 너무 커요. 개별 기록으로 보내 주세요." }
        val connection = URI(settings.endpoint).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000; connection.readTimeout = 10000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            require(status == 200) { if (status == 401) "연결 키가 바뀌었어요. 수집기 주소를 다시 등록해 주세요." else "수집기가 보고서를 받지 못했어요 ($status)." }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val output = StringBuilder(); val buffer = CharArray(2048)
                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.length + count <= 65536); output.append(buffer, 0, count) }
                output.toString()
            }
            val result = JSONObject(response)
            require(result.optInt("conflicts", 0) == 0) { "전송됐지만 충돌 기록이 있어요. 노트북 집계 화면을 확인해 주세요." }
            return result.getInt("count")
        } finally { connection.disconnect() }
    }
}
