package com.foodlog.app.cloud

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.*
import com.foodlog.app.FoodLogApplication
import com.foodlog.app.data.Repository
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.Dataset
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit

class AutomaticBackupCoordinator(private val app: FoodLogApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = app.cloudSessions
    private val repo = app.repository
    private val client = CloudBackupClient(sessions)
    private val directRequests = Channel<Long>(Channel.CONFLATED)
    @Volatile var interactiveBusy = false
    private val seed by lazy {
        dataJson.decodeFromString<Dataset>(app.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
    }
    private val environment = object : AutomaticBackupEnvironment {
        override val identity = sessions.identity
        override suspend fun login(username: String, password: String) = sessions.login(username, password)
        override suspend fun logout() = sessions.logout()
        override fun requireIdentity(expected: CloudIdentity) = sessions.requireIdentity(expected)
        override suspend fun snapshot() = repo.snapshot()
        override suspend fun writeBackup(file: File) { file.outputStream().use { repo.writeBackup(it) } }
        override suspend fun readBackup(file: File) = file.inputStream().use { repo.readBackup(it) }
        override suspend fun restore(data: Dataset, expected: CloudIdentity, datasetId: String, revision: Long) {
            repo.restore(data, datasetId, revision) {
                sessions.requireIdentity(expected)
                check(!restoreBlocked()) { "正在编辑或处理文件，稍后再恢复" }
            }
        }
        override suspend fun list(identity: CloudIdentity, offset: Int) = client.list(identity, offset)
        override suspend fun upload(identity: CloudIdentity, record: CloudBackupRecord, file: File) = client.upload(identity, record, file)
        override suspend fun download(identity: CloudIdentity, record: CloudBackupRecord, file: File) = client.download(identity, record, file)
        override suspend fun commit(identity: CloudIdentity, record: CloudBackupRecord, file: File, expectedHead: String?) =
            client.uploadAutomatic(identity, record, file, expectedHead)
        override fun isPristine(data: Dataset) = isPristineInstallation(data, seed)
        override fun restoreBlocked() = interactiveBusy || File(app.filesDir, "cloud-transfers/active.json").exists() ||
            File(app.filesDir, "transfers/active.json").exists()
    }
    val engine = AutomaticBackupEngine(environment, File(app.filesDir, "automatic-cloud"),
        "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" })
    val status = engine.status

    fun start() {
        // Some networks can reach domestic CloudBase while Android's validation probe fails.
        // Try the real HTTPS endpoint while this process is alive; durable WorkManager remains
        // responsible for retry/reboot scheduling. All runners share the engine mutex/journal.
        scope.launch {
            var retryJob: Job? = null
            var retryDelay = 10_000L
            for (delayMillis in directRequests) {
                retryJob?.cancel()
                delay(delayMillis)
                val identity = sessions.identity.value ?: continue
                try {
                    repo.initialize()
                    if (engine.run(identity) == AutomaticBackupResult.RETRY) {
                        val wait = retryDelay
                        retryDelay = (retryDelay * 2).coerceAtMost(900_000L)
                        retryJob = scope.launch {
                            delay(wait)
                            if (sessions.identity.value == identity) directRequests.trySend(0L)
                        }
                    } else retryDelay = 10_000L
                }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { engine.waiting(sessions.identity.value != null) }
            }
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kick() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) kick()
            }
        })
        scope.launch {
            sessions.identity.collect { identity ->
                engine.accountChanged(identity != null)
                if (identity != null) {
                    schedule(identity, immediate = true)
                    val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(network()).setInputData(input(identity)).build()
                    WorkManager.getInstance(app).enqueueUniquePeriodicWork("foodlog-auto-periodic",
                        ExistingPeriodicWorkPolicy.UPDATE, request)
                } else {
                    WorkManager.getInstance(app).cancelUniqueWork("foodlog-auto-periodic")
                }
            }
        }
        scope.launch {
            try {
                repo.initialize()
                repo.state.map { it.meta.datasetId to it.meta.revision }.distinctUntilChanged().collect {
                    sessions.identity.value?.let { identity ->
                        engine.waiting(true)
                        schedule(identity, immediate = false)
                    }
                }
            } catch (_: Exception) { engine.waiting(sessions.identity.value != null) }
        }
    }

    fun kick() { sessions.identity.value?.let { schedule(it, immediate = true) } }

    private fun schedule(identity: CloudIdentity, immediate: Boolean) {
        directRequests.trySend(if (immediate) 0L else 4000L)
        val request = OneTimeWorkRequestBuilder<AutomaticBackupWorker>().setConstraints(network())
            .setInputData(input(identity)).setInitialDelay(if (immediate) 0 else 4, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        // APPEND_OR_REPLACE never cancels a running upload/restore. Duplicate tail jobs are cheap
        // and content fingerprints prevent duplicate snapshots. Work survives process/device restart.
        val name = "foodlog-auto-${identity.uid}-${identity.generation}"
        WorkManager.getInstance(app).enqueueUniqueWork(if (immediate) "$name-now" else name,
            if (immediate) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun keepLocal(): Boolean {
        val identity = sessions.identity.value ?: return false
        val accepted = engine.keepLocal(identity)
        if (accepted) kick()
        return accepted
    }

    suspend fun runWorker(expectedUid: String?, expectedGeneration: String?): AutomaticBackupResult {
        val identity = sessions.identity.value ?: return AutomaticBackupResult.DONE
        if (identity.uid != expectedUid || identity.generation != expectedGeneration) return AutomaticBackupResult.DONE
        repo.initialize()
        return engine.run(identity)
    }

    companion object {
        private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private fun input(identity: CloudIdentity) = workDataOf("uid" to identity.uid, "generation" to identity.generation)
    }
}

class AutomaticBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FoodLogApplication
        return try {
            when (app.automaticBackup.runWorker(inputData.getString("uid"), inputData.getString("generation"))) {
                AutomaticBackupResult.RETRY -> Result.retry()
                else -> Result.success()
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Result.retry() }
    }
}
