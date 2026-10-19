package com.foodlog.app.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class CloudAuthTokens(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "CloudAuthTokens(credentials=redacted)"
}

internal interface CloudAuthGateway {
    suspend fun login(username: String, password: String): CloudAuthTokens
    suspend fun refresh(refreshToken: String): CloudAuthTokens
    suspend fun logout(accessToken: String)
}

internal class CloudAuth(
    private val http: CloudJsonTransport,
    private val deviceId: () -> String,
) : CloudAuthGateway {
    override suspend fun login(username: String, password: String): CloudAuthTokens = tokens(
        request("/auth/v1/signin", buildJsonObject {
            put("username", username)
            put("password", password)
        })
    )

    override suspend fun refresh(refreshToken: String): CloudAuthTokens = tokens(
        request("/auth/v1/token", buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        })
    )

    override suspend fun logout(accessToken: String) {
        request("/auth/v1/user/signout", buildJsonObject {}, accessToken)
    }

    private suspend fun request(path: String, body: JsonElement, token: String? = null): JsonElement =
        http.requestJson(CloudJsonRequest("POST", path, body, token, mapOf("x-device-id" to deviceId())))

    private fun tokens(value: JsonElement): CloudAuthTokens {
        val body = value as? JsonObject ?: throw CloudException("云端登录返回格式异常，请稍后重试")
        fun text(key: String, limit: Int): String {
            val text = (body[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank() || text.length > limit) throw CloudException("云端登录返回格式异常，请稍后重试")
            return text
        }
        val seconds = runCatching { body["expires_in"]?.jsonPrimitive?.longOrNull }.getOrNull()
        if (seconds == null || seconds !in 1..31_536_000) throw CloudException("云端登录有效期异常，请稍后重试")
        if ((body["token_type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull != "Bearer") {
            throw CloudException("云端登录返回格式异常，请稍后重试")
        }
        return CloudAuthTokens(text("sub", 128), text("access_token", 8192), text("refresh_token", 2048), seconds)
    }
}
