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
class TemplateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val today get() = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
    private val repo get() = (compose.activity.application as FoodLogApplication).repository
    private fun ready() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("今日").performClick()
        compose.onNodeWithText("回到今天").performScrollTo().performClick()
    }
    private fun seed(): Dataset = runBlocking {
        repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() }).copy(kind = "FULL_BACKUP", meta = Meta())
    }

    @Test fun saveApplyAndDeleteTemplateKeepsMealsAndIntakeSeparate() {
        ready()
        val initial = seed()
        val food = initial.versions.first { it.name == "荞麦面" }
        val original = Meal(date = today, type = "午餐", status = "POSTED", items = listOf(NutritionCalculator.item(food, "60", "G")))
        runBlocking { repo.restore(initial.copy(meals = listOf(original))) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("meal-more-${original.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("meal-more-${original.id}").performScrollTo().performClick()
        compose.onNodeWithText("存为常用餐").performScrollTo().performClick()
        compose.onNodeWithTag("常用餐名称").performTextReplacement("测试常用午餐")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("常用餐名称").assertTextContains("测试常用午餐")
        compose.onNodeWithText("保存模板").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().templates.any { it.name == "测试常用午餐" } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("保存模板").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, runBlocking { repo.snapshot().templates.size })
        val template = runBlocking { repo.snapshot().templates.single { it.name == "测试常用午餐" } }
        compose.onNodeWithTag("常用餐入口").performScrollTo().performClick()
        compose.onNodeWithText("测试常用午餐").assertExists()
        compose.onNodeWithText("用于 $today · 生成草稿不计入摄入").assertExists()
        compose.onNodeWithTag("使用常用餐_${template.id}").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("本次份量（留空为待补）").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("本次份量（留空为待补）").performScrollTo().assertTextContains("60")
        val applied = runBlocking { repo.snapshot() }
        assertEquals(1, applied.meals.count { it.date == today && it.status == "POSTED" })
        val draft = applied.meals.single { it.date == today && it.status == "DRAFT" }
        assertNotEquals(original.id, draft.id)
        assertNotEquals(original.items.single().id, draft.items.single().id)
        assertNull(draft.replacesMealId)
        assertEquals(original.items.single().derived, draft.items.single().derived)
        assertEquals(original.items.single().derived.kcal, NutritionCalculator.aggregate(applied.meals.filter { it.status == "POSTED" }.flatMap { it.items })[0].known)
        compose.onNodeWithContentDescription("返回并保留草稿").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("常用餐入口").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("常用餐入口").performScrollTo().performClick()
        compose.onNodeWithText("删除模板").performScrollTo().performClick()
        compose.onNodeWithText("删除常用餐？").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, runBlocking { repo.snapshot().templates.size })
        compose.onNodeWithText("删除模板").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().templates.isEmpty() } }
        val deleted = runBlocking { repo.snapshot() }
        assertEquals(2, deleted.meals.size)
        assertEquals(original, deleted.meals.single { it.id == original.id })
        assertEquals(draft.id, deleted.meals.single { it.status == "DRAFT" }.id)
    }

    @Test fun copyPreviousDayPreviewsDatesCreatesDraftsAndRequiresRepeatConfirmation() {
        ready()
        val sourceDate = LocalDate.parse(today).minusDays(1).toString()
        val initial = seed()
        val food = initial.versions.first { it.name == "荞麦面" }
        val lunch = Meal(date = sourceDate, type = "午餐", status = "POSTED", items = listOf(NutritionCalculator.item(food, "60", "G")))
        val dinner = Meal(date = sourceDate, type = "晚餐", status = "POSTED", items = listOf(NutritionCalculator.item(food, "90", "G")))
        val unfinished = Meal(date = sourceDate, type = "加餐", items = listOf(NutritionCalculator.item(food, null, "G")))
        runBlocking {
            repo.restore(initial.copy(meals = listOf(lunch, dinner, unfinished),
                days = listOf(DayLog(sourceDate, training = "STRENGTH", loggingStatus = "COMPLETE")),
                bodies = listOf(BodyRecord(sourceDate, weight = "77", waist = "80"))))
        }
        compose.onNodeWithContentDescription("前一天").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("2 餐已吃 · 1 份草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回到今天").performScrollTo().performClick()
        compose.onNodeWithTag("复制昨天入口").performScrollTo().performClick()
        compose.onNodeWithText("来源日：$sourceDate\n目标日：$today").assertExists()
        compose.onNodeWithText("将复制 2 餐").assertExists()
        compose.onNodeWithText("生成草稿不计入摄入。请核对当天食品与份量，再保存已吃。").assertExists()
        compose.onNodeWithTag("确认复制昨天").performClick()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().meals.count { it.date == today } == 2 } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("复制昨天的餐食").fetchSemanticsNodes().isEmpty() }
        val copied = runBlocking { repo.snapshot() }
        val drafts = copied.meals.filter { it.date == today }
        assertTrue(drafts.all { it.status == "DRAFT" && it.replacesMealId == null && it.note.contains("复制自 $sourceDate") })
        assertEquals(setOf("午餐", "晚餐"), drafts.map { it.type }.toSet())
        assertEquals(setOf("60", "90"), drafts.flatMap { it.items }.map { it.quantity }.toSet())
        assertTrue(drafts.flatMap { it.items }.none { it.id == lunch.items.single().id || it.id == dinner.items.single().id })
        assertTrue(copied.days.none { it.date == today })
        assertTrue(copied.bodies.none { it.date == today })
        assertTrue(copied.targets.none { it.date == today })
        assertEquals("COMPLETE", copied.days.single().loggingStatus)
        assertEquals(0, copied.meals.count { it.date == today && it.status == "POSTED" })
        compose.onNodeWithTag("复制昨天入口").performScrollTo().performClick()
        compose.onNodeWithText("确认再次复制").assertExists()
        compose.onNodeWithText("本日已有从 $sourceDate 复制的草稿。再次复制会新增一组草稿。").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(2, runBlocking { repo.snapshot().meals.count { it.date == today } })
        compose.onNodeWithTag("复制昨天入口").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("确认再次复制").performClick()
        compose.waitUntil(15_000) { runBlocking { repo.snapshot().meals.count { it.date == today } == 4 } }
        assertTrue(runBlocking { repo.snapshot().meals.filter { it.date == today }.all { it.status == "DRAFT" } })
        assertEquals(3, runBlocking { repo.snapshot().meals.count { it.date == sourceDate } })
    }
}
