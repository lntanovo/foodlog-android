package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodlog.app.domain.*
import java.time.LocalDate

/** The two input modes keep independent values; changing mode never silently converts a plan. */
@Composable fun TargetDialog(vm: AppViewModel, data: Dataset, dismiss: () -> Unit) {
    val date by vm.selectedDate.collectAsState()
    val old = remember(data.plans, date) {
        data.plans.filter { it.effectiveFrom <= date }
            .maxWithOrNull(compareBy<TargetPlan> { it.effectiveFrom }.thenBy { it.createdAt })
    }
    val latestWeight = remember(data.bodies, date) {
        data.bodies.filter { it.standard && it.weight != null && it.date <= date }.maxByOrNull { it.date }
    }
    fun initialValues(forMode: String): List<String> = if (old?.mode == forMode) {
        listOf(old.training.carbs, old.training.protein, old.training.fat,
            old.rest.carbs, old.rest.protein, old.rest.fat,
            old.flex?.carbs ?: "", old.flex?.protein ?: "", old.flex?.fat ?: "")
    } else List(9) { "" }
    var mode by rememberSaveable { mutableStateOf(old?.mode ?: "PER_KG") }
    var reference by rememberSaveable { mutableStateOf(old?.referenceWeight ?: "") }
    var effective by rememberSaveable { mutableStateOf(date) }
    var selectedType by rememberSaveable { mutableIntStateOf(0) }
    var direct by rememberSaveable { mutableStateOf(initialValues("DIRECT_MACROS")) }
    var perKg by rememberSaveable { mutableStateOf(initialValues("PER_KG")) }
    var directFlex by rememberSaveable { mutableStateOf(old?.mode == "DIRECT_MACROS" && old.flex != null) }
    var perKgFlex by rememberSaveable { mutableStateOf(old?.mode == "PER_KG" && old.flex != null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val requestId = rememberSaveable { "save-quota:${uid()}" }
    val editorScroll = rememberScrollState()
    val action by vm.quickMealAction.collectAsStateWithLifecycle()
    val saving = action.running
    val actionError = action.takeIf { it.id == requestId }?.error
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(action.id, action.completed) {
        if (action.id == requestId && action.completed) currentDismiss()
    }
    LaunchedEffect(error, actionError) {
        if (error != null || actionError != null) {
            withFrameNanos { }
            editorScroll.animateScrollTo(editorScroll.maxValue)
        }
    }
    val values = if (mode == "PER_KG") perKg else direct
    val flexEnabled = if (mode == "PER_KG") perKgFlex else directFlex
    fun change(index: Int, text: String) {
        if (text.length > 256) return
        val changed = values.mapIndexed { i, v -> if (i == index) text else v }
        if (mode == "PER_KG") perKg = changed else direct = changed
        error = null
    }
    fun macros(offset: Int): Macros {
        val c = values[offset].trim(); val p = values[offset + 1].trim(); val f = values[offset + 2].trim()
        listOf(c, p, f).forEach { decimal(it) }
        return Macros(protein = p, carbs = c, fat = f)
    }
    fun buildPlan(): TargetPlan {
        LocalDate.parse(effective)
        if (mode == "PER_KG") decimal(reference.trim(), true)
        val training = runCatching { macros(0) }.getOrElse { error("请填写训练日的有效碳水、蛋白质和脂肪数值") }
        val rest = runCatching { macros(3) }.getOrElse { error("请填写休息日的有效碳水、蛋白质和脂肪数值") }
        val flex = if (flexEnabled) runCatching { macros(6) }.getOrElse { error("请填写放纵日的有效碳水、蛋白质和脂肪数值，或关闭该日参考值") } else null
        return TargetPlan(effectiveFrom = effective, mode = mode,
            referenceWeight = if (mode == "PER_KG") reference.trim() else null,
            training = training, rest = rest, flex = flex)
    }
    val preview = runCatching {
        val m = macros(selectedType * 3)
        val factor = if (mode == "PER_KG") decimal(reference.trim(), true) else java.math.BigDecimal.ONE
        val c = decimal(m.carbs) * factor; val p = decimal(m.protein) * factor; val f = decimal(m.fat) * factor
        Nutrients((c * java.math.BigDecimal(4) + p * java.math.BigDecimal(4) + f * java.math.BigDecimal(9)).canonical(), p.canonical(), c.canonical(), f.canonical())
    }.getOrNull()

    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("设置每日参考值") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(editorScroll).testTag("quota-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("填入你选择的系数，按参考体重生成碳蛋脂克数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choices(listOf("按参考体重系数", "直接填写克数"), if (mode == "PER_KG") "按参考体重系数" else "直接填写克数", {
                if (!saving) { mode = if (it == "按参考体重系数") "PER_KG" else "DIRECT_MACROS"; error = null }
            })
            if (mode == "PER_KG") {
                Field("参考体重 kg", reference, { if (!saving && it.length <= 256) { reference = it; error = null } }, numeric = true)
                if (latestWeight != null) TextButton(enabled = !saving, onClick = { reference = latestWeight.weight!!; error = null }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("采用最近晨重 ${display(latestWeight.weight)} kg · ${latestWeight.date}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("生效日期", style = MaterialTheme.typography.bodyMedium)
                DateButton(effective, { if (!saving) effective = it })
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("训练日", "休息日", "放纵日").forEachIndexed { index, label ->
                    FilterChip(selectedType == index, onClick = { if (!saving) { selectedType = index; error = null } },
                        label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("quota-type-$index"))
                }
            }
            if (selectedType == 2) CheckRow("设置放纵日参考值", flexEnabled, { enabled ->
                if (!saving) { if (mode == "PER_KG") perKgFlex = enabled else directFlex = enabled; error = null }
            })
            if (selectedType != 2 || flexEnabled) {
                val unit = if (mode == "PER_KG") "g/kg" else "g"
                listOf("碳水", "蛋白质", "脂肪").forEachIndexed { index, name ->
                    Field("$name $unit", values[selectedType * 3 + index], { if (!saving) change(selectedType * 3 + index, it) }, numeric = true)
                }
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${listOf("训练日", "休息日", "放纵日")[selectedType]} · 每日参考", style = MaterialTheme.typography.labelLarge)
                        if (preview == null) Text("填写完整数值后预览", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("碳水 ${display(preview.carbs)} g · 蛋白质 ${display(preview.protein)} g · 脂肪 ${display(preview.fat)} g", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("quota-preview-macros"))
                            Text("约 ${display(preview.kcal)} kcal", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("quota-preview-kcal"))
                        }
                    }
                }
            } else Text("这类日期只做标签，不显示摄入参考值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("热量按碳水×4＋蛋白质×4＋脂肪×9计算。保存新计划后，已有日期的参考快照保持原值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (error ?: actionError)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("quota-editor-error")) }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = {
            if (!saving) runCatching { buildPlan() }.onSuccess { plan ->
                error = null
                vm.runQuickMealAction(requestId) {
                    vm.repo.plan(plan)
                    vm.notices.send(Notice("已保存每日参考值"))
                }
            }.onFailure { error = it.message ?: "请检查输入" }
        }) { Text(if (saving) "正在保存…" else "保存三类参考值") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("取消") } })
}
