package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodEditorFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun createCustomFoodThenPersistNewVersion() {
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        val repo=(compose.activity.application as FoodLogApplication).repository
        compose.onNodeWithText("食品").performClick()
        compose.onNodeWithText("新增自定义食品").performClick()
        compose.onNodeWithTag("食品名称 *").performScrollTo().performTextInput("测试自定义食品")
        compose.onNodeWithTag("热量 kcal（未知留空）").performScrollTo().performTextInput("300")
        compose.onNodeWithTag("蛋白质 g").performScrollTo().performTextInput("10")
        compose.onNodeWithTag("碳水化合物 g").performScrollTo().performTextInput("50")
        compose.onNodeWithTag("脂肪 g").performScrollTo().performTextInput("6")
        compose.onNodeWithText("保存食品").performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().versions.any{it.name=="测试自定义食品"}}}
        val data=runBlocking{repo.snapshot()};val v=data.versions.single{it.name=="测试自定义食品"}
        assertEquals("120",NutritionCalculator.item(v,"40","G").derived.kcal)
        assertNull(v.nutrients.fiber)
        compose.waitUntil(15_000){compose.onAllNodesWithTag("搜索名称或品牌").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("搜索名称或品牌").performTextInput("测试自定义食品")
        compose.onNodeWithText("编辑新版本").performScrollTo().performClick()
        compose.onNodeWithTag("食品名称 *").performTextReplacement("修改后的食品")
        compose.onNodeWithText("保存食品新版本").performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().versions.any{it.name=="修改后的食品"}}}
        assertEquals(2,runBlocking{repo.snapshot().versions.count{it.foodId==v.foodId}})
    }
}
