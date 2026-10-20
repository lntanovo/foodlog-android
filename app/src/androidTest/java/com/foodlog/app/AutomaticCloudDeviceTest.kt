package com.foodlog.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foodlog.app.cloud.*
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in synthetic environment only. No credentials or real user records are bundled. */
@RunWith(AndroidJUnit4::class)
class AutomaticCloudDeviceTest {
    @Test fun automaticBackupAndNewDeviceRecovery() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("autoSyntheticOnly") == "yes")
        check(Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.contains("emulator", true))
        val app = instrumentation.targetContext.applicationContext as FoodLogApplication
        val config = dataJson.parseToJsonElement(File(app.filesDir, "auto-synthetic-config.json").readText()).jsonObject
        val username = config.getValue("name").jsonPrimitive.content
        val password = config.getValue("password").jsonPrimitive.content
        val expectedUid = config.getValue("uid").jsonPrimitive.content
        check(username.startsWith("foodlog_auto_"))
        val repo = app.repository; repo.initialize()
        val client = CloudBackupClient(app.cloudSessions)
        suspend fun awaitState(predicate: suspend () -> Boolean) {
            withTimeout(150_000) { while (!predicate()) delay(1000) }
        }
        suspend fun remote(): Dataset? {
            val identity = app.cloudSessions.identity.value ?: return null
            val row = client.list(identity).firstOrNull() ?: return null
            val file = File(app.cacheDir, "synthetic-verification.foodlog.gz")
            client.download(identity, row, file)
            return file.inputStream().use { repo.readBackup(it) }
        }
        when (args.getString("autoRole")) {
            "upload" -> {
                check(app.cloudSessions.identity.value == null)
                val label = repo.snapshot().versions.first()
                val items = listOf(NutritionCalculator.item(label, "100", label.basisUnit))
                repo.meal(Meal(date = "2026-10-20", type = "午餐", items = items), true)
                repo.meal(Meal(date = "2026-10-20", type = "晚餐", items = items.map { it.copy(id = uid()) }), true)
                repo.day(DayLog("2026-10-20"), BodyRecord("2026-10-20", "71.2"))
                repo.saveTemplate("Synthetic meal", repo.snapshot().meals.first())
                app.cloudSessions.login(username, password)
                assertEquals(expectedUid, app.cloudSessions.identity.value?.uid)
                // No manual backup/engine run/kick: session and repository observers schedule work.
                awaitState { remote()?.meals?.size == 2 }
                repo.day(DayLog("2026-10-20"), BodyRecord("2026-10-20", "72.3"))
                awaitState { remote()?.bodies?.singleOrNull()?.weight == "72.3" }
                assertTrue(client.list(app.cloudSessions.identity.value!!).size >= 2)
            }
            "offline" -> {
                assertEquals(expectedUid, app.cloudSessions.identity.value?.uid)
                repo.day(DayLog("2026-10-20"), BodyRecord("2026-10-20", "75.6"))
                assertEquals("75.6", repo.snapshot().bodies.single().weight)
            }
            "verify" -> {
                assertEquals(expectedUid, app.cloudSessions.identity.value?.uid)
                awaitState { remote()?.bodies?.singleOrNull()?.weight == "75.6" }
                assertEquals(2, repo.snapshot().meals.size)
            }
            "restore" -> {
                check(app.cloudSessions.identity.value == null)
                assertTrue(repo.snapshot().meals.isEmpty())
                app.cloudSessions.login(username, password)
                awaitState { repo.snapshot().meals.size == 2 && repo.snapshot().bodies.singleOrNull()?.weight == "75.6" }
                assertEquals(1, repo.snapshot().templates.size)
                val recovery = requireNotNull(repo.latestRecovery())
                val previous = recovery.inputStream().use { repo.readBackup(it) }
                assertTrue(previous.meals.isEmpty())
                val current = cloudContentFingerprint(repo.snapshot())
                app.cloudSessions.logout()
                assertEquals(current, cloudContentFingerprint(repo.snapshot()))
            }
            else -> error("Missing synthetic automatic backup role")
        }
    }
}
