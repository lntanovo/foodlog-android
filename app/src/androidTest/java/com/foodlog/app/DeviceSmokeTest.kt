package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun canOpenAllFiveTabsOffline() {
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("食品").performClick();compose.onNodeWithText("食品库").assertExists()
        compose.onNodeWithText("趋势").performClick();compose.onNodeWithText("身体趋势").assertExists()
        compose.onNodeWithText("配额").performClick();compose.onNodeWithText("每日配额").assertExists()
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("数据备份与恢复").performScrollTo().assertExists()
        compose.onNodeWithText("今日").performClick();compose.onNodeWithContentDescription("记一餐").assertExists()
    }
}
