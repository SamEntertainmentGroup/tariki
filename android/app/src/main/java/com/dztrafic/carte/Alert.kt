package com.dztrafic.carte

import org.json.JSONObject

data class Alert(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    val title: String,
    val confirmations: Int,
    val ageSeconds: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("lat", latitude)
        .put("lng", longitude)
        .put("type", type)
        .put("title", title)
        .put("confirmations", confirmations)
        .put("age", ageSeconds)

    companion object {
        fun fromJson(json: JSONObject) = Alert(
            id = json.getLong("id"),
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
            type = json.optString("type"),
            title = json.optString("title"),
            confirmations = json.optInt("confirmations", 1),
            ageSeconds = json.optLong("age", 0),
        )
    }
}

data class PendingReport(
    val type: String,
    val latitude: Double,
    val longitude: Double,
    val createdAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("lat", latitude)
        .put("lng", longitude)
        .put("createdAt", createdAtMs)

    companion object {
        fun fromJson(json: JSONObject) = PendingReport(
            type = json.getString("type"),
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
            createdAtMs = json.optLong("createdAt", 0),
        )
    }
}

object AlertTypes {
    const val ROUTE = "route"
    const val ACCIDENT = "accident"
    const val DANGER = "danger"

    val all = listOf(ROUTE, ACCIDENT, DANGER)

    fun emoji(type: String) = when (type) {
        ROUTE -> "🚧"
        ACCIDENT -> "💥"
        else -> "⚠️"
    }

    fun label(type: String) = when (type) {
        ROUTE -> "طريق مكسرة"
        ACCIDENT -> "حادث مرور"
        else -> "خطر غير محدد"
    }
}
