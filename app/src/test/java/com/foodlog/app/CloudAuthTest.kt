package com.foodlog.app

import com.foodlog.app.cloud.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CloudAuthTest {
    private val generation = UUID.randomUUID().toString()
    private val initial = CloudSession("user-a", "tester", "access-old", "refresh-old", 1_001, generation)
    private val rotated = CloudAuthTokens("user-a", "access-new", "refresh-new", 7200)

    private class Store(var value: CloudSession?) : CloudSessionPersistence {
        var failSave = false
        var saves = 0
        override fun load() = value
        override fun save(session: CloudSession) {
            if (failSave) throw CloudException("无法安全保存登录状态")
            saves++
            value = session
        }
        override fun clear() { value = null }
        override fun deviceId() = "fixed-device"
    }

    private class Auth : CloudAuthGateway {
        val refreshes = AtomicInteger()
        var logins = 0
        var logouts = 0
        var loginAction: suspend (String, String) -> CloudAuthTokens = { _, _ ->
            CloudAuthTokens("user-a", "access-login", "refresh-login", 7200)
        }
        var refreshAction: suspend (String) -> CloudAuthTokens = {
            delay(25)
            CloudAuthTokens("user-a", "access-new", "refresh-new", 7200)
        }
        var logoutError: CloudException? = null
        override suspend fun login(username: String, password: String): CloudAuthTokens {
            logins++
            return loginAction(username, password)
        }
        override suspend fun refresh(refreshToken: String): CloudAuthTokens {
            refreshes.incrementAndGet()
            return refreshAction(refreshToken)
        }
        override suspend fun logout(accessToken: String) {
            logouts++
            logoutError?.let { throw it }
        }
    }

    private suspend fun expectCloudFailure(action: suspend () -> Unit): CloudException {
        try { action() } catch (error: CloudException) { return error }
        throw AssertionError("应拒绝当前云操作")
    }

    @Test fun concurrentRequestsRefreshOnceAndCommitThePairTogether() = runBlocking<Unit> {
        val store = Store(initial)
        val auth = Auth()
        val manager = CloudSessionManager(store, auth) { 10_000L }
        val identity = requireNotNull(manager.identity.value)
        val tokens = List(24) { async(Dispatchers.Default) { manager.accessToken(identity) } }.awaitAll()
        assertEquals(setOf("access-new"), tokens.toSet())
        assertEquals(1, auth.refreshes.get())
        assertEquals(1, store.saves)
        assertEquals("access-new", store.value!!.accessToken)
        assertEquals("refresh-new", store.value!!.refreshToken)
        assertEquals(identity, manager.identity.value)
    }

    @Test fun cancellingCallerAfterRefreshStartsStillCommitsRotatedTokens() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store(initial)
        val auth = Auth().apply {
            refreshAction = { started.complete(Unit); release.await(); rotated }
        }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        val expected = manager.identity.value!!
        val job = launch(Dispatchers.Default) { manager.accessToken(expected) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals("refresh-new", store.value!!.refreshToken)
        val recreated = CloudSessionManager(store, Auth()) { 10_000L }
        assertEquals("access-new", recreated.accessToken(expected))
    }

    @Test fun validSessionSurvivesManagerRecreationWithoutAnotherLogin() = runBlocking<Unit> {
        val store = Store(initial.copy(expiresAtMillis = 1_000_000))
        val first = CloudSessionManager(store, Auth()) { 10_000L }
        val auth = Auth()
        val recreated = CloudSessionManager(store, auth) { 10_000L }
        assertEquals(first.identity.value, recreated.identity.value)
        assertEquals("access-old", recreated.accessToken(first.identity.value!!))
        assertEquals(0, auth.refreshes.get())
    }

    @Test fun reloginToSameAccountInvalidatesEarlierOperations() = runBlocking<Unit> {
        val manager = CloudSessionManager(Store(initial), Auth()) { 10_000L }
        val old = manager.identity.value!!
        manager.login("tester", "example-password")
        assertEquals(old.uid, manager.identity.value!!.uid)
        assertNotEquals(old.generation, manager.identity.value!!.generation)
        expectCloudFailure { manager.accessToken(old) }
        expectCloudFailure { manager.requireIdentity(old) }
    }

    @Test fun transientRefreshFailureKeepsSessionAvailableForRetry() = runBlocking<Unit> {
        val store = Store(initial)
        val auth = Auth().apply { refreshAction = { throw CloudException("连接超时") } }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        val old = manager.identity.value!!
        expectCloudFailure { manager.accessToken(old) }
        assertEquals(old, manager.identity.value)
        assertEquals(initial, store.value)
        auth.refreshAction = { rotated }
        assertEquals("access-new", manager.accessToken(old))
    }

    @Test fun rejectedRefreshClearsOnlyCloudSession() = runBlocking<Unit> {
        val store = Store(initial)
        val auth = Auth().apply { refreshAction = { throw CloudException("登录已失效", 401) } }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        expectCloudFailure { manager.accessToken(manager.identity.value!!) }
        assertNull(manager.identity.value)
        assertNull(store.value)
    }

    @Test fun refreshedIdentityMustMatchTheOriginalAccount() = runBlocking<Unit> {
        val store = Store(initial)
        val auth = Auth().apply { refreshAction = { rotated.copy(uid = "other-user") } }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        expectCloudFailure { manager.accessToken(manager.identity.value!!) }
        assertNull(manager.identity.value)
        assertNull(store.value)
    }

    @Test fun failedLoginPersistenceDoesNotReplaceExistingAccount() = runBlocking<Unit> {
        val store = Store(initial).apply { failSave = true }
        val manager = CloudSessionManager(store, Auth()) { 10_000L }
        val before = manager.identity.value
        expectCloudFailure { manager.login("tester", "example-password") }
        assertEquals(before, manager.identity.value)
        assertEquals(initial, store.value)
    }

    @Test fun cannotClaimRefreshedSessionWhenItsPersistenceFailed() = runBlocking<Unit> {
        val store = Store(initial).apply { failSave = true }
        val manager = CloudSessionManager(store, Auth()) { 10_000L }
        expectCloudFailure { manager.accessToken(manager.identity.value!!) }
        assertNull(manager.identity.value)
        assertNull(store.value)
    }

    @Test fun offlineLogoutStillForgetsLocalTokens() = runBlocking<Unit> {
        val store = Store(initial)
        val auth = Auth().apply { logoutError = CloudException("离线") }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        val old = manager.identity.value!!
        manager.logout()
        assertNull(manager.identity.value)
        assertNull(store.value)
        assertEquals(1, auth.logouts)
        expectCloudFailure { manager.accessToken(old) }
    }

    @Test fun logoutQueuedDuringRefreshCannotResurrectSession() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store(initial)
        val auth = Auth().apply { refreshAction = { started.complete(Unit); release.await(); rotated } }
        val manager = CloudSessionManager(store, auth) { 10_000L }
        val expected = manager.identity.value!!
        val refresh = launch(Dispatchers.Default) { manager.accessToken(expected) }
        started.await()
        val logout = launch(Dispatchers.Default) { manager.logout() }
        release.complete(Unit)
        refresh.join()
        logout.join()
        assertNull(manager.identity.value)
        assertNull(store.value)
    }

    @Test fun invalidCredentialsNeverReachTransport() = runBlocking<Unit> {
        val auth = Auth()
        val manager = CloudSessionManager(Store(null), auth) { 10_000L }
        expectCloudFailure { manager.login("中文", "pass") }
        expectCloudFailure { manager.login("valid-user", "") }
        assertEquals(0, auth.logins)
    }

    @Test fun authUsesDocumentedEndpointsAndPersistentDeviceHeader() = runBlocking<Unit> {
        val requests = mutableListOf<CloudJsonRequest>()
        val transport = CloudJsonTransport { request ->
            requests += request
            buildJsonObject {
                put("token_type", "Bearer")
                put("sub", "user-a")
                put("access_token", "sensitive-access")
                put("refresh_token", "sensitive-refresh")
                put("expires_in", 7200)
            }
        }
        val gateway = CloudAuth(transport) { "fixed-device" }
        gateway.login("tester", "sensitive-password")
        gateway.refresh("sensitive-refresh")
        gateway.logout("sensitive-access")
        assertEquals(listOf("/auth/v1/signin", "/auth/v1/token", "/auth/v1/user/signout"), requests.map { it.path })
        assertTrue(requests.all { it.method == "POST" && it.extraHeaders["x-device-id"] == "fixed-device" })
        assertNull(requests[0].bearerToken)
        assertNull(requests[1].bearerToken)
        assertEquals("sensitive-access", requests[2].bearerToken)
        assertEquals("refresh_token", (requests[1].body as JsonObject)["grant_type"]!!.jsonPrimitive.content)
        assertFalse(requests[0].toString().contains("sensitive"))
    }

    @Test fun incompleteTokensAreRejectedBeforePersistence() = runBlocking<Unit> {
        val gateway = CloudAuth(CloudJsonTransport {
            buildJsonObject {
                put("token_type", "Bearer"); put("sub", "a"); put("access_token", "token"); put("expires_in", 7200)
            }
        }) { "fixed-device" }
        expectCloudFailure { gateway.login("tester", "password") }
    }

    @Test fun errorsAndDebugStringsNeverEchoServerCredentials() {
        val body = Json.parseToJsonElement("""{"error":"unexpected","error_description":"secret-password token-abc"}""")
        val error = CloudHttp.safeError(400, body)
        assertFalse(error.message!!.contains("secret-password"))
        assertFalse(error.message!!.contains("token-abc"))
        assertFalse(initial.toString().contains("access-old"))
        assertFalse(rotated.toString().contains("refresh-new"))
        assertEquals("用户名或密码不正确", CloudHttp.safeError(400, Json.parseToJsonElement("""{"error":"invalid_username_or_password"}""")).message)
    }
}
