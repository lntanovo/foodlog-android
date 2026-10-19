package com.foodlog.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

fun interface CloudJsonTransport {
    suspend fun requestJson(request: CloudJsonRequest): JsonElement
}

data class CloudJsonRequest(
    val method: String,
    val path: String,
    val body: JsonElement? = null,
    val bearerToken: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "CloudJsonRequest(method=$method, credentials=redacted)"
}

/** HTTPS only; never follows a redirect carrying a user's credentials. */
class CloudHttp(private val config: CloudConfig = CloudConfig()) : CloudJsonTransport {
    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonElement? = null,
        bearerToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonElement = requestJson(CloudJsonRequest(method, path, body, bearerToken, extraHeaders))

    override suspend fun requestJson(request: CloudJsonRequest): JsonElement = withContext(Dispatchers.IO) {
        if (!request.path.startsWith('/') || request.path.startsWith("//") ||
            request.path.any { it == '\r' || it == '\n' }) {
            throw CloudException("云服务请求地址无效")
        }
        val payload = request.body?.toString()?.toByteArray(Charsets.UTF_8)
        if (payload != null && payload.size > MAX_JSON_BYTES) throw CloudException("云服务请求内容过大")
        val connection = (URL(config.baseUrl.trimEnd('/') + request.path).openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            request.bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            request.extraHeaders.forEach { (key, value) ->
                if (key.equals("Authorization", true) || key.equals("Host", true) ||
                    key.equals("Content-Length", true) || key.any { it == '\r' || it == '\n' } ||
                    value.any { it == '\r' || it == '\n' }) throw CloudException("云服务请求头无效")
                connection.setRequestProperty(key, value)
            }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            currentCoroutineContext().ensureActive()
            val status = connection.responseCode
            val source = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = source?.use { input ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_JSON_BYTES) throw CloudException("云服务返回内容过大", status)
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } ?: byteArrayOf()
            val decoded = if (bytes.isEmpty()) JsonNull else try {
                json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                if (status !in 200..299) throw safeError(status, null)
                throw CloudException("云服务返回格式异常，请稍后重试", status)
            }
            if (status !in 200..299) throw safeError(status, decoded)
            decoded
        } catch (error: CloudException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw CloudException("连接云服务超时，请检查网络后重试")
        } catch (_: SSLException) {
            throw CloudException("无法安全连接云服务，请检查网络和手机时间")
        } catch (_: IOException) {
            throw CloudException("无法连接云服务，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_JSON_BYTES = 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        /** Do not expose raw provider errors: they may echo request credentials or account details. */
        fun safeError(status: Int, body: JsonElement?): CloudException {
            val objectBody = body as? JsonObject
            val code = (objectBody?.get("error") ?: objectBody?.get("code"))
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
            val message = when (code) {
                "invalid_username_or_password" -> "用户名或密码不正确"
                "captcha_required", "invalid_status" -> "登录尝试过多，请稍后重试或在云端完成验证"
                "password_not_set" -> "此账号尚未设置密码，请先在云端设置"
                "invalid_grant", "invalid_token", "UNAUTHORIZED" -> "登录已失效，请重新登录"
                "STORAGE_BUCKET_NOT_FOUND" -> "云备份存储尚未配置，请稍后重试"
                "STORAGE_PERMISSION_DENIED", "PERMISSION_DENIED", "STORAGE_EXCEED_AUTHORITY" -> "当前账号没有访问这份云备份的权限"
                else -> when (status) {
                    401 -> "登录已失效，请重新登录"
                    403 -> "当前账号没有访问这份云备份的权限"
                    404 -> "云服务资源尚未配置，请稍后重试"
                    409 -> "这份云备份已经存在，未覆盖原备份"
                    413 -> "云备份文件过大"
                    429 -> "请求过于频繁，请稍后重试"
                    501 -> "云端尚未开启用户名密码登录"
                    in 500..599 -> "云服务暂时不可用，请稍后重试"
                    else -> "云服务请求失败（HTTP $status）"
                }
            }
            return CloudException(message, status)
        }
    }
}
