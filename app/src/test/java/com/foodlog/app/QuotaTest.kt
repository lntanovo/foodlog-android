package com.foodlog.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=FoodLogApplication::class)
class QuotaTest {
    private lateinit var db: FoodLogDatabase
    private lateinit var repo: Repository
    private lateinit var context: Context
    private val date="2026-10-19"
    @Before fun setup()=runBlocking {
        context=ApplicationProvider.getApplicationContext()
        db=Room.inMemoryDatabaseBuilder(context,FoodLogDatabase::class.java).allowMainThreadQueries().build()
        repo=Repository(context,db); repo.initialize()
    }
    @After fun close() { db.close() }
    private fun plan(weight: String="65", flex: Boolean=true, from: String=date, created: String="2026-10-18T01:00:00Z") = TargetPlan(
        effectiveFrom=from,mode="PER_KG",referenceWeight=weight,
        training=Macros("1.5","3","1"),rest=Macros("1.5","2.5","0.8"),
        flex=if(flex) Macros("1.5","4","1.2") else null,createdAt=created)
    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Invalid operation succeeded") } catch(_:IllegalArgumentException) { }
    }

    @Test fun legacyExerciseInfersDietLabelWithoutChangingActualTraining() {
        assertNull(effectiveDietDayType(null))
        assertNull(effectiveDietDayType(DayLog(date)))
        assertEquals("REST",effectiveDietDayType(DayLog(date,training="REST")))
        listOf("STRENGTH","BADMINTON","BOTH").forEach {
            assertEquals("TRAINING",effectiveDietDayType(DayLog(date,training=it)))
        }
        assertEquals("FLEX",effectiveDietDayType(DayLog(date,training="BOTH",dietDayType="FLEX")))
    }
    @Test fun threePerKilogramReferencesUseFixedWeightAndFourFourNineEnergy() {
        val p=plan()
        val training=NutritionCalculator.target(p,date,"TRAINING")
        assertEquals(Nutrients(kcal="1755",protein="97.5",carbs="195",fat="65"),training.nutrients)
        assertEquals("162.5",NutritionCalculator.target(p,date,"REST").nutrients.carbs)
        assertEquals(Nutrients(kcal="2132",protein="97.5",carbs="260",fat="78"),NutritionCalculator.target(p,date,"FLEX").nutrients)
        assertEquals(training.nutrients,NutritionCalculator.target(p,date,"BOTH").nutrients)
        val direct=p.copy(mode="DIRECT_MACROS",referenceWeight=null,training=Macros("100","200","50"))
        assertEquals("1650",NutritionCalculator.target(direct,date,"TRAINING").nutrients.kcal)
    }
    @Test fun optionalFlexIsUnconfiguredRatherThanBorrowedFromTraining() {
        val p=plan(flex=false)
        assertNull(NutritionCalculator.targetOrNull(p,date,"FLEX"))
        assertNull(NutritionCalculator.targetOrNull(null,date,"TRAINING"))
        assertNull(NutritionCalculator.targetOrNull(p,date,null))
        assertThrows(IllegalArgumentException::class.java) { NutritionCalculator.target(p,date,"FLEX") }
        assertThrows(IllegalArgumentException::class.java) { NutritionCalculator.target(p,date,"invalid") }
    }
    @Test fun selectingDietTypePreservesExerciseBodyAndRecordedFood(): Unit = runBlocking {
        val p=plan(); repo.plan(p)
        val day=DayLog(date,training="BOTH",strengthMinutes=55,badmintonMinutes=30,hunger=6,performance="稳定",fatigue="轻微",loggingStatus="COMPLETE",note="保留备注")
        val body=BodyRecord(date,"66.2","75")
        repo.day(day,body)
        val d=repo.snapshot(); val v=d.version(d.foods.first())
        repo.meal(Meal(date=date,items=listOf(NutritionCalculator.item(v,"50","G"))),true)
        val meals=repo.snapshot().meals
        val selected=repo.selectDietDayType(date,"REST")!!
        val after=repo.snapshot()
        assertEquals(day.copy(dietDayType="REST"),after.days.single())
        assertEquals(body,after.bodies.single()); assertEquals(meals,after.meals)
        assertEquals(NutritionCalculator.target(p,date,"REST"),selected)
    }
    @Test fun missingFlexDeletesMismatchedTargetFromDatabaseAndBackup(): Unit = runBlocking {
        repo.plan(plan(flex=false)); repo.selectDietDayType(date,"TRAINING")
        assertNotNull(repo.snapshot().targets.single())
        assertNull(repo.selectDietDayType(date,"FLEX"))
        assertEquals("FLEX",repo.snapshot().days.single().dietDayType)
        assertTrue(repo.snapshot().targets.isEmpty())
        assertTrue(Repository(context,db).snapshot().targets.isEmpty())
        repo.ensureTarget(date); assertTrue(repo.snapshot().targets.isEmpty())
        assertTrue(repo.parse(repo.export(false)).targets.isEmpty())
    }
    @Test fun addingPlanAndEditingWeightDoNotRewriteFrozenReferences(): Unit = runBlocking {
        repo.plan(plan()); repo.selectDietDayType(date,"TRAINING")
        val frozen=repo.snapshot().targets.single()
        repo.plan(plan(weight="80",created="2026-10-18T02:00:00Z"))
        repo.day(repo.snapshot().days.single().copy(training="REST"),BodyRecord(date,"79"))
        repo.ensureTarget(date)
        assertEquals(frozen,repo.snapshot().targets.single())
        assertEquals(frozen,repo.selectDietDayType(date,"TRAINING"))
        assertEquals(frozen,repo.snapshot().targets.single())
        assertEquals("80",repo.recalculateTarget(date)!!.referenceWeight)
    }
    @Test fun explicitReapplyChangesOnlyTheSelectedDateAndCanChangeItsLabelAtomically(): Unit = runBlocking {
        repo.plan(plan())
        repo.selectDietDayType(date,"TRAINING"); repo.selectDietDayType("2026-10-20","REST")
        val other=repo.snapshot().targets.first { it.date!=date }
        val latest=plan(weight="70",created="2026-10-18T02:00:00Z"); repo.plan(latest)
        val result=repo.recalculateTarget(date,"FLEX")!!
        assertEquals(latest.id,result.planId); assertEquals("FLEX",result.dayType)
        assertEquals("FLEX",repo.snapshot().days.first { it.date==date }.dietDayType)
        assertEquals(other,repo.snapshot().targets.first { it.date!=date })
    }
    @Test fun staleBodyEditorNeverClearsNewDietLabelEvenWhenExplicitlyRecalculating(): Unit = runBlocking {
        repo.plan(plan()); val old=DayLog(date,training="STRENGTH",note="旧表单")
        repo.day(old); repo.selectDietDayType(date,"FLEX")
        repo.day(old.copy(training="REST",note="已编辑"),BodyRecord(date,"65.8"),recalculateTarget=true)
        val after=repo.snapshot()
        assertEquals("FLEX",after.days.single().dietDayType)
        assertEquals("REST",after.days.single().training)
        assertEquals("已编辑",after.days.single().note)
        assertEquals("FLEX",after.targets.single().dayType)
    }
    @Test fun futurePlanAppliesOnlyOnOrAfterItsEffectiveDate(): Unit = runBlocking {
        val current=plan(); val future=plan(weight="75",from="2026-10-20")
        repo.plan(current); repo.plan(future)
        assertEquals(current,repo.snapshot().planFor(date))
        assertEquals(future,repo.snapshot().planFor("2026-10-20"))
        assertNull(repo.snapshot().planFor("2026-10-18"))
        assertEquals(current.id,repo.selectDietDayType(date,"TRAINING")!!.planId)
        assertEquals(future.id,repo.selectDietDayType("2026-10-20","TRAINING")!!.planId)
    }
    @Test fun firstReferenceCanBeCreatedAfterSelectingLabelBeforeAnyPlanExists(): Unit = runBlocking {
        assertNull(repo.selectDietDayType(date,"FLEX"))
        repo.plan(plan()); assertTrue(repo.snapshot().targets.isEmpty())
        repo.ensureTarget(date)
        assertEquals("FLEX",repo.snapshot().targets.single().dayType)
        assertEquals("260",repo.snapshot().targets.single().nutrients.carbs)
    }
    @Test fun schemaOneAndTwoBackupsWithoutNewFieldsRetainOldTargetSnapshots(): Unit = runBlocking {
        val p=plan(flex=false)
        val old=repo.snapshot().copy(days=listOf(DayLog(date,training="STRENGTH")),plans=listOf(p),
            targets=listOf(NutritionCalculator.target(p,date,"STRENGTH")))
        for(schema in 1..2) {
            val root=dataJson.parseToJsonElement(dataJson.encodeToString(old.copy(schemaVersion=schema,appVersion="1.0.0"))).jsonObject.toMutableMap()
            root["days"]=JsonArray(root.getValue("days").jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key!="dietDayType" }) })
            root["plans"]=JsonArray(root.getValue("plans").jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key!="flex" }) })
            val imported=repo.parse(JsonObject(root).toString())
            assertEquals(3,imported.schemaVersion); assertEquals(com.foodlog.app.BuildConfig.VERSION_NAME,imported.appVersion)
            assertNull(imported.days.single().dietDayType); assertNull(imported.plans.single().flex)
            assertEquals("TRAINING",effectiveDietDayType(imported.days.single()))
            assertEquals(old.targets,imported.targets)
            repo.restore(imported); repo.ensureTarget(date)
            assertEquals(old.targets,repo.snapshot().targets)
        }
    }
    @Test fun compressedBackupRestoresThreeReferencesAndExplicitDietLabels(): Unit = runBlocking {
        repo.plan(plan()); repo.selectDietDayType(date,"FLEX")
        val original=repo.snapshot()
        val bytes=ByteArrayOutputStream(); repo.writeBackup(bytes)
        val restored=repo.readBackup(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(original.days,restored.days); assertEquals(original.targets,restored.targets)
        assertEquals(original.plans,restored.plans)
        repo.selectDietDayType(date,"REST"); repo.restore(restored)
        assertEquals(original.days,repo.snapshot().days)
        assertEquals(original.targets,repo.snapshot().targets)
        val library=repo.parse(repo.export(true,original.foods.map { it.id }.toSet()))
        assertTrue(library.days.isEmpty()); assertTrue(library.plans.isEmpty()); assertTrue(library.targets.isEmpty())
    }
    @Test fun invalidTypeOrPlanLeavesAllStoredReferencesUnchanged(): Unit = runBlocking {
        repo.plan(plan()); repo.selectDietDayType(date,"TRAINING")
        val original=repo.snapshot()
        rejects { repo.selectDietDayType(date,"INDULGENCE") }
        rejects { repo.recalculateTarget(date,"UNKNOWN") }
        rejects { repo.plan(plan().copy(flex=Macros("1.5","-2","1"))) }
        rejects { repo.day(DayLog(date="2026-10-21",dietDayType="INVALID")) }
        assertEquals(original,repo.snapshot().copy(exportedAt=original.exportedAt))
        assertThrows(IllegalArgumentException::class.java) { Validation.dataset(original.copy(schemaVersion=2)) }
        val badTarget=original.targets.single().copy(nutrients=Nutrients(kcal="0"))
        assertThrows(IllegalArgumentException::class.java) { Validation.dataset(original.copy(targets=listOf(badTarget))) }
    }
    @Test fun concurrentBodyEditAndDietSelectionBothPersist(): Unit = runBlocking {
        repo.plan(plan()); val old=DayLog(date,training="BOTH")
        repo.day(old)
        coroutineScope {
            val label=async { repo.selectDietDayType(date,"FLEX") }
            val body=async { repo.day(old.copy(note="并发保留"),BodyRecord(date,"65.7")) }
            label.await(); body.await()
        }
        assertEquals("FLEX",repo.snapshot().days.single().dietDayType)
        assertEquals("并发保留",repo.snapshot().days.single().note)
        assertEquals("65.7",repo.snapshot().bodies.single().weight)
        assertEquals("FLEX",repo.snapshot().targets.single().dayType)
    }
    @Test fun loggingCompletenessUpdatesOnlyItsOwnField(): Unit = runBlocking {
        repo.plan(plan())
        repo.day(DayLog(date,training="BOTH",hunger=7,note="独立字段"))
        repo.selectDietDayType(date,"FLEX")
        val day=repo.snapshot().days.single(); val target=repo.snapshot().targets.single()
        repo.updateLoggingStatus(date,"COMPLETE")
        assertEquals(day.copy(loggingStatus="COMPLETE"),repo.snapshot().days.single())
        assertEquals(target,repo.snapshot().targets.single())
        rejects { repo.updateLoggingStatus(date,"INVALID") }
        assertEquals("COMPLETE",repo.snapshot().days.single().loggingStatus)
    }
}
