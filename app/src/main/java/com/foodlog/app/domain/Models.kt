package com.foodlog.app.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString()
fun now(): String = Instant.now().toString()

@Serializable data class Nutrients(
    val kcal: String? = null, val protein: String? = null, val carbs: String? = null,
    val fat: String? = null, val sodium: String? = null, val fiber: String? = null
) {
    fun values() = listOf(kcal, protein, carbs, fat, sodium, fiber)
    companion object { fun from(v: List<String?>) = Nutrients(v[0],v[1],v[2],v[3],v[4],v[5]) }
}
@Serializable data class Portion(val name: String, val amount: String, val unit: String = "G", val estimated: Boolean = false)
@Serializable data class Food(
    val id: String = uid(), val seedId: String? = null, val activeVersionId: String,
    val favorite: Boolean = false, val archived: Boolean = false, val createdAt: String = now(), val updatedAt: String = now()
)
@Serializable data class FoodVersion(
    val id: String = uid(), val foodId: String, val versionNo: Int = 1, val name: String,
    val brand: String = "", val basisQuantity: String = "100", val basisUnit: String = "G",
    val labelKcal: String? = null, val labelKj: String? = null, val tableKcal: String? = null,
    val nutrients: Nutrients = Nutrients(), val portions: List<Portion> = emptyList(),
    val sourceKind: String = "USER_INPUT", val sourceText: String = "用户输入", val sourceUrl: String = "",
    val weighingBasis: String = "实际可食重量", val estimatedFields: List<String> = emptyList(),
    val densityGPerMl: String? = null, val createdAt: String = now()
)
@Serializable data class MealItem(
    val id: String = uid(), val foodId: String, val foodVersionId: String,
    val quantity: String? = null, val unit: String = "G", val quantityStatus: String = "KNOWN",
    val snapshot: FoodVersion, val normalizedAmount: String? = null, val derived: Nutrients = Nutrients()
)
@Serializable data class Meal(
    val id: String = uid(), val date: String, val type: String = "午餐", val status: String = "DRAFT",
    val items: List<MealItem> = emptyList(), val note: String = "", val replacesMealId: String? = null,
    val createdAt: String = now(), val updatedAt: String = now()
)
@Serializable data class DayLog(
    val date: String, val training: String = "UNKNOWN", val strengthMinutes: Int? = null,
    val badmintonMinutes: Int? = null, val hunger: Int? = null, val performance: String? = null,
    val fatigue: String = "", val loggingStatus: String = "UNKNOWN", val note: String = ""
)
@Serializable data class BodyRecord(
    val date: String, val weight: String? = null, val waist: String? = null,
    val standard: Boolean = true, val note: String = ""
)
@Serializable data class Macros(val protein: String, val carbs: String, val fat: String)
@Serializable data class TargetPlan(
    val id: String = uid(), val effectiveFrom: String, val mode: String = "DIRECT_MACROS",
    val referenceWeight: String? = null, val training: Macros, val rest: Macros, val createdAt: String = now()
)
@Serializable data class DayTarget(
    val date: String, val planId: String, val dayType: String,
    val referenceWeight: String? = null, val nutrients: Nutrients
)
@Serializable data class ImportOrigin(
    val namespace: String, val key: String, val entityId: String, val fingerprint: String, val importedAt: String = now()
)
@Serializable data class Profile(val name: String = "", val height: String? = null, val age: Int? = null, val sex: String = "")
@Serializable data class Meta(
    val datasetId: String = uid(), val timezone: String = "Asia/Shanghai", val profile: Profile = Profile(),
    val revision: Long = 0, val lastExport: String? = null
)
@Serializable data class Dataset(
    val schemaVersion: Int = 1, val appVersion: String = "1.0.0", val kind: String = "FULL_BACKUP",
    val exportedAt: String = now(), val meta: Meta = Meta(), val foods: List<Food> = emptyList(),
    val versions: List<FoodVersion> = emptyList(), val meals: List<Meal> = emptyList(),
    val days: List<DayLog> = emptyList(), val bodies: List<BodyRecord> = emptyList(),
    val plans: List<TargetPlan> = emptyList(), val targets: List<DayTarget> = emptyList(),
    val origins: List<ImportOrigin> = emptyList(), val report: List<String> = emptyList()
) {
    fun version(food: Food): FoodVersion = versions.first { it.id == food.activeVersionId }
}

val trainingLabels = linkedMapOf("UNKNOWN" to "未填写", "REST" to "休息", "STRENGTH" to "力量", "BADMINTON" to "羽毛球", "BOTH" to "力量＋羽毛球")
val mealTypes = listOf("午餐", "晚餐", "加餐", "早餐")
