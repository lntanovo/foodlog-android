package com.foodlog.app.domain

data class DateInventory(val date: String, val posted: Int, val drafts: Int)
data class DataInspection(val posted: Int, val drafts: Int, val dates: List<DateInventory>, val pendingItems: Int)

fun inspectData(data: Dataset) = DataInspection(
    data.meals.count { it.status == "POSTED" }, data.meals.count { it.status == "DRAFT" },
    data.meals.groupBy { it.date }.toSortedMap(reverseOrder()).map { (date, meals) ->
        DateInventory(date, meals.count { it.status == "POSTED" }, meals.count { it.status == "DRAFT" })
    }, data.meals.sumOf { meal -> meal.items.count { it.quantity == null } }
)

data class RecordDifference(val group: String, val key: String, val action: String, val before: String?, val after: String?)
data class RestoreDifference(val records: List<RecordDifference>) {
    val added get() = records.count { it.action == "新增" }
    val removed get() = records.count { it.action == "移除" }
    val changed get() = records.count { it.action == "变更" }
}

/** Direction is always current local data -> the selected full backup. Never merges or mutates. */
fun restoreDifference(local: Dataset, incoming: Dataset): RestoreDifference {
    val result = mutableListOf<RecordDifference>()
    fun <T, K> compare(group: String, before: List<T>, after: List<T>, key: (T) -> K, describe: (T) -> String, content: (T) -> Any? = { it }) {
        val old = before.associateBy(key)
        val next = after.associateBy(key)
        (old.keys + next.keys).sortedBy { it.toString() }.forEach { id ->
            val a = old[id]; val b = next[id]
            if (a == null || b == null || content(a) != content(b)) result += RecordDifference(
                group, id.toString(), if (a == null) "新增" else if (b == null) "移除" else "变更", a?.let(describe), b?.let(describe))
        }
    }
    compare("餐食", local.meals, incoming.meals, { it.id }, ::describeMeal, { it.copy(createdAt = "", updatedAt = "") })
    compare("身体记录", local.bodies, incoming.bodies, { it.date }, {
        "${it.date} · 体重 ${it.weight ?: "未测"} kg · 腰围 ${it.waist ?: "未测"} cm · ${if (it.standard) "标准晨重" else "非标准称重"}" + if (it.note.isBlank()) "" else " · ${it.note}"
    })
    compare("每日记录", local.days, incoming.days, { it.date }, {
        "${it.date} · ${trainingLabels[it.training]} · ${dietDayTypeLabels[it.dietDayType] ?: "未设日型"}\n力量 ${it.strengthMinutes ?: "未填"} / 羽毛球 ${it.badmintonMinutes ?: "未填"} 分钟 · 饥饿感 ${it.hunger ?: "未填"}\n表现 ${it.performance ?: "未填"} · 疲劳 ${it.fatigue.ifBlank { "未填" }} · ${when(it.loggingStatus) { "COMPLETE" -> "记录完整"; "PARTIAL" -> "部分记录"; else -> "完整性未确认" }} · ${it.note}"
    })
    compare("每日目标", local.targets, incoming.targets, { it.date }, { "${it.date} · ${it.dayType} · ${describeNutrients(it.nutrients)}" })
    val versions = (local.versions + incoming.versions).associateBy { it.id }
    compare("食品", local.foods, incoming.foods, { it.id }, { "${versions[it.activeVersionId]?.name ?: it.id} · ${if(it.archived) "已归档" else "在用"} · ${if(it.favorite) "收藏" else "未收藏"}" }, { it.copy(createdAt = "", updatedAt = "") })
    compare("食品标签", local.versions, incoming.versions, { it.id }, {
        "${it.name} ${it.brand} 第${it.versionNo}版 · 基准 ${it.basisQuantity}${it.basisUnit}\n标签 ${it.labelKcal ?: "未知"} kcal / ${it.labelKj ?: "未知"} kJ · 资料热量 ${it.tableKcal ?: "未知"}\n${describeNutrients(it.nutrients)}\n${it.weighingBasis} · ${it.sourceText}\n份量：${it.portions.joinToString { p -> "${p.name}=${p.amount}${p.unit}${if(p.estimated) "（估算）" else ""}" }}"
    }, { it.copy(createdAt = "") })
    compare("目标计划", local.plans, incoming.plans, { it.id }, {
        "${it.effectiveFrom} 起 · ${if(it.mode == "PER_KG") "体重系数" else "直接克数"} · 参考体重 ${it.referenceWeight ?: "未设"}\n训练：${describeMacros(it.training)}\n休息：${describeMacros(it.rest)}\n放纵：${it.flex?.let(::describeMacros) ?: "未设"}\n计划创建：${it.createdAt}"
    })
    compare("常用餐", local.templates, incoming.templates, { it.id }, { "${it.name} · ${it.type}\n${it.items.joinToString("\n", transform = ::describeItem)}" }, { it.copy(createdAt = "", updatedAt = "") })
    compare("导入来源", local.origins, incoming.origins, { it.namespace to it.key }, { "${it.namespace} · ${it.key}" }, { it.copy(importedAt = "") })
    if (local.meta.profile != incoming.meta.profile) result += RecordDifference("个人资料", "profile", "变更", describeProfile(local.meta.profile), describeProfile(incoming.meta.profile))
    if (local.meta.timezone != incoming.meta.timezone) result += RecordDifference("业务时区", "timezone", "变更", local.meta.timezone, incoming.meta.timezone)
    return RestoreDifference(result)
}

private fun describeProfile(p: Profile) = "${p.name.ifBlank { "未填称呼" }} · 身高 ${p.height ?: "未填"} cm · 年龄 ${p.age ?: "未填"} · ${p.sex.ifBlank { "未填性别" }}"
private fun describeMacros(m: Macros) = "蛋白 ${m.protein} · 碳水 ${m.carbs} · 脂肪 ${m.fat}"
private fun nutrient(value: String?) = value?.let { display(it) } ?: "未知"
private fun describeNutrients(n: Nutrients) = "热量 ${nutrient(n.kcal)} kcal · 蛋白 ${nutrient(n.protein)} g · 碳水 ${nutrient(n.carbs)} g · 脂肪 ${nutrient(n.fat)} g · 钠 ${nutrient(n.sodium)} mg · 纤维 ${nutrient(n.fiber)} g"
private fun describeItem(item: MealItem): String {
    val amount = item.draftQuantityText?.let { "草稿输入 $it" } ?: item.quantity?.let { "$it${when(item.unit) { "G" -> "g"; "ML" -> "ml"; else -> item.unit }}" } ?: "份量待补"
    return "${item.snapshot.name} $amount${if(item.quantityStatus == "ESTIMATED") "（估算）" else ""} · ${describeNutrients(item.derived)}\n标签第${item.snapshot.versionNo}版 · ${item.snapshot.sourceText}"
}
private fun describeMeal(meal: Meal): String = buildString {
    append("${meal.date} ${meal.type} · ${if (meal.status == "POSTED") "已吃" else "草稿"}")
    meal.items.forEach { item ->
        append("\n${describeItem(item)}")
    }
    if (meal.note.isNotBlank()) append("\n备注：${meal.note}")
}
