package com.foodlog.app

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repo get()=(compose.activity.application as FoodLogApplication).repository
    @Before fun setup() {
        compose.waitUntil(30_000){compose.onAllNodesWithContentDescription("记一餐").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {
            val seed=repo.parse(compose.activity.assets.open("foods_seed_v1.json").bufferedReader().readText())
            repo.restore(seed.copy(kind="FULL_BACKUP",meta=Meta()))
        }
        Intents.init()
    }
    @After fun finish(){Intents.release()}
    private fun returnFile(action:String,name:String) {
        Intents.intending(hasAction(action)).respondWith(ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.parse("content://com.foodlog.app.test.files/$name")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
    }
    @Test fun actualMigrationPreviewImportAndDuplicateSkip() {
        try {
        org.junit.Assume.assumeTrue("个人迁移夹具只用于本人本机测试",androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.list("")!!.contains("legacy_personal.json"))
        returnFile(Intent.ACTION_OPEN_DOCUMENT,"legacy")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("食品库与旧数据").performScrollTo().performClick()
        compose.onNodeWithText("导入电脑转换的旧记录…").performScrollTo().performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("预览导入").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().meals.size==5}}
        val first=runBlocking{repo.snapshot()};assertEquals(16,first.meals.sumOf{it.items.size});assertEquals(3,first.bodies.size)
        compose.waitUntil(15_000){compose.onAllNodesWithText("预览导入").fetchSemanticsNodes().isEmpty()}
        compose.waitUntil(15_000){compose.onAllNodesWithText("导入完成").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("导入电脑转换的旧记录…").performScrollTo().performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("预览导入").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("确认导入").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("预览导入").fetchSemanticsNodes().isEmpty()}
        assertEquals(first.meals,runBlocking{repo.snapshot().meals})
        } catch(e:Throwable){android.util.Log.e("FoodLogTransferTest",compose.onRoot().printToString());throw e}
    }
    @Test fun fullBackupSafExportThenRestorePreview() {
        runBlocking {
            val d=repo.snapshot();val v=d.version(d.foods.first{it.seedId=="buckwheat_noodles_60g"})
            repo.meal(Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(v,"60","G"))),true)
        }
        returnFile(Intent.ACTION_CREATE_DOCUMENT,"backup")
        returnFile(Intent.ACTION_OPEN_DOCUMENT,"backup")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("导出完整备份").performScrollTo().performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().meta.lastExport!=null}}
        runBlocking {repo.deleteMeal(repo.snapshot().meals.single().id)}
        compose.onNodeWithText("从完整备份恢复…").performScrollTo().performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("预览完整恢复").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("确认替换并恢复").performClick()
        compose.waitUntil(15_000){runBlocking{repo.snapshot().meals.size==1}}
        assertNotNull(repo.latestRecovery())
        assertEquals("60",runBlocking{repo.snapshot().meals.single().items.single().quantity})
    }
}
