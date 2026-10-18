package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class QuotaFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val date get() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private val repo get() = (compose.activity.application as FoodLogApplication).repository

    @Before fun reset() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
            repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta()))
        }
        compose.onNodeWithText("配额").performClick()
    }

    private fun fill(label: String, value: String) {
        compose.onNodeWithTag(label).performScrollTo().performTextReplacement(value)
    }

    @Test fun perKgThreeDayPreviewAndSaveDoesNotInventBodyOrDailyRecords() {
        compose.onNodeWithTag("quota-edit").performScrollTo().performClick()
        fill("参考体重 kg", "65")
        fill("碳水 g/kg", "3")
        fill("蛋白质 g/kg", "1.5")
        fill("脂肪 g/kg", "0.8")
        compose.onNodeWithTag("quota-preview-macros").performScrollTo().assertTextContains("碳水 195 g · 蛋白质 97.5 g · 脂肪 52 g")
        compose.onNodeWithTag("quota-preview-kcal").assertTextContains("约 1638 kcal")
        compose.onNodeWithTag("quota-type-1").performScrollTo().performClick()
        fill("碳水 g/kg", "2.5")
        fill("蛋白质 g/kg", "1.5")
        fill("脂肪 g/kg", "0.8")
        compose.onNodeWithTag("quota-preview-kcal").performScrollTo().assertTextContains("约 1508 kcal")
        compose.onNodeWithTag("quota-type-2").performScrollTo().performClick()
        compose.onNodeWithText("设置放纵日参考值").performScrollTo().performClick()
        fill("碳水 g/kg", "4")
        fill("蛋白质 g/kg", "1.5")
        fill("脂肪 g/kg", "1")
        compose.onNodeWithTag("quota-preview-kcal").performScrollTo().assertTextContains("约 2015 kcal")
        compose.onNodeWithText("保存三类参考值").performClick()
        compose.waitUntil(30_000) { runBlocking { repo.snapshot().plans.size == 1 } }
        runBlocking {
            val saved = repo.snapshot()
            check(saved.bodies.isEmpty() && saved.days.isEmpty() && saved.targets.isEmpty())
            val plan = saved.plans.single()
            check(plan.mode == "PER_KG" && plan.referenceWeight == "65" && plan.effectiveFrom == date)
            check(plan.training == Macros("1.5", "3", "0.8") && plan.rest == Macros("1.5", "2.5", "0.8") && plan.flex == Macros("1.5", "4", "1"))
        }
    }

    @Test fun rawModeAndTabInputsSurviveActivityRecreation() {
        compose.onNodeWithTag("quota-edit").performScrollTo().performClick()
        fill("参考体重 kg", "65.")
        fill("碳水 g/kg", "3.")
        compose.onNodeWithText("直接填写克数").performScrollTo().performClick()
        fill("碳水 g", "180.")
        compose.onNodeWithTag("quota-type-1").performScrollTo().performClick()
        fill("碳水 g", "160.")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("碳水 g").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("碳水 g").performScrollTo().assertTextContains("160.")
        compose.onNodeWithTag("quota-type-0").performScrollTo().performClick()
        compose.onNodeWithTag("碳水 g").performScrollTo().assertTextContains("180.")
        compose.onNodeWithText("按参考体重系数").performScrollTo().performClick()
        compose.onNodeWithTag("参考体重 kg").performScrollTo().assertTextContains("65.")
        compose.onNodeWithTag("碳水 g/kg").performScrollTo().assertTextContains("3.")
        compose.onNodeWithText("保存三类参考值").performClick()
        compose.onNodeWithTag("quota-editor-error").performScrollTo().assertExists()
        check(runBlocking { repo.snapshot().plans.isEmpty() })
    }

    @Test fun explicitApplyUpdatesOnlySelectedDayReferenceAndLabel() {
        val oldPlan = TargetPlan(effectiveFrom = date, training = Macros("100", "200", "50"), rest = Macros("90", "160", "50"), createdAt = "2026-01-01T00:00:00Z")
        val newPlan = TargetPlan(effectiveFrom = date, mode = "PER_KG", referenceWeight = "65", training = Macros("1.5", "3", "0.8"), rest = Macros("1.5", "2.5", "0.8"), createdAt = "2026-02-01T00:00:00Z")
        val originalDay = DayLog(date, training = "REST", note = "保留训练与饮食备注")
        val originalBody = BodyRecord(date, weight = "64.5", waist = "75")
        runBlocking {
            val seed = repo.snapshot()
            repo.restore(seed.copy(plans = listOf(oldPlan, newPlan), days = listOf(originalDay), bodies = listOf(originalBody),
                targets = listOf(NutritionCalculator.target(oldPlan, date, "REST"))))
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("参考体重 65 kg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quota-apply-TRAINING").performScrollTo().performClick()
        compose.onNodeWithText("确认使用").performClick()
        compose.waitUntil(30_000) { runBlocking { repo.snapshot().targets.single().planId == newPlan.id } }
        runBlocking {
            val result = repo.snapshot()
            check(result.days.single().training == "REST" && result.days.single().dietDayType == "TRAINING")
            check(result.days.single().note == originalDay.note && result.bodies.single() == originalBody)
            check(result.targets.single().nutrients.carbs == "195")
        }
    }
}
