package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import com.foodlog.app.ui.AppViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class TrendFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val earliest = today.minusDays(3).toString()
    private val repo get() = (compose.activity.application as FoodLogApplication).repository

    private fun selectDate(date: String) {
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppViewModel::class.java].date(date) }
    }

    private fun records() = (0..3).map { index ->
        BodyRecord(today.minusDays(index.toLong()).toString(), "${65 + index}.2", note = "合成趋势记录 $index")
    }

    private fun openTrend(bodies: List<BodyRecord> = records()) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
            repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta(), bodies = bodies))
        }
        selectDate(today.toString())
        compose.onNodeWithText("趋势").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("trend-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertRange(end: LocalDate = today, count: Int = 4) {
        compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("trend-range-summary"))
        compose.onNodeWithTag("trend-range-summary")
            .assertTextEquals("${end.minusDays(29)} 至 $end · ${count}次标准晨重")
        compose.onNodeWithTag("weight-chart").assertExists()
    }

    private fun editEarliest() {
        compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("trend-day-$earliest"))
        compose.onNodeWithTag("trend-day-$earliest").performClick()
        compose.onNodeWithText("$earliest · 身体与训练").assertExists()
    }

    private fun waitForDialogClose() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("体重 kg（留空为未测）").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun editingAnOlderWeightKeepsLaterPointsAfterSaveCancelAndRecreation() {
        val original = records()
        openTrend(original)
        assertRange()

        editEarliest()
        compose.onNodeWithTag("体重 kg（留空为未测）").performScrollTo().performTextReplacement("66.8")
        compose.onNodeWithText("保存").performClick()
        waitForDialogClose()
        assertRange()
        val saved = runBlocking { repo.snapshot().bodies }
        assertEquals(4, saved.size)
        assertEquals("66.8", saved.single { it.date == earliest }.weight)
        assertEquals(original.filterNot { it.date == earliest }.toSet(), saved.filterNot { it.date == earliest }.toSet())

        editEarliest()
        compose.onNodeWithTag("体重 kg（留空为未测）").performScrollTo().assertTextContains("66.8")
        compose.onNodeWithTag("体重 kg（留空为未测）").performTextReplacement("99.9")
        compose.onNodeWithText("取消").performClick()
        waitForDialogClose()
        assertRange()
        assertEquals(saved.toSet(), runBlocking { repo.snapshot().bodies }.toSet())

        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("trend-list").fetchSemanticsNodes().isNotEmpty() }
        assertRange()
        assertEquals(saved.toSet(), runBlocking { repo.snapshot().bodies }.toSet())
        compose.runOnIdle {
            assertEquals(today.toString(), ViewModelProvider(compose.activity)[AppViewModel::class.java].selectedDate.value)
        }
    }

    @Test fun explicitCutoffFiltersPointsAndTodayRestoresTheFullWindow() {
        val original = records()
        openTrend(original)
        assertRange()

        selectDate(earliest)
        assertRange(LocalDate.parse(earliest), count = 1)
        compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("trend-today"))
        compose.onNodeWithTag("trend-today").performClick()
        assertRange()
        assertEquals(original.toSet(), runBlocking { repo.snapshot().bodies }.toSet())
    }

    @Test fun nonstandardWeightsStayInRecordsButDoNotBecomeChartPoints() {
        val original = records().mapIndexed { index, record -> record.copy(standard = index != 0) }
        openTrend(original)
        assertRange(count = 3)
        compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("trend-day-$today"))
        compose.onNodeWithTag("trend-day-$today")
            .assertTextContains("非标准称重（不计均值） · 未填写")
        assertEquals(original.toSet(), runBlocking { repo.snapshot().bodies }.toSet())

        runBlocking { repo.restore(repo.snapshot().copy(bodies = original.map { it.copy(standard = false) })) }
        compose.onNodeWithTag("trend-list").performScrollToNode(hasText("还没有标准晨重，记录后在这里查看。"))
        compose.onNodeWithText("还没有标准晨重，记录后在这里查看。").assertIsDisplayed()
        compose.onNodeWithTag("weight-chart").assertDoesNotExist()
        assertEquals(4, runBlocking { repo.snapshot().bodies.size })
    }
}
