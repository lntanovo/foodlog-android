package com.foodlog.app

import com.foodlog.app.cloud.*
import com.foodlog.app.data.Validation
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.security.MessageDigest

class AutomaticBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val seed = Dataset(meta = Meta())
    private fun records(weight: String = "70.5") = seed.copy(bodies = listOf(BodyRecord("2026-10-18", weight)),
        days = listOf(DayLog("2026-10-18")), meta = seed.meta.copy(revision = 3))
    private fun engine(env: Fake, dir: File = temporary.root) = AutomaticBackupEngine(env, dir, "Synthetic device")

    @Test fun localCommitUploadsOnceAndTransportCountersDoNotCreateMoreSnapshots() = runBlocking {
        val env = Fake(records()); val engine = engine(env, File(temporary.root, "not-yet-created"))
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        val first = env.remote.first()
        env.local = env.local.copy(exportedAt = now(), appVersion = "future", meta = env.local.meta.copy(revision = 999, lastExport = now()))
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        assertEquals(1, env.remote.size); assertEquals(first.backupId, env.remote.first().backupId)
        env.local = records("71.2")
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        assertEquals(2, env.remote.size)
    }

    @Test fun pristineDeviceRestoresInsteadOfUploadingItsSeed() = runBlocking {
        val env = Fake(seed); val original = records(); env.addRemote(original)
        assertEquals(AutomaticBackupResult.DONE, engine(env).run(env.current()))
        assertEquals(cloudContentFingerprint(original), cloudContentFingerprint(env.local))
        assertEquals(1, env.restores); assertEquals(0, env.commits.size)
    }

    @Test fun nonemptyUnassociatedDeviceRequiresChoiceAndKeepLocalPreservesCloudHistory() = runBlocking {
        val env = Fake(records("72")); val old = env.addRemote(records("70")); val engine = engine(env)
        assertEquals(AutomaticBackupResult.CONFLICT, engine.run(env.current()))
        assertEquals(0, env.restores); assertEquals(0, env.commits.size)
        assertTrue(engine.keepLocal(env.current()))
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        assertEquals(2, env.remote.size); assertEquals(old.backupId, env.remote.last().backupId)
        assertEquals("72", env.readRemote(env.remote.first()).bodies.single().weight)
    }

    @Test fun offlineAndLostAcknowledgementReuseExactPreparedSnapshotAfterRestart() = runBlocking {
        val env = Fake(records()); env.failAfterCommit = true
        assertEquals(AutomaticBackupResult.RETRY, engine(env).run(env.current()))
        val firstId = env.commits.single(); val originalBytes = env.bytes.getValue(firstId).clone()
        env.local = records("73")
        assertEquals(AutomaticBackupResult.DONE, engine(env).run(env.current()))
        assertEquals(firstId, env.commits[1]); assertArrayEquals(originalBytes, env.bytes.getValue(firstId))
        assertEquals(2, env.remote.size); assertEquals("73", env.readRemote(env.remote.first()).bodies.single().weight)
    }

    @Test fun networkFailureBeforeCommitKeepsPendingPayloadAndLocalRecords() = runBlocking {
        val env = Fake(records()); val before = env.local; env.failBeforeCommit = true
        assertEquals(AutomaticBackupResult.RETRY, engine(env).run(env.current()))
        assertEquals(before, env.local); assertEquals(0, env.remote.size)
        assertEquals(AutomaticBackupResult.DONE, engine(env).run(env.current()))
        assertEquals(env.commits[0], env.commits[1]); assertEquals(1, env.remote.size)
    }

    @Test fun cleanAssociatedDeviceAutomaticallyReceivesNewerCloudRecords() = runBlocking {
        val env = Fake(records()); val engine = engine(env); engine.run(env.current())
        val incoming = records("74"); env.addRemote(incoming)
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        assertEquals(cloudContentFingerprint(incoming), cloudContentFingerprint(env.local)); assertEquals(1, env.restores)
    }

    @Test fun localAndRemoteEditsArePreservedAsConflictInsteadOfLastWriterWins() = runBlocking {
        val env = Fake(records()); val engine = engine(env); engine.run(env.current())
        env.addRemote(records("74")); env.local = records("75")
        assertEquals(AutomaticBackupResult.CONFLICT, engine.run(env.current()))
        assertEquals("75", env.local.bodies.single().weight); assertEquals(0, env.restores); assertEquals(2, env.remote.size)
    }

    @Test fun localChangeBetweenDownloadAndRestoreIsNeverReplaced() = runBlocking {
        val env = Fake(seed); env.addRemote(records()); env.beforeRestore = {
            env.local = records("79").copy(meta = seed.meta.copy(revision = 10))
        }
        assertEquals(AutomaticBackupResult.RETRY, engine(env).run(env.current()))
        assertEquals("79", env.local.bodies.single().weight); assertEquals(0, env.restores)
        assertEquals(AutomaticBackupResult.CONFLICT, engine(env).run(env.current()))
        assertEquals(0, env.restores)
    }

    @Test fun interruptedSuccessfulRestoreIsRecognizedWithoutReplayingAfterRestart() = runBlocking {
        val env = Fake(seed); val incoming = records(); env.addRemote(incoming); env.failAfterRestore = true
        assertEquals(AutomaticBackupResult.RETRY, engine(env).run(env.current()))
        assertEquals(1, env.restores)
        assertEquals(AutomaticBackupResult.DONE, engine(env).run(env.current()))
        assertEquals(1, env.restores); assertEquals(cloudContentFingerprint(incoming), cloudContentFingerprint(env.local))
    }

    @Test fun accountSwitchCannotUploadPreviouslyAssociatedLocalRecords() = runBlocking {
        val env = Fake(records()); val engine = engine(env); engine.run(env.current())
        env.identity.value = CloudIdentity("2002", "other-account", uid()); env.remote.clear()
        assertEquals(AutomaticBackupResult.CONFLICT, engine.run(env.current()))
        assertEquals(1, env.commits.size); assertEquals(0, env.restores)
    }

    @Test fun changingAccountDuringDownloadPreventsRestoration() = runBlocking {
        val env = Fake(seed); env.addRemote(records()); env.afterDownload = { env.identity.value = null }
        assertEquals(AutomaticBackupResult.DONE, engine(env).run(env.current()))
        assertEquals(0, env.restores); assertEquals(seed, env.local)
    }

    @Test fun aConcurrentCloudWriterCannotAdvanceTheHeadUsingAStaleBase() = runBlocking {
        val env = Fake(records()); env.beforeCommit = { env.addRemote(records("81")) }
        assertEquals(AutomaticBackupResult.CONFLICT, engine(env).run(env.current()))
        assertEquals(1, env.remote.size); assertEquals("81", env.readRemote(env.remote.first()).bodies.single().weight)
        assertEquals("70.5", env.local.bodies.single().weight)
    }

    @Test fun keepLocalChoiceExpiresWhenCloudHeadChangesAgain() = runBlocking {
        val env = Fake(records()); env.addRemote(records("82")); val engine = engine(env)
        engine.run(env.current()); env.addRemote(records("83"))
        assertFalse(engine.keepLocal(env.current())); assertEquals(0, env.commits.size)
    }

    @Test fun anEditDuringUploadRemainsPendingForTheNextWorker() = runBlocking {
        val env = Fake(records()); env.beforeCommit = { env.local = records("84") }; val engine = engine(env)
        assertEquals(AutomaticBackupResult.RETRY, engine.run(env.current()))
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current()))
        assertEquals(2, env.remote.size); assertEquals("84", env.readRemote(env.remote.first()).bodies.single().weight)
    }

    @Test fun malformedFileAndMismatchedSummaryCannotRestoreAnything() = runBlocking {
        val env = Fake(seed); val record = env.addRemote(records()); env.bytes[record.backupId] = byteArrayOf(1, 2)
        assertEquals(AutomaticBackupResult.RETRY, engine(env).run(env.current())); assertEquals(0, env.restores)
        val env2 = Fake(seed); val valid = env2.addRemote(records()); env2.remote[0] = valid.copy(recordCounts = mapOf("meals" to 100))
        assertEquals(AutomaticBackupResult.RETRY, engine(env2, temporary.newFolder()).run(env2.current())); assertEquals(0, env2.restores)
    }

    @Test fun corruptedJournalFailsClosedAndAnActiveEditorDefersAutomaticWork() = runBlocking {
        File(temporary.root, "state.json").writeText("broken")
        val env = Fake(records()); assertEquals(AutomaticBackupResult.CONFLICT, engine(env).run(env.current()))
        assertEquals(0, env.commits.size)
        val env2 = Fake(seed); env2.addRemote(records()); env2.blocked = true
        assertEquals(AutomaticBackupResult.RETRY, engine(env2, temporary.newFolder()).run(env2.current())); assertEquals(0, env2.restores)
    }

    @Test fun aManualRestoreCanResolveConflictOnlyWhenItMatchesTheCloudContents() = runBlocking {
        val env = Fake(records()); val incoming = records("85"); env.addRemote(incoming); val engine = engine(env)
        engine.run(env.current()); env.local = incoming.copy(meta = incoming.meta.copy(revision = 500))
        assertEquals(AutomaticBackupResult.DONE, engine.run(env.current())); assertEquals("CURRENT", engine.status.value.phase)
        assertEquals(0, env.commits.size)
    }

    @Test fun pristineCheckIncludesProfileCustomFoodsAndFavoriteFlags() {
        val bundled = dataJson.decodeFromString<Dataset>(File(System.getProperty("projectDir"), "src/main/assets/foods_seed_v1.json").readText())
        assertTrue(isPristineInstallation(bundled.copy(meta = Meta(revision = 200)), bundled))
        assertTrue(isPristineInstallation(bundled.copy(schemaVersion = 3, kind = "FULL_BACKUP", meta = Meta(revision = 1)), bundled))
        assertFalse(isPristineInstallation(bundled.copy(meta = bundled.meta.copy(profile = Profile(name = "changed"))), bundled))
        assertFalse(isPristineInstallation(bundled.copy(foods = bundled.foods.drop(1)), bundled))
        assertFalse(isPristineInstallation(bundled.copy(foods = bundled.foods.mapIndexed { i, food -> if (i == 0) food.copy(favorite = true) else food }), bundled))
        assertEquals(cloudContentFingerprint(bundled), cloudContentFingerprint(bundled.copy(foods = bundled.foods.reversed(), versions = bundled.versions.reversed())))
    }

    private inner class Fake(var local: Dataset) : AutomaticBackupEnvironment {
        override val identity = MutableStateFlow<CloudIdentity?>(CloudIdentity("1001", "synthetic-account", uid()))
        val remote = mutableListOf<CloudBackupRecord>()
        val bytes = mutableMapOf<String, ByteArray>()
        val commits = mutableListOf<String>()
        var restores = 0; var blocked = false; var failBeforeCommit = false; var failAfterCommit = false; var failAfterRestore = false
        var beforeRestore: (() -> Unit)? = null; var afterDownload: (() -> Unit)? = null; var beforeCommit: (() -> Unit)? = null
        fun current() = identity.value!!
        override suspend fun login(username: String, password: String) {}
        override suspend fun logout() { identity.value = null }
        override fun requireIdentity(expected: CloudIdentity) { check(identity.value == expected) }
        override suspend fun snapshot() = local
        override fun isPristine(data: Dataset) = isPristineInstallation(data, seed)
        override fun restoreBlocked() = blocked
        override suspend fun writeBackup(file: File) { file.writeBytes(compress(local)) }
        override suspend fun readBackup(file: File): Dataset = read(file.readBytes())
        private fun compress(data: Dataset): ByteArray = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(dataJson.encodeToString(data).toByteArray()) }; out.toByteArray()
        }
        private fun read(content: ByteArray): Dataset = GZIPInputStream(content.inputStream()).bufferedReader().use {
            dataJson.decodeFromString<Dataset>(it.readText()).also(Validation::dataset)
        }
        fun readRemote(record: CloudBackupRecord) = read(bytes.getValue(record.backupId))
        override suspend fun list(identity: CloudIdentity, offset: Int) = remote.drop(offset).take(20)
        override suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File) {
            file.writeBytes(bytes.getValue(record.backupId)); afterDownload?.invoke(); requireIdentity(identity)
        }
        override suspend fun restore(data: Dataset, expected: CloudIdentity, datasetId: String, revision: Long) {
            beforeRestore?.invoke(); beforeRestore = null; requireIdentity(expected)
            check(local.meta.datasetId == datasetId && local.meta.revision == revision); check(!blocked)
            local = data.copy(meta = data.meta.copy(revision = data.meta.revision + 1)); restores++
            if (failAfterRestore) { failAfterRestore = false; error("synthetic lost restore acknowledgement") }
        }
        override suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File) = error("Manual path must not be called")
        override suspend fun commit(identity: CloudIdentity, record: CloudBackupRecord, file: File, expectedHead: String?): CloudBackupRecord? {
            requireIdentity(identity); commits += record.backupId
            if (failBeforeCommit) { failBeforeCommit = false; error("synthetic offline") }
            remote.find { it.backupId == record.backupId }?.let { return it }
            beforeCommit?.invoke(); beforeCommit = null
            if (remote.firstOrNull()?.backupId != expectedHead) return null
            val saved = record.copy(ownerId = identity.uid, createdAt = "2026-10-20T07:00:00Z")
            bytes[record.backupId] = file.readBytes(); remote.add(0, saved)
            if (failAfterCommit) { failAfterCommit = false; error("synthetic lost upload acknowledgement") }
            return saved
        }
        fun addRemote(data: Dataset): CloudBackupRecord {
            val id = uid(); val content = compress(data); val summary = cloudSummary(data)
            return CloudBackupRecord(id, ownerId = current().uid, createdAt = "2026-10-20T07:00:00Z",
                objectPath = "${current().uid}/$id.foodlog.gz",
                sha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) },
                sizeBytes = content.size.toLong(), appVersion = data.appVersion, schemaVersion = data.schemaVersion,
                deviceName = "Synthetic remote", datasetId = data.meta.datasetId, recordCounts = summary.counts,
                firstDate = summary.firstDate, lastDate = summary.lastDate, clientRevision = data.meta.revision).also {
                bytes[id] = content; remote.add(0, it)
            }
        }
    }
}
