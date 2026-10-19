package com.foodlog.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FoodLogApplication::class)
class ReliabilityTest {
    private lateinit var db: FoodLogDatabase
    private lateinit var repo: Repository
    private lateinit var context: Context
    @Before fun setup()=runBlocking {
        context=ApplicationProvider.getApplicationContext()
        db=Room.inMemoryDatabaseBuilder(context,FoodLogDatabase::class.java).allowMainThreadQueries().build()
        repo=Repository(context,db); repo.initialize()
    }
    @After fun close() { db.close() }
    private suspend fun version()=repo.snapshot().let { d -> d.version(d.foods.first()) }
    private suspend fun meal(date: String="2026-10-17")=Meal(date=date,items=listOf(NutritionCalculator.item(version(),"60","G")))

    @Test fun incompleteDraftSurvivesDatabaseAndBackupButCannotBePosted()=runBlocking {
        val v=version()
        val item=NutritionCalculator.editQuantity(NutritionCalculator.item(v,"60","G"),"60.")
        val draft=Meal(date="2026-10-18",items=listOf(item))
        repo.meal(draft,false)
        assertEquals("60.",repo.snapshot().meals.single().items.single().draftQuantityText)
        val bytes=ByteArrayOutputStream(); repo.writeBackup(bytes)
        val restored=repo.readBackup(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("60.",restored.meals.single().items.single().draftQuantityText)
        try { repo.meal(draft,true); fail("Incomplete quantity was posted") } catch(_:IllegalArgumentException) { }
        assertEquals("DRAFT",repo.snapshot().meals.single().status)
        val fixed=draft.copy(items=listOf(NutritionCalculator.editQuantity(item,"60")))
        repo.meal(fixed,true)
        assertNull(repo.snapshot().meals.single().items.single().draftQuantityText)
    }
    @Test fun deleteUndoRestoresEditingDraftsAndImportOriginsAtomically()=runBlocking {
        repo.meal(meal(),true)
        val posted=repo.snapshot().meals.single()
        val draft=posted.copy(id=uid(),status="DRAFT",replacesMealId=posted.id,note="尚未保存的修改")
        repo.meal(draft,false)
        val original=repo.snapshot()
        val withOrigin=original.copy(origins=listOf(ImportOrigin("test","meal/1",posted.id,"posted"),ImportOrigin("test","draft/1",draft.id,"draft")))
        repo.restore(withOrigin)
        val bundle=repo.deleteMeal(posted.id)
        assertEquals(withOrigin.meals.filter { it.replacesMealId==posted.id },bundle.drafts)
        assertTrue(repo.snapshot().meals.isEmpty()); assertTrue(repo.snapshot().origins.isEmpty())
        repo.undoDeletion(bundle)
        val after=repo.snapshot()
        assertEquals(withOrigin.meals.toSet(),after.meals.toSet()); assertEquals(withOrigin.origins.toSet(),after.origins.toSet())
    }
    @Test fun discardDraftIsSafeBeforeAutosaveAndPreservesPostedMeals()=runBlocking {
        repo.discardDraft(uid())
        val draft=meal(); repo.meal(draft,false)
        repo.discardDraft(draft.id); assertTrue(repo.snapshot().meals.isEmpty())
        repo.meal(draft,true)
        repo.discardDraft(draft.id); assertEquals("POSTED",repo.snapshot().meals.single().status)
    }
    @Test fun templateAndYesterdayCopiesCreateNewDraftsPreservingSnapshots()=runBlocking {
        repo.meal(meal(),true)
        val posted=repo.snapshot().meals.single()
        val template=repo.saveTemplate(" 常吃午饭 ",posted)
        assertEquals("常吃午饭",template.name)
        val active=repo.snapshot().foods.first { it.id==posted.items.single().foodId }
        repo.food(posted.items.single().snapshot.copy(id=uid(),versionNo=2,name="新版标签",labelKcal="999"),active)
        val fromTemplate=repo.applyTemplate(template.id,"2026-10-18")
        val copied=repo.copyPreviousDay("2026-10-18").single()
        val again=repo.copyPreviousDay("2026-10-18").single()
        listOf(fromTemplate,copied,again).forEach { draft ->
            assertEquals("DRAFT",draft.status); assertNull(draft.replacesMealId)
            assertEquals(posted.items.single().snapshot,draft.items.single().snapshot)
            assertNotEquals(posted.id,draft.id); assertNotEquals(posted.items.single().id,draft.items.single().id)
        }
        assertNotEquals(copied.id,again.id); assertTrue(copied.note.contains("复制自 2026-10-17"))
        assertEquals(1,repo.snapshot().meals.count { it.status=="POSTED" })
        assertTrue(repo.snapshot().meals.filter { it.date=="2026-10-18" && it.status=="POSTED" }.isEmpty())
        val full=repo.parse(repo.export(false)); assertEquals(listOf(template),full.templates)
        val library=repo.parse(repo.export(true,setOf(active.id))); assertTrue(library.templates.isEmpty())
        repo.deleteTemplate(template.id); assertTrue(repo.snapshot().templates.isEmpty())
        repo.restore(full); assertEquals(listOf(template),repo.snapshot().templates)
    }
    @Test fun yesterdayUsesPreviousNaturalDateAndSkipsDrafts()=runBlocking {
        repo.meal(meal("2025-12-31"),true)
        repo.meal(meal("2025-12-31"),false)
        repo.meal(meal("2025-12-30"),true)
        assertEquals(1,repo.copyPreviousDay("2026-01-01").size)
        assertTrue(repo.copyPreviousDay("2026-01-03").isEmpty())
    }
    @Test fun draftAutosaveWritesOnlyChangedMealAndNeverRewritesOriginRows()=runBlocking {
        val a=meal(); val b=meal("2026-10-16")
        repo.meal(a,false); repo.meal(b,false)
        db.openHelper.writableDatabase.execSQL("CREATE TABLE audit (entity TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER meals_audit AFTER UPDATE ON meals BEGIN INSERT INTO audit VALUES ('meal'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER foods_audit AFTER UPDATE ON foods BEGIN INSERT INTO audit VALUES ('food'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER versions_audit AFTER UPDATE ON food_versions BEGIN INSERT INTO audit VALUES ('version'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER origins_audit AFTER DELETE ON import_origins BEGIN INSERT INTO audit VALUES ('origin'); END")
        repo.meal(a.copy(note="autosave"),false)
        db.openHelper.readableDatabase.query("SELECT entity FROM audit").use { cursor ->
            assertEquals(1,cursor.count); cursor.moveToFirst(); assertEquals("meal",cursor.getString(0))
        }
        assertEquals(b.items,repo.snapshot().meals.first { it.id==b.id }.items)
    }
    @Test fun ensureTargetDoesNotOverwriteLatestDailyFields()=runBlocking {
        val date="2026-10-18"
        repo.plan(TargetPlan(effectiveFrom=date,training=Macros("100","200","50"),rest=Macros("90","150","50")))
        repo.day(DayLog(date,training="UNKNOWN",note="原备注"))
        coroutineScope {
            val target=async { repo.ensureTarget(date) }
            val edit=async { repo.day(DayLog(date,training="REST",note="新备注",hunger=7)) }
            target.await(); edit.await()
        }
        repo.ensureTarget(date)
        assertEquals("新备注",repo.snapshot().days.single().note)
        assertEquals(7,repo.snapshot().days.single().hunger)
        assertEquals("REST",repo.snapshot().targets.single().dayType)
    }
    @Test fun oldJsonAndGzipBackupsNormalizeToCurrentSchema()=runBlocking {
        val original=repo.snapshot().copy(schemaVersion=1,appVersion="1.0.0")
        val json=dataJson.encodeToString(original)
        val parsed=repo.parse(json)
        assertEquals(3,parsed.schemaVersion); assertEquals("1.2.1",parsed.appVersion)
        val bytes=ByteArrayOutputStream(); GZIPOutputStream(bytes).use { it.write(json.toByteArray()) }
        assertEquals(parsed,repo.readBackup(ByteArrayInputStream(bytes.toByteArray())))
        val current=ByteArrayOutputStream(); repo.writeBackup(current)
        GZIPInputStream(ByteArrayInputStream(current.toByteArray())).use { assertEquals('{'.code,it.read()) }
    }
    @Test fun backupLargerThanOldThirtyMegabyteLimitCanBeRecovered()=runBlocking {
        // A repetitive note compresses well, but expands past the old 30 MB reader limit.
        val marker="x".repeat(30_000_100)
        val large=repo.snapshot().copy(report=listOf(marker))
        val text=dataJson.encodeToString(large)
        assertTrue(text.length>30_000_000)
        assertEquals(marker.length,repo.parse(text).report.single().length)
        val compressed=ByteArrayOutputStream(); GZIPOutputStream(compressed).use { it.write(text.toByteArray()) }
        assertEquals(marker.length,repo.readBackup(ByteArrayInputStream(compressed.toByteArray())).report.single().length)
        assertTrue(MAX_BACKUP_BYTES>=256L*1024*1024)
    }
    @Test fun streamingReaderRejectsOversizedExpandedDataWithoutBufferingItAll()=runBlocking {
        val spaces=object:InputStream() {
            private var remaining=MAX_BACKUP_BYTES+1
            override fun read():Int = if(remaining-- > 0) 32 else -1
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                if(remaining<=0) return -1
                val size=minOf(length.toLong(),remaining).toInt()
                bytes.fill(32,offset,offset+size); remaining-=size
                return size
            }
        }
        try { repo.readBackup(spaces); fail("Oversized stream was accepted") }
        catch(error:IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("256MiB")) }
    }
    @Test fun roomVersionOneMigrationRetainsAllPreviousRowsAndAddsTemplates()=runBlocking {
        val original=repo.snapshot().let { d ->
            val m=meal().copy(status="POSTED")
            val plan=TargetPlan(effectiveFrom=m.date,training=Macros("100","200","50"),rest=Macros("90","150","50"))
            d.copy(meals=listOf(m),days=listOf(DayLog(m.date,training="REST",note="升级保留")),
                bodies=listOf(BodyRecord(m.date,"65.2","75")),plans=listOf(plan),targets=listOf(NutritionCalculator.target(plan,m.date,"REST")),
                origins=listOf(ImportOrigin("migration","meal",m.id,"original")))
        }
        val filename="migration-${uid()}.db"
        val path=context.getDatabasePath(filename); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { sqlite ->
            val schema=JSONObject(File(System.getProperty("projectDir"),"schemas/com.foodlog.app.data.FoodLogDatabase/1.json").readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(index in 0 until entities.length()) {
                val entity=entities.getJSONObject(index)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            fun put(table: String,key: Any,json: String) { sqlite.execSQL("INSERT INTO $table VALUES (?, ?)",arrayOf(key,json)) }
            original.foods.forEach { put("foods",it.id,dataJson.encodeToString(it)) }
            original.versions.forEach { put("food_versions",it.id,dataJson.encodeToString(it)) }
            original.meals.forEach { put("meals",it.id,dataJson.encodeToString(it)) }
            original.days.forEach { put("day_logs",it.date,dataJson.encodeToString(it)) }
            original.bodies.forEach { put("body_records",it.date,dataJson.encodeToString(it)) }
            original.plans.forEach { put("target_plans",it.id,dataJson.encodeToString(it)) }
            original.targets.forEach { put("day_targets",it.date,dataJson.encodeToString(it)) }
            original.origins.forEach { sqlite.execSQL("INSERT INTO import_origins VALUES (?, ?, ?)",arrayOf(it.namespace,it.key,dataJson.encodeToString(it))) }
            put("metadata",1,dataJson.encodeToString(original.meta)); sqlite.version=1
        }
        val migrated=Room.databaseBuilder(context,FoodLogDatabase::class.java,filename).addMigrations(MIGRATION_1_2).build()
        try {
            val after=Repository(context,migrated).snapshot()
            assertEquals(original.copy(exportedAt=after.exportedAt),after)
            assertTrue(after.templates.isEmpty())
        } finally { migrated.close(); context.deleteDatabase(filename) }
    }
}
