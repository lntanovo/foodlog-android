package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataInspectionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = (compose.activity.application as FoodLogApplication).repository

    @Before fun setup() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().readText())
            repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta()))
            val v = repo.snapshot().versions.first()
            repo.meal(Meal(date = "2026-10-01", items = listOf(NutritionCalculator.item(v, "60", v.basisUnit))), true)
            repo.meal(Meal(date = "2026-10-02", items = listOf(NutritionCalculator.item(v, null, v.basisUnit))), false)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Native dialog/Activity window animations are outside Compose's test clock.
        android.os.SystemClock.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun diagnosticsShowsHistoricalDraftsAndSurvivesRecreationWithoutChangingData() {
        val before = runBlocking { repo.snapshot() }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("数据诊断").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-meals").assertTextEquals("已吃 1 餐 · 草稿 1 餐")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("diagnostics-meals").assertTextEquals("已吃 1 餐 · 草稿 1 餐")
        screenshot("141-diagnostics.png")
        compose.onNodeWithTag("diagnostics-list").performScrollToNode(hasText("2026-10-01 · 已吃 1 · 草稿 0"))
        compose.onNodeWithText("2026-10-01 · 已吃 1 · 草稿 0").performClick()
        compose.onNodeWithContentDescription("记一餐").assertExists()
        assertEquals(before.meals, runBlocking { repo.snapshot().meals })
        assertEquals(before.bodies, runBlocking { repo.snapshot().bodies })
    }

    @Test fun olderProtectionPreviewShowsEqualCountChangesAndCancelKeepsRecords() {
        val selected = runBlocking { repo.recoveryBackup() }
        runBlocking {
            val meal = repo.snapshot().meals.first { it.status == "POSTED" }
            repo.meal(meal.copy(items = meal.items.map { NutritionCalculator.item(it.snapshot, "120", it.unit, it.id) }), true)
        }
        val before = runBlocking { repo.snapshot() }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("历史保护副本").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(selected.name).fetchSemanticsNodes().isNotEmpty() }
        screenshot("141-recovery-history.png")
        compose.onAllNodesWithText("校验并预览").onFirst().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("restore-difference").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看具体变化").performScrollTo().performClick()
        compose.onNodeWithText("变更 · 餐食").assertExists()
        screenshot("141-restore-difference.png")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(before.meals, runBlocking { repo.snapshot().meals })
        assertTrue(selected.exists())
    }
}
