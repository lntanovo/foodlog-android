package com.foodlog.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foodlog.app.domain.*
import java.time.LocalDate
import java.time.ZoneId

private val StringSetSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) }, restore = { it.toSet() }
)

@Composable fun SettingsScreen(vm: AppViewModel, data: Dataset, transfer: TransferViewModel = viewModel()) {
    var profile by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf(false) }
    var libraryDialog by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var libraryError by rememberSaveable { mutableStateOf<String?>(null) }
    val dark by vm.dark.collectAsState()
    val task by transfer.task.collectAsState()
    val incoming by transfer.incoming.collectAsState()
    val busy by transfer.busy.collectAsState()
    val transferError by transfer.error.collectAsState()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip"), transfer::picked)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), transfer::picked)
    LaunchedEffect(transfer) { for (notice in transfer.notices) vm.notices.send(Notice(notice)) }
    LaunchedEffect(task?.id, task?.stage) {
        val active = task ?: return@LaunchedEffect
        when (active.stage) {
            "EXPORT_READY" -> { if(transfer.pickerLaunched()) export.launch(active.filename) }
            "IMPORT_READY" -> { if(transfer.pickerLaunched()) open.launch(arrayOf("*/*")) }
        }
    }
    val canStart = task == null && !busy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineLarge)
            Text("管理目标、外观和本机数据", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task != null || transferError != null) Section("文件操作", if (busy) "正在处理，请稍候" else "操作进度会保留，切换页面后可继续") {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            transferError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (task != null && incoming == null && !busy) {
                Text(if (task?.operation == "EXPORT") "备份副本已保存在本机，请选择导出位置。" else "请选择导入文件；支持原JSON及压缩备份。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = transfer::chooseAgain) { Text("重新选择文件") }
                    TextButton(onClick = transfer::cancel) { Text("取消操作") }
                }
            }
        }
        Section("个人与目标") {
            Text(data.meta.profile.name.ifBlank { "尚未填写个人资料" }, style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { profile = true }, modifier = Modifier.fillMaxWidth()) { Text("个人资料与业务时区") }
            val plan = data.plans.maxWithOrNull(compareBy<TargetPlan> { it.effectiveFrom }.thenBy { it.createdAt })
            Text(plan?.let { "计划自 ${it.effectiveFrom} 生效 · ${if (it.mode == "PER_KG") "按参考体重系数" else "直接填写碳蛋脂"}" }
                ?: "可以只记录饮食，也可以设置自己的每日配额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { target = true }, modifier = Modifier.fillMaxWidth()) { Text("设置训练日 / 休息日目标") }
        }
        Section("数据备份与恢复", "完整备份保存在你选择的位置，建议定期导出。") {
            Text("最近完整导出：${data.meta.lastExport?.let { java.time.Instant.parse(it).atZone(ZoneId.of(data.meta.timezone)).toLocalDateTime().toString().replace('T', ' ') } ?: "尚未导出"}", style = MaterialTheme.typography.bodySmall)
            Button(enabled = canStart, onClick = { transfer.prepareExport("食记_完整备份_${LocalDate.now(ZoneId.of(data.meta.timezone))}.foodlog.gz") }, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
            OutlinedButton(enabled = canStart, onClick = { transfer.prepareImport("FULL_BACKUP") }, modifier = Modifier.fillMaxWidth()) { Text("从完整备份恢复…") }
            TextButton(enabled = canStart, onClick = { transfer.prepareExport("食记_恢复前备份.foodlog.gz", recovery = true) }, modifier = Modifier.fillMaxWidth()) { Text("取出最近的恢复前自动备份") }
        }
        Section("食品库与旧数据") {
            OutlinedButton(enabled = canStart, onClick = { selected = data.foods.filter { !it.archived }.map { it.id }.toSet(); libraryError = null; libraryDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("选择食品并导出分享文件…") }
            OutlinedButton(enabled = canStart, onClick = { transfer.prepareImport("FOOD_LIBRARY") }, modifier = Modifier.fillMaxWidth()) { Text("导入食品库…") }
            OutlinedButton(enabled = canStart, onClick = { transfer.prepareImport("LEGACY_IMPORT") }, modifier = Modifier.fillMaxWidth()) { Text("导入电脑转换的旧记录…") }
            Text("旧Excel经工程中的 migrate_legacy.py 转为JSON后导入。提交前可核对重复项与冲突日期。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section("显示与关于") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("深色外观")
                Switch(dark ?: androidx.compose.foundation.isSystemInDarkTheme(), { vm.theme(it) })
            }
            Text("食记 1.1.0 · 本地离线版", style = MaterialTheme.typography.titleSmall)
            Text("未知营养留空，真实零值填0；估算信息单独标记。完整备份包含全部文字数据，食品分享文件不包含个人记录。目标均为自设配额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (profile) ProfileDialog(vm, data) { profile = false }
    if (target) TargetDialog(vm, data) { target = false }
    if (libraryDialog) FormDialog("选择要分享的食品", confirm = "导出所选食品", error = libraryError, dismiss = { libraryDialog = false }, save = {
        if (selected.isEmpty()) libraryError = "请至少选择一种食品"
        else { transfer.prepareExport("食记_食品库.foodlog.gz", library = true, selected = selected); libraryDialog = false }
    }) {
        Text("仅分享所选食品的当前标签与份量定义。", style = MaterialTheme.typography.bodySmall)
        data.foods.forEach { f -> CheckRow(data.version(f).name, f.id in selected, { checked -> selected = if (checked) selected + f.id else selected - f.id }) }
    }
    incoming?.let { parsed ->
        val active = task ?: return@let
        val conflicts = vm.repo.conflicts(data, parsed)
        val dates = (parsed.meals.map { it.date } + parsed.bodies.map { it.date } + parsed.days.map { it.date }).sorted()
        val existingOrigins = data.origins.associateBy { it.namespace + ":" + it.key }
        val matched = parsed.origins.count { existingOrigins[it.namespace + ":" + it.key]?.fingerprint == it.fingerprint }
        val changed = parsed.origins.count { o -> existingOrigins[o.namespace + ":" + o.key]?.let { it.fingerprint != o.fingerprint } == true }
        val added = parsed.origins.count { existingOrigins[it.namespace + ":" + it.key] == null }
        AlertDialog(onDismissRequest = transfer::cancel, title = { Text(if (parsed.kind == "FULL_BACKUP") "预览完整恢复" else "预览导入") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("食品 ${parsed.foods.size} · 版本 ${parsed.versions.size}\n餐食 ${parsed.meals.size} · 明细 ${parsed.meals.sumOf { it.items.size }}\n身体记录 ${parsed.bodies.size} · 日志 ${parsed.days.size}\n目标计划 ${parsed.plans.size} · 日目标 ${parsed.targets.size}")
                if (dates.isNotEmpty()) Text("${dates.first()} 至 ${dates.last()}", style = MaterialTheme.typography.bodySmall)
                Text("份量待补 ${parsed.meals.sumOf { m -> m.items.count { it.quantity == null } }} 项", style = MaterialTheme.typography.bodySmall)
                when (parsed.kind) {
                    "FULL_BACKUP" -> Text("恢复将替换当前全部数据。提交前自动保存本机恢复前备份，可在设置中取出。", color = MaterialTheme.colorScheme.primary)
                    "FOOD_LIBRARY" -> {
                        val newFoods = parsed.foods.count { food -> data.foods.none { it.id == food.id } }
                        val versionConflicts = parsed.versions.count { v -> data.versions.any { it.id == v.id && it != v } }
                        Text("食品新增 $newFoods · 已存在 ${parsed.foods.size - newFoods} · 版本冲突 $versionConflicts")
                        Text("已存在的食品保留本机启用标签；同ID版本内容冲突时取消整次导入。", style = MaterialTheme.typography.bodySmall)
                    }
                    "LEGACY_IMPORT" -> {
                        Text("来源新增 $added · 已存在 $matched · 变更 $changed\n日期冲突 ${conflicts.size} 天")
                        if (conflicts.isEmpty()) Text("未发现日期冲突；重复来源默认跳过。", style = MaterialTheme.typography.bodySmall)
                        conflicts.sorted().forEach { date ->
                            HorizontalDivider()
                            Text(date, style = MaterialTheme.typography.titleSmall)
                            Text("当前：${dayImportSummary(data, date)}", style = MaterialTheme.typography.bodySmall)
                            Text("替换后：${dayImportSummary(parsed, date, data)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(date in active.replacements, { transfer.replaceDay(date, it) }, enabled = !busy)
                                Text("替换这一天", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (conflicts.isNotEmpty()) Text("未勾选日期跳过。勾选会移除当天全部现有餐食与草稿；只在文件含有对应记录时替换体重和训练。", style = MaterialTheme.typography.bodySmall)
                        if (parsed.plans.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(active.includePlans, transfer::includePlans, enabled = !busy)
                            Text("同时导入个人历史自设配额")
                        }
                    }
                }
                parsed.report.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                transferError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = transfer::applyImport) { Text(if (busy) "处理中…" else if (parsed.kind == "FULL_BACKUP") "确认替换并恢复" else "确认导入") } },
            dismissButton = { TextButton(enabled = !busy, onClick = transfer::cancel) { Text("取消") } })
    }
}

private fun dayImportSummary(data: Dataset, date: String, fallback: Dataset? = null): String {
    val meals = data.meals.filter { it.date == date }
    val body = data.bodies.find { it.date == date } ?: fallback?.bodies?.find { it.date == date }
    val day = data.days.find { it.date == date } ?: fallback?.days?.find { it.date == date }
    val foodText = meals.joinToString("；") { meal ->
        "${meal.type}${if (meal.status == "DRAFT") "草稿" else ""}：" + meal.items.joinToString("、") {
            it.snapshot.name + (it.quantity?.let { q -> " ${display(q)}${if (it.unit == "G") "g" else if (it.unit == "ML") "ml" else it.unit}" } ?: " 份量待补")
        }
    }.ifBlank { "无餐食" }
    return "$foodText\n体重 ${body?.weight?.let { "$it kg" } ?: "未测"} · 腰围 ${body?.waist?.let { "$it cm" } ?: "未测"}\n训练 ${trainingLabels[day?.training] ?: "未填写"}（力量 ${day?.strengthMinutes ?: "—"} / 羽毛球 ${day?.badmintonMinutes ?: "—"} 分钟）"
}

@Composable fun ProfileDialog(vm: AppViewModel, data: Dataset, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(data.meta.profile.name) }
    var height by rememberSaveable { mutableStateOf(data.meta.profile.height ?: "") }
    var age by rememberSaveable { mutableStateOf(data.meta.profile.age?.toString() ?: "") }
    var sex by rememberSaveable { mutableStateOf(data.meta.profile.sex) }
    var timezone by rememberSaveable { mutableStateOf(data.meta.timezone) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FormDialog("个人资料", dismiss = dismiss, error = error, save = { runCatching {
        ZoneId.of(timezone.trim())
        val h = height.trim().ifEmpty { null }?.also { decimal(it, true) }
        val a = if (age.isBlank()) null else age.trim().toIntOrNull()?.also { require(it in 1..150) { "年龄须为1–150整数" } } ?: error("请输入有效年龄")
        vm.launch { vm.repo.profile(Profile(name.trim(), h, a, sex), timezone.trim()); dismiss() }
    }.onFailure { error = it.message } }) {
        Text("资料均可留空。目标参考体重在目标计划中设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("称呼", name, { name = it })
        Field("身高 cm", height, { height = it }, numeric = true)
        Field("年龄", age, { age = it }, numeric = true)
        Choices(listOf("未填写", "男", "女"), sex.ifBlank { "未填写" }, { sex = if (it == "未填写") "" else it })
        HorizontalDivider()
        Field("业务时区", timezone, { timezone = it })
        Text("例如 Asia/Shanghai；已有餐食日期保持原样。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun TargetDialog(vm: AppViewModel, data: Dataset, dismiss: () -> Unit) {
    val old = data.plans.maxWithOrNull(compareBy<TargetPlan> { it.effectiveFrom }.thenBy { it.createdAt })
    var mode by rememberSaveable { mutableStateOf(old?.mode ?: "DIRECT_MACROS") }
    var reference by rememberSaveable { mutableStateOf(old?.referenceWeight ?: "") }
    var effective by rememberSaveable { mutableStateOf(LocalDate.now(ZoneId.of(data.meta.timezone)).toString()) }
    var direct by rememberSaveable { mutableStateOf(if (old?.mode == "DIRECT_MACROS") listOf(old.training.protein, old.training.carbs, old.training.fat, old.rest.protein, old.rest.carbs, old.rest.fat) else List(6) { "" }) }
    var perKg by rememberSaveable { mutableStateOf(if (old?.mode == "PER_KG") listOf(old.training.protein, old.training.carbs, old.training.fat, old.rest.protein, old.rest.carbs, old.rest.fat) else List(6) { "" }) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val values = if (mode == "PER_KG") perKg else direct
    fun change(index: Int, value: String) {
        val next = values.mapIndexed { i, current -> if (i == index) value else current }
        if (mode == "PER_KG") perKg = next else direct = next
    }
    FormDialog("新建自设配额计划", dismiss = dismiss, error = error, save = { runCatching {
        values.forEach { decimal(it) }
        if (mode == "PER_KG") decimal(reference.trim(), true)
        LocalDate.parse(effective)
        val plan = TargetPlan(effectiveFrom = effective, mode = mode, referenceWeight = if (mode == "PER_KG") reference.trim() else null,
            training = Macros(values[0].trim(), values[1].trim(), values[2].trim()), rest = Macros(values[3].trim(), values[4].trim(), values[5].trim()))
        vm.launch { vm.repo.plan(plan); dismiss() }
    }.onFailure { error = it.message } }) {
        Text("自行设定的记录配额。两种模式各自保留输入，切换不会换算或清空。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Choices(listOf("直接填写克数", "按参考体重系数"), if (mode == "PER_KG") "按参考体重系数" else "直接填写克数", {
            mode = if (it == "按参考体重系数") "PER_KG" else "DIRECT_MACROS"
            error = null
        })
        Row(verticalAlignment = Alignment.CenterVertically) { Text("生效日期"); DateButton(effective, { effective = it }) }
        if (mode == "PER_KG") Field("目标参考体重 kg（不是晨重）", reference, { reference = it }, numeric = true)
        val unit = if (mode == "PER_KG") "g/kg" else "g"
        HorizontalDivider()
        Text("训练日 · 力量 / 羽毛球 / 两练", style = MaterialTheme.typography.titleSmall)
        Field("碳水 $unit", values[1], { change(1, it) }, numeric = true)
        Field("蛋白质 $unit", values[0], { change(0, it) }, numeric = true)
        Field("脂肪 $unit", values[2], { change(2, it) }, numeric = true)
        HorizontalDivider()
        Text("休息日", style = MaterialTheme.typography.titleSmall)
        Field("碳水 $unit", values[4], { change(4, it) }, numeric = true)
        Field("蛋白质 $unit", values[3], { change(3, it) }, numeric = true)
        Field("脂肪 $unit", values[5], { change(5, it) }, numeric = true)
        Text("已有日目标保持原快照；需调整某天时，在身体与训练中勾选重算。", style = MaterialTheme.typography.bodySmall)
    }
}
