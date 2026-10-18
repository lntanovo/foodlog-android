package com.foodlog.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

val precision: MathContext = MathContext.DECIMAL128
fun decimal(value: String, positive: Boolean = false): BigDecimal {
    require(value.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) { "请输入有效的非负十进制数字" }
    val n = value.toBigDecimal()
    require(n.precision() <= 120 && n.scale() <= 100) { "数字过长" }
    require(if (positive) n > BigDecimal.ZERO else n >= BigDecimal.ZERO) { "数值必须大于0" }
    return n
}
fun BigDecimal.canonical(): String = stripTrailingZeros().toPlainString()
fun display(value: String?, scale: Int = 1): String = value?.toBigDecimal()?.setScale(scale, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "待补"

data class Energy(val value: String?, val source: String)
data class MetricTotal(val known: String?, val missing: Int, val count: Int) {
    val complete get() = missing == 0 && known != null
    fun label(unit: String = ""): String = when {
        count == 0 -> "未记录"
        known == null -> "$missing 项待补"
        missing > 0 -> "已知 ${display(known)}$unit · $missing 项待补"
        else -> "${display(known)}$unit"
    }
}
object NutritionCalculator {
    fun energy(v: FoodVersion): Energy = when {
        v.labelKcal != null -> Energy(v.labelKcal, if(v.sourceKind=="PACKAGE_LABEL") "包装 kcal" else "输入 kcal")
        v.labelKj != null -> Energy(decimal(v.labelKj).divide(BigDecimal("4.184"), precision).canonical(), "由 kJ 换算")
        v.tableKcal != null -> Energy(v.tableKcal, "常见食品平均估算")
        listOf(v.nutrients.protein,v.nutrients.carbs,v.nutrients.fat).all { it != null } -> Energy(
            (decimal(v.nutrients.protein!!) * BigDecimal(4) + decimal(v.nutrients.carbs!!) * BigDecimal(4) + decimal(v.nutrients.fat!!) * BigDecimal(9)).canonical(), "按碳蛋脂估算热量")
        else -> Energy(null, "能量待补")
    }
    fun normalize(v: FoodVersion, quantity: String?, unit: String): String? {
        if (quantity == null) return null
        val n = decimal(quantity)
        val portion = v.portions.find { it.name == unit }
        val sourceUnit = portion?.unit ?: unit
        val amount = n * (portion?.amount?.let { decimal(it, true) } ?: BigDecimal.ONE)
        val result = if (sourceUnit == v.basisUnit) amount else {
            require(sourceUnit in listOf("G", "ML") && v.densityGPerMl != null) { "g 与 ml 不能直接换算；请补充密度或切换单位" }
            val density = decimal(v.densityGPerMl, true)
            if (sourceUnit == "ML") amount * density else amount.divide(density, precision)
        }
        return result.canonical()
    }
    fun calculate(v: FoodVersion, normalized: String?): Nutrients {
        if (normalized == null) return Nutrients()
        val factor = decimal(normalized).divide(decimal(v.basisQuantity, true), precision)
        return Nutrients.from(v.nutrients.copy(kcal = energy(v).value).values().map { it?.let { n -> (decimal(n) * factor).canonical() } })
    }
    /** Convert the quantity while keeping the amount in the label's base unit constant. */
    fun convertQuantity(v: FoodVersion, quantity: String?, fromUnit: String, toUnit: String): String? {
        require(toUnit in listOf("G", "ML") || v.portions.any { it.name == toUnit }) { "不支持的份量单位" }
        if (quantity == null) return null
        if (fromUnit == toUnit) return quantity
        val amount = decimal(normalize(v, quantity, fromUnit)!!)
        return amount.divide(decimal(normalize(v, "1", toUnit)!!, true), precision).canonical()
    }
    fun changeUnit(item: MealItem, toUnit: String): MealItem {
        require(item.draftQuantityText == null || runCatching { decimal(item.draftQuantityText) }.isSuccess) { "请先填写完整份量再切换单位" }
        val amount = convertQuantity(item.snapshot, item.quantity, item.unit, toUnit)
        // A converted estimated portion remains estimated until the user enters an actual measurement.
        return item(item.snapshot, amount, toUnit, item.id, item.quantityStatus == "ESTIMATED")
    }
    fun editQuantity(item: MealItem, raw: String): MealItem {
        require(raw.length <= 256) { "份量输入过长" }
        val quantity = raw.takeIf { runCatching { decimal(it) }.isSuccess }
        // Grams/ml entered by the user are an actual measurement. Label estimates stay on the snapshot.
        return item(item.snapshot, quantity, item.unit, item.id,
            estimated = item.unit !in listOf("G", "ML") && item.quantityStatus == "ESTIMATED")
            .copy(draftQuantityText = raw)
    }
    fun item(v: FoodVersion, quantity: String?, unit: String, id: String = uid(), estimated: Boolean = false): MealItem {
        val normalized = normalize(v, quantity, unit)
        return MealItem(id, v.foodId, v.id, quantity, unit,
            if (quantity == null) "UNKNOWN" else if (estimated || v.portions.any { it.name == unit && it.estimated }) "ESTIMATED" else "KNOWN",
            v, normalized, calculate(v, normalized))
    }
    fun aggregate(items: List<MealItem>, confirmedEmpty: Boolean = false): List<MetricTotal> = (0..5).map { index ->
        val values = items.map { it.derived.values()[index] }
        val known = values.filterNotNull()
        MetricTotal(if (known.isEmpty() && !(confirmedEmpty && items.isEmpty())) null else known.fold(BigDecimal.ZERO) { a,b -> a + decimal(b) }.canonical(), values.count { it == null }, if (confirmedEmpty && items.isEmpty()) 1 else items.size)
    }
    fun target(plan: TargetPlan, date: String, training: String): DayTarget {
        require(training != "UNKNOWN") { "请先填写当日训练类型" }
        val macros = if (training == "REST") plan.rest else plan.training
        val factor = if (plan.mode == "PER_KG") decimal(plan.referenceWeight ?: error("缺少目标参考体重"), true) else BigDecimal.ONE
        val p = decimal(macros.protein) * factor; val c = decimal(macros.carbs) * factor; val f = decimal(macros.fat) * factor
        return DayTarget(date,plan.id,training,plan.referenceWeight, Nutrients((p*BigDecimal(4)+c*BigDecimal(4)+f*BigDecimal(9)).canonical(),p.canonical(),c.canonical(),f.canonical()))
    }
    fun edible(total: String, bone: String): String {
        val result = decimal(total) - decimal(bone)
        require(result >= BigDecimal.ZERO) { "骨重不能大于总重" }
        return result.canonical()
    }
}

data class WeightWindow(val mean: String?, val count: Int, val change: String?)
object Trends {
    fun window(records: List<BodyRecord>, date: LocalDate): WeightWindow {
        fun values(end: LocalDate) = records.filter { it.standard && it.weight != null && LocalDate.parse(it.date) in end.minusDays(6)..end }.map { decimal(it.weight!!, true) }
        fun mean(values: List<BigDecimal>) = if (values.isEmpty()) null else values.reduce(BigDecimal::add).divide(BigDecimal(values.size), precision)
        val a = values(date); val b = values(date.minusDays(7)); val av = mean(a)
        return WeightWindow(av?.canonical(), a.size, if (a.size == 7 && b.size == 7) (av!! - mean(b)!!).canonical() else null)
    }
}
