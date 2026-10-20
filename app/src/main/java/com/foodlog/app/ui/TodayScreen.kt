package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import com.foodlog.app.FoodLogApplication
import java.math.BigDecimal
import java.time.LocalDate

@Composable fun TodayScreen(vm: AppViewModel, data: Dataset, date: String) {
    val app = LocalContext.current.applicationContext as FoodLogApplication
    val automatic by app.automaticBackup.status.collectAsState()
    val cloudIdentity by app.cloudSessions.identity.collectAsState()
    var bodyDialog by rememberSaveable(date) { mutableStateOf(false) }
    var secondary by rememberSaveable { mutableStateOf(false) }
    var templatesDialog by rememberSaveable(date) { mutableStateOf(false) }
    var copyDialog by rememberSaveable(date) { mutableStateOf(false) }
    var templateMealId by rememberSaveable(date) { mutableStateOf<String?>(null) }
    val dietDayBusy by vm.dietDayBusy.collectAsState()
    val dietDayError by vm.dietDayError.collectAsState()
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
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp).testTag("today-content"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.date(LocalDate.parse(date).minusDays(1).toString()) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "前一天", modifier = Modifier.size(20.dp))
                }
                DateButton(date, vm::date, Modifier.weight(1f).heightIn(min = 48.dp))
                IconButton(onClick = { vm.date(LocalDate.parse(date).plusDays(1).toString()) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "后一天", modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = { vm.today() }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("回到今天", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (cloudIdentity != null) TextButton(onClick = { vm.tab(4) },
                modifier = Modifier.fillMaxWidth().testTag("todayCloudStatus"), contentPadding = PaddingValues(0.dp)) {
                Text(automatic.detail, style = MaterialTheme.typography.labelMedium,
                    color = if (automatic.phase == "CONFLICT") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TodayNutritionOverview(
                total, target, effectiveDietDayType(day), complete, secondary, dietDayBusy, dietDayError,
                vm::selectDietDayType, { vm.tab(3) }, { secondary = !secondary }
            )
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
                Text("餐食", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${posted.size} 餐已吃${if (drafts.isNotEmpty()) " · ${drafts.size} 份草稿" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            drafts.forEach { draft -> TodayDraftCard(draft, { vm.edit(draft) }, { vm.delete(draft) }) }
            if (posted.isEmpty() && drafts.isEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("还没有餐食记录", style = MaterialTheme.typography.titleMedium)
                        Text("记下食品与份量，对照当天参考值。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.edit() }, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text("记一餐") }
                    }
                }
            }
            mealTypes.forEach { type ->
                posted.filter { it.type == type }.forEach { meal ->
                    TodayMealCard(meal, { vm.edit(meal) }, { templateMealId = meal.id }, { vm.delete(meal) })
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
            Column {
                CheckRow("今天已记录完整", complete, { checked ->
                    vm.launch { vm.repo.updateLoggingStatus(date, if (checked) "COMPLETE" else if (posted.isEmpty()) "UNKNOWN" else "PARTIAL") }
                })
                if (!complete) Text("摄入仅代表已记录部分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp))
            }
            Spacer(Modifier.height(86.dp))
        }
    }
    if (bodyDialog) BodyDayDialog(vm, data, date) { bodyDialog = false }
    if (templatesDialog) MealTemplatesDialog(vm, data, date) { templatesDialog = false }
    if (copyDialog) CopyPreviousDayDialog(vm, data, date) { copyDialog = false }
    templateMealId?.let { id -> data.meals.find { it.id == id }?.let { meal -> SaveMealTemplateDialog(vm, meal) { templateMealId = null } } }
}

@Composable private fun TodayNutritionOverview(
    totals: List<MetricTotal>, target: DayTarget?, dayType: String?, complete: Boolean,
    secondary: Boolean, busy: Boolean, error: String?, selectDayType: (String) -> Unit,
    openQuota: () -> Unit, toggleSecondary: () -> Unit
) {
    val referenceType = when (val type = target?.dayType) {
        "STRENGTH", "BADMINTON", "BOTH" -> "TRAINING"
        "TRAINING", "REST", "FLEX" -> type
        else -> null
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                dietDayTypeLabels.forEach { (value, label) ->
                    FilterChip(
                        selected = dayType == value,
                        onClick = { if (dayType != value || referenceType != value) selectDayType(value) },
                        enabled = !busy,
                        label = { Text(label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("日型_$value")
                    )
                }
            }
            if (referenceType != null && referenceType != dayType) {
                Text("参考快照：${dietDayTypeLabels[referenceType]} · 点日型更新", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("已记录 / 参考", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = openQuota, modifier = Modifier.heightIn(min = 48.dp).testTag("首页设置配额"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(if (target == null) "设置配额" else "调整配额")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("碳水" to 2, "蛋白质" to 1, "脂肪" to 3).forEach { (label, index) ->
                    TodayMacro(label, totals[index], target?.nutrients?.values()?.get(index), Modifier.weight(1f))
                }
            }
            if (target == null) {
                Text(
                    if (dayType == null) "选择日型，再按体重设置每日参考值。" else "${dietDayTypeLabels[dayType]}尚未设置参考值。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            val energy = totals[0]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("热量 ${energy.label(" kcal")}${target?.nutrients?.kcal?.let { " · 参考 ${display(it)} kcal" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                Text(if (complete) "全天已记录完整" else "仅已记录部分 · 尚未确认全天完整", style = MaterialTheme.typography.labelSmall)
            }
            if (secondary) {
                Text("钠：${totals[4].label(" mg")}\n膳食纤维：${totals[5].label(" g")}", style = MaterialTheme.typography.bodySmall)
                Text("热量按食品标签或碳蛋脂换算，部分食品为估算值。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = toggleSecondary, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (secondary) "收起更多营养" else "更多营养", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable private fun RowScope.TodayMacro(label: String, total: MetricTotal, reference: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            total.known?.let { "${if (total.missing > 0) "已知 " else ""}${display(it)}" } ?: if (total.count == 0) "未记录" else "待补",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("首页_${label}_已记录")
        )
        Text("/ ${reference?.let { display(it) } ?: "—"} g", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("首页_${label}_参考"))
        val goal = reference?.toBigDecimalOrNull()
        val amount = total.known?.toBigDecimalOrNull()
        if (goal != null && goal > BigDecimal.ZERO && amount != null) {
            LinearProgressIndicator(
                progress = { amount.divide(goal, precision).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f),
                drawStopIndicator = {}
            )
        }
        if (total.missing > 0) Text("${total.missing} 项待补", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun TodayDraftCard(draft: Meal, edit: () -> Unit, delete: () -> Unit) {
    var menu by remember(draft.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${draft.type}草稿", style = MaterialTheme.typography.titleMedium)
                    Text(if (draft.replacesMealId != null) "修改中 · 原记录仍计入摄入" else "未计入摄入", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = edit, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续填写") }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("draft-more-${draft.id}")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多草稿操作")
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("丢弃草稿") }, onClick = { menu = false; delete() })
                    }
                }
            }
            Text(draft.items.joinToString("、") { it.snapshot.name }.ifBlank { "尚未选择食品" }, style = MaterialTheme.typography.bodyMedium)
            if (draft.note.isNotBlank()) Text(draft.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun TodayMealCard(meal: Meal, edit: () -> Unit, saveTemplate: () -> Unit, delete: () -> Unit) {
    var menu by remember(meal.id) { mutableStateOf(false) }
    val total = NutritionCalculator.aggregate(meal.items)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(meal.type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(total[0].label(" kcal"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("meal-more-${meal.id}")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多${meal.type}操作")
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; edit() })
                        DropdownMenuItem(text = { Text("存为常用餐") }, onClick = { menu = false; saveTemplate() })
                        DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; delete() })
                    }
                }
            }
            meal.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Text(item.snapshot.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(item.quantity?.let { "${display(it)} ${if (item.unit == "G") "g" else if (item.unit == "ML") "ml" else item.unit}${if (item.quantityStatus == "ESTIMATED") "（估算）" else ""}" } ?: "份量待补", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("碳 ${total[2].label(" g")} · 蛋 ${total[1].label(" g")} · 脂 ${total[3].label(" g")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (meal.note.isNotBlank()) Text(meal.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
