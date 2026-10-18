package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import com.foodlog.app.domain.Meta

@RunWith(AndroidJUnit4::class)
class MealFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun foodToMealToEditAndHistoryDate() {
        try {
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {
            val repo=(compose.activity.application as FoodLogApplication).repository
            val seed=repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().readText())
            repo.restore(seed.copy(kind="FULL_BACKUP",meta=Meta()))
        }
        compose.onNodeWithContentDescription("记一餐").performClick()
        compose.onNodeWithText("荞麦面").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("本次份量（留空为待补）").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("本次份量（留空为待补）").performScrollTo().performTextInput("60")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("本次份量（留空为待补）").performScrollTo().assertTextContains("60")
        compose.onNodeWithText("保存已吃").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("荞麦面").performScrollTo().assertExists()
        compose.onNodeWithText("编辑").performScrollTo().performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithTag("本次份量（留空为待补）").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("本次份量（留空为待补）").performScrollTo().performTextReplacement("120")
        compose.onNodeWithText("保存已吃").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("120 g").performScrollTo().assertExists()
        compose.onNodeWithText("前一天").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        val yesterday=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(1).toString()
        compose.onNodeWithText(yesterday).assertExists()
        compose.onNodeWithText("食品").performClick()
        compose.onNodeWithText("食品库").assertExists()
        compose.onNodeWithText("今日").performClick()
        compose.onNodeWithText(yesterday).assertExists()
        compose.onAllNodesWithText("未记录",substring=true).onFirst().assertExists()
        } catch(e:Throwable){android.util.Log.e("FoodLogTest",compose.onRoot().printToString());throw e}
    }
}
