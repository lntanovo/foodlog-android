package com.foodlog.app

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.domain.*
import com.foodlog.app.data.dataJson
import com.foodlog.app.ui.TransferTask
import com.foodlog.app.ui.TransferViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FoodLogApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class TransferViewModelTest {
    private lateinit var application: FoodLogApplication

    @Before fun setup() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        File(application.filesDir, "transfers").deleteRecursively()
        application.repository.initialize()
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

    @Test fun preparedExportSurvivesNewViewModelWithoutPuttingBackupInSavedState() {
        val saved = SavedStateHandle()
        val first = TransferViewModel(application, saved)
        first.prepareExport("backup.foodlog.gz")
        await("导出准备应完成") { !first.busy.value }
        assertNull(first.error.value)
        val active = requireNotNull(first.task.value)
        assertEquals("EXPORT_READY", active.stage)
        val bytes = File(application.filesDir, "transfers/${active.path}").readBytes()
        assertEquals(0x1f, bytes[0].toInt() and 255)
        assertEquals(setOf("transferTaskId"), saved.keys())
        first.pickerLaunched()
        val journal = File(application.filesDir, "transfers/active.json")
        assertTrue("任务日志应写入磁盘", journal.exists())
        assertEquals("EXPORT_PICKER", dataJson.decodeFromString<TransferTask>(journal.readText()).stage)
        val rebuilt = TransferViewModel(application, SavedStateHandle())
        assertEquals("EXPORT_PICKER", rebuilt.task.value?.stage)
        assertEquals(active.path, rebuilt.task.value?.path)
        rebuilt.chooseAgain()
        assertEquals("EXPORT_READY", rebuilt.task.value?.stage)
        rebuilt.cancel()
        assertNull(rebuilt.task.value)
    }

    @Test fun importPreviewAndChoicesRestoreFromPrivateCopyAfterSourceDisappears() = runBlocking {
        val source = File(application.cacheDir, "source.foodlog.gz")
        source.outputStream().use { application.repository.writeBackup(it) }
        val first = TransferViewModel(application, SavedStateHandle())
        first.prepareImport("FULL_BACKUP")
        first.picked(Uri.fromFile(source))
        await("应从所选文件建立预览") { !first.busy.value }
        assertNull(first.error.value)
        assertNotNull(first.incoming.value)
        first.replaceDay("2026-10-17", true)
        first.includePlans(true)
        source.delete()
        val rebuilt = TransferViewModel(application, SavedStateHandle())
        await("重建后应从私有副本恢复预览") { !rebuilt.busy.value }
        assertNull(rebuilt.error.value)
        assertEquals(first.incoming.value, rebuilt.incoming.value)
        assertEquals(setOf("2026-10-17"), rebuilt.task.value?.replacements)
        assertTrue(rebuilt.task.value!!.includePlans)
        rebuilt.cancel()
    }

    @Test fun wrongImportEntryShowsErrorWithoutChangingData() = runBlocking {
        val before = application.repository.snapshot()
        val source = File(application.cacheDir, "wrong-kind.foodlog.gz")
        source.outputStream().use { application.repository.writeBackup(it) }
        val model = TransferViewModel(application, SavedStateHandle())
        model.prepareImport("FOOD_LIBRARY")
        model.picked(Uri.fromFile(source))
        await("文件类型校验应完成") { !model.busy.value }
        assertTrue(model.error.value!!.contains("文件类型不符"))
        assertNull(model.incoming.value)
        assertEquals(before.foods, application.repository.snapshot().foods)
        assertEquals(before.meals, application.repository.snapshot().meals)
        model.cancel()
    }
}
