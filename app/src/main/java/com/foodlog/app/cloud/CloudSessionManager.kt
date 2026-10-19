package com.foodlog.app.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class CloudSessionManager internal constructor(
    private val store: CloudSessionPersistence,
    private val auth: CloudAuthGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, config: CloudConfig = CloudConfig()) : this(
        CloudSessionStore(context.applicationContext), config,
    )

    private constructor(store: CloudSessionStore, config: CloudConfig) : this(
        store, CloudAuth(CloudHttp(config), store::deviceId),
    )

    private val mutex = Mutex()
    private var session = try { store.load() } catch (_: CloudException) { null }
    private val mutableIdentity = MutableStateFlow(session?.identity)
    val identity: StateFlow<CloudIdentity?> = mutableIdentity.asStateFlow()

    suspend fun login(username: String, password: String) {
        val normalized = username.trim()
        if (!normalized.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) {
            throw CloudException("用户名需为 1～64 位字母、数字或 . _ -，且以字母或数字开头")
        }
        if (password.isEmpty() || password.length > 1024) throw CloudException("请输入有效密码")
        mutex.withLock {
            val tokens = auth.login(normalized, password)
            val next = CloudSession(tokens.uid, normalized, tokens.accessToken, tokens.refreshToken,
                expiry(tokens.expiresInSeconds), UUID.randomUUID().toString())
            withContext(NonCancellable + Dispatchers.IO) {
                store.save(next)
                session = next
                mutableIdentity.value = next.identity
            }
        }
    }

    suspend fun logout() {
        mutex.withLock {
            val previous = withContext(NonCancellable + Dispatchers.IO) {
                store.clear()
                val old = session
                session = null
                mutableIdentity.value = null
                old
            }
            // Keep logout before any subsequent login, including providers that revoke by device.
            if (previous != null) {
                try { auth.logout(previous.accessToken) } catch (_: CloudException) {
                    // Local logout must succeed offline. Local food and body records are untouched.
                }
            }
        }
    }

    fun requireIdentity(expected: CloudIdentity) {
        val active = mutableIdentity.value
        if (active == null || active.uid != expected.uid || active.generation != expected.generation) {
            throw CloudException("登录账号已改变，请重新打开云备份后重试")
        }
    }

    suspend fun accessToken(expected: CloudIdentity): String = mutex.withLock {
        requireIdentity(expected)
        val current = session ?: throw CloudException("请先登录云备份账号")
        if (current.expiresAtMillis > now() + REFRESH_EARLY_MILLIS) return@withLock current.accessToken
        // Refresh rotates both tokens remotely. Complete its atomic local commit even if a UI task is cancelled.
        withContext(NonCancellable + Dispatchers.IO) {
            val tokens = try {
                auth.refresh(current.refreshToken)
            } catch (error: CloudException) {
                if (error.httpStatus in setOf(400, 401, 403)) invalidateSession()
                throw error
            }
            if (tokens.uid != current.uid) {
                invalidateSession()
                throw CloudException("云端登录身份异常，请重新登录")
            }
            val next = current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
                expiresAtMillis = expiry(tokens.expiresInSeconds))
            try {
                store.save(next)
            } catch (error: CloudException) {
                invalidateSession()
                throw error
            }
            session = next
            mutableIdentity.value = next.identity
            requireIdentity(expected)
            next.accessToken
        }
    }

    private fun invalidateSession() {
        session = null
        mutableIdentity.value = null
        try { store.clear() } catch (_: CloudException) { /* Stale persisted tokens cannot authorize another user. */ }
    }

    private fun expiry(seconds: Long): Long {
        if (seconds !in 1..31_536_000) throw CloudException("云端登录有效期异常，请重新登录")
        val time = now()
        if (time < 0 || time > Long.MAX_VALUE - seconds * 1000) throw CloudException("手机时间异常，请检查后重试")
        return time + seconds * 1000
    }

    companion object { private const val REFRESH_EARLY_MILLIS = 60_000L }
}
