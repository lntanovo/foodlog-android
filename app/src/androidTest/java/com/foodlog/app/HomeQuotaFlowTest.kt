package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class HomeQuotaFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val today get() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private val repo get() = (compose.activity.application as FoodLogApplication).repository

    private fun ready() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("今日").performClick()
        compose.onNodeWithText("回到今天").performScrollTo().performClick()
    }

    private fun setup(includeFlex: Boolean = true, unknownQuantity: Boolean = false): Meal = runBlocking {
        val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
        val food = seed.versions.first { it.name == "荞麦面" }
        val meal = Meal(date = today, status = "POSTED", items = listOf(NutritionCalculator.item(food, if (unknownQuantity) null else "60", "G")))
        val plan = TargetPlan(
            effectiveFrom = today, mode = "PER_KG", referenceWeight = "80",
            training = Macros(protein = "2", carbs = "3", fat = "0.8"),
            rest = Macros(protein = "2", carbs = "2", fat = "0.8"),
            flex = if (includeFlex) Macros(protein = "1.5", carbs = "4", fat = "1") else null
        )
        repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta(), meals = listOf(meal),
            days = listOf(DayLog(today, training = "STRENGTH", dietDayType = "REST")),
            plans = listOf(plan), targets = listOf(NutritionCalculator.target(plan, today, "REST"))))
        meal
    }

    @Test fun dayTypeChangesReferencesWithoutChangingFoodOrActualTraining() {
        ready()
        val original = setup()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("日型_REST") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 160 g")
        compose.onNodeWithTag("首页_蛋白质_参考").assertTextEquals("/ 160 g")
        compose.onNodeWithTag("首页_脂肪_参考").assertTextEquals("/ 64 g")
        val consumed = compose.onNodeWithTag("首页_碳水_已记录").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text

        compose.onNodeWithTag("日型_TRAINING").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().targets.single().nutrients.carbs == "240" } }
        compose.onNodeWithTag("日型_TRAINING").assertIsSelected()
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 240 g")
        compose.onNodeWithTag("首页_碳水_已记录").assertTextEquals(consumed)

        compose.onNodeWithTag("日型_FLEX").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().targets.single().nutrients.carbs == "320" } }
        compose.onNodeWithTag("日型_FLEX").assertIsSelected()
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 320 g")
        compose.onNodeWithTag("首页_蛋白质_参考").assertTextEquals("/ 120 g")
        compose.onNodeWithTag("首页_脂肪_参考").assertTextEquals("/ 80 g")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("日型_FLEX").assertIsSelected()
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 320 g")
        val after = runBlocking { repo.snapshot() }
        assertEquals("STRENGTH", after.days.single().training)
        assertEquals("FLEX", after.days.single().dietDayType)
        assertEquals(original, after.meals.single())
    }

    @Test fun missingPortionStaysUnknownAndUnconfiguredFlexClearsOldReference() {
        ready()
        setup(includeFlex = false, unknownQuantity = true)
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().plans.isNotEmpty() } }
        listOf("碳水", "蛋白质", "脂肪").forEach { name -> compose.onNodeWithTag("首页_${name}_已记录").assertTextEquals("待补") }
        compose.onNodeWithTag("日型_FLEX").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().days.single().dietDayType == "FLEX" && repo.snapshot().targets.isEmpty() } }
        listOf("碳水", "蛋白质", "脂肪").forEach { name ->
            compose.onNodeWithTag("首页_${name}_已记录").assertTextEquals("待补")
            compose.onNodeWithTag("首页_${name}_参考").assertTextEquals("/ — g")
        }
        compose.onNodeWithText("放纵日尚未设置参考值。").assertExists()
        compose.onNodeWithTag("首页设置配额").performClick()
        compose.onNodeWithText("每日配额").assertExists()
        assertEquals("STRENGTH", runBlocking { repo.snapshot().days.single().training })
    }

    @Test fun legacySnapshotMismatchIsVisibleAndSelectedChipCanExplicitlyRefreshIt() {
        ready()
        setup()
        runBlocking {
            val data = repo.snapshot()
            repo.restore(data.copy(
                days = listOf(DayLog(today, training = "REST")),
                targets = listOf(NutritionCalculator.target(data.plans.single(), today, "STRENGTH"))
            ))
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("参考快照：训练日 · 点日型更新").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("日型_REST").assertIsSelected()
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 240 g")
        compose.onNodeWithTag("日型_REST").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().targets.single().nutrients.carbs == "160" } }
        compose.onNodeWithTag("首页_碳水_参考").assertTextEquals("/ 160 g")
        compose.onNodeWithText("参考快照：训练日 · 点日型更新").assertDoesNotExist()
        val refreshed = runBlocking { repo.snapshot() }
        assertEquals("REST", refreshed.days.single().training)
        assertEquals("REST", refreshed.days.single().dietDayType)
        assertEquals("REST", refreshed.targets.single().dayType)
    }
}
