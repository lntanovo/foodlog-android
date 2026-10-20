package com.foodlog.app

import com.foodlog.app.cloud.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudBackupClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = CloudIdentity("2112049876648939522", "synthetic-user", "synthetic-generation")
    private val json = Json { encodeDefaults = true }
    private val bytes = byteArrayOf(31, -117, 8, 0, 1, 2, 3, 4)
    private val config = CloudConfig()

    private class Credentials(var current: CloudIdentity) : CloudBackupCredentials {
        var checks = 0
        override fun requireIdentity(expected: CloudIdentity) {
            checks++
            if (expected != current) throw CloudException("账号已变化，请重新操作")
        }
        override suspend fun accessToken(expected: CloudIdentity): String {
            requireIdentity(expected)
            return "synthetic-access-token"
        }
    }

    private class JsonTransport(val handler: suspend (CloudJsonRequest) -> JsonElement) : CloudJsonTransport {
        val requests = mutableListOf<CloudJsonRequest>()
        override suspend fun requestJson(request: CloudJsonRequest): JsonElement {
            requests += request
            return handler(request)
        }
    }

    private class Connection(
        url: URL,
        private val status: Int = 200,
        private val response: InputStream = ByteArrayInputStream(byteArrayOf()),
        private val declaredLength: Long = -1,
        private val responseFailure: IOException? = null,
    ) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var onDisconnect: (() -> Unit)? = null
        override fun connect() {}
        override fun disconnect() { disconnected = true; onDisconnect?.invoke() }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = response
        override fun getResponseCode(): Int { responseFailure?.let { throw it }; return status }
        override fun getContentLengthLong() = declaredLength
    }

    private fun record(content: ByteArray = bytes) = CloudBackupRecord(
        backupId = "f51c64cb-e825-427a-b2d3-e791cfde3899",
        objectPath = "${identity.uid}/f51c64cb-e825-427a-b2d3-e791cfde3899.foodlog.gz",
        sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) },
        sizeBytes = content.size.toLong(), appVersion = "1.2.2", schemaVersion = 3,
        deviceName = "合成测试设备", datasetId = "75fa86fc-3d7b-4cfd-83e3-888eab70735c",
        recordCounts = mapOf("bodies" to 4, "meals" to 0), firstDate = "2026-10-16", lastDate = "2026-10-19", clientRevision = 7,
    )

    private fun saved(record: CloudBackupRecord = record()) = record.copy(ownerId = identity.uid, createdAt = "2026-10-19T05:00:00+00:00")
    private fun rows(record: CloudBackupRecord = saved()) = JsonArray(listOf(json.encodeToJsonElement(record)))
    private fun signed(record: CloudBackupRecord = record(), host: String? = null) = buildJsonObject {
        put("fullSignedURL", "https://${host ?: URL(config.baseUrl).host}/v1/storages/object/sign/${config.bucket}/${record.objectPath}?token=synthetic-signature")
    }
    private fun source() = temporary.newFile("source-${System.nanoTime()}.gz").apply { writeBytes(bytes) }
    private fun destination() = temporary.newFile("destination-${System.nanoTime()}.gz").apply { writeText("existing destination") }
    private fun assertNoPartial() { assertTrue(temporary.root.listFiles()!!.none { it.extension == "part" || it.name.startsWith("foodlog-existing-") }) }
    private suspend fun failure(block: suspend () -> Unit): CloudException {
        try { block() } catch (error: CloudException) { return error }
        throw AssertionError("Expected CloudException")
    }

    @Test fun automaticUploadUsesAtomicCompareAndAppendWithoutClientOwnedIdentityFields() = runBlocking {
        val baseId = "477f5b62-5e24-46d6-b4e7-ddd79721613c"
        val transport = JsonTransport { request ->
            assertEquals("/v1/rdb/rest/rpc/foodlog_commit_backup", request.path)
            val body = request.body!!.jsonObject
            assertEquals(baseId, body.getValue("expected_head").jsonPrimitive.content)
            val payload = body.getValue("payload").jsonObject
            assertFalse(payload.containsKey("owner_id")); assertFalse(payload.containsKey("created_at"))
            buildJsonObject { put("status", "saved"); put("record", json.encodeToJsonElement(saved())) }
        }
        val client = CloudBackupClient(Credentials(identity), config, transport) { Connection(it) }
        assertEquals(saved(), client.uploadAutomatic(identity, record(), source(), baseId))
        assertEquals(1, transport.requests.size)
    }

    @Test fun automaticConflictReturnsNoSuccessAndDoesNotUseTheUnconditionalInsertPath() = runBlocking {
        val transport = JsonTransport { request ->
            assertEquals("/v1/rdb/rest/rpc/foodlog_commit_backup", request.path)
            assertEquals(JsonNull, request.body!!.jsonObject["expected_head"])
            buildJsonObject { put("status", "conflict"); put("head", "477f5b62-5e24-46d6-b4e7-ddd79721613c") }
        }
        val client = CloudBackupClient(Credentials(identity), config, transport) { Connection(it) }
        assertNull(client.uploadAutomatic(identity, record(), source(), null))
        assertEquals(1, transport.requests.size)
    }

    @Test fun uploadUsesImmutableBinaryPostAndServerOwnedMetadataThenListsStablePages() = runBlocking {
        val auth = Credentials(identity)
        val transport = JsonTransport { request ->
            assertEquals("synthetic-access-token", request.bearerToken)
            if (request.method == "POST") {
                val body = request.body!!.jsonObject
                assertFalse(body.containsKey("owner_id"))
                assertFalse(body.containsKey("created_at"))
                assertEquals(record().objectPath, body.getValue("object_path").jsonPrimitive.content)
                assertEquals(7L, body.getValue("client_revision").jsonPrimitive.long)
                assertEquals("return=representation", request.extraHeaders["Prefer"])
            } else {
                assertEquals("/v1/rdb/rest/foodlog_backups?select=*&order=created_at.desc,backup_id.desc&limit=20&offset=20", request.path)
            }
            rows()
        }
        lateinit var connection: Connection
        val client = CloudBackupClient(auth, config, transport) { url -> Connection(url).also { connection = it } }
        assertEquals(saved(), client.upload(identity, record(), source()))
        assertArrayEquals(bytes, connection.sent.toByteArray())
        assertEquals("POST", connection.requestMethod)
        assertEquals("false", connection.getRequestProperty("x-upsert"))
        assertEquals("application/gzip", connection.getRequestProperty("Content-Type"))
        assertEquals("Bearer synthetic-access-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertEquals(listOf(saved()), client.list(identity, offset = 20))
        assertTrue(auth.checks >= 10)
    }

    @Test fun duplicateObjectAndLostMetadataAcknowledgementRecoverOnlyAfterByteVerification() = runBlocking {
        val auth = Credentials(identity)
        val order = mutableListOf<String>()
        val transport = JsonTransport { request ->
            when {
                request.path.contains("/object/sign/") -> { order += "sign"; signed() }
                request.method == "POST" -> { order += "insert"; throw CloudException("合成丢失确认") }
                else -> { order += "find"; assertTrue(request.path.contains("backup_id=eq.${record().backupId}")); rows() }
            }
        }
        val connections = mutableListOf<Connection>()
        val client = CloudBackupClient(auth, config, transport) { url ->
            val connection = if (connections.isEmpty()) Connection(url, status = 409)
                else Connection(url, response = ByteArrayInputStream(bytes), declaredLength = bytes.size.toLong())
            connections += connection
            connection
        }
        assertEquals(saved(), client.upload(identity, record(), source()))
        assertEquals(listOf("sign", "insert", "find"), order)
        assertEquals(2, connections.size)
        assertEquals("GET", connections[1].requestMethod)
        assertNull(connections[1].getRequestProperty("Authorization"))
        assertNoPartial()
    }

    @Test fun duplicateObjectWithDifferentBytesNeverRegistersMetadata() = runBlocking {
        val auth = Credentials(identity)
        val transport = JsonTransport { request ->
            assertTrue(request.path.contains("/object/sign/"))
            signed()
        }
        var opened = 0
        val client = CloudBackupClient(auth, config, transport) { url ->
            if (opened++ == 0) Connection(url, status = 409)
            else Connection(url, response = ByteArrayInputStream(bytes.reversedArray()))
        }
        failure { client.upload(identity, record(), source()) }
        assertEquals(1, transport.requests.size)
        assertNoPartial()
    }

    @Test fun lostObjectAcknowledgementIsRecoveredByVerifyingStoredBytesBeforeMetadata() = runBlocking {
        val auth = Credentials(identity)
        val order = mutableListOf<String>()
        val transport = JsonTransport { request ->
            if (request.path.contains("/object/sign/")) { order += "sign"; signed() }
            else { order += "insert"; rows() }
        }
        var opened = 0
        val client = CloudBackupClient(auth, config, transport) { url ->
            if (opened++ == 0) Connection(url, responseFailure = IOException("synthetic lost object acknowledgement"))
            else Connection(url, response = ByteArrayInputStream(bytes))
        }
        assertEquals(saved(), client.upload(identity, record(), source()))
        assertEquals(listOf("sign", "insert"), order)
        assertEquals(2, opened)
        assertNoPartial()
    }

    @Test fun lostInsertResponseCannotResolveToDifferentMetadata() = runBlocking {
        val auth = Credentials(identity)
        val transport = JsonTransport { request ->
            if (request.method == "POST") throw CloudException("合成丢失确认")
            rows(saved().copy(clientRevision = 8))
        }
        val client = CloudBackupClient(auth, config, transport) { Connection(it) }
        val error = failure { client.upload(identity, record(), source()) }
        assertTrue(error.message!!.contains("不同内容"))
    }

    @Test fun failedDownloadsPreserveDestinationAndCleanPartialFiles() = runBlocking {
        val auth = Credentials(identity)
        for (badBytes in listOf(bytes.reversedArray(), bytes.copyOf(3), bytes + byteArrayOf(9))) {
            val transport = JsonTransport { signed() }
            val client = CloudBackupClient(auth, config, transport) { Connection(it, response = ByteArrayInputStream(badBytes)) }
            val target = destination()
            failure { client.download(identity, saved(), target) }
            assertEquals("existing destination", target.readText())
            assertNoPartial()
        }
    }

    @Test fun successfulDownloadUsesSignedUrlWithoutBearerAndAtomicallyReplacesDestination() = runBlocking {
        val auth = Credentials(identity)
        val transport = JsonTransport { request ->
            assertEquals("synthetic-access-token", request.bearerToken)
            assertEquals(300, request.body!!.jsonObject.getValue("expiresIn").jsonPrimitive.int)
            signed()
        }
        lateinit var connection: Connection
        val client = CloudBackupClient(auth, config, transport) { url ->
            Connection(url, response = ByteArrayInputStream(bytes), declaredLength = bytes.size.toLong()).also { connection = it }
        }
        val target = destination()
        client.download(identity, saved(), target)
        assertArrayEquals(bytes, target.readBytes())
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertNoPartial()
    }

    @Test fun hostileSignedLinksAndForeignMetadataAreRejectedBeforeOpeningBinaryConnection() = runBlocking {
        val auth = Credentials(identity)
        var opened = 0
        for (value in listOf(
            "http://${URL(config.baseUrl).host}/v1/storages/object/sign/${config.bucket}/${record().objectPath}?token=x",
            "https://attacker.example/v1/storages/object/sign/${config.bucket}/${record().objectPath}?token=x",
            "https://${URL(config.baseUrl).host}.attacker.example/v1/storages/object/sign/${config.bucket}/${record().objectPath}?token=x",
            "https://${URL(config.baseUrl).host}/v1/storages/object/sign/${config.bucket}/other-user/other-file?token=x",
            "https://user:password@${URL(config.baseUrl).host}/v1/storages/object/sign/${config.bucket}/${record().objectPath}?token=x",
        )) {
            val client = CloudBackupClient(auth, config, JsonTransport { buildJsonObject { put("fullSignedURL", value) } }) { opened++; Connection(it) }
            failure { client.download(identity, saved(), destination()) }
        }
        val client = CloudBackupClient(auth, config, JsonTransport { rows(saved().copy(ownerId = "foreign-user")) }) { opened++; Connection(it) }
        failure { client.list(identity) }
        failure { client.download(identity, saved().copy(objectPath = "../${record().backupId}.foodlog.gz"), destination()) }
        failure { client.download(identity, saved().copy(sizeBytes = CloudConfig.MAX_COMPRESSED_BYTES + 1), destination()) }
        assertEquals(0, opened)
        assertNoPartial()
    }

    @Test fun accountGenerationChangeDuringSigningStopsDownloadBeforeFileAccess() = runBlocking {
        val auth = Credentials(identity)
        var opened = 0
        val transport = JsonTransport {
            auth.current = identity.copy(generation = "new-login-generation")
            signed()
        }
        val client = CloudBackupClient(auth, config, transport) { opened++; Connection(it) }
        val target = destination()
        failure { client.download(identity, saved(), target) }
        assertEquals(0, opened)
        assertEquals("existing destination", target.readText())
        assertNoPartial()
    }

    @Test fun connectionFailureDoesNotExposeTheSignedUrl() = runBlocking {
        val auth = Credentials(identity)
        val client = CloudBackupClient(auth, config, JsonTransport { signed() }) { url -> throw IOException("failed opening $url") }
        val target = destination()
        val error = failure { client.download(identity, saved(), target) }
        assertFalse(error.message!!.contains("token="))
        assertFalse(error.message!!.contains("synthetic-signature"))
        assertEquals("existing destination", target.readText())
        assertNoPartial()
    }

    @Test fun cancellationDisconnectsBlockedDownloadAndKeepsDestination() = runBlocking {
        val auth = Credentials(identity)
        val readStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                if (!released.await(5, TimeUnit.SECONDS)) throw IOException("synthetic timeout")
                throw IOException("synthetic disconnect")
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = read()
        }
        lateinit var connection: Connection
        val client = CloudBackupClient(auth, config, JsonTransport { signed() }) { url ->
            Connection(url, response = input).also { connection = it; it.onDisconnect = { released.countDown() } }
        }
        val target = destination()
        val work = launch(Dispatchers.Default) { client.download(identity, saved(), target) }
        assertTrue(withContext(Dispatchers.IO) { readStarted.await(3, TimeUnit.SECONDS) })
        withTimeout(2_000) { work.cancelAndJoin() }
        assertTrue(connection.disconnected)
        assertTrue(work.isCancelled)
        assertEquals("existing destination", target.readText())
        assertNoPartial()
    }
}
