package com.foodlog.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FoodLogApplication::class)
class RepositoryTest {
    private lateinit var db: FoodLogDatabase
    private lateinit var repo: Repository
    private lateinit var context: Context
    @Before fun setup() = runBlocking {
        context=ApplicationProvider.getApplicationContext()
        db=Room.inMemoryDatabaseBuilder(context,FoodLogDatabase::class.java).allowMainThreadQueries().build()
        repo=Repository(context,db);repo.initialize()
    }
    @After fun close(){db.close()}
    private suspend fun noodle()=repo.snapshot().let{d->d.version(d.foods.first{it.seedId=="buckwheat_noodles_60g"})}
    @Test fun firstInstallHasNoPrivateRecordsAndSeedOnlyOnce()=runBlocking {
        repo.initialize();val d=repo.snapshot();assertEquals(11,d.foods.size);assertTrue(d.meals.isEmpty());assertTrue(d.bodies.isEmpty());assertTrue(d.plans.isEmpty());assertEquals(Profile(),d.meta.profile)
    }
    @Test fun draftsEditSnapshotsUndoAndZeroFiltering()=runBlocking {
        val v=noodle();val m=Meal(date="2026-10-17",items=listOf(NutritionCalculator.item(v,"60","G")))
        repo.meal(m,false);assertTrue(repo.snapshot().meals.none{it.status=="POSTED"})
        repo.meal(m,true);assertEquals(1,repo.snapshot().meals.size)
        val food=repo.snapshot().foods.first{it.id==v.foodId};val changed=v.copy(id=uid(),versionNo=2,labelKcal="999",name="改名的面")
        repo.food(changed,food)
        assertEquals(v,repo.snapshot().meals.single().items.single().snapshot)
        val edit=m.copy(id=uid(),status="DRAFT",replacesMealId=m.id,items=listOf(NutritionCalculator.item(v,"120","G")))
        repo.meal(edit,false);assertEquals(1,repo.snapshot().meals.count{it.status=="POSTED"})
        repo.meal(edit,true);val saved=repo.snapshot().meals.single();assertEquals(m.id,saved.id);assertEquals(v,saved.items.single().snapshot);assertEquals("92.88",saved.items.single().derived.carbs)
        repo.deleteMeal(saved.id);assertTrue(repo.snapshot().meals.isEmpty());repo.undoMeal(saved);assertEquals(saved,repo.snapshot().meals.single())
        repo.meal(Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(v,"0","G"))),false)
        assertTrue(repo.snapshot().meals.first{it.date=="2026-10-18"}.items.isEmpty())
    }
    @Test fun dailyWeightsUpsertAndTargetSnapshotsStayFixed()=runBlocking {
        val date="2026-10-16";repo.day(DayLog(date,training="BOTH"),BodyRecord(date,weight="64"))
        repo.day(DayLog(date,training="BOTH"),BodyRecord(date,weight="63.9"))
        assertEquals(1,repo.snapshot().bodies.size)
        val p=TargetPlan(effectiveFrom=date,mode="PER_KG",referenceWeight="65",training=Macros("1.5","2.75","0.8"),rest=Macros("1.5","2.25","0.8"))
        repo.plan(p);repo.ensureTarget(date);val old=repo.snapshot().targets.single()
        repo.plan(p.copy(id=uid(),referenceWeight="70"));repo.ensureTarget(date);assertEquals(old,repo.snapshot().targets.single());assertEquals(1,repo.snapshot().bodies.size)
        repo.day(DayLog(date,training="REST"),recalculateTarget=true);assertEquals("REST",repo.snapshot().targets.single().dayType)
    }
    @Test fun completeBackupRoundTripIsIdempotentAndBadInputDoesNotWrite()=runBlocking {
        val v=noodle();repo.meal(Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(v,null,"G"))),true)
        repo.day(DayLog("2026-10-18",training="STRENGTH",hunger=5),BodyRecord("2026-10-18","64.5","76"))
        val backup=repo.parse(repo.export(false));val original=repo.snapshot()
        val recovery=repo.restore(backup);assertTrue(recovery.exists());repo.restore(backup)
        val after=repo.snapshot();assertEquals(original.meals,after.meals);assertEquals(original.versions,after.versions);assertEquals(original.bodies,after.bodies);assertEquals(original.meta.datasetId,after.meta.datasetId)
        assertThrows(IllegalArgumentException::class.java){repo.parse(dataJson.encodeToString(backup.copy(schemaVersion=999)))}
        assertEquals(after,repo.snapshot().copy(exportedAt=after.exportedAt))
    }
    @Test fun foodLibraryContainsNoPersonalData()=runBlocking {
        val v=noodle();repo.meal(Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(v,"60","G"))),true)
        repo.profile(Profile("本人", "173",20,"男"),"Asia/Shanghai")
        val library=repo.parse(repo.export(true,setOf(v.foodId)));assertEquals(1,library.foods.size);assertEquals(1,library.versions.size);assertTrue(library.meals.isEmpty());assertEquals(Profile(),library.meta.profile);assertNotEquals(repo.snapshot().meta.datasetId,library.meta.datasetId)
    }
    @Test fun actualLegacyImportIsIdempotentAndConflictsNeedExplicitReplacement()=runBlocking {
        val file=File(System.getProperty("projectDir"),"../deliverables/个人旧记录_导入.json")
        org.junit.Assume.assumeTrue("个人迁移夹具可单独提供",file.exists())
        val incoming=repo.parse(file.readText());repo.merge(incoming);val first=repo.snapshot();repo.merge(incoming)
        assertEquals(first.meals,repo.snapshot().meals);assertEquals(first.bodies,repo.snapshot().bodies);assertTrue(repo.snapshot().plans.isEmpty())
        assertEquals(5,first.meals.size);assertEquals(16,first.meals.sumOf{it.items.size});assertEquals(3,first.bodies.size)
        val m=incoming.meals.first{it.date=="2026-10-16"};val changed=m.copy(note=m.note+"更正")
        val changedImport=incoming.copy(meals=incoming.meals.map{if(it.id==m.id)changed else it},origins=incoming.origins.map{if(it.entityId==m.id)it.copy(fingerprint="changed") else it})
        assertTrue("2026-10-16" in repo.conflicts(repo.snapshot(),changedImport))
        repo.merge(changedImport);assertEquals(m.note,repo.snapshot().meals.first{it.id==m.id}.note)
        repo.merge(changedImport,setOf("2026-10-16"));assertEquals(changed.note,repo.snapshot().meals.first{it.id==m.id}.note)
        assertTrue(repo.conflicts(repo.snapshot(),changedImport).isEmpty())
    }
    @Test fun diskReopenRetainsMeals()=runBlocking {
        val filename="persistence-${uid()}.db"
        val disk=Room.databaseBuilder(context,FoodLogDatabase::class.java,filename).build();val r=Repository(context,disk);r.initialize()
        val v=noodle();r.meal(Meal(date="2026-10-18",items=listOf(NutritionCalculator.item(v,"60","G"))),true);val saved=r.snapshot().meals;disk.close()
        val reopened=Room.databaseBuilder(context,FoodLogDatabase::class.java,filename).build();assertEquals(saved,Repository(context,reopened).snapshot().meals);reopened.close();context.deleteDatabase(filename);Unit
    }
}
