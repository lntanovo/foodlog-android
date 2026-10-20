package com.foodlog.app.cloud

import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.Dataset
import com.foodlog.app.domain.uid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Excludes transport/local counters, but includes all user data, deletions and draft contents. */
@OptIn(ExperimentalSerializationApi::class)
fun cloudContentFingerprint(data: Dataset): String {
    val normalized = data.copy(schemaVersion = 3, kind = "FULL_BACKUP", appVersion = "", exportedAt = "", report = emptyList(),
        meta = data.meta.copy(revision = 0, lastExport = null),
        foods = data.foods.sortedBy { it.id }, versions = data.versions.sortedBy { it.id },
        meals = data.meals.sortedBy { it.id }, days = data.days.sortedBy { it.date },
        bodies = data.bodies.sortedBy { it.date }, plans = data.plans.sortedBy { it.id },
        targets = data.targets.sortedBy { it.date }, origins = data.origins.sortedBy { it.namespace + ":" + it.key },
        templates = data.templates.sortedBy { it.id })
    val digest = MessageDigest.getInstance("SHA-256")
    dataJson.encodeToStream(normalized, object : OutputStream() {
        override fun write(value: Int) { digest.update(value.toByte()) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { digest.update(bytes, offset, length) }
    })
    return digest.digest()
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Custom foods/favorites/profile count as existing records, even without a meal. */
fun isPristineInstallation(data: Dataset, seed: Dataset): Boolean =
    cloudContentFingerprint(data.copy(meta = data.meta.copy(datasetId = seed.meta.datasetId))) == cloudContentFingerprint(seed)

interface AutomaticBackupEnvironment : CloudBackupEnvironment {
    fun isPristine(data: Dataset): Boolean
    fun restoreBlocked(): Boolean
    suspend fun commit(identity: CloudIdentity, record: CloudBackupRecord, file: File, expectedHead: String?): CloudBackupRecord?
}

@Serializable
data class AutomaticPending(val record: CloudBackupRecord, val expectedHead: String?, val fingerprint: String)

@Serializable
data class AutomaticBackupState(
    val ownerId: String? = null,
    val baseId: String? = null,
    val fingerprint: String? = null,
    val lastBackupAt: String? = null,
    val pending: AutomaticPending? = null,
    val conflict: Boolean = false,
    val conflictHead: String? = null,
    val restoreIntent: AutomaticRestoreIntent? = null,
    val localChoice: Boolean = false,
)

@Serializable
data class AutomaticRestoreIntent(val record: CloudBackupRecord, val datasetId: String, val revision: Long)

data class AutomaticBackupStatus(
    val phase: String = "WAITING", val detail: String = "等待自动备份", val lastBackupAt: String? = null,
    val remote: CloudBackupRecord? = null,
)

enum class AutomaticBackupResult { DONE, RETRY, CONFLICT }

/** Durable automatic state is separate from manual previews. No password or token enters the journal. */
class AutomaticBackupEngine(
    private val environment: AutomaticBackupEnvironment,
    private val directory: File,
    private val deviceName: String,
) {
    private val mutex = Mutex()
    private val journal = File(directory, "state.json")
    private var state = load()
    private val mutableStatus = MutableStateFlow(AutomaticBackupStatus(lastBackupAt = state.lastBackupAt))
    val status = mutableStatus.asStateFlow()

    private fun load(): AutomaticBackupState = try {
        if (!journal.exists()) AutomaticBackupState() else dataJson.decodeFromString<AutomaticBackupState>(journal.readText())
    } catch (_: Exception) {
        // A damaged association must not silently turn into permission to replace cloud/local data.
        AutomaticBackupState(conflict = true)
    }

    private fun save(next: AutomaticBackupState) {
        directory.mkdirs()
        check(directory.isDirectory) { "无法保存自动备份进度" }
        val temporary = File(directory, "state.json.new")
        FileOutputStream(temporary).use { output ->
            output.write(dataJson.encodeToString(next).toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        state = next
    }

    fun waiting(signedIn: Boolean) {
        if (mutableStatus.value.phase !in setOf("UPLOADING", "RESTORING", "CONFLICT"))
            show(if (signedIn) "WAITING" else "SIGNED_OUT", if (signedIn) "记录已保存在本机，等待自动备份" else "登录后自动云备份")
    }

    fun accountChanged(signedIn: Boolean) = show(if (signedIn) "WAITING" else "SIGNED_OUT",
        if (signedIn) "正在检查账号的云端记录" else "登录后自动云备份")

    private fun show(phase: String, detail: String, remote: CloudBackupRecord? = null) {
        mutableStatus.value = AutomaticBackupStatus(phase, detail,
            state.lastBackupAt.takeIf { state.ownerId == environment.identity.value?.uid }, remote)
    }

    private fun payload(id: String): File {
        require(java.util.UUID.fromString(id).toString() == id)
        return File(directory, "$id.foodlog.gz")
    }

    private suspend fun contents(identity: CloudIdentity, record: CloudBackupRecord): Dataset {
        environment.requireIdentity(identity)
        require(record.ownerId == identity.uid && record.objectPath == "${identity.uid}/${record.backupId}.foodlog.gz")
        val file = payload(record.backupId)
        if (!file.exists()) environment.download(identity, record, file)
        environment.requireIdentity(identity)
        if (file.length() != record.sizeBytes || cloudSha256(file) != record.sha256) {
            file.delete()
            error("云备份文件校验失败")
        }
        val data = environment.readBackup(file)
        val summary = cloudSummary(data)
        require(data.kind == "FULL_BACKUP" && data.meta.datasetId == record.datasetId &&
            data.meta.revision == record.clientRevision && summary.counts == record.recordCounts &&
            summary.firstDate == record.firstDate && summary.lastDate == record.lastDate) { "云备份内容与清单不一致" }
        environment.requireIdentity(identity)
        return data
    }

    private fun accept(identity: CloudIdentity, record: CloudBackupRecord, fingerprint: String) {
        environment.requireIdentity(identity)
        val old = state.pending
        save(AutomaticBackupState(ownerId = identity.uid, baseId = record.backupId,
            fingerprint = fingerprint, lastBackupAt = record.createdAt))
        old?.let { payload(it.record.backupId).delete() }
        payload(record.backupId).delete()
        show("CURRENT", "记录已自动备份到云端")
    }

    private fun conflict(head: CloudBackupRecord?): AutomaticBackupResult {
        save(state.copy(conflict = true, conflictHead = head?.backupId))
        show("CONFLICT", "本机与云端记录不同，已暂停自动处理，请先核对", head)
        return AutomaticBackupResult.CONFLICT
    }

    suspend fun run(identity: CloudIdentity): AutomaticBackupResult = mutex.withLock {
        try { cycle(identity) }
        catch (error: CancellationException) {
            show("WAITING", "记录已保存在本机，等待继续云备份")
            throw error
        }
        catch (_: Exception) {
            val signedIn = environment.identity.value == identity
            show(if (signedIn) "RETRY" else "SIGNED_OUT",
                if (signedIn) "记录已保存在本机，云备份未完成，将自动重试" else "请重新登录以继续自动云备份")
            if (signedIn) AutomaticBackupResult.RETRY else AutomaticBackupResult.DONE
        }
    }

    private suspend fun cycle(identity: CloudIdentity): AutomaticBackupResult {
        environment.requireIdentity(identity)
        directory.mkdirs()
        check(directory.isDirectory) { "无法创建自动备份目录" }
        if (environment.restoreBlocked()) {
            show("WAITING", "正在编辑或处理文件，稍后自动备份")
            return AutomaticBackupResult.RETRY
        }
        show("CHECKING", "正在检查云端记录")
        var head = environment.list(identity, 0).firstOrNull()
        environment.requireIdentity(identity)
        var local = environment.snapshot()
        var fingerprint = cloudContentFingerprint(local)

        // A completed restore may have lost its acknowledgement. Re-evaluate actual data on restart.
        val intent = state.restoreIntent
        if (intent != null && state.ownerId == identity.uid) {
            val restored = contents(identity, intent.record)
            if (fingerprint == cloudContentFingerprint(restored)) accept(identity, intent.record, fingerprint)
            else {
                save(state.copy(restoreIntent = null))
                if (local.meta.datasetId != intent.datasetId || local.meta.revision != intent.revision) return conflict(head)
            }
        }

        if (state.ownerId != null && state.ownerId != identity.uid && !environment.isPristine(local)) return conflict(head)

        // Retrying uses the exact immutable payload and ID even if newer edits arrived meanwhile.
        val pending = state.pending
        if (pending != null && state.ownerId == identity.uid && !state.conflict) {
            show("UPLOADING", "正在上传自动备份")
            val saved = environment.commit(identity, pending.record, payload(pending.record.backupId), pending.expectedHead)
            environment.requireIdentity(identity)
            if (saved == null) return conflict(environment.list(identity, 0).firstOrNull())
            require(saved.backupId == pending.record.backupId && saved.sha256 == pending.record.sha256)
            accept(identity, saved, pending.fingerprint)
            head = environment.list(identity, 0).firstOrNull()
            local = environment.snapshot(); fingerprint = cloudContentFingerprint(local)
        }

        if (head != null && (state.baseId != head.backupId || (state.fingerprint == null && !state.localChoice) || state.conflict || state.ownerId != identity.uid)) {
            val incoming = contents(identity, head)
            val incomingFingerprint = cloudContentFingerprint(incoming)
            local = environment.snapshot(); fingerprint = cloudContentFingerprint(local)
            if (fingerprint == incomingFingerprint) {
                accept(identity, head, fingerprint)
                return AutomaticBackupResult.DONE
            }
            val safeToRestore = !state.conflict && (environment.isPristine(local) ||
                (state.ownerId == identity.uid && state.fingerprint == fingerprint))
            if (!safeToRestore) return conflict(head)
            if (environment.restoreBlocked()) return AutomaticBackupResult.RETRY
            show("RESTORING", "正在自动恢复云端记录")
            save(state.copy(ownerId = identity.uid,
                restoreIntent = AutomaticRestoreIntent(head, local.meta.datasetId, local.meta.revision)))
            environment.restore(incoming, identity, local.meta.datasetId, local.meta.revision)
            environment.requireIdentity(identity)
            val actual = environment.snapshot()
            // If a new user commit followed restoration, do not acknowledge that commit as backed up.
            accept(identity, head, incomingFingerprint)
            show("RESTORED", "已自动恢复云端记录")
            return if (cloudContentFingerprint(actual) == incomingFingerprint) AutomaticBackupResult.DONE else AutomaticBackupResult.RETRY
        }
        if (state.conflict || (head == null && state.baseId != null)) return conflict(head)
        if (state.ownerId == identity.uid && fingerprint == state.fingerprint && head?.backupId == state.baseId) {
            show("CURRENT", "记录已自动备份到云端")
            return AutomaticBackupResult.DONE
        }
        // Cloud was checked first. Capture a coherent backup and read it back to derive its metadata.
        val id = uid()
        val file = payload(id)
        val partial = File(directory, "$id.partial")
        try {
            environment.writeBackup(partial)
            RandomAccessFile(partial, "rw").use { it.fd.sync() }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { partial.delete() }
        RandomAccessFile(file, "rw").use { it.fd.sync() }
        require(file.length() in 1..CloudConfig.MAX_COMPRESSED_BYTES)
        val captured = environment.readBackup(file)
        val summary = cloudSummary(captured)
        val record = CloudBackupRecord(id, objectPath = "${identity.uid}/$id.foodlog.gz",
            sha256 = cloudSha256(file), sizeBytes = file.length(), appVersion = captured.appVersion,
            schemaVersion = captured.schemaVersion, deviceName = deviceName.take(80), datasetId = captured.meta.datasetId,
            recordCounts = summary.counts, firstDate = summary.firstDate, lastDate = summary.lastDate,
            clientRevision = captured.meta.revision)
        environment.requireIdentity(identity)
        val capturedFingerprint = cloudContentFingerprint(captured)
        save(state.copy(ownerId = identity.uid, pending = AutomaticPending(record, head?.backupId, capturedFingerprint)))
        show("UPLOADING", "正在上传自动备份")
        val saved = environment.commit(identity, record, file, head?.backupId)
        environment.requireIdentity(identity)
        if (saved == null) return conflict(environment.list(identity, 0).firstOrNull())
        require(saved.backupId == id && saved.sha256 == record.sha256)
        accept(identity, saved, capturedFingerprint)
        return if (cloudContentFingerprint(environment.snapshot()) == capturedFingerprint) AutomaticBackupResult.DONE else AutomaticBackupResult.RETRY
    }

    /** The explicit choice is valid only for the cloud head that was displayed to the user. */
    suspend fun keepLocal(identity: CloudIdentity): Boolean = mutex.withLock {
        environment.requireIdentity(identity)
        check(state.conflict) { "请先核对云端与本机记录" }
        val head = environment.list(identity, 0).firstOrNull()
        environment.requireIdentity(identity)
        if (head?.backupId != state.conflictHead) { conflict(head); return@withLock false }
        val previous = state.pending
        save(AutomaticBackupState(ownerId = identity.uid, baseId = head?.backupId, lastBackupAt = state.lastBackupAt, localChoice = true))
        previous?.let { payload(it.record.backupId).delete() }
        show("WAITING", "已选择保留本机记录，正在准备自动备份")
        true
    }
}
