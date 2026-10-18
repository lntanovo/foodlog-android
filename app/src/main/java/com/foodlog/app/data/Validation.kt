package com.foodlog.app.data

import com.foodlog.app.domain.*
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

val dataJson = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

object Validation {
    private fun date(s: String) { require(LocalDate.parse(s).toString() == s) { "非法日期" } }
    private fun id(s: String) { require(UUID.fromString(s).toString() == s) { "非法ID" } }
    private fun unique(ids: List<String>) { require(ids.distinct().size == ids.size) { "存在重复ID或日期" } }
    fun food(v: FoodVersion) {
        id(v.id); id(v.foodId); require(v.versionNo > 0 && v.name.isNotBlank() && v.name.length <= 200)
        decimal(v.basisQuantity,true); require(v.basisUnit in listOf("G","ML"))
        (v.nutrients.values()+listOf(v.labelKcal,v.labelKj,v.tableKcal)).filterNotNull().forEach { decimal(it) }
        v.densityGPerMl?.let { decimal(it,true) }
        unique(v.portions.map { it.name })
        v.portions.forEach { require(it.name.isNotBlank() && it.name !in listOf("G","ML")); decimal(it.amount,true); require(it.unit in listOf("G","ML")) }
        Instant.parse(v.createdAt)
    }
    fun meal(m: Meal, versions: Map<String, FoodVersion>, postedIds: Set<String> = emptySet()) {
        id(m.id); date(m.date); require(m.status in listOf("DRAFT", "POSTED") && m.type in mealTypes)
        unique(m.items.map { it.id })
        m.replacesMealId?.let { require(m.status == "DRAFT" && it in postedIds) { "草稿引用断裂" } }
        Instant.parse(m.createdAt); Instant.parse(m.updatedAt)
        m.items.forEach { i ->
            id(i.id); food(i.snapshot)
            require(i.foodId == i.snapshot.foodId && i.foodVersionId == i.snapshot.id && versions[i.foodVersionId] == i.snapshot) { "餐食快照引用不一致" }
            require(i.quantityStatus in listOf("KNOWN", "UNKNOWN", "ESTIMATED"))
            require((i.quantity == null) == (i.quantityStatus == "UNKNOWN"))
            require(i.unit in listOf("G", "ML") || i.snapshot.portions.any { it.name == i.unit }) { "不支持的份量单位" }
            i.draftQuantityText?.let { raw ->
                require(m.status == "DRAFT" && raw.length <= 256) { "未完成的份量仅可存于草稿" }
                val value = runCatching { decimal(raw) }.getOrNull()
                require(if (value == null) i.quantity == null else i.quantity?.let { decimal(it).compareTo(value) == 0 } == true) { "草稿份量与编辑文本不一致" }
            }
            val computed = NutritionCalculator.item(i.snapshot, i.quantity, i.unit, i.id, i.quantityStatus == "ESTIMATED")
            require(i.normalizedAmount == computed.normalizedAmount && i.derived == computed.derived) { "餐食营养计算不一致" }
            if (m.status == "POSTED") require(i.quantity == null || decimal(i.quantity) > java.math.BigDecimal.ZERO)
        }
    }
    fun template(t: MealTemplate, versions: Map<String, FoodVersion>) {
        id(t.id); require(t.name.isNotBlank() && t.name.length <= 100 && t.type in mealTypes) { "常用餐名称或类型无效" }
        Instant.parse(t.createdAt); Instant.parse(t.updatedAt)
        require(t.items.isNotEmpty() && t.items.all { it.draftQuantityText == null }) { "请先完成常用餐的份量" }
        meal(Meal(id=t.id, date="2000-01-01", type=t.type, status="POSTED", items=t.items), versions)
    }
    fun dataset(d: Dataset) {
        require(d.schemaVersion in 1..2) { "不支持的备份格式版本" }
        require(d.kind in listOf("FULL_BACKUP","FOOD_LIBRARY","LEGACY_IMPORT")) { "文件类型不支持" }
        ZoneId.of(d.meta.timezone); id(d.meta.datasetId); Instant.parse(d.exportedAt)
        d.meta.lastExport?.let(Instant::parse)
        d.meta.profile.height?.let { decimal(it,true) }; d.meta.profile.age?.let { require(it in 1..150) }
        listOf(d.foods.map{it.id},d.versions.map{it.id},d.meals.map{it.id},d.days.map{it.date},d.bodies.map{it.date},d.plans.map{it.id},d.targets.map{it.date},d.origins.map{it.namespace+":"+it.key},d.templates.map{it.id}).forEach(::unique)
        val foods=d.foods.associateBy{it.id}; val versions=d.versions.associateBy{it.id}; val plans=d.plans.associateBy{it.id}
        d.foods.forEach { f -> id(f.id); require(versions[f.activeVersionId]?.foodId == f.id) { "食品当前版本引用断裂" }; Instant.parse(f.createdAt); Instant.parse(f.updatedAt) }
        unique(d.foods.mapNotNull { it.seedId })
        d.versions.forEach { food(it); require(it.foodId in foods) { "食品版本引用断裂" } }
        val postedIds = d.meals.filter { it.status == "POSTED" }.map { it.id }.toSet()
        d.meals.forEach { meal(it, versions, postedIds) }
        d.templates.forEach { template(it, versions) }
        d.days.forEach { date(it.date); require(it.training in trainingLabels && it.loggingStatus in listOf("UNKNOWN","PARTIAL","COMPLETE")); it.hunger?.let { n -> require(n in 0..10) }; it.strengthMinutes?.let { n-> require(n in 0..1440) }; it.badmintonMinutes?.let { n-> require(n in 0..1440) } }
        d.bodies.forEach { date(it.date); it.weight?.let{n->decimal(n,true)}; it.waist?.let{n->decimal(n,true)} }
        d.plans.forEach { p -> id(p.id); date(p.effectiveFrom); Instant.parse(p.createdAt); require(p.mode in listOf("PER_KG","DIRECT_MACROS")); if(p.mode=="PER_KG") decimal(p.referenceWeight?:error("缺少参考体重"),true); listOf(p.rest,p.training).forEach { decimal(it.protein); decimal(it.carbs); decimal(it.fat) } }
        d.targets.forEach { t -> date(t.date); require(t.planId in plans && t.dayType in trainingLabels && t.dayType!="UNKNOWN"); require(t == NutritionCalculator.target(plans.getValue(t.planId),t.date,t.dayType)) { "目标快照不一致" } }
        val entities = (d.foods.map{it.id}+d.meals.map{it.id}+d.bodies.map{it.date}+d.days.map{it.date}+d.plans.map{it.id}).toSet()
        d.origins.forEach { require(it.namespace.isNotBlank() && it.key.isNotBlank() && it.fingerprint.isNotBlank()); require(it.entityId in entities) { "导入来源引用断裂" }; Instant.parse(it.importedAt) }
        if(d.kind=="FOOD_LIBRARY") require(d.meals.isEmpty() && d.templates.isEmpty() && d.days.isEmpty() && d.bodies.isEmpty() && d.plans.isEmpty() && d.targets.isEmpty() && d.origins.isEmpty() && d.meta.profile==Profile()) { "食品库夹带个人数据" }
    }
}
