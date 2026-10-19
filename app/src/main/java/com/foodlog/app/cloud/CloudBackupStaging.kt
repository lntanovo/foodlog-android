package com.foodlog.app.cloud

import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.Dataset
import com.foodlog.app.domain.uid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** No credentials or record contents are put in this private operation journal. */
@Serializable
data class CloudBackupTask(
    val backupId: String,
    val ownerId: String,
    val generation: String,
    val operation: String,
    val stage: String,
    val record: CloudBackupRecord? = null,
)

data class CloudDatasetSummary(
    val counts: Map<String, Int>,
    val firstDate: String?,
    val lastDate: String?,
) {
    val personalRecords: Int get() = listOf("meals", "days", "bodies", "plans", "templates")
        .sumOf { counts[it] ?: 0 }
}

fun cloudSummary(data: Dataset): CloudDatasetSummary {
    val dates = (data.meals.map { it.date } + data.days.map { it.date } + data.bodies.map { it.date } +
        data.targets.map { it.date }).sorted()
    return CloudDatasetSummary(linkedMapOf(
        "foods" to data.foods.size, "versions" to data.versions.size,
        "meals" to data.meals.size, "items" to data.meals.sumOf { it.items.size },
        "days" to data.days.size, "bodies" to data.bodies.size, "plans" to data.plans.size,
        "targets" to data.targets.size, "origins" to data.origins.size, "templates" to data.templates.size,
    ), dates.firstOrNull(), dates.lastOrNull())
}

data class CloudRestorePreview(
    val identity: CloudIdentity,
    val record: CloudBackupRecord,
    val backup: Dataset,
    val current: CloudDatasetSummary,
    val localDatasetId: String,
    val localRevision: Long,
) {
    val incoming: CloudDatasetSummary get() = cloudSummary(backup)
}

/** The same workflow can be tested without credentials, a live account, or real user records. */
interface CloudBackupEnvironment {
    val identity: StateFlow<CloudIdentity?>
    suspend fun login(username: String, password: String)
    suspend fun logout()
    fun requireIdentity(expected: CloudIdentity)
    suspend fun snapshot(): Dataset
    suspend fun writeBackup(file: File)
    suspend fun readBackup(file: File): Dataset
    suspend fun restore(data: Dataset, expected: CloudIdentity, datasetId: String, revision: Long)
    suspend fun list(identity: CloudIdentity, offset: Int): List<CloudBackupRecord>
    suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File): CloudBackupRecord
    suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File)
}

class CloudBackupEngine(
    private val scope: CoroutineScope,
    private val environment: CloudBackupEnvironment,
    private val directory: File,
    private val deviceName: String,
) {
    private val journal = File(directory, "active.json")
    val identity = environment.identity
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val records = MutableStateFlow<List<CloudBackupRecord>>(emptyList())
    val listOpen = MutableStateFlow(false)
    val listLoaded = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    val preview = MutableStateFlow<CloudRestorePreview?>(null)
    val task = MutableStateFlow(readTask())
    val blockLocalTransfer = MutableStateFlow(task.value != null)
    @Volatile private var localTransferActive = false
    private var operation: Job? = null
    @Volatile private var operationGeneration = 0L
    @Volatile private var authenticationOperation = false

    init {
        directory.mkdirs()
        if (journal.exists()) message.value = "上次云备份操作尚未结束。不会自动再次恢复，请核对本机记录后继续。"
        scope.launch {
            var previous = identity.value
            identity.collect { current ->
                if (current != previous) {
                    previous = current
                    // A successful login/logout itself changes identity. Let its credential
                    // persistence and best-effort server logout finish rather than cancel itself.
                    if (!authenticationOperation) {
                        operationGeneration++
                        operation?.cancel()
                        busy.value = false
                    }
                    preview.value = null
                    records.value = emptyList()
                    listLoaded.value = false
                    listOpen.value = false
                    hasMore.value = false
                    error.value = null
                    message.value = if (task.value != null) "有尚未完成的云备份操作，请登录原账号继续，或取消该操作。" else null
                    updateBlocking()
                }
            }
        }
    }

    private fun readTask(): CloudBackupTask? = runCatching {
        if (!journal.exists()) null else dataJson.decodeFromString<CloudBackupTask>(journal.readText())
            .also { UUID.fromString(it.backupId); require(it.operation in listOf("UPLOAD", "DOWNLOAD")) }
    }.getOrNull()

    private fun payload(active: CloudBackupTask): File {
        require(UUID.fromString(active.backupId).toString() == active.backupId) { "云备份任务编号无效" }
        return File(directory, "${active.backupId}.foodlog.gz")
    }

    private fun save(active: CloudBackupTask) {
        directory.mkdirs()
        val temporary = File(directory, "active.json.new")
        temporary.outputStream().use { output ->
            output.write(dataJson.encodeToString(active).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicMove(temporary, journal)
        task.value = active
        updateBlocking()
    }

    private fun clearTask() {
        require(!journal.exists() || journal.delete()) { "无法清理云备份进度，请重试" }
        val previous = task.value
        task.value = null
        preview.value = null
        previous?.let { active ->
            runCatching { payload(active).delete(); File(directory, "${active.backupId}.partial").delete() }
        }
        updateBlocking()
    }

    private fun updateBlocking() { blockLocalTransfer.value = busy.value || task.value != null || preview.value != null }

    fun setLocalTransferActive(active: Boolean) { localTransferActive = active }

    private fun checkLocalTransfer() {
        check(!localTransferActive) { "请先完成或取消本机文件操作，再使用云备份。" }
    }

    private fun requireCurrent(): CloudIdentity = requireNotNull(identity.value) { "请先登录账号。" }
        .also(environment::requireIdentity)

    private fun run(authentication: Boolean = false, block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        authenticationOperation = authentication
        val generation = ++operationGeneration
        error.value = null
        message.value = null
        updateBlocking()
        operation = scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == operationGeneration) error.value = e.message ?: "云备份操作失败，请重试。"
            } finally {
                if (generation == operationGeneration) {
                    authenticationOperation = false
                    busy.value = false
                    updateBlocking()
                }
            }
        }
    }

    fun login(username: String, password: String) = run(authentication = true) {
        require(username.isNotBlank() && password.isNotEmpty()) { "请输入用户名和密码。" }
        environment.login(username.trim(), password)
    }

    fun logout() = run(authentication = true) {
        environment.logout()
        message.value = "已退出账号，本机记录仍完整保留。"
    }

    fun backup() = run {
        checkLocalTransfer()
        require(task.value == null) { "请先继续或取消尚未完成的云备份操作。" }
        val current = requireCurrent()
        val active = CloudBackupTask(uid(), current.uid, current.generation, "UPLOAD", "PREPARING")
        save(active)
        upload(active, current)
    }

    fun showBackups() = run {
        val current = requireCurrent()
        require(task.value == null) { "请先继续或取消尚未完成的云备份操作。" }
        val result = environment.list(current, 0)
        environment.requireIdentity(current)
        result.forEach { validateRecord(it, current) }
        records.value = result
        hasMore.value = result.size >= 20
        listLoaded.value = true
        listOpen.value = true
    }

    fun moreBackups() = run {
        val current = requireCurrent()
        val result = environment.list(current, records.value.size)
        environment.requireIdentity(current)
        result.forEach { validateRecord(it, current) }
        records.value = (records.value + result).distinctBy { it.backupId }
        hasMore.value = result.size >= 20
    }

    fun hideBackups() { if (!busy.value) listOpen.value = false }

    fun preview(record: CloudBackupRecord) = run {
        checkLocalTransfer()
        require(task.value == null) { "请先继续或取消尚未完成的云备份操作。" }
        val current = requireCurrent()
        validateRecord(record, current)
        val active = CloudBackupTask(record.backupId, current.uid, current.generation, "DOWNLOAD", "DOWNLOADING", record)
        save(active)
        download(active, current)
    }

    fun continueTask() = run {
        checkLocalTransfer()
        val active = requireNotNull(task.value) { "没有待继续的操作。" }
        val current = requireCurrent()
        require(current.uid == active.ownerId) { "请登录发起这次操作的原账号，或取消该操作。" }
        val rebound = active.copy(generation = current.generation)
        save(rebound)
        if (active.operation == "UPLOAD") upload(rebound, current) else download(rebound, current)
    }

    private suspend fun upload(active: CloudBackupTask, current: CloudIdentity) {
        environment.requireIdentity(current)
        val file = payload(active)
        val record = active.record ?: kotlin.run {
            val partial = File(directory, "${active.backupId}.partial")
            environment.writeBackup(partial)
            environment.requireIdentity(current)
            require(partial.length() in 1..CloudConfig.MAX_COMPRESSED_BYTES) { "云备份压缩文件不能超过 64 MiB，请使用本机导出。" }
            RandomAccessFile(partial, "rw").use { it.fd.sync() }
            val contents = environment.readBackup(partial)
            require(contents.kind == "FULL_BACKUP") { "仅支持完整记录的云备份。" }
            environment.requireIdentity(current)
            val summary = cloudSummary(contents)
            val candidate = CloudBackupRecord(
                backupId = active.backupId, ownerId = current.uid,
                objectPath = "${current.uid}/${active.backupId}.foodlog.gz",
                sha256 = cloudSha256(partial), sizeBytes = partial.length(),
                appVersion = contents.appVersion, schemaVersion = contents.schemaVersion,
                deviceName = deviceName.take(80), datasetId = contents.meta.datasetId,
                recordCounts = summary.counts, firstDate = summary.firstDate, lastDate = summary.lastDate,
                clientRevision = contents.meta.revision,
            )
            atomicMove(partial, file)
            save(active.copy(stage = "UPLOADING", record = candidate))
            candidate
        }
        validateRecord(record, current)
        validateFile(file, record)
        environment.requireIdentity(current)
        val uploaded = environment.upload(current, record, file)
        environment.requireIdentity(current)
        validateRecord(uploaded, current)
        require(uploaded.backupId == record.backupId && uploaded.sha256 == record.sha256 && uploaded.sizeBytes == record.sizeBytes) {
            "云端备份确认内容不一致，请重试核对。"
        }
        records.value = (listOf(uploaded) + records.value).distinctBy { it.backupId }
        clearTask()
        message.value = "已备份到当前账号。换设备后登录同一账号，选择这份备份恢复。"
    }

    private suspend fun download(active: CloudBackupTask, current: CloudIdentity) {
        val record = requireNotNull(active.record) { "备份任务缺少文件信息，请取消后重新选择。" }
        validateRecord(record, current)
        environment.requireIdentity(current)
        val file = payload(active)
        if (!file.exists()) environment.download(current, record, file)
        environment.requireIdentity(current)
        val parsed = validatedContents(file, record)
        environment.requireIdentity(current)
        val local = environment.snapshot()
        environment.requireIdentity(current)
        save(active.copy(stage = "PREVIEW", generation = current.generation))
        preview.value = CloudRestorePreview(current, record, parsed, cloudSummary(local), local.meta.datasetId, local.meta.revision)
        updateBlocking()
    }

    fun restore() = run {
        checkLocalTransfer()
        val shown = requireNotNull(preview.value) { "请先查看备份预览。" }
        environment.requireIdentity(shown.identity)
        val active = requireNotNull(task.value)
        // Recheck the private payload, not just the list metadata or a previous preview.
        val incoming = validatedContents(payload(active), shown.record)
        val local = environment.snapshot()
        environment.requireIdentity(shown.identity)
        if (local.meta.datasetId != shown.localDatasetId || local.meta.revision != shown.localRevision) {
            preview.value = shown.copy(current = cloudSummary(local), localDatasetId = local.meta.datasetId, localRevision = local.meta.revision)
            error.value = "本机记录刚刚有变化，尚未恢复。请重新核对数量，再确认替换。"
            return@run
        }
        save(active.copy(stage = "APPLYING"))
        environment.restore(incoming, shown.identity, shown.localDatasetId, shown.localRevision)
        environment.requireIdentity(shown.identity)
        clearTask()
        message.value = "恢复完成。原本机记录已保存为恢复前自动备份，可在设置中取出。"
    }

    fun cancelTask() {
        if (busy.value) return
        try { clearTask(); error.value = null; message.value = "已取消待处理的云备份操作，可继续查看本机记录。" }
        catch (e: Exception) { error.value = e.message ?: "无法取消操作，请重试。" }
    }

    private suspend fun validatedContents(file: File, record: CloudBackupRecord): Dataset {
        validateFile(file, record)
        val data = environment.readBackup(file)
        require(data.kind == "FULL_BACKUP") { "该文件不是完整备份，本机记录未改动。" }
        // readBackup upgrades supported old schemas/appVersion before returning the dataset.
        require(data.meta.datasetId == record.datasetId && data.meta.revision == record.clientRevision) { "备份内容与云端信息不一致，本机记录未改动。" }
        val summary = cloudSummary(data)
        require(summary.counts == record.recordCounts && summary.firstDate == record.firstDate && summary.lastDate == record.lastDate) {
            "备份记录摘要校验失败，本机记录未改动。"
        }
        return data
    }
}

fun cloudSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun validateRecord(record: CloudBackupRecord, identity: CloudIdentity) {
    require(UUID.fromString(record.backupId).toString() == record.backupId) { "云端备份编号无效。" }
    require(record.ownerId == identity.uid && record.objectPath == "${identity.uid}/${record.backupId}.foodlog.gz") { "这份备份不属于当前账号。" }
    require(record.sizeBytes in 1..CloudConfig.MAX_COMPRESSED_BYTES && record.sha256.matches(Regex("[a-f0-9]{64}"))) { "云端备份文件信息无效。" }
    require(record.schemaVersion in 1..3) { "这份备份需要更高版本的食记，请先升级应用。" }
}

private fun validateFile(file: File, record: CloudBackupRecord) {
    require(file.isFile && file.length() == record.sizeBytes) { "备份文件不完整，请取消后重新创建或选择备份，本机记录未改动。" }
    require(cloudSha256(file) == record.sha256) { "备份文件校验失败，请取消后重新创建或选择备份，本机记录未改动。" }
}

private fun atomicMove(source: File, destination: File) {
    try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}
