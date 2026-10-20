package com.foodlog.app

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foodlog.app.data.dataJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutomaticCloudUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun signingInThroughSettingsRestoresAPristineDeviceWithoutManualBackupOrRestore() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("autoSyntheticOnly") == "yes")
        check(Build.MODEL.contains("sdk", true) || Build.FINGERPRINT.contains("emulator", true))
        val app = compose.activity.application as FoodLogApplication
        val config = dataJson.parseToJsonElement(File(app.filesDir, "auto-synthetic-config.json").readText()).jsonObject
        val username = config.getValue("name").jsonPrimitive.content
        check(username.startsWith("foodlog_auto_"))
        assertNull(app.cloudSessions.identity.value)
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.repository.snapshot().meals.isEmpty() })
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("cloudLogin").performScrollTo().performClick()
        compose.onNodeWithTag("cloudUsername").performTextInput(username)
        compose.onNodeWithTag("cloudPassword").performTextInput(config.getValue("password").jsonPrimitive.content)
        compose.onNodeWithTag("cloudLoginConfirm").performClick()
        compose.waitUntil(150_000) {
            runBlocking { app.repository.snapshot().meals.size == 2 } &&
                app.automaticBackup.status.value.phase in setOf("RESTORED", "CURRENT")
        }
        compose.onNodeWithTag("automaticCloudStatus").performScrollTo().assertExists()
        assertEquals("75.6", runBlocking { app.repository.snapshot().bodies.single().weight })
        assertNotNull(app.repository.latestRecovery())
        runBlocking { app.cloudSessions.logout() }
        assertEquals(2, runBlocking { app.repository.snapshot().meals.size })
    }
}
