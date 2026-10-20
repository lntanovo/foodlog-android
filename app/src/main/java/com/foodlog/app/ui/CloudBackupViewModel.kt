package com.foodlog.app.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foodlog.app.FoodLogApplication
import com.foodlog.app.cloud.CloudBackupClient
import com.foodlog.app.cloud.CloudBackupEngine
import com.foodlog.app.cloud.CloudBackupEnvironment
import com.foodlog.app.cloud.CloudBackupRecord
import com.foodlog.app.cloud.CloudIdentity
import com.foodlog.app.domain.Dataset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FoodLogApplication
    private val sessions = app.cloudSessions
    private val repo = app.repository
    private val client = CloudBackupClient(sessions)
    private val environment = object : CloudBackupEnvironment {
        override val identity = sessions.identity
        override suspend fun login(username: String, password: String) = sessions.login(username, password)
        override suspend fun logout() = sessions.logout()
        override fun requireIdentity(expected: CloudIdentity) = sessions.requireIdentity(expected)
        override suspend fun snapshot() = repo.snapshot()
        override suspend fun writeBackup(file: File) { file.outputStream().use { repo.writeBackup(it) } }
        override suspend fun readBackup(file: File) = file.inputStream().use { repo.readBackup(it) }
        override suspend fun restore(data: Dataset, expected: CloudIdentity, datasetId: String, revision: Long) {
            repo.restore(data, expectedLocalDatasetId = datasetId, expectedLocalRevision = revision,
                preflight = { sessions.requireIdentity(expected) })
        }
        override suspend fun list(identity: CloudIdentity, offset: Int) = client.list(identity, offset)
        override suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File) = client.upload(identity, record, file)
        override suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File) = client.download(identity, record, file)
    }
    private val engine = CloudBackupEngine(viewModelScope, environment,
        File(application.filesDir, "cloud-transfers"),
        "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" })
    val identity = engine.identity
    val busy = engine.busy
    val error = engine.error
    val message = engine.message
    val records = engine.records
    val listOpen = engine.listOpen
    val listLoaded = engine.listLoaded
    val hasMore = engine.hasMore
    val preview = engine.preview
    val task = engine.task
    val blockLocalTransfer = engine.blockLocalTransfer
    val automaticStatus = app.automaticBackup.status
    fun backupNow() = app.automaticBackup.kick()
    fun keepLocal() = viewModelScope.launch {
        try {
            val accepted = withContext(Dispatchers.IO) { app.automaticBackup.keepLocal() }
            if (!accepted) engine.error.value = "云端记录刚刚有变化，请重新核对后选择。"
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { engine.error.value = "暂时无法核对云端记录，请联网后重试。本机记录未改动。" }
    }

    fun setLocalTransferActive(active: Boolean) = engine.setLocalTransferActive(active)
    fun login(username: String, password: String) = engine.login(username, password)
    fun logout() = engine.logout()
    fun backup() = engine.backup()
    fun showBackups() = engine.showBackups()
    fun moreBackups() = engine.moreBackups()
    fun hideBackups() = engine.hideBackups()
    fun preview(record: CloudBackupRecord) = engine.preview(record)
    fun continueTask() = engine.continueTask()
    fun restore() = engine.restore()
    fun cancelTask() = engine.cancelTask()
}
