package com.dztrafic.carte

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TIMEOUT_MS = 10_000
private const val USER_AGENT = "DZCarte/0.2"

class AlertsApi(private val endpoint: String) {

    class Result(val success: Boolean, val message: String, val confirmed: Boolean)

    fun fetchAlerts(): List<Alert> {
        val response = request("GET", "?action=dztrafic_get_alerts&_=${System.currentTimeMillis()}", null)
        val data = response.optJSONArray("data") ?: throw IOException("Réponse invalide")
        return (0 until data.length()).mapNotNull { index ->
            runCatching { Alert.fromJson(data.getJSONObject(index)) }.getOrNull()
        }
    }

    fun sendReport(report: PendingReport, deviceId: String): Result {
        val nonce = fetchNonce("dztrafic_get_nonce")
        val form = mapOf(
            "action" to "dztrafic_add_alert",
            "nonce" to nonce,
            "device_id" to deviceId,
            "type" to report.type,
            "latitude" to report.latitude.toString(),
            "longitude" to report.longitude.toString(),
        )
        return toResult(request("POST", "", form))
    }

    fun confirm(alertId: Long, deviceId: String): Result {
        val nonce = fetchNonce("dztrafic_get_confirm_nonce")
        val form = mapOf(
            "action" to "dztrafic_confirm_alert",
            "nonce" to nonce,
            "id" to alertId.toString(),
            "device_id" to deviceId,
        )
        return toResult(request("POST", "", form))
    }

    private fun fetchNonce(action: String): String {
        val response = request("GET", "?action=$action&_=${System.currentTimeMillis()}", null)
        val nonce = response.optJSONObject("data")?.optString("nonce").orEmpty()
        if (!response.optBoolean("success") || nonce.isEmpty()) throw IOException("Nonce invalide")
        return nonce
    }

    private fun toResult(response: JSONObject): Result {
        val data = response.optJSONObject("data")
        return Result(
            success = response.optBoolean("success"),
            message = data?.optString("message").orEmpty(),
            confirmed = data?.optBoolean("confirmation", false) ?: false,
        )
    }

    private fun request(method: String, query: String, form: Map<String, String>?): JSONObject {
        val connection = URL(endpoint + query).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val body = form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("HTTP $code : réponse illisible") }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
