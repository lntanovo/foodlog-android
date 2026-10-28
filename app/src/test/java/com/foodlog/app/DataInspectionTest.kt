package com.foodlog.app

import com.foodlog.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class DataInspectionTest {
    @Test fun equalCountsStillIdentifyMovedEditedAndReplacedMeals() {
        val a = Meal(id = "a", date = "2026-10-01", status = "POSTED")
        val b = Meal(id = "b", date = "2026-10-02", status = "DRAFT")
        val local = Dataset(meals = listOf(a, b))
        val next = local.copy(meals = listOf(a.copy(date = "2026-10-03", note = "修正"), b.copy(id = "c")))
        val diff = restoreDifference(local, next)
        assertEquals(1, diff.added); assertEquals(1, diff.removed); assertEquals(1, diff.changed)
        assertTrue(diff.records.single { it.action == "变更" }.before!!.contains("2026-10-01"))
        assertTrue(diff.records.single { it.action == "变更" }.after!!.contains("2026-10-03"))
    }

    @Test fun timestampsOrderingAndExportMetadataDoNotInventBusinessChanges() {
        val a = Meal(id = "a", date = "2026-10-01")
        val b = Meal(id = "b", date = "2026-10-02")
        val local = Dataset(meals = listOf(a, b))
        val next = local.copy(exportedAt = "different", appVersion = "next", meta = local.meta.copy(revision = 40, lastExport = "other"),
            meals = listOf(b.copy(updatedAt = "other"), a))
        assertTrue(restoreDifference(local, next).records.isEmpty())
    }

    @Test fun statusSnapshotAndUnknownQuantityChangesAreVisible() {
        val food = FoodVersion(foodId = "food", name = "测试米饭", labelKcal = "100")
        val item = NutritionCalculator.item(food, null, "G")
        val meal = Meal(date = "2026-10-01", items = listOf(item))
        val local = Dataset(meals = listOf(meal))
        val next = local.copy(meals = listOf(meal.copy(status = "POSTED", items = listOf(item.copy(quantity = "50", snapshot = food.copy(labelKcal = "200"))))))
        assertEquals(1, restoreDifference(local, next).changed)
        assertTrue(restoreDifference(local, next).records.single().after!!.contains("已吃"))
        assertTrue(restoreDifference(local, next).records.single().before!!.contains("份量待补"))
    }

    @Test fun emptyBackupShowsRemovalsAndProfileTimezoneChanges() {
        val local = Dataset(meals = listOf(Meal(date = "2026-10-01")), bodies = listOf(BodyRecord("2026-10-01", "70")))
        val next = Dataset(meta = local.meta.copy(timezone = "UTC", profile = Profile(name = "测试")))
        val diff = restoreDifference(local, next)
        assertEquals(2, diff.removed); assertEquals(2, diff.changed)
    }

    @Test fun diagnosisSeparatesDraftsAndListsHistoricalDatesWithoutMutation() {
        val data = Dataset(meals = listOf(Meal(date = "2026-09-01", status = "POSTED"), Meal(date = "2026-10-02"), Meal(date = "2026-09-01")))
        val result = inspectData(data)
        assertEquals(1, result.posted); assertEquals(2, result.drafts)
        assertEquals(listOf("2026-10-02", "2026-09-01"), result.dates.map { it.date })
        assertEquals(1, result.dates.last().posted)
        assertEquals(3, data.meals.size)
    }
}
