package com.foodlog.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.foodlog.app.FoodLogApplication
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.Dataset
import com.foodlog.app.domain.RestoreDifference
import com.foodlog.app.domain.restoreDifference
import com.foodlog.app.domain.uid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPOutputStream

/** Only this small journal is saved with the operation; backup contents stay in private files. */
@Serializable
data class TransferTask(
    val id: String = uid(), val operation: String, val expectedKind: String = "FULL_BACKUP",
    val path: String, val filename: String = "", val exportIsFull: Boolean = false,
    val stage: String, val uri: String? = null, val replacements: Set<String> = emptySet(),
    val includePlans: Boolean = false, val recoveryName: String? = null
)

class TransferViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val repo = (application as FoodLogApplication).repository
    private val resolver = application.contentResolver
    private val directory = File(application.filesDir, "transfers").apply { mkdirs() }
    private val journal = File(directory, "active.json")
    val task = MutableStateFlow(readTask())
    val incoming = MutableStateFlow<Dataset?>(null)
    val previewLocal = MutableStateFlow<Dataset?>(null)
    val difference = MutableStateFlow<RestoreDifference?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val notices = Channel<String>(Channel.BUFFERED)

    init {
        task.value?.let { active ->
            when (active.stage) {
                "PREVIEW", "APPLYING" -> runOperation {
                    loadPreview(active)
                    if (active.stage == "APPLYING") {
                        update(active.copy(stage = "PREVIEW"))
                        error.value = "上次导入被中断，请核对当前记录后重新确认。"
                    }
                }
                "IMPORT_COPY" -> runOperation { copyImport(active) }
                "EXPORT_WRITING" -> runOperation { writeExport(active) }
                "PREPARING" -> {
                    cancel()
                    if (task.value == null) error.value = "上次备份准备被中断，请重新导出。"
                }
            }
        }
    }

    private fun readTask(): TransferTask? = runCatching {
        dataJson.decodeFromString<TransferTask>(journal.readText())
    }.getOrNull()

    private fun payload(active: TransferTask): File = File(directory, active.path).also {
        require(it.canonicalFile.parentFile == directory.canonicalFile) { "文件任务路径无效，请取消后重试" }
    }

    private fun update(active: TransferTask) {
        val temporary = File(directory, "active.json.new")
        try {
            temporary.outputStream().use { stream ->
                stream.write(dataJson.encodeToString(active).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        saved["transferTaskId"] = active.id
        task.value = active
    }

    private fun updateSafely(active: TransferTask): Boolean = try {
        update(active)
        true
    } catch (e: Exception) {
        error.value = "无法保存文件操作进度：${e.message ?: "本机存储写入失败"}。可取消后重试。"
        false
    }

    private fun clear() {
        // Remove the journal first so cancelled/completed work cannot reappear next launch.
        require(!journal.exists() || journal.delete()) { "无法清理文件操作记录，请重试取消或完成操作" }
        val previous = task.value
        saved.remove<String>("transferTaskId")
        task.value = null
        incoming.value = null
        previewLocal.value = null
        difference.value = null
        // Leftover payload files are harmless once there is no active journal.
        runCatching { File(directory, "active.json.new").delete() }
        previous?.let { runCatching { payload(it).delete(); File(directory, "${it.path}.partial").delete() } }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "文件操作失败，请重试" }
            finally { busy.value = false }
        }
    }

    fun prepareExport(filename: String, library: Boolean = false, selected: Set<String> = emptySet(), recovery: Boolean = false, recoveryName: String? = null) = runOperation {
        require(task.value == null) { "请先完成或取消当前文件操作" }
        val active = TransferTask(operation = "EXPORT", path = "${uid()}.foodlog.gz", filename = filename,
            exportIsFull = !library && !recovery && recoveryName == null, stage = "PREPARING")
        update(active)
        try {
            withContext(Dispatchers.IO) {
                if (recovery || recoveryName != null) {
                    val source = recoveryName?.let(repo::recoveryFile) ?: repo.latestRecovery() ?: error("还没有恢复前自动备份")
                    source.inputStream().use { repo.readBackup(it) }
                    source.inputStream().buffered().use { input ->
                        input.mark(2)
                        val gzip = input.read() == 0x1f && input.read() == 0x8b
                        input.reset()
                        if (gzip) payload(active).outputStream().use { input.copyTo(it) }
                        else GZIPOutputStream(payload(active).outputStream()).use { input.copyTo(it) }
                    }
                } else payload(active).outputStream().use { repo.writeBackup(it, library, selected) }
            }
            update(active.copy(stage = "EXPORT_READY"))
        } catch (e: Exception) { clear(); throw e }
    }

    fun prepareImport(kind: String) {
        if (busy.value || task.value != null) return
        error.value = null
        updateSafely(TransferTask(operation = "IMPORT", expectedKind = kind, path = "${uid()}.incoming", stage = "IMPORT_READY"))
    }

    fun previewRecovery(name: String) = runOperation {
        require(task.value == null) { "请先完成或取消当前文件操作" }
        val active = TransferTask(operation = "IMPORT", path = "${uid()}.incoming", stage = "PREPARING", recoveryName = name)
        update(active)
        try {
            withContext(Dispatchers.IO) { repo.recoveryFile(name).copyTo(payload(active)) }
            loadPreview(active)
            update(active.copy(stage = "PREVIEW"))
        } catch (e: Exception) { clear(); throw e }
    }

    fun pickerLaunched(): Boolean {
        if (busy.value) return false
        val active = task.value ?: return false
        return updateSafely(active.copy(stage = if (active.operation == "EXPORT") "EXPORT_PICKER" else "IMPORT_PICKER"))
    }

    fun chooseAgain() {
        if (busy.value) return
        val active = task.value ?: return
        error.value = null
        updateSafely(active.copy(stage = if (active.operation == "EXPORT") "EXPORT_READY" else "IMPORT_READY", uri = null))
    }

    fun picked(uri: Uri?) {
        if (busy.value) return
        val active = task.value ?: return
        if (uri == null) { cancel(); return }
        // Persisted document grants allow a copy interrupted by process death to resume.
        runCatching { resolver.takePersistableUriPermission(uri,
            if (active.operation == "EXPORT") Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val next = active.copy(uri = uri.toString(), stage = if (active.operation == "EXPORT") "EXPORT_WRITING" else "IMPORT_COPY")
        if (!updateSafely(next)) return
        runOperation { if (next.operation == "EXPORT") writeExport(next) else copyImport(next) }
    }

    private suspend fun writeExport(active: TransferTask) {
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(Uri.parse(requireNotNull(active.uri)), "wt")?.use { output ->
                payload(active).inputStream().use { it.copyTo(output) }
            } ?: error("无法写入目标文件，请重新选择保存位置")
        }
        if (active.exportIsFull) repo.markExport()
        clear()
        notices.send("文件已导出")
    }

    private suspend fun copyImport(active: TransferTask) {
        withContext(Dispatchers.IO) {
            val partial = File(directory, "${active.path}.partial")
            resolver.openInputStream(Uri.parse(requireNotNull(active.uri)))?.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= 300L * 1024 * 1024) { "所选文件超过300MB，请选择食记备份文件" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取所选文件，请重新选择")
            require(partial.renameTo(payload(active))) { "无法保存导入副本，请重试" }
        }
        loadPreview(active)
        update(active.copy(stage = "PREVIEW", uri = null))
    }

    private suspend fun loadPreview(active: TransferTask) {
        val parsed = withContext(Dispatchers.IO) { payload(active).inputStream().use { repo.readBackup(it) } }
        require(parsed.kind == active.expectedKind) { "文件类型不符，请取消并使用对应的恢复、食品库或旧数据入口" }
        val local = repo.snapshot()
        difference.value = if (parsed.kind == "FULL_BACKUP") withContext(Dispatchers.Default) { restoreDifference(local, parsed) } else null
        previewLocal.value = local
        incoming.value = parsed
    }

    fun replaceDay(day: String, checked: Boolean) {
        val active = task.value ?: return
        if (!busy.value) updateSafely(active.copy(replacements = if (checked) active.replacements + day else active.replacements - day))
    }

    fun includePlans(checked: Boolean) {
        val active = task.value ?: return
        if (!busy.value) updateSafely(active.copy(includePlans = checked))
    }

    fun applyImport() = runOperation {
        val active = task.value ?: error("导入任务已结束")
        val parsed = incoming.value ?: error("请等待预览读取完成")
        if (parsed.kind == "FULL_BACKUP") {
            val local = repo.snapshot()
            val shown = previewLocal.value
            if (shown == null || shown.meta.datasetId != local.meta.datasetId || shown.meta.revision != local.meta.revision) {
                previewLocal.value = local
                difference.value = withContext(Dispatchers.Default) { restoreDifference(local, parsed) }
                error.value = "本机记录刚刚有变化，尚未恢复。请重新核对差异，再确认替换。"
                return@runOperation
            }
        }
        update(active.copy(stage = "APPLYING"))
        try {
            if (parsed.kind == "FULL_BACKUP") {
                val shown = requireNotNull(previewLocal.value)
                repo.restore(parsed, shown.meta.datasetId, shown.meta.revision)
            }
            else {
                if (active.replacements.isNotEmpty()) repo.recoveryBackup()
                repo.merge(parsed, active.replacements, active.includePlans)
            }
            clear()
            notices.send(if (parsed.kind == "FULL_BACKUP") "恢复完成" else "导入完成")
        } catch (e: Exception) { update(active.copy(stage = "PREVIEW")); throw e }
    }

    fun cancel() {
        if (busy.value) return
        try { clear(); error.value = null }
        catch (e: Exception) { error.value = e.message ?: "无法取消文件操作，请重试" }
    }
}
