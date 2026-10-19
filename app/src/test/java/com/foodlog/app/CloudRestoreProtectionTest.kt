package com.foodlog.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=FoodLogApplication::class)
class CloudRestoreProtectionTest {
    private lateinit var db: FoodLogDatabase
    private lateinit var repo: Repository
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<FoodLogApplication>()
        db=Room.inMemoryDatabaseBuilder(context,FoodLogDatabase::class.java).allowMainThreadQueries().build()
        repo=Repository(context,db); repo.initialize()
        repo.day(DayLog("2026-10-17",note="Synthetic original"),BodyRecord("2026-10-17",weight="65"))
    }
    @After fun close() { db.close() }
    @Test fun stalePreviewNeverReplacesNewLocalRecords()=runBlocking {
        val preview=repo.snapshot()
        repo.day(DayLog("2026-10-18"),BodyRecord("2026-10-18",weight="66"))
        val current=repo.snapshot()
        try { repo.restore(Dataset(),preview.meta.datasetId,preview.meta.revision); fail("stale preview") }
        catch (_:IllegalStateException) { }
        assertEquals(current,repo.snapshot())
    }
    @Test fun accountChangeBeforeCommitLeavesDataAndUsableProtectionCopy()=runBlocking {
        val before=repo.snapshot(); var checks=0
        try {
            repo.restore(Dataset(),before.meta.datasetId,before.meta.revision) {
                checks++; check(checks==1) { "Synthetic account switch" }
            }
            fail("account switch")
        } catch (_:IllegalStateException) { }
        assertEquals(before,repo.snapshot().copy(exportedAt=before.exportedAt))
        val protection=checkNotNull(repo.latestRecovery())
        val saved=protection.inputStream().use { repo.readBackup(it) }
        assertEquals(before.meta,saved.meta); assertEquals(before.days,saved.days); assertEquals(before.bodies,saved.bodies)
        assertFalse(protection.name.endsWith(".partial"))
    }
    @Test fun successfulRestoreCanRecoverAllPreviousRows()=runBlocking {
        val before=repo.snapshot()
        val protection=repo.restore(Dataset(),before.meta.datasetId,before.meta.revision)
        assertTrue(repo.snapshot().bodies.isEmpty())
        val previous=protection.inputStream().use { repo.readBackup(it) }
        repo.restore(previous)
        val after=repo.snapshot()
        assertEquals(before.foods,after.foods); assertEquals(before.versions,after.versions)
        assertEquals(before.days,after.days); assertEquals(before.bodies,after.bodies)
        assertEquals(before.meta.datasetId,after.meta.datasetId)
    }
}
