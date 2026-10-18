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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodlog.app.domain.*

@Composable fun QuotaScreen(vm: AppViewModel, data: Dataset) {
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    var edit by rememberSaveable { mutableStateOf(false) }
    var applyType by rememberSaveable(date) { mutableStateOf<String?>(null) }
    val plan = data.planFor(date)
    val nextPlan = data.plans.filter { it.effectiveFrom > date }.minByOrNull { it.effectiveFrom }
    val day = data.days.find { it.date == date }
    val currentType = effectiveDietDayType(day)
    val currentTarget = data.targets.find { it.date == date }
    val requestId = rememberSaveable(date, applyType) { "apply-quota:${uid()}" }
    val action by vm.quickMealAction.collectAsStateWithLifecycle()
    val saving = action.running
    val actionError = action.takeIf { it.id == requestId }?.error
    LaunchedEffect(action.id, action.completed) {
        if (action.id == requestId && action.completed) applyType = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("quota-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("每日配额", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("按自己的计划，设置三类日期的碳蛋脂参考。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("所选日期", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(currentType?.let { dietDayTypeLabels[it] } ?: "尚未标记日型", style = MaterialTheme.typography.titleSmall)
                }
                DateButton(date, vm::date)
            }
            if (plan == null) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("先设置你的参考值", style = MaterialTheme.typography.titleMedium)
                        Text("例如碳水系数 × 参考体重 = 每天碳水克数。系数由你填写，训练日、休息日和放纵日分别设置。", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { edit = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quota-edit")) { Text("设置每日参考值") }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${plan.effectiveFrom} 起生效", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (plan.mode == "PER_KG") "参考体重 ${display(plan.referenceWeight)} kg" else "直接填写克数", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { edit = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("quota-edit")) { Text("编辑参考值") }
                }
            }
            nextPlan?.let { Text("另有 ${it.effectiveFrom} 起生效的新计划。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            listOf("TRAINING", "REST", "FLEX").forEach { type ->
                val target = NutritionCalculator.targetOrNull(plan, date, type)
                QuotaReferenceCard(type, target, plan, currentType == type, enabled = !saving && target != null) { applyType = type }
            }
            if (currentTarget != null && currentTarget.planId != plan?.id) Text("这一天保留了较早的参考快照。若要更新，请点对应日型的「用于所选日期」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("参考值用于记录对照。保存新计划不会改动已有日期；热量由碳水×4＋蛋白质×4＋脂肪×9计算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (edit) TargetDialog(vm, data) { edit = false }
    applyType?.let { type ->
        val target = NutritionCalculator.targetOrNull(plan, date, type)
        AlertDialog(onDismissRequest = { if (!saving) applyType = null }, title = { Text("用于 $date？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("将这一天标记为${dietDayTypeLabels[type]}，并使用当前生效计划更新当天参考值。")
                target?.nutrients?.let { MacroReferenceLine(it) }
                Text("仅更新所选日期。餐食、训练记录和体重保持原值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = !saving && target != null, onClick = {
                vm.runQuickMealAction(requestId) {
                    vm.repo.recalculateTarget(date, type)
                    vm.notices.send(Notice("已更新 $date 的${dietDayTypeLabels[type]}参考值"))
                }
            }) { Text(if (saving) "正在更新…" else "确认使用") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { applyType = null }) { Text("取消") } })
    }
}

@Composable private fun QuotaReferenceCard(type: String, target: DayTarget?, plan: TargetPlan?, selected: Boolean, enabled: Boolean, apply: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("quota-reference-$type"), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(dietDayTypeLabels[type] ?: type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (selected) Text("所选日型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (target == null) Text(if (type == "FLEX") "未设置 · 可只标记日型" else "尚未设置参考值", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("碳水" to target.nutrients.carbs, "蛋白质" to target.nutrients.protein, "脂肪" to target.nutrients.fat).forEach { (label, value) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${display(value)} g", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (plan?.mode == "PER_KG") {
                    val macros = when (type) { "TRAINING" -> plan.training; "REST" -> plan.rest; else -> plan.flex }
                    macros?.let { Text("系数 ${display(it.carbs)} / ${display(it.protein)} / ${display(it.fat)} g/kg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(target?.nutrients?.kcal?.let { "约 ${display(it)} kcal" } ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(enabled = enabled, onClick = apply, modifier = Modifier.heightIn(min = 48.dp).testTag("quota-apply-$type")) { Text("用于所选日期") }
            }
        }
    }
}

@Composable private fun MacroReferenceLine(nutrients: Nutrients) {
    Text("碳水 ${display(nutrients.carbs)} g · 蛋白质 ${display(nutrients.protein)} g · 脂肪 ${display(nutrients.fat)} g\n约 ${display(nutrients.kcal)} kcal", style = MaterialTheme.typography.bodyMedium)
}
