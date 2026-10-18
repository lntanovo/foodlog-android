package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.data.dataJson
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormReliabilityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun reset() {
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty() }
        val repo = (compose.activity.application as FoodLogApplication).repository
        runBlocking {
            val seed = repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
            repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta()))
        }
    }

    private fun recreateWithTag(tag: String) {
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun foodEditorKeepsRawFieldsAndPortionsAfterRecreate() {
        compose.onNodeWithText("食品").performClick()
        compose.onNodeWithText("新增自定义食品").performClick()
        compose.onNodeWithTag("食品名称 *").performTextInput("旋转中的食物")
        compose.onNodeWithTag("热量 kcal（未知留空）").performScrollTo().performTextInput("60.")
        compose.onNodeWithText("＋ 添加一种份量").performScrollTo().performClick()
        compose.onNodeWithTag("份量名称（如袋、盒、个）").performScrollTo().performTextInput("袋")
        compose.onNodeWithTag("每袋的数量").performScrollTo().performTextInput("120.")
        recreateWithTag("食品名称 *")
        compose.onNodeWithTag("食品名称 *").performScrollTo().assertTextContains("旋转中的食物")
        compose.onNodeWithTag("热量 kcal（未知留空）").performScrollTo().assertTextContains("60.")
        compose.onNodeWithTag("份量名称（如袋、盒、个）").performScrollTo().assertTextContains("袋")
        compose.onNodeWithTag("每袋的数量").performScrollTo().assertTextContains("120.")
    }

    @Test fun targetModesKeepSeparateInputsAndRecreate() {
        compose.onNodeWithText("配额").performClick()
        compose.onNodeWithTag("quota-edit").performScrollTo().performClick()
        compose.onNodeWithTag("参考体重 kg").performScrollTo().performTextInput("65")
        compose.onNodeWithTag("碳水 g/kg").performScrollTo().performTextInput("2.7")
        compose.onNodeWithText("直接填写克数").performScrollTo().performClick()
        compose.onNodeWithTag("碳水 g").performScrollTo().performTextInput("180.")
        compose.onNodeWithText("直接填写克数").performScrollTo().performClick()
        compose.onNodeWithTag("碳水 g").performScrollTo().assertTextContains("180.")
        compose.onNodeWithText("按参考体重系数").performScrollTo().performClick()
        compose.onNodeWithTag("参考体重 kg").performScrollTo().assertTextContains("65")
        compose.onNodeWithTag("碳水 g/kg").performScrollTo().assertTextContains("2.7")
        recreateWithTag("碳水 g/kg")
        compose.onNodeWithTag("碳水 g/kg").performScrollTo().assertTextContains("2.7")
        compose.onNodeWithText("直接填写克数").performScrollTo().performClick()
        compose.onNodeWithTag("碳水 g").performScrollTo().assertTextContains("180.")
    }

    @Test fun bodyAndProfileInputsSurviveRecreate() {
        compose.onNodeWithContentDescription("记录晨重、腰围与训练").performScrollTo().performClick()
        compose.onNodeWithTag("体重 kg（留空为未测）").performScrollTo().performTextInput("64.")
        compose.onNodeWithTag("腰围 cm（留空为未测）").performScrollTo().performTextInput("76.5")
        recreateWithTag("体重 kg（留空为未测）")
        compose.onNodeWithTag("体重 kg（留空为未测）").performScrollTo().assertTextContains("64.")
        compose.onNodeWithTag("腰围 cm（留空为未测）").performScrollTo().assertTextContains("76.5")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("个人资料与业务时区").performScrollTo().performClick()
        compose.onNodeWithTag("称呼").performTextInput("未提交资料")
        compose.onNodeWithTag("身高 cm").performTextInput("173.")
        recreateWithTag("称呼")
        compose.onNodeWithTag("称呼").assertTextContains("未提交资料")
        compose.onNodeWithTag("身高 cm").assertTextContains("173.")
    }
}
