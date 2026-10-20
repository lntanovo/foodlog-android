package com.foodlog.app.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Serializable
data class CloudBackupRecord(
    @SerialName("backup_id") val backupId: String,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("object_path") val objectPath: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("app_version") val appVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("device_name") val deviceName: String,
    @SerialName("dataset_id") val datasetId: String,
    @SerialName("record_counts") val recordCounts: Map<String, Int>,
    @SerialName("first_date") val firstDate: String? = null,
    @SerialName("last_date") val lastDate: String? = null,
    @SerialName("client_revision") val clientRevision: Long,
)

/** An identity includes a login generation, so signing out and back in also invalidates in-flight work. */
internal interface CloudBackupCredentials {
    suspend fun accessToken(expected: CloudIdentity): String
    fun requireIdentity(expected: CloudIdentity)
}

/** Immutable objects plus append-only metadata; no update, overwrite, or deletion operation exists here. */
class CloudBackupClient internal constructor(
    private val credentials: CloudBackupCredentials,
    private val config: CloudConfig = CloudConfig(),
    private val jsonTransport: CloudJsonTransport = CloudHttp(config),
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    constructor(sessions: CloudSessionManager, baseUrl: String = CloudConfig().baseUrl) : this(
        object : CloudBackupCredentials {
            override suspend fun accessToken(expected: CloudIdentity) = sessions.accessToken(expected)
            override fun requireIdentity(expected: CloudIdentity) = sessions.requireIdentity(expected)
        }, CloudConfig(baseUrl = baseUrl)
    )

    suspend fun list(identity: CloudIdentity, offset: Int = 0): List<CloudBackupRecord> {
        requireIdentity(identity)
        if (offset < 0) throw CloudException("备份列表页码无效")
        val response = request(identity, "GET", "$TABLE?select=*&order=created_at.desc,backup_id.desc&limit=20&offset=$offset")
        val records = parseRecords(response, identity)
        if (records.size > 20 || records.map { it.backupId }.toSet().size != records.size) {
            throw CloudException("云备份列表格式异常")
        }
        return records
    }

    suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File): CloudBackupRecord {
        prepareObject(identity, record, file)
        return publishRecord(identity, record)
    }

    /** Null means another device advanced the cloud head. Existing snapshots are never replaced. */
    suspend fun uploadAutomatic(identity: CloudIdentity, record: CloudBackupRecord, file: File, expectedHead: String?): CloudBackupRecord? {
        require(expectedHead == null || UUID.fromString(expectedHead).toString() == expectedHead)
        prepareObject(identity, record, file)
        val encoded = json.encodeToJsonElement(record).jsonObject
        val response = request(identity, "POST", "/v1/rdb/rest/rpc/foodlog_commit_backup", buildJsonObject {
            put("payload", JsonObject(encoded.filterKeys { it != "owner_id" && it != "created_at" }))
            put("expected_head", expectedHead?.let(::JsonPrimitive) ?: JsonNull)
        }) as? JsonObject ?: throw CloudException("云服务未确认自动备份，请稍后重试")
        return when (response["status"]?.jsonPrimitive?.contentOrNull) {
            "conflict" -> null
            "saved" -> parseRecords(JsonArray(listOf(response["record"] ?: JsonNull)), identity).single().also {
                ensureSameMetadata(record, it)
            }
            else -> throw CloudException("云服务未确认自动备份，请稍后重试")
        }
    }

    private suspend fun prepareObject(identity: CloudIdentity, record: CloudBackupRecord, file: File) {
        requireIdentity(identity)
        validateRecord(record, identity, fromServer = false)
        verifyFile(identity, record, file)
        // A failed acknowledgement may leave an immutable object behind. Verify its bytes before reusing it.
        try {
            uploadObject(identity, record, file)
        } catch (error: CloudException) {
            if (error.httpStatus != null && error.httpStatus != 409 && error.httpStatus !in 500..599) throw error
            try {
                verifyExistingObject(identity, record, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (verification: IOException) {
                // An early 409 may close a large upload stream before its status is readable.
                // A network error alone is never proof that the object was safely stored.
                if (error.httpStatus == 409) throw verification else throw error
            }
        }
        requireIdentity(identity)
    }

    private suspend fun publishRecord(identity: CloudIdentity, record: CloudBackupRecord): CloudBackupRecord {
        val encoded = json.encodeToJsonElement(record).jsonObject
        val insert = JsonObject(encoded.filterKeys { it != "owner_id" && it != "created_at" })
        try {
            val response = request(identity, "POST", TABLE, insert, mapOf("Prefer" to "return=representation"))
            val saved = parseRecords(response, identity).singleOrNull()
                ?: throw CloudException("云服务未确认备份，请重试")
            ensureSameMetadata(record, saved)
            return saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            requireIdentity(identity)
            // A conflict or lost response must never create a duplicate or replace a previous version.
            if (error is CloudException && error.httpStatus != null && error.httpStatus !in 500..599 && error.httpStatus != 409) throw error
            val existing = try { find(identity, record.backupId) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) { null }
            if (existing != null) {
                ensureSameMetadata(record, existing)
                return existing
            }
            throw error
        }
    }

    /** The existing destination survives every failed transfer; only fully verified bytes replace it. */
    suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File) {
        requireIdentity(identity)
        validateRecord(record, identity, fromServer = true)
        downloadVerified(identity, record, file)
    }

    private suspend fun find(identity: CloudIdentity, id: String): CloudBackupRecord? {
        val response = request(identity, "GET", "$TABLE?select=*&backup_id=eq.$id&limit=2")
        val records = parseRecords(response, identity)
        if (records.size > 1 || records.any { it.backupId != id }) throw CloudException("云备份标识冲突")
        return records.singleOrNull()
    }

    private suspend fun request(
        identity: CloudIdentity, method: String, path: String, body: JsonElement? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement {
        requireIdentity(identity)
        val token = credentials.accessToken(identity)
        requireIdentity(identity)
        val response = jsonTransport.requestJson(CloudJsonRequest(method, path, body, token, headers))
        requireIdentity(identity)
        return response
    }

    private fun parseRecords(element: JsonElement, identity: CloudIdentity): List<CloudBackupRecord> {
        val array = element as? JsonArray ?: throw CloudException("云备份列表格式异常")
        return array.map {
            val record = try { json.decodeFromJsonElement<CloudBackupRecord>(it) }
            catch (_: Exception) { throw CloudException("云备份记录格式异常") }
            validateRecord(record, identity, fromServer = true)
            record
        }
    }

    private fun requireIdentity(identity: CloudIdentity) {
        if (!identity.uid.matches(Regex("[A-Za-z0-9_-]{1,64}"))) throw CloudException("云账号标识无效，请重新登录")
        credentials.requireIdentity(identity)
    }

    private fun validateRecord(record: CloudBackupRecord, identity: CloudIdentity, fromServer: Boolean) {
        requireIdentity(identity)
        fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        fun validDate(value: String?) = value == null || runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
        val valid = validUuid(record.backupId) && validUuid(record.datasetId) &&
            record.objectPath == "${identity.uid}/${record.backupId}.foodlog.gz" &&
            (record.ownerId == null && !fromServer || record.ownerId == identity.uid) &&
            record.sha256.matches(Regex("[0-9a-f]{64}")) &&
            record.sizeBytes in 1..CloudConfig.MAX_COMPRESSED_BYTES &&
            record.schemaVersion in 1..3 && record.appVersion.length in 1..40 &&
            record.deviceName.length in 1..80 && record.clientRevision >= 0 &&
            record.recordCounts.size <= 32 && record.recordCounts.all { (key, value) -> key.length in 1..64 && value >= 0 } &&
            validDate(record.firstDate) && validDate(record.lastDate) &&
            (record.firstDate == null || record.lastDate == null || record.firstDate <= record.lastDate)
        if (!valid) throw CloudException("云备份记录无效或不属于当前账号")
        if (fromServer && (record.createdAt == null || runCatching { OffsetDateTime.parse(record.createdAt) }.isFailure)) {
            throw CloudException("云备份创建时间无效")
        }
    }

    private fun ensureSameMetadata(expected: CloudBackupRecord, actual: CloudBackupRecord) {
        if (expected.copy(ownerId = null, createdAt = null) != actual.copy(ownerId = null, createdAt = null)) {
            throw CloudException("备份标识已有不同内容，请重新创建备份")
        }
    }

    private suspend fun verifyFile(identity: CloudIdentity, record: CloudBackupRecord, file: File) = withContext(Dispatchers.IO) {
        requireIdentity(identity)
        if (!file.isFile || file.length() != record.sizeBytes) throw CloudException("备份文件大小不符，请重新生成")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> stream(identity, input, record.sizeBytes) { bytes, count -> digest.update(bytes, 0, count) } }
        if (digest.hex() != record.sha256) throw CloudException("备份文件校验失败，请重新生成")
        requireIdentity(identity)
    }

    private suspend fun uploadObject(identity: CloudIdentity, record: CloudBackupRecord, file: File) {
        val token = credentials.accessToken(identity)
        requireIdentity(identity)
        withConnection(identity, URL(config.baseUrl.trimEnd('/') + "/v1/storages/object/${config.bucket}/${record.objectPath}")) { connection ->
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/gzip")
            connection.setRequestProperty("x-upsert", "false")
            connection.setFixedLengthStreamingMode(record.sizeBytes)
            connection.doOutput = true
            val digest = MessageDigest.getInstance("SHA-256")
            connection.outputStream.use { output ->
                file.inputStream().use { input -> stream(identity, input, record.sizeBytes) { bytes, count ->
                    digest.update(bytes, 0, count)
                    output.write(bytes, 0, count)
                } }
            }
            if (digest.hex() != record.sha256) throw CloudException("备份文件已变化，请重新生成")
            requireIdentity(identity)
            val status = connection.responseCode
            if (status !in 200..299) throw CloudHttp.safeError(status, null)
            // Drain only a bounded acknowledgement. Raw storage response bodies are never shown or logged.
            connection.inputStream.use { input -> readSmallResponse(identity, input) }
            requireIdentity(identity)
        }
    }

    private suspend fun verifyExistingObject(identity: CloudIdentity, record: CloudBackupRecord, source: File) {
        val directory = source.absoluteFile.parentFile ?: throw CloudException("本机备份目录不可用")
        var verificationFile: File? = null
        try {
            withContext(Dispatchers.IO) { verificationFile = File.createTempFile("foodlog-existing-", ".gz", directory) }
            downloadVerified(identity, record, requireNotNull(verificationFile))
        } finally { withContext(NonCancellable + Dispatchers.IO) { verificationFile?.delete() } }
    }

    private suspend fun signedUrl(identity: CloudIdentity, record: CloudBackupRecord): URL {
        val response = request(identity, "POST", "/v1/storages/object/sign/${config.bucket}/${record.objectPath}",
            buildJsonObject { put("expiresIn", 300) }) as? JsonObject ?: throw CloudException("无法获取备份下载地址")
        fun text(key: String) = (response[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val value = text("fullSignedURL") ?: text("signedURL") ?: throw CloudException("无法获取备份下载地址")
        val uri = try { URI(config.baseUrl.trimEnd('/') + "/").resolve(URI(value)) }
        catch (_: Exception) { throw CloudException("云备份下载地址无效") }
        val base = URI(config.baseUrl)
        val expectedPath = "/v1/storages/object/sign/${config.bucket}/${record.objectPath}"
        if (uri.scheme != "https" || !uri.host.equals(base.host, true) || uri.port != base.port ||
            uri.userInfo != null || uri.fragment != null || uri.rawPath != expectedPath || uri.rawQuery.isNullOrBlank()) {
            throw CloudException("云备份下载地址不可信")
        }
        return try { uri.toURL() } catch (_: Exception) { throw CloudException("云备份下载地址无效") }
    }

    private suspend fun downloadVerified(identity: CloudIdentity, record: CloudBackupRecord, destination: File) {
        val url = signedUrl(identity, record)
        requireIdentity(identity)
        val directory = destination.absoluteFile.parentFile ?: throw CloudException("本机备份目录不可用")
        var partialFile: File? = null
        try {
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory || destination.isDirectory) throw CloudException("本机备份目录不可用")
                partialFile = File.createTempFile("foodlog-download-", ".part", directory)
            }
            val partial = requireNotNull(partialFile)
            // Deliberately unauthenticated, including on redirects: a signed URL already grants file access.
            withConnection(identity, url) { connection ->
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status != 200) throw CloudHttp.safeError(status, null)
                val declared = connection.contentLengthLong
                if (declared >= 0 && declared != record.sizeBytes) throw CloudException("云备份文件大小与记录不符")
                val digest = MessageDigest.getInstance("SHA-256")
                partial.outputStream().use { output ->
                    connection.inputStream.use { input -> stream(identity, input, record.sizeBytes) { bytes, count ->
                        digest.update(bytes, 0, count)
                        output.write(bytes, 0, count)
                    } }
                }
                if (digest.hex() != record.sha256) throw CloudException("云备份校验失败，文件可能损坏")
            }
            requireIdentity(identity)
            withContext(Dispatchers.IO) {
                requireIdentity(identity)
                try { Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            requireIdentity(identity)
        } finally { withContext(NonCancellable + Dispatchers.IO) { partialFile?.delete() } }
    }

    private suspend fun <T> withConnection(identity: CloudIdentity, url: URL, block: suspend (HttpURLConnection) -> T): T = coroutineScope {
        requireIdentity(identity)
        val connection = try { withContext(Dispatchers.IO) { openConnection(url) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { throw CloudException("云备份传输失败，请检查网络后重试") }
        connection.connectTimeout = CloudHttp.CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = CloudHttp.READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        val disconnectOnCancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try { withContext(Dispatchers.IO) { requireIdentity(identity); block(connection) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: CloudException) { currentCoroutineContext().ensureActive(); throw error }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); throw CloudException("云备份传输失败，请检查网络后重试") }
        finally { connection.disconnect(); disconnectOnCancellation.cancel() }
    }

    private suspend fun stream(identity: CloudIdentity, input: InputStream, expectedSize: Long, sink: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            requireIdentity(identity)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > expectedSize || total > CloudConfig.MAX_COMPRESSED_BYTES) throw CloudException("云备份文件超过大小限制")
            sink(buffer, count)
        }
        if (total != expectedSize) throw CloudException("云备份文件不完整，请重新下载")
        requireIdentity(identity)
    }

    private suspend fun readSmallResponse(identity: CloudIdentity, input: InputStream) {
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            requireIdentity(identity)
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            if (total > CloudHttp.MAX_JSON_BYTES) throw CloudException("云服务返回内容过大")
        }
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val TABLE = "/v1/rdb/rest/foodlog_backups"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
