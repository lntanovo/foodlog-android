package com.foodlog.app

import com.foodlog.app.cloud.*
import com.foodlog.app.data.Validation
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CloudBackupEngineTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var environment: FakeEnvironment
    private lateinit var engine: CloudBackupEngine

    @Before fun setup() = runBlocking {
        directory = Files.createTempDirectory("foodlog-cloud-test-").toFile()
        environment = FakeEnvironment()
        rebuild()
    }

    @After fun cleanup() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private suspend fun rebuild() {
        if (::scope.isInitialized) { scope.cancel(); scope.coroutineContext[Job]?.join() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = CloudBackupEngine(scope, environment, directory, "Synthetic test device")
        withTimeout(5_000) { while (environment.identity.subscriptionCount.value == 0) delay(5) }
    }

    private suspend fun done() {
        withTimeout(5_000) { while (engine.busy.value) delay(5) }
    }

    @Test fun failedUploadRetriesSameImmutableSnapshotAfterProcessRecreation() = runBlocking {
        environment.uploadFailures = 1
        val before = environment.local
        engine.backup(); done()
        assertNotNull(engine.error.value)
        val pending = requireNotNull(engine.task.value)
        val original = requireNotNull(pending.record)
        assertTrue(engine.blockLocalTransfer.value)
        val journal = File(directory, "active.json").readText()
        assertFalse(journal.contains("71.2345"))
        assertFalse(journal.contains("password"))
        environment.local = before.copy(bodies = before.bodies + BodyRecord("2026-10-19", "72"),
            meta = before.meta.copy(revision = before.meta.revision + 1))
        rebuild()
        assertNull(engine.preview.value)
        assertEquals(1, environment.uploadCalls)
        engine.continueTask(); done()
        assertNull(engine.error.value)
        assertNull(engine.task.value)
        assertEquals(2, environment.uploadCalls)
        assertEquals(original.backupId, environment.uploaded.single().backupId)
        assertEquals(original.sha256, environment.uploaded.single().sha256)
        assertEquals(1, environment.uploaded.single().recordCounts["bodies"])
        assertEquals(2, environment.local.bodies.size)
    }

    @Test fun emptyCloudNeverChangesOrRestoresLocalRecords() = runBlocking {
        val before = environment.local
        engine.showBackups(); done()
        assertTrue(engine.listLoaded.value)
        assertTrue(engine.records.value.isEmpty())
        assertEquals(before, environment.local)
        assertEquals(0, environment.restoreCalls)
        assertNull(engine.task.value)
    }

    @Test fun corruptedDownloadCannotReachPreviewOrChangeLocalData() = runBlocking {
        val before = environment.local
        val record = environment.remote(before)
        environment.corruptDownload = true
        engine.preview(record); done()
        assertNotNull(engine.error.value)
        assertNull(engine.preview.value)
        assertEquals(before, environment.local)
        assertEquals(0, environment.restoreCalls)
    }

    @Test fun previewDoesNotRestoreAndNewLocalEditsRequireAnotherConfirmation() = runBlocking {
        val backup = environment.local
        val record = environment.remote(backup)
        engine.preview(record); done()
        assertNotNull(engine.preview.value)
        assertEquals(0, environment.restoreCalls)
        environment.local = backup.copy(bodies = backup.bodies + BodyRecord("2026-10-19", "72"),
            meta = backup.meta.copy(revision = backup.meta.revision + 1))
        engine.restore(); done()
        assertTrue(engine.error.value!!.contains("本机记录刚刚有变化"))
        assertEquals(2, environment.local.bodies.size)
        assertEquals(2, engine.preview.value!!.current.counts["bodies"])
        assertEquals(0, environment.restoreCalls)
        engine.restore(); done()
        assertNull(engine.error.value)
        assertEquals(backup.bodies, environment.local.bodies)
        assertEquals(1, environment.restoreCalls)
        assertNull(engine.task.value)
    }

    @Test fun interruptedPreviewRequiresUserContinueAndNeverAutomaticallyRestores() = runBlocking {
        val before = environment.local
        engine.preview(environment.remote(before)); done()
        assertNotNull(engine.preview.value)
        assertEquals("PREVIEW", engine.task.value!!.stage)
        rebuild()
        assertNull(engine.preview.value)
        assertEquals(0, environment.restoreCalls)
        assertEquals(1, environment.downloadCalls)
        engine.continueTask(); done()
        assertNotNull(engine.preview.value)
        assertEquals(1, environment.downloadCalls)
        assertEquals(before, environment.local)
    }

    @Test fun switchingAccountDuringDownloadCannotExposePreviewOrReplayTask() = runBlocking {
        val before = environment.local
        val original = environment.identity.value!!
        val gate = CompletableDeferred<Unit>()
        environment.downloadGate = gate
        engine.preview(environment.remote(before))
        withTimeout(5_000) { while (environment.downloadCalls == 0) delay(5) }
        environment.identity.value = CloudIdentity("2002", "second-account", uid())
        withTimeout(5_000) { while (engine.busy.value) delay(5) }
        gate.complete(Unit)
        delay(30)
        assertNull(engine.preview.value)
        assertTrue(engine.records.value.isEmpty())
        assertEquals(original.uid, engine.task.value!!.ownerId)
        engine.continueTask(); done()
        assertNotNull(engine.error.value)
        assertEquals(0, environment.restoreCalls)
        assertEquals(before, environment.local)
    }

    @Test fun foreignOwnerAndInvalidObjectPathAreRejectedBeforeDownloading() = runBlocking {
        val record = environment.remote(environment.local)
        engine.preview(record.copy(ownerId = "other-owner")); done()
        assertNotNull(engine.error.value)
        assertEquals(0, environment.downloadCalls)
        assertNull(engine.task.value)
        engine.preview(record.copy(objectPath = "../other/backup.gz")); done()
        assertNotNull(engine.error.value)
        assertEquals(0, environment.downloadCalls)
        assertEquals(0, environment.restoreCalls)
    }

    @Test fun validHashWithMismatchedSummaryStillCannotRestore() = runBlocking {
        val before = environment.local
        val record = environment.remote(before).let { it.copy(recordCounts = it.recordCounts + ("bodies" to 999)) }
        engine.preview(record); done()
        assertNotNull(engine.error.value)
        assertNull(engine.preview.value)
        assertEquals(before, environment.local)
    }

    @Test fun pendingLocalFileOperationBlocksCloudBackupAndRestore() = runBlocking {
        engine.setLocalTransferActive(true)
        engine.backup(); done()
        assertNotNull(engine.error.value)
        assertNull(engine.task.value)
        assertEquals(0, environment.uploadCalls)
        engine.setLocalTransferActive(false)
        engine.preview(environment.remote(environment.local)); done()
        engine.setLocalTransferActive(true)
        engine.restore(); done()
        assertNotNull(engine.error.value)
        assertEquals(0, environment.restoreCalls)
    }

    @Test fun futureSchemaIsRejectedBeforeDownloadAndLogoutKeepsAllLocalData() = runBlocking {
        val before = environment.local
        engine.preview(environment.remote(before).copy(schemaVersion = 999)); done()
        assertNotNull(engine.error.value)
        assertEquals(0, environment.downloadCalls)
        engine.logout(); done()
        assertNull(environment.identity.value)
        assertEquals(before, environment.local)
    }

    @Test fun authenticationFinishesAfterItsOwnIdentityChange() = runBlocking {
        val before = environment.local
        engine.login("another-test-name", "test-only-password"); done()
        assertEquals("another-test-name", environment.identity.value!!.username)
        assertEquals(1, environment.loginCompletions)
        assertNull(engine.error.value)
        engine.logout(); done()
        assertEquals(1, environment.logoutCompletions)
        assertEquals(before, environment.local)
    }

    private class FakeEnvironment : CloudBackupEnvironment {
        override val identity = MutableStateFlow<CloudIdentity?>(CloudIdentity("1001", "test-account", uid()))
        @Volatile var local = Dataset(appVersion = "1.3.0", meta = Meta(revision = 7),
            bodies = listOf(BodyRecord("2026-10-18", "71.2345")), days = listOf(DayLog("2026-10-18")))
        @Volatile var uploadCalls = 0
        @Volatile var restoreCalls = 0
        @Volatile var downloadCalls = 0
        @Volatile var uploadFailures = 0
        @Volatile var corruptDownload = false
        @Volatile var loginCompletions = 0
        @Volatile var logoutCompletions = 0
        var downloadGate: CompletableDeferred<Unit>? = null
        val uploaded = mutableListOf<CloudBackupRecord>()
        private val remoteBytes = mutableMapOf<String, ByteArray>()

        override suspend fun login(username: String, password: String) {
            require(password == "test-only-password")
            identity.value = CloudIdentity("1001", username, uid())
            delay(20)
            loginCompletions++
        }
        override suspend fun logout() { identity.value = null; delay(20); logoutCompletions++ }
        override fun requireIdentity(expected: CloudIdentity) { check(identity.value == expected) { "账号已改变，请重新登录。" } }
        override suspend fun snapshot() = local
        override suspend fun writeBackup(file: File) {
            GZIPOutputStream(file.outputStream()).use { it.write(dataJson.encodeToString(local).toByteArray()) }
        }
        override suspend fun readBackup(file: File): Dataset {
            val data = GZIPInputStream(file.inputStream()).bufferedReader().use { dataJson.decodeFromString<Dataset>(it.readText()) }
            Validation.dataset(data)
            return data
        }
        override suspend fun restore(data: Dataset, expected: CloudIdentity, datasetId: String, revision: Long) {
            requireIdentity(expected)
            check(local.meta.datasetId == datasetId && local.meta.revision == revision)
            restoreCalls++
            local = data.copy(meta = data.meta.copy(revision = data.meta.revision + 1))
        }
        override suspend fun list(identity: CloudIdentity, offset: Int) = uploaded.drop(offset).take(20)
        override suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File): CloudBackupRecord {
            requireIdentity(identity)
            uploadCalls++
            if (uploadFailures-- > 0) error("synthetic network interruption")
            remoteBytes[record.backupId] = file.readBytes()
            return record.copy(createdAt = "2026-10-19T00:00:00Z").also { uploaded += it }
        }
        override suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File) {
            downloadCalls++
            downloadGate?.await()
            requireIdentity(identity)
            file.writeBytes(if (corruptDownload) byteArrayOf(1, 2, 3) else remoteBytes.getValue(record.backupId))
        }
        fun remote(data: Dataset): CloudBackupRecord {
            val temporary = Files.createTempFile("foodlog-synthetic-remote-", ".gz").toFile()
            try {
                GZIPOutputStream(temporary.outputStream()).use { it.write(dataJson.encodeToString(data).toByteArray()) }
                val summary = cloudSummary(data)
                val backupId = uid()
                val owner = identity.value!!.uid
                remoteBytes[backupId] = temporary.readBytes()
                return CloudBackupRecord(backupId = backupId, ownerId = owner, createdAt = "2026-10-19T00:00:00Z",
                    objectPath = "$owner/$backupId.foodlog.gz", sha256 = cloudSha256(temporary),
                    sizeBytes = temporary.length(), appVersion = data.appVersion, schemaVersion = data.schemaVersion,
                    deviceName = "Synthetic device", datasetId = data.meta.datasetId, recordCounts = summary.counts,
                    firstDate = summary.firstDate, lastDate = summary.lastDate, clientRevision = data.meta.revision)
            } finally { temporary.delete() }
        }
    }
}
