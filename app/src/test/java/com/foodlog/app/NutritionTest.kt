package com.foodlog.app

import com.foodlog.app.domain.*
import com.foodlog.app.data.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class NutritionTest {
    private val seed=dataJson.decodeFromString<Dataset>(File(System.getProperty("projectDir"),"src/main/assets/foods_seed_v1.json").readText())
    private fun food(id:String)=seed.version(seed.foods.first{it.seedId==id})
    private fun assertDecimal(expected:String,actual:String?) { assertNotNull(actual);assertTrue("$expected != $actual",(expected.toBigDecimal()-actual!!.toBigDecimal()).abs()<"0.00000000001".toBigDecimal()) }
    @Test fun seedHasElevenPublicFoods() { Validation.dataset(seed);assertEquals(11,seed.foods.size);assertTrue(seed.meals.isEmpty());assertTrue(seed.bodies.isEmpty());assertTrue(seed.plans.isEmpty()) }
    @Test fun noodlesDryWeight() {val i=NutritionCalculator.item(food("buckwheat_noodles_60g"),"60","G");assertDecimal("221.8451242829828",i.derived.kcal);assertDecimal("8.16",i.derived.protein);assertDecimal("46.44",i.derived.carbs);assertDecimal("0",i.derived.fat);assertNull(i.derived.fiber)}
    @Test fun twoBags() {val i=NutritionCalculator.item(food("buckwheat_noodles_60g"),"2","袋");assertDecimal("120",i.normalizedAmount);assertDecimal("92.88",i.derived.carbs)}
    @Test fun milkKeepsOriginal250GramBasis() {val v=food("eurasia_whole_milk_250g");assertEquals("250",v.basisQuantity);val i=NutritionCalculator.item(v,"1","盒");assertDecimal("160.1338432122371",i.derived.kcal);assertDecimal("7.8",i.derived.protein);assertDecimal("12.6",i.derived.carbs);assertDecimal("9",i.derived.fat);assertDecimal("138",i.derived.sodium)}
    @Test fun cereal18Grams() {val n=NutritionCalculator.item(food("crispy_cereal_18g"),"18","G").derived;assertDecimal("67.0697896749522",n.kcal);assertDecimal("2.232",n.protein);assertDecimal("12.33",n.carbs);assertDecimal("0.432",n.fat);assertDecimal("2.124",n.fiber)}
    @Test fun cpt30Grams() {val n=NutritionCalculator.item(food("cpt_protein_powder"),"30","G").derived;assertDecimal("110.9942638623327",n.kcal);assertDecimal("26.4",n.protein);assertDecimal("0.66",n.carbs);assertDecimal("0.12",n.fat);assertDecimal("6",n.sodium)}
    @Test fun pandaUsesKcalNotKj() {val n=NutritionCalculator.item(food("myprotein_chocolate_powder"),"30","G").derived;assertDecimal("112.8",n.kcal);assertDecimal("21.9",n.protein);assertDecimal("1.95",n.carbs);assertDecimal("1.86",n.fat);assertDecimal("51.6",n.sodium)}
    @Test fun estimatedEgg() {val v=food("egg_hard_boiled_estimate");val i=NutritionCalculator.item(v,"1","个");assertEquals("ESTIMATED",i.quantityStatus);assertDecimal("77.5",i.derived.kcal);assertDecimal("6.29",i.derived.protein);assertDecimal("0.56",i.derived.carbs);assertDecimal("5.3",i.derived.fat)}
    @Test fun tomato() {val n=NutritionCalculator.item(food("tomato_raw_estimate"),"150","G").derived;assertDecimal("27",n.kcal);assertDecimal("1.32",n.protein);assertDecimal("5.835",n.carbs);assertDecimal("0.3",n.fat)}
    @Test fun sumBeforeRounding() {val items=listOf(NutritionCalculator.item(food("buckwheat_noodles_60g"),"60","G"),NutritionCalculator.item(food("eurasia_whole_milk_250g"),"250","G"),NutritionCalculator.item(food("mixed_nuts"),"15","G"));val n=NutritionCalculator.aggregate(items);assertDecimal("478.3460803059273",n[0].known);assertDecimal("18.525",n[1].known);assertDecimal("61.845",n[2].known);assertDecimal("17.205",n[3].known);assertFalse(n[5].complete)}
    @Test fun customLabelWins() {val v=FoodVersion(foodId=uid(),name="自定义",labelKcal="300",nutrients=Nutrients(protein="10",carbs="50",fat="6"));val n=NutritionCalculator.item(v,"40","G").derived;assertEquals("120",n.kcal);assertEquals("4",n.protein);assertEquals("20",n.carbs);assertEquals("2.4",n.fat)}
    @Test fun macroFallbackIsMarked() {val v=FoodVersion(foodId=uid(),name="估算",nutrients=Nutrients(protein="10",carbs="50",fat="6"));assertEquals(Energy("294","按碳蛋脂估算热量"),NutritionCalculator.energy(v))}
    @Test fun unknownAndZeroAreIndependent() {val i=NutritionCalculator.item(food("buckwheat_noodles_60g"),"60","G");val n=NutritionCalculator.aggregate(listOf(i));assertEquals("0",n[3].known);assertTrue(n[3].complete);assertNull(n[5].known);assertFalse(n[5].complete);assertEquals(1,n[5].missing)}
    @Test fun unknownQuantityNeverContributesZero() {val v=food("mixed_nuts");val sum=NutritionCalculator.aggregate(listOf(NutritionCalculator.item(v,"15","G"),NutritionCalculator.item(v,null,"G")));assertEquals(1,sum[0].missing);assertNotNull(sum[0].known);assertFalse(sum[0].complete)}
    @Test fun emptyDayIsUnrecordedUnlessConfirmed() {assertNull(NutritionCalculator.aggregate(emptyList())[0].known);assertEquals("0",NutritionCalculator.aggregate(emptyList(),true)[0].known)}
    @Test fun mlAndGramsNeedDensity() {val v=FoodVersion(foodId=uid(),name="液体",basisUnit="ML");assertThrows(IllegalArgumentException::class.java){NutritionCalculator.item(v,"100","G")};assertEquals("50",NutritionCalculator.normalize(v.copy(densityGPerMl="2"),"100","G"))}
    @Test fun rejectsInvalidNumbers() {listOf("-1","NaN","Infinity","1e3","1..2","").forEach{assertThrows(IllegalArgumentException::class.java){decimal(it)}};assertThrows(IllegalArgumentException::class.java){decimal("0",true)};assertEquals("0",decimal("0").canonical())}
    @Test fun duckAssistantIsArithmeticOnly() {assertEquals("93",NutritionCalculator.edible("110","17"));assertThrows(IllegalArgumentException::class.java){NutritionCalculator.edible("10","17")}}
    @Test fun fourteenNaturalDays() {val start=LocalDate.of(2026,1,1);val rows=(0..13).map{BodyRecord(start.plusDays(it.toLong()).toString(),("65.0".toBigDecimal()-it.toBigDecimal()*"0.1".toBigDecimal()).toPlainString())};assertDecimal("64.7",Trends.window(rows,start.plusDays(6)).mean);val w=Trends.window(rows,start.plusDays(13));assertDecimal("64.0",w.mean);assertDecimal("-0.7",w.change);assertEquals(7,w.count)}
    @Test fun missingAndNonstandardWeightsNotFilled() {val date=LocalDate.of(2026,1,14);val rows=(0..13).map{BodyRecord(date.minusDays(it.toLong()).toString(),"65",standard=it!=2)};val w=Trends.window(rows,date);assertEquals(6,w.count);assertNull(w.change);assertEquals(0,Trends.window(rows,date.plusDays(30)).count)}
    @Test fun historicalFoodSnapshotSurvivesLabelChange() {val v=food("mixed_nuts");val i=NutritionCalculator.item(v,"15","G");val edited=v.copy(id=uid(),name="新版",labelKcal="999");assertEquals(v.name,i.snapshot.name);assertNotEquals(NutritionCalculator.item(edited,"15","G").derived.kcal,i.derived.kcal);assertEquals(i.derived,NutritionCalculator.item(i.snapshot,"15","G").derived)}
    @Test fun quotasAreIndependentOfMorningWeight() {val p=TargetPlan(effectiveFrom="2026-10-16",mode="PER_KG",referenceWeight="65",training=Macros("1.5","2.75","0.8"),rest=Macros("1.5","2.25","0.8"));assertEquals("1573",NutritionCalculator.target(p,"2026-10-16","BOTH").nutrients.kcal);assertEquals("1443",NutritionCalculator.target(p,"2026-10-17","REST").nutrients.kcal);assertThrows(IllegalArgumentException::class.java){NutritionCalculator.target(p,"2026-10-16","UNKNOWN")}}
    @Test fun backupRoundTripAndRejectBadReferences() {val full=seed.copy(kind="FULL_BACKUP");assertEquals(full,dataJson.decodeFromString<Dataset>(dataJson.encodeToString(full)));assertThrows(IllegalArgumentException::class.java){Validation.dataset(full.copy(schemaVersion=999))};assertThrows(IllegalArgumentException::class.java){Validation.dataset(full.copy(versions=emptyList()))};assertThrows(IllegalArgumentException::class.java){Validation.dataset(full.copy(foods=full.foods+full.foods.first()))}}
    @Test fun changingQuantityUnitsKeepsActualAmount() {
        val v=food("buckwheat_noodles_60g")
        val original=NutritionCalculator.item(v,"60","G")
        val bag=NutritionCalculator.changeUnit(original,"袋")
        assertEquals("1",bag.quantity); assertEquals(original.derived,bag.derived)
        assertEquals("60",NutritionCalculator.changeUnit(bag,"G").quantity)
        val liquid=FoodVersion(foodId=uid(),name="液体",basisUnit="ML",densityGPerMl="2",portions=listOf(Portion("瓶","250","ML")))
        assertEquals("0.2",NutritionCalculator.convertQuantity(liquid,"100","G","瓶"))
        assertThrows(IllegalArgumentException::class.java) { NutritionCalculator.convertQuantity(liquid.copy(densityGPerMl=null),"100","G","ML") }
    }
    @Test fun weighingAnEstimatedPortionClearsOnlyTheQuantityEstimate() {
        val v=food("egg_hard_boiled_estimate")
        val grams=NutritionCalculator.changeUnit(NutritionCalculator.item(v,"2","个"),"G")
        assertEquals("ESTIMATED",grams.quantityStatus)
        val measured=NutritionCalculator.editQuantity(grams,"98")
        assertEquals("KNOWN",measured.quantityStatus); assertEquals(v.estimatedFields,measured.snapshot.estimatedFields)
        val incomplete=NutritionCalculator.editQuantity(measured,"60.")
        assertEquals("60.",incomplete.draftQuantityText); assertNull(incomplete.quantity); assertNull(incomplete.derived.kcal)
    }
    @Test fun actualLegacyFilePassesTheSameStrictValidator() {val f=File(System.getProperty("projectDir"),"../deliverables/个人旧记录_导入.json");if(f.exists()){val d=dataJson.decodeFromString<Dataset>(f.readText());Validation.dataset(d);assertEquals(3,d.bodies.size);assertEquals(2,d.meals.flatMap{it.items}.count{it.quantity==null});assertTrue(d.meals.none{it.note.contains("建议补餐")})}}
}
