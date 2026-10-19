package com.foodlog.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an isolated emulator: these tests reset only its synthetic app records. No cloud request is made. */
@RunWith(AndroidJUnit4::class)
class CloudBackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val application get() = compose.activity.application as FoodLogApplication
    private val repo get() = application.repository

    @Before fun resetSyntheticData() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()
        }
        assumeTrue("本组测试不退出或使用任何真实云账号", application.cloudSessions.identity.value == null)
        runBlocking {
            val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
            repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta()))
            repo.day(DayLog("2026-10-18", training = "REST"), BodyRecord("2026-10-18", "65"))
        }
        compose.waitForIdle()
    }

    private fun openLogin() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("cloudLogin").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("cloudUsername").assertExists()
        compose.onNodeWithTag("cloudPassword").assertExists()
    }

    private fun assertBusinessUnchanged(before: Dataset) {
        val after = runBlocking { repo.snapshot() }
        assertEquals(before.foods, after.foods)
        assertEquals(before.versions, after.versions)
        assertEquals(before.meals, after.meals)
        assertEquals(before.days, after.days)
        assertEquals(before.bodies, after.bodies)
        assertEquals(before.plans, after.plans)
        assertEquals(before.targets, after.targets)
        assertEquals(before.templates, after.templates)
        assertEquals(before.origins, after.origins)
        assertEquals(before.meta, after.meta)
    }

    @Test fun offlineMealCanBeSavedBeforeLoginAndCancellingLoginDoesNotChangeRecords() {
        compose.onNodeWithContentDescription("记一餐").performClick()
        compose.onNodeWithTag("meal-content").performScrollToNode(hasTestTag("choose-food-buckwheat_noodles_60g"))
        compose.onNodeWithTag("choose-food-buckwheat_noodles_60g").performClick()
        compose.onNodeWithTag("meal-content").performScrollToNode(hasTestTag("本次份量（留空为待补）"))
        compose.onNodeWithTag("本次份量（留空为待补）").performTextInput("60")
        compose.onNodeWithText("保存已吃").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()
        }
        val before = runBlocking { repo.snapshot() }
        assertEquals(1, before.meals.count { it.status == "POSTED" })
        assertNull(application.cloudSessions.identity.value)
        openLogin()
        compose.onNodeWithTag("cloudUsername").performTextInput("synthetic-ui-only")
        compose.onNodeWithTag("cloudPassword").performTextInput("not-a-real-password")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("cloudPassword").assertDoesNotExist()
        assertNull(application.cloudSessions.identity.value)
        assertBusinessUnchanged(before)
    }

    @Test fun loginRecreationRetainsUsernameButClearsPasswordAndNeverChangesLocalData() {
        val before = runBlocking { repo.snapshot() }
        openLogin()
        compose.onNodeWithTag("cloudUsername").performTextInput("synthetic-ui-only")
        compose.onNodeWithTag("cloudPassword").performTextInput("not-a-real-password")
        assertEquals("•".repeat("not-a-real-password".length),
            compose.onNodeWithTag("cloudPassword").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("cloudPassword").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("cloudUsername").assertTextContains("synthetic-ui-only")
        assertEquals("", compose.onNodeWithTag("cloudPassword").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("cloudLoginConfirm").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        assertNull(application.cloudSessions.identity.value)
        assertBusinessUnchanged(before)
    }
}
