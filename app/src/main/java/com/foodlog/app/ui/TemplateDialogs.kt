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

@Composable fun SaveMealTemplateDialog(vm: AppViewModel, meal: Meal, dismiss: () -> Unit) {
    var name by rememberSaveable(meal.id) { mutableStateOf("${meal.type}常吃") }
    val requestId = rememberSaveable(meal.id) { "save-template:${uid()}" }
    val action by vm.quickMealAction.collectAsStateWithLifecycle()
    val submitting = action.running
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    val error = validationError ?: action.takeIf { it.id == requestId }?.error
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(action.id, action.completed) { if (action.id == requestId && action.completed) currentDismiss() }
    FormDialog("存为常用餐", confirm = if (submitting) "保存中…" else "保存模板", dismiss = { if (!submitting) dismiss() }, error = error, save = {
        if (!submitting) {
            if (name.isBlank()) validationError = "请给这份常用餐起个名字"
            else {
                val submittedName = name.trim()
                vm.runQuickMealAction(requestId) {
                    vm.repo.saveTemplate(submittedName, meal)
                    vm.notices.send(Notice("已保存常用餐「$submittedName」"))
                }
            }
        }
    }) {
        Field("常用餐名称", name, { name = it.take(60); validationError = null })
        Text(meal.items.joinToString("、") { it.snapshot.name }, style = MaterialTheme.typography.bodyMedium)
        Text("保存当前食品、份量与营养快照。以后使用时先生成草稿，可再修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun MealTemplatesDialog(vm: AppViewModel, data: Dataset, date: String, dismiss: () -> Unit) {
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val requestPrefix = rememberSaveable(date) { "templates:${uid()}:" }
    val action by vm.quickMealAction.collectAsStateWithLifecycle()
    val submitting = action.running
    val error = action.takeIf { it.id.startsWith(requestPrefix) }?.error
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(action.id, action.completed) {
        if (action.completed && action.id.startsWith(requestPrefix)) {
            if (action.id.startsWith("${requestPrefix}delete:")) deleteId = null else currentDismiss()
        }
    }
    AlertDialog(onDismissRequest = { if (!submitting) dismiss() }, title = { Text("常用餐") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("用于 $date · 生成草稿不计入摄入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.templates.isEmpty()) {
                Text("还没有常用餐", style = MaterialTheme.typography.titleMedium)
                Text("先保存一餐，再点餐食下方的「存为常用餐」。", style = MaterialTheme.typography.bodyMedium)
            }
            data.templates.sortedByDescending { it.updatedAt }.forEach { template ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(template.name, style = MaterialTheme.typography.titleSmall)
                        Text("${template.type} · ${template.items.size} 项食品", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(template.items.joinToString("、") { it.snapshot.name }, style = MaterialTheme.typography.bodyMedium)
                        val total = NutritionCalculator.aggregate(template.items)
                        Text(total[0].label(" kcal"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { deleteId = template.id }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除模板", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            FilledTonalButton(onClick = {
                                vm.runQuickMealAction("${requestPrefix}apply:${template.id}") {
                                    val draft = vm.repo.applyTemplate(template.id, date)
                                    vm.edit(draft)
                                }
                            }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).testTag("使用常用餐_${template.id}")) { Text("使用常用餐") }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = dismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
    deleteId?.let { id -> data.templates.find { it.id == id }?.let { template ->
        AlertDialog(onDismissRequest = { if (!submitting) deleteId = null }, title = { Text("删除常用餐？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("删除「${template.name}」模板后，已记录的餐食仍会保留。")
                if (action.id == "${requestPrefix}delete:$id") error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton(onClick = {
                vm.runQuickMealAction("${requestPrefix}delete:$id") {
                    vm.repo.deleteTemplate(id)
                    vm.notices.send(Notice("已删除常用餐"))
                }
            }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认删除") }
        }, dismissButton = { TextButton(onClick = { deleteId = null }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    } }
}

@Composable fun CopyPreviousDayDialog(vm: AppViewModel, data: Dataset, date: String, dismiss: () -> Unit) {
    val source = LocalDate.parse(date).minusDays(1).toString()
    val meals = data.meals.filter { it.date == source && it.status == "POSTED" && it.items.isNotEmpty() }.sortedBy { mealTypes.indexOf(it.type) }
    val duplicates = data.meals.any { it.date == date && it.status == "DRAFT" && it.note.contains("复制自 $source") }
    val requestId = rememberSaveable(date) { "copy-day:${uid()}" }
    val action by vm.quickMealAction.collectAsStateWithLifecycle()
    val submitting = action.running
    val error = action.takeIf { it.id == requestId }?.error
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(action.id, action.completed) { if (action.id == requestId && action.completed) currentDismiss() }
    AlertDialog(onDismissRequest = { if (!submitting) dismiss() }, title = { Text("复制昨天的餐食") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("来源日：$source\n目标日：$date", style = MaterialTheme.typography.bodyMedium)
            if (meals.isEmpty()) Text("来源日还没有已吃餐食，无法复制。草稿不会作为复制来源。")
            else {
                Text("将复制 ${meals.size} 餐", style = MaterialTheme.typography.titleSmall)
                meals.forEach { meal ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(meal.type, style = MaterialTheme.typography.titleSmall)
                        Text(meal.items.joinToString("、") { it.snapshot.name }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("生成草稿不计入摄入。请核对当天食品与份量，再保存已吃。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                if (duplicates) Text("本日已有从 $source 复制的草稿。再次复制会新增一组草稿。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        if (meals.isNotEmpty()) TextButton(onClick = {
            vm.runQuickMealAction(requestId) {
                val drafts = vm.repo.copyPreviousDay(date)
                vm.notices.send(Notice("已生成 ${drafts.size} 份草稿，请核对后保存已吃"))
            }
        }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp).testTag("确认复制昨天")) { Text(if (submitting) "复制中…" else if (duplicates) "确认再次复制" else "生成草稿") }
    }, dismissButton = { TextButton(onClick = dismiss, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (meals.isEmpty()) "关闭" else "取消") } })
}
