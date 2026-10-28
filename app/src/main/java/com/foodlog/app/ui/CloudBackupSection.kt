package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foodlog.app.cloud.CloudDatasetSummary
import com.foodlog.app.cloud.cloudSummary
import com.foodlog.app.domain.Dataset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CloudBackupSection(
    vm: AppViewModel,
    data: Dataset,
    cloud: CloudBackupViewModel = viewModel(),
    transfer: TransferViewModel = viewModel(),
) {
    val identity by cloud.identity.collectAsState()
    val manualBusy by cloud.busy.collectAsState()
    val automatic by cloud.automaticStatus.collectAsState()
    val autoBusy = identity != null && automatic.phase in setOf("CHECKING", "UPLOADING", "RESTORING")
    val busy = manualBusy || autoBusy
    val error by cloud.error.collectAsState()
    val message by cloud.message.collectAsState()
    val task by cloud.task.collectAsState()
    val records by cloud.records.collectAsState()
    val listOpen by cloud.listOpen.collectAsState()
    val listLoaded by cloud.listLoaded.collectAsState()
    val hasMore by cloud.hasMore.collectAsState()
    val preview by cloud.preview.collectAsState()
    val localTask by transfer.task.collectAsState()
    val localBusy by transfer.busy.collectAsState()
    val editor by vm.editor.collectAsState()
    val ownedRecords = records.filter { it.ownerId == identity?.uid }
    var loginDialog by rememberSaveable { mutableStateOf(false) }
    var keepLocalDialog by remember { mutableStateOf(false) }
    val localActive = localTask != null || localBusy || editor != null
    LaunchedEffect(localActive) { cloud.setLocalTransferActive(localActive) }
    LaunchedEffect(identity) { if (identity != null) loginDialog = false }

    Section("账号与云备份", "记录后自动备份，断网后自动重试。新设备登录同一账号会自动取回记录。") {
        if (identity == null) {
            Text("已有本机记录会先核对，发现差异后由你选择。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { loginDialog = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("cloudLogin")) { Text("用户名与密码登录") }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已登录：${identity?.username.orEmpty()}", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("cloudIdentity"))
                TextButton(onClick = cloud::logout, enabled = !busy, modifier = Modifier.testTag("cloudLogout")) { Text("退出") }
            }
            Text("本机：${compactCounts(cloudSummary(data))}", style = MaterialTheme.typography.bodySmall)
            Text(automatic.detail, color = if (automatic.phase == "CONFLICT") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("automaticCloudStatus"), style = MaterialTheme.typography.bodyMedium)
            automatic.lastBackupAt?.let { Text("最近云端备份：${backupTime(it, data.meta.timezone)}", style = MaterialTheme.typography.bodySmall) }
            if (autoBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (automatic.phase == "CONFLICT") {
                automatic.remote?.let { record ->
                    Text("云端：${compactCounts(CloudDatasetSummary(record.recordCounts, record.firstDate, record.lastDate))}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { cloud.preview(record) }, enabled = !busy && task == null && !localActive,
                        modifier = Modifier.fillMaxWidth().testTag("autoUseCloud")) { Text("核对并使用云端记录…") }
                }
                OutlinedButton(onClick = { keepLocalDialog = true }, enabled = !busy && task == null && !localActive,
                    modifier = Modifier.fillMaxWidth().testTag("autoKeepLocal")) { Text("保留本机并备份…") }
            } else Button(onClick = cloud::backupNow, enabled = !busy && task == null && !localActive,
                modifier = Modifier.fillMaxWidth().testTag("cloudBackup")) { Text("立即检查并备份") }
            OutlinedButton(onClick = cloud::showBackups, enabled = !busy && task == null && !localActive,
                modifier = Modifier.fillMaxWidth().testTag("cloudList")) { Text("查看云端备份") }
            Text("每次备份都会保留一个独立版本；恢复需要你确认。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("云服务到期或暂时不可用不影响本机记录，建议定期导出本机完整备份。云服务期限以控制台为准。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (localActive) Text("请先完成当前编辑或本机文件操作。", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("cloudProgress"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cloudError")) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloudMessage")) }
        if (task != null && preview == null && !busy) {
            Text(if (task?.ownerId == identity?.uid) "有尚未结束的${if (task?.operation == "UPLOAD") "备份" else "恢复预览"}操作。"
                else "有其他登录状态下未结束的操作，请登录原账号继续，或取消。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = cloud::continueTask,
                    enabled = identity?.uid == task?.ownerId && !localActive,
                    modifier = Modifier.testTag("cloudContinue")) { Text(if (task?.operation == "UPLOAD") "重试备份" else "继续预览") }
                TextButton(onClick = cloud::cancelTask, modifier = Modifier.testTag("cloudCancelTask")) { Text("取消操作") }
            }
        }
        if (listOpen && listLoaded && preview == null) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("云端备份", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = cloud::hideBackups, enabled = !busy) { Text("收起") }
            }
            if (ownedRecords.isEmpty()) Text("这个账号还没有云备份。本机记录未改动，可先备份这台设备。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloudEmpty"))
            ownedRecords.forEach { record ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(backupTime(record.createdAt, data.meta.timezone), style = MaterialTheme.typography.titleSmall)
                    Text("${record.deviceName} · 食记 ${record.appVersion}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(compactCounts(CloudDatasetSummary(record.recordCounts, record.firstDate, record.lastDate)),
                        style = MaterialTheme.typography.bodySmall)
                    record.firstDate?.let { Text("$it 至 ${record.lastDate ?: it}", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { cloud.preview(record) }, enabled = !busy && task == null && !localActive,
                        modifier = Modifier.fillMaxWidth().testTag("cloudPreview-${record.backupId}")) { Text("预览并恢复…") }
                }
                HorizontalDivider()
            }
            if (hasMore) TextButton(onClick = cloud::moreBackups, enabled = !busy && task == null,
                modifier = Modifier.fillMaxWidth()) { Text("查看更多备份") }
        }
    }

    if (loginDialog && identity == null) {
        var username by rememberSaveable { mutableStateOf("") }
        // The password deliberately stays out of SavedState and the operation journal.
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!busy) { password = ""; loginDialog = false } },
            title = { Text("登录云备份账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("登录后开启自动备份。空白设备会自动恢复云端记录；已有记录时先核对差异。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(username, { username = it.take(128) }, label = { Text("用户名") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("cloudUsername"))
                    OutlinedTextField(password, { password = it.take(512) }, label = { Text("密码") }, singleLine = true,
                        enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("cloudPassword"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = {
                TextButton(enabled = !busy && username.isNotBlank() && password.isNotEmpty(), onClick = {
                    val submitted = password
                    password = ""
                    cloud.login(username, submitted)
                }, modifier = Modifier.testTag("cloudLoginConfirm")) { Text("登录") }
            }, dismissButton = {
                TextButton(enabled = !busy, onClick = { password = ""; loginDialog = false }) { Text("取消") }
            })
    }

    if (keepLocalDialog && identity != null && automatic.phase == "CONFLICT") {
        AlertDialog(onDismissRequest = { keepLocalDialog = false }, title = { Text("使用这台设备的记录？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("本机：${compactCounts(cloudSummary(data))}")
                automatic.remote?.let { Text("云端：${compactCounts(CloudDatasetSummary(it.recordCounts, it.firstDate, it.lastDate))}") }
                Text("本机记录将成为账号的最新备份。旧云端备份仍保留；两边的记录不会合并。")
            } },
            confirmButton = { TextButton(onClick = { keepLocalDialog = false; cloud.keepLocal() },
                modifier = Modifier.testTag("autoKeepLocalConfirm")) { Text("保留本机并自动备份") } },
            dismissButton = { TextButton(onClick = { keepLocalDialog = false }) { Text("取消") } })
    }

    preview?.takeIf { it.identity == identity }?.let { shown ->
        AlertDialog(onDismissRequest = { if (!busy) cloud.cancelTask() },
            title = { Text("预览云备份恢复") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("账号：${shown.identity.username}", style = MaterialTheme.typography.titleSmall)
                    Text(backupTime(shown.record.createdAt, data.meta.timezone), style = MaterialTheme.typography.bodySmall)
                    Text("现在的本机记录", style = MaterialTheme.typography.titleSmall)
                    Text(fullCounts(shown.current), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloudCurrentSummary"))
                    HorizontalDivider()
                    Text("将恢复的云端记录", style = MaterialTheme.typography.titleSmall)
                    Text(fullCounts(shown.incoming), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("cloudIncomingSummary"))
                    shown.incoming.firstDate?.let { Text("$it 至 ${shown.incoming.lastDate ?: it}", style = MaterialTheme.typography.bodySmall) }
                    val fewer = linkedMapOf("foods" to "食品", "versions" to "标签版本", "meals" to "餐食", "items" to "餐食明细",
                        "bodies" to "身体记录", "days" to "日志", "plans" to "目标计划", "targets" to "每日目标",
                        "templates" to "常用餐", "origins" to "导入来源")
                        .filterKeys { (shown.incoming.counts[it] ?: 0) < (shown.current.counts[it] ?: 0) }.values
                    if (fewer.isNotEmpty()) Text("这份备份的${fewer.joinToString("、")}数量比本机少，请核对版本。", color = MaterialTheme.colorScheme.error)
                    RestoreDifferenceSummary(shown.difference)
                    Text("恢复会替换当前全部记录，不会自动合并。确认前会先保存本机恢复前备份。", color = MaterialTheme.colorScheme.primary)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }, confirmButton = {
                TextButton(onClick = cloud::restore, enabled = !busy && !localActive,
                    modifier = Modifier.testTag("cloudRestoreConfirm")) { Text(if (busy) "处理中…" else "确认替换并恢复") }
            }, dismissButton = {
                TextButton(onClick = cloud::cancelTask, enabled = !busy, modifier = Modifier.testTag("cloudRestoreCancel")) { Text("取消") }
            })
    }
}

private fun compactCounts(summary: CloudDatasetSummary): String =
    "餐食 ${summary.counts["meals"] ?: 0} · 身体记录 ${summary.counts["bodies"] ?: 0} · 常用餐 ${summary.counts["templates"] ?: 0}"

private fun fullCounts(summary: CloudDatasetSummary): String = with(summary.counts) {
    "食品 ${get("foods") ?: 0} · 标签版本 ${get("versions") ?: 0}\n" +
        "餐食 ${get("meals") ?: 0} · 明细 ${get("items") ?: 0}\n" +
        "身体记录 ${get("bodies") ?: 0} · 日志 ${get("days") ?: 0}\n" +
        "计划 ${get("plans") ?: 0} · 每日目标 ${get("targets") ?: 0}\n" +
        "常用餐 ${get("templates") ?: 0} · 导入来源 ${get("origins") ?: 0}"
}

private fun backupTime(value: String?, timezone: String): String = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }
        .getOrDefault("云端备份")
} ?: "云端备份"
