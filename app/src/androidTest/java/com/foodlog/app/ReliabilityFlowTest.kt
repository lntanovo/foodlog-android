package com.foodlog.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReliabilityFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo get()=(compose.activity.application as FoodLogApplication).repository
    private val amount="本次份量（留空为待补）"
    @Before fun reset() {
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {
            val seed=repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().use{it.readText()})
            repo.restore(seed.copy(kind="FULL_BACKUP",meta=Meta()))
        }
    }
    private fun scroll(matcher: SemanticsMatcher) { compose.onNodeWithTag("meal-content").performScrollToNode(matcher) }
    private fun noodles() {
        compose.onNodeWithContentDescription("记一餐").performClick()
        scroll(hasTestTag("choose-food-buckwheat_noodles_60g"))
        compose.onNodeWithTag("choose-food-buckwheat_noodles_60g").performClick()
        scroll(hasTestTag(amount))
    }
    @Test fun unfinishedDecimalSurvivesRecreationAndReopeningDraft() {
        noodles()
        compose.onNodeWithTag(amount).performTextInput("60.")
        compose.onNodeWithText("保存已吃").assertIsNotEnabled()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().meals.any{it.items.any{i->i.draftQuantityText=="60."}}}}
        compose.activityRule.scenario.recreate()
        scroll(hasTestTag(amount));compose.onNodeWithTag(amount).assertTextContains("60.")
        compose.onNodeWithContentDescription("返回并保留草稿").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("继续填写").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(0,runBlocking{repo.snapshot().meals.count{it.status=="POSTED"}})
        compose.onNodeWithText("继续填写").performScrollTo().performClick()
        scroll(hasTestTag(amount));compose.onNodeWithTag(amount).assertTextContains("60.")
        compose.onNodeWithTag(amount).performTextReplacement("60.5")
        compose.onNodeWithText("保存已吃").performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().meals.any{it.status=="POSTED"}}}
        val item=runBlocking{repo.snapshot().meals.single{it.status=="POSTED"}.items.single()}
        assertEquals("60.5",item.quantity);assertNull(item.draftQuantityText)
    }
    @Test fun changingUnitsPreservesPhysicalQuantityAndIncompleteText() {
        noodles()
        compose.onNodeWithTag(amount).performTextInput("60")
        scroll(hasText("袋"));compose.onNodeWithText("袋").performClick()
        scroll(hasTestTag(amount));compose.onNodeWithTag(amount).assertTextContains("1")
        scroll(hasText("g"));compose.onNodeWithText("g").performClick()
        scroll(hasTestTag(amount));compose.onNodeWithTag(amount).assertTextContains("60")
        compose.onNodeWithTag(amount).performTextReplacement("60.")
        scroll(hasText("袋"));compose.onNodeWithText("袋").performClick()
        scroll(hasTestTag(amount));compose.onNodeWithTag(amount).assertTextContains("60.")
        compose.onNodeWithText("保存已吃").assertIsNotEnabled()
    }
    @Test fun immediateEmptyDraftDiscardDoesNotTrapEditor() {
        compose.onNodeWithContentDescription("记一餐").performClick()
        compose.onNodeWithContentDescription("返回并保留草稿").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(runBlocking{repo.snapshot().meals.isEmpty()})
    }
}
