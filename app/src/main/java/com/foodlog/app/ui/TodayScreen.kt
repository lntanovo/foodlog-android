package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TodayScreen(vm: AppViewModel, data: Dataset, date: String) {
    var bodyDialog by rememberSaveable(date) { mutableStateOf(false) }
    var secondary by rememberSaveable { mutableStateOf(false) }
    var templatesDialog by rememberSaveable(date) { mutableStateOf(false) }
    var copyDialog by rememberSaveable(date) { mutableStateOf(false) }
    var templateMealId by rememberSaveable(date) { mutableStateOf<String?>(null) }
    val meals = data.meals.filter { it.date == date }
    val day = data.days.find { it.date == date } ?: DayLog(date)
    val body = data.bodies.find { it.date == date }
    val target = data.targets.find { it.date == date }
    val posted = meals.filter { it.status == "POSTED" }
    val drafts = meals.filter { it.status == "DRAFT" }
    val complete = day.loggingStatus == "COMPLETE"
    val total = NutritionCalculator.aggregate(posted.flatMap { it.items }, complete)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("食记", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("认真记录，自在吃饭", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.today() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("回到今天") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.date(LocalDate.parse(date).minusDays(1).toString()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前一天") }
                DateButton(date, vm::date, Modifier.heightIn(min = 48.dp))
                TextButton(onClick = { vm.date(LocalDate.parse(date).plusDays(1).toString()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("后一天") }
            }
            TodayNutritionOverview(total, target, complete, secondary) { secondary = !secondary }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { templatesDialog = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("常用餐入口")) {
                    Icon(Icons.Outlined.Restaurant, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("常用餐")
                }
                OutlinedButton(onClick = { copyDialog = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("复制昨天入口")) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("复制昨天")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("餐食记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${posted.size} 餐已吃${if (drafts.isNotEmpty()) " · ${drafts.size} 份草稿" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            drafts.forEach { draft ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${draft.type}草稿", style = MaterialTheme.typography.titleMedium)
                        Text("草稿不计入摄入${if (draft.replacesMealId != null) " · 正在修改已有餐食" else ""}", style = MaterialTheme.typography.bodySmall)
                        Text(draft.items.joinToString("、") { it.snapshot.name }.ifBlank { "尚未选择食品" }, style = MaterialTheme.typography.bodyMedium)
                        if (draft.note.contains("复制自 ")) Text(draft.note, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.edit(draft) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续填写") }
                            TextButton(onClick = { vm.delete(draft) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("丢弃草稿") }
                        }
                    }
                }
            }
            mealTypes.filter { it != "早餐" || meals.any { meal -> meal.type == it } }.forEach { type ->
                val entries = posted.filter { it.type == type }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (entries.isEmpty()) Text("未记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        entries.forEachIndexed { index, meal ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            meal.items.forEach { item ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                    Text(item.snapshot.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(item.quantity?.let { "${display(it)} ${if (item.unit == "G") "g" else if (item.unit == "ML") "ml" else item.unit}${if (item.quantityStatus == "ESTIMATED") "（估算）" else ""}" } ?: "份量待补", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            val sum = NutritionCalculator.aggregate(meal.items)
                            Text("${sum[0].label(" kcal")} · P ${sum[1].label()} / C ${sum[2].label()} / F ${sum[3].label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            if (meal.note.isNotBlank()) Text(meal.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { vm.edit(meal) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("编辑") }
                                TextButton(onClick = { templateMealId = meal.id }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("存为常用餐") }
                                TextButton(onClick = { vm.delete(meal) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            OutlinedCard(onClick = { bodyDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("身体与训练", style = MaterialTheme.typography.titleSmall)
                        Text("${body?.weight?.let { "${display(it)} kg${if (body.standard) " 晨重" else " 非标准称重"}" } ?: "晨重未记录"} · ${trainingLabels[day.training]}${body?.waist?.let { " · 腰围 ${display(it)} cm" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "记录晨重、腰围与训练", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(complete, { checked -> vm.launch { vm.repo.day(day.copy(loggingStatus = if (checked) "COMPLETE" else if (posted.isEmpty()) "UNKNOWN" else "PARTIAL")) } })
                Column {
                    Text("今天已记录完整", style = MaterialTheme.typography.bodyMedium)
                    Text("确认前，摄入仅代表已记录部分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(86.dp))
        }
    }
    if (bodyDialog) BodyDayDialog(vm, data, date) { bodyDialog = false }
    if (templatesDialog) MealTemplatesDialog(vm, data, date) { templatesDialog = false }
    if (copyDialog) CopyPreviousDayDialog(vm, data, date) { copyDialog = false }
    templateMealId?.let { id -> data.meals.find { it.id == id }?.let { meal -> SaveMealTemplateDialog(vm, meal) { templateMealId = null } } }
}

@Composable private fun TodayNutritionOverview(totals: List<MetricTotal>, target: DayTarget?, complete: Boolean, secondary: Boolean, toggleSecondary: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("已记录摄入", style = MaterialTheme.typography.titleSmall)
                Text(if (complete) "全天记录完整" else "全天尚未确认完整", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            val energy = totals[0]
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(energy.known?.let { "${if (energy.missing > 0) "已知 " else ""}${display(it)}" } ?: if (energy.count == 0) "未记录" else "待补", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (energy.known != null) Text("kcal", modifier = Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (energy.missing > 0) Text("${energy.missing} 项热量待补", style = MaterialTheme.typography.bodySmall)
            if (target != null) {
                val goal = target.nutrients.kcal
                Text("${trainingLabels[target.dayType]}日目标 ${goal?.let { "${display(it)} kcal" } ?: "待补"} · 已固定本日快照", style = MaterialTheme.typography.bodySmall)
                if (complete && energy.complete && goal != null) {
                    val delta = goal.toBigDecimal() - energy.known!!.toBigDecimal()
                    Text(if (delta.signum() >= 0) "与目标差额 ${display(delta.toPlainString())} kcal" else "超出目标 ${display(delta.abs().toPlainString())} kcal", style = MaterialTheme.typography.bodySmall)
                }
            } else Text("尚无本日设定目标", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("蛋白质" to 1, "碳水" to 2, "脂肪" to 3).forEach { (label, index) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Text(totals[index].label(" g"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        target?.nutrients?.values()?.get(index)?.let { Text("目标 ${display(it)} g", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            if (secondary) Text("钠：${totals[4].label(" mg")}\n膳食纤维：${totals[5].label(" g")}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = toggleSecondary, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (secondary) "收起钠与纤维" else "钠与膳食纤维", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
