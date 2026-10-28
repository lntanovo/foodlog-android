package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodlog.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun RestoreDifferenceSummary(difference: RestoreDifference) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text("恢复后的变化：新增 ${difference.added} · 移除 ${difference.removed} · 变更 ${difference.changed}",
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("restore-difference"))
    Text("方向：本机 → 所选备份。按记录编号比较；不同编号会列为移除与新增。", style = MaterialTheme.typography.bodySmall)
    if (difference.records.isEmpty()) Text("业务内容一致（不比较导出时间等管理信息）。", style = MaterialTheme.typography.bodySmall)
    else {
        difference.records.groupBy { it.group }.forEach { (group, records) ->
            Text("$group：新增 ${records.count { it.action == "新增" }} · 移除 ${records.count { it.action == "移除" }} · 变更 ${records.count { it.action == "变更" }}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { expanded = true }) { Text("查看具体变化") }
    }
    if (expanded) InspectionDialog("恢复差异明细", { expanded = false }) {
        LazyColumn(Modifier.fillMaxSize().testTag("difference-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("仅供核对，关闭明细后仍需确认恢复。标签快照、备注等变化也会计入。", style = MaterialTheme.typography.bodySmall) }
            items(difference.records) { record ->
                Section("${record.action} · ${record.group}") {
                    record.before?.let { Text("本机：$it", style = MaterialTheme.typography.bodySmall) }
                    record.after?.let { Text("恢复后：$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private data class RecoveryEntry(val name: String, val time: Long, val bytes: Long)

@Composable fun DataInspectionDialog(vm: AppViewModel, data: Dataset, backupStatus: String, lastBackup: String?, dismiss: () -> Unit) {
    val inspection = remember(data) { inspectData(data) }
    val selectedDate by vm.selectedDate.collectAsStateWithLifecycle()
    InspectionDialog("数据诊断", dismiss) {
        LazyColumn(Modifier.fillMaxSize().testTag("diagnostics-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Section("本机记录", "查看诊断不会修改数据") {
                Text("已吃 ${inspection.posted} 餐 · 草稿 ${inspection.drafts} 餐", modifier = Modifier.testTag("diagnostics-meals"))
                Text("份量待补 ${inspection.pendingItems} 项 · 身体记录 ${data.bodies.size} 条")
                Text("食品 ${data.foods.size} · 标签版本 ${data.versions.size} · 常用餐 ${data.templates.size}")
                Text("每日记录 ${data.days.size} · 目标计划 ${data.plans.size} · 每日目标 ${data.targets.size}")
                Text(if (inspection.dates.isEmpty()) "尚无餐食记录" else "餐食日期：${inspection.dates.last().date} 至 ${inspection.dates.first().date}")
                Text("当前查看日期：$selectedDate")
            } }
            item { Section("备份状态") {
                Text(backupStatus)
                Text("最近云备份：${formatBackupTime(lastBackup, data.meta.timezone)}")
                Text("最近完整导出：${formatBackupTime(data.meta.lastExport, data.meta.timezone)}")
                Text("未显示在当天的记录可能在其他日期或草稿中。此页不能据此判断历史记录曾被删除。", style = MaterialTheme.typography.bodySmall)
            } }
            item { Section("诊断信息") {
                Text("食记 ${com.foodlog.app.BuildConfig.VERSION_NAME} · 数据库 v2 · 备份格式 v${data.schemaVersion}")
                Text("业务时区：${data.meta.timezone}")
                Text("数据集：${data.meta.datasetId}", style = MaterialTheme.typography.bodySmall)
                Text("本机修订：${data.meta.revision}", style = MaterialTheme.typography.bodySmall)
            } }
            item { Text("按日期查看餐食", style = MaterialTheme.typography.titleMedium) }
            items(inspection.dates, key = { it.date }) { day ->
                OutlinedButton(onClick = { vm.date(day.date); vm.tab(0); dismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("${day.date} · 已吃 ${day.posted} · 草稿 ${day.drafts}")
                }
            }
        }
    }
}

@Composable fun RecoveryHistoryDialog(vm: AppViewModel, transfer: TransferViewModel, timezone: String, dismiss: () -> Unit) {
    var entries by remember { mutableStateOf<List<RecoveryEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy by transfer.busy.collectAsState()
    val task by transfer.task.collectAsState()
    LaunchedEffect(Unit) {
        try { entries = withContext(Dispatchers.IO) { vm.repo.recoveryFiles().map { RecoveryEntry(it.name, it.lastModified(), it.length()) } } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取保护副本列表，请关闭后重试。" }
    }
    InspectionDialog("历史保护副本", dismiss) {
        LazyColumn(Modifier.fillMaxSize().testTag("recovery-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("恢复前留下的本机副本，按时间从新到旧排列。预览会校验内容，不会覆盖当前记录。副本不会自动删除。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (entries == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (entries?.isEmpty() == true) Text("还没有恢复前保护副本。")
            }
            items(entries.orEmpty(), key = { it.name }) { entry ->
                Section(formatBackupTime(Instant.ofEpochMilli(entry.time).toString(), timezone)) {
                    Text(entry.name, style = MaterialTheme.typography.bodySmall)
                    Text("${entry.bytes / 1024} KiB · 预览后查看记录数量及差异", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy && task == null, onClick = { transfer.previewRecovery(entry.name); dismiss() }) { Text("校验并预览") }
                }
            }
        }
    }
}

private fun formatBackupTime(value: String?, timezone: String): String = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrDefault("时间不可用")
} ?: "尚无成功记录"

@Composable private fun InspectionDialog(title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text("关闭") }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
