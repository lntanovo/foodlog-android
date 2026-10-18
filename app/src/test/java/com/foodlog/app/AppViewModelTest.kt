package com.foodlog.app

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.domain.*
import com.foodlog.app.ui.AppViewModel
import com.foodlog.app.ui.EditableItem
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FoodLogApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelTest {
    private lateinit var application: FoodLogApplication
    private val stores = mutableListOf<ViewModelStore>()
    private val repo get() = application.repository

    @Before fun setup(): Unit = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        repo.initialize()
        val seed = repo.parse(application.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
        repo.restore(seed.copy(kind = "FULL_BACKUP", meta = Meta()))
        Unit
    }

    @After fun cleanup() {
        stores.forEach { it.clear() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun model(saved: SavedStateHandle = SavedStateHandle()): AppViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(application, saved) as T
        }
        return ViewModelProvider(store, factory)[AppViewModel::class.java].also { vm ->
            await("应读取本机数据") { vm.data.value != null }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, condition())
    }

    @Test fun postingRefusesConcurrentCloseEditsAndRepeatedSaveWithoutDowngradingMeal() {
        val vm = model()
        val d = requireNotNull(vm.data.value)
        val v = d.version(d.foods.first { it.seedId == "buckwheat_noodles_60g" })
        vm.edit()
        vm.modify { it.copy(rows = listOf(EditableItem(snapshot = v, amount = "60"))) }
        val original = requireNotNull(vm.editor.value)
        val row = original.rows.single()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Keep Room busy so saveMeal is suspended while the competing user actions arrive.
        val blocker = CoroutineScope(Dispatchers.IO).launch {
            repo.db.withTransaction { entered.complete(Unit); release.await() }
        }
        runBlocking { entered.await() }
        try {
            vm.saveMeal()
            assertTrue(vm.busy.value)
            vm.closeEditor()
            vm.closeEditor(discard = true)
            vm.modify { it.copy(rows = emptyList()) }
            vm.amount(row.id, "120")
            vm.unit(row.id, "袋")
            vm.edit()
            vm.saveMeal()
            assertEquals(original, vm.editor.value)
            assertTrue(vm.busy.value)
        } finally {
            release.complete(Unit)
            runBlocking { blocker.join() }
        }
        await("应完成一次保存并退出编辑") { !vm.busy.value && vm.editor.value == null }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        val result = runBlocking { repo.snapshot() }
        assertEquals(1, result.meals.size)
        val meal = result.meals.single()
        assertEquals(original.draft.id, meal.id)
        assertEquals("POSTED", meal.status)
        assertEquals("60", meal.items.single().quantity)
        assertEquals(v, meal.items.single().snapshot)
    }

    @Test fun estimatedPortionStaysEstimatedWhenConvertedUntilActualGramsAreEntered() {
        val vm = model()
        val d = requireNotNull(vm.data.value)
        val v = d.version(d.foods.first { it.seedId == "egg_hard_boiled_estimate" })
        vm.edit()
        val row = EditableItem(snapshot = v, amount = "1", unit = "个")
        vm.modify { it.copy(rows = listOf(row)) }
        assertEquals("ESTIMATED", vm.editor.value!!.rows.single().item().quantityStatus)
        vm.unit(row.id, "G")
        val converted = vm.editor.value!!.rows.single()
        assertEquals("50", converted.amount)
        assertEquals("G", converted.unit)
        assertEquals("ESTIMATED", converted.item().quantityStatus)
        vm.amount(row.id, "53")
        val measured = vm.editor.value!!.rows.single()
        assertEquals("KNOWN", measured.item().quantityStatus)
        assertEquals("53", measured.item().normalizedAmount)
        // Nutrition from this average food remains labelled on the immutable food snapshot.
        assertTrue(measured.snapshot.estimatedFields.contains("all"))
    }

    @Test fun freshViewModelRestoresRawRoomDraftUsingOnlySavedDraftIdentifier() = runBlocking {
        val data = repo.snapshot()
        val v = data.version(data.foods.first { it.seedId == "buckwheat_noodles_60g" })
        val draft = Meal(date = "2026-10-17", type = "晚餐", note = "重建前未完成输入", items = listOf(
            NutritionCalculator.item(v, null, "G").copy(draftQuantityText = "60.")
        ))
        repo.meal(draft, false)
        val saved = SavedStateHandle(mapOf("editingDraftId" to draft.id, "selectedDate" to draft.date, "tab" to 0))
        val rebuilt = model(saved)
        await("应恢复正在编辑的草稿") { rebuilt.editor.value?.draft?.id == draft.id }
        val restored = requireNotNull(rebuilt.editor.value)
        assertEquals("60.", restored.rows.single().amount)
        assertEquals(v, restored.rows.single().snapshot)
        assertEquals("晚餐", restored.draft.type)
        assertEquals(draft.note, restored.draft.note)
        assertEquals(draft.date, rebuilt.selectedDate.value)
        assertEquals(setOf("editingDraftId", "selectedDate", "tab"), saved.keys())
        assertTrue(repo.snapshot().meals.none { it.status == "POSTED" })
    }
}
