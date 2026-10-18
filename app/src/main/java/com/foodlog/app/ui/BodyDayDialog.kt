package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.foodlog.app.domain.*

@Composable fun BodyDayDialog(vm: AppViewModel, data: Dataset, date: String, dismiss: () -> Unit) {
    val oldBody = data.bodies.find { it.date == date } ?: BodyRecord(date)
    val oldDay = data.days.find { it.date == date } ?: DayLog(date)
    var weight by rememberSaveable(date) { mutableStateOf(oldBody.weight ?: "") }
    var waist by rememberSaveable(date) { mutableStateOf(oldBody.waist ?: "") }
    var standard by rememberSaveable(date) { mutableStateOf(oldBody.standard) }
    var training by rememberSaveable(date) { mutableStateOf(oldDay.training) }
    var strength by rememberSaveable(date) { mutableStateOf(oldDay.strengthMinutes?.toString() ?: "") }
    var badminton by rememberSaveable(date) { mutableStateOf(oldDay.badmintonMinutes?.toString() ?: "") }
    var hunger by rememberSaveable(date) { mutableStateOf(oldDay.hunger?.toString() ?: "") }
    var performance by rememberSaveable(date) { mutableStateOf(oldDay.performance ?: "未填写") }
    var fatigue by rememberSaveable(date) { mutableStateOf(oldDay.fatigue) }
    var note by rememberSaveable(date) { mutableStateOf(oldDay.note) }
    var bodyNote by rememberSaveable(date) { mutableStateOf(oldBody.note) }
    var recalc by rememberSaveable(date) { mutableStateOf(false) }
    var error by rememberSaveable(date) { mutableStateOf<String?>(null) }
    FormDialog("$date · 身体与训练", dismiss = dismiss, error = error, save = {
        runCatching {
            fun positive(s: String) = s.trim().ifEmpty { null }?.also { decimal(it, true) }
            fun integer(s: String, max: Int) = s.trim().ifEmpty { null }?.let { v ->
                v.toIntOrNull()?.also { require(it in 0..max) { "数值应在0–${max}之间" } } ?: error("请输入整数")
            }
            val body = oldBody.copy(weight = positive(weight), waist = positive(waist), standard = standard, note = bodyNote)
            val day = oldDay.copy(training = training, strengthMinutes = integer(strength, 1440), badmintonMinutes = integer(badminton, 1440),
                hunger = integer(hunger, 10), performance = performance.takeUnless { it == "未填写" }, fatigue = fatigue, note = note)
            vm.launch { vm.repo.day(day, body, recalc); dismiss() }
        }.onFailure { error = it.message }
    }) {
        Text("身体测量", style = MaterialTheme.typography.titleSmall)
        Text("未测留空，体重与腰围可分别记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("体重 kg（留空为未测）", weight, { weight = it }, numeric = true)
        CheckRow("标准晨起空腹称重", standard, { standard = it })
        Field("腰围 cm（留空为未测）", waist, { waist = it }, numeric = true)
        Field("称重备注", bodyNote, { bodyNote = it })
        HorizontalDivider()
        Text("当天训练类型", style = MaterialTheme.typography.titleSmall)
        Choices(trainingLabels.values.toList(), trainingLabels.getValue(training), { label -> training = trainingLabels.entries.first { it.value == label }.key })
        Field("力量时长（分钟，可留空）", strength, { strength = it }, numeric = true)
        Field("羽毛球时长（分钟，可留空）", badminton, { badminton = it }, numeric = true)
        HorizontalDivider()
        Text("身体感受", style = MaterialTheme.typography.titleSmall)
        Field("饥饿感 0–10（可留空）", hunger, { hunger = it }, numeric = true)
        Text("力量表现", style = MaterialTheme.typography.bodySmall)
        Choices(listOf("未填写", "提升", "稳定", "下降"), performance, { performance = it })
        Field("疲劳感", fatigue, { fatigue = it })
        Field("训练与饮食备注", note, { note = it })
        if (data.targets.any { it.date == date }) {
            HorizontalDivider()
            CheckRow("按新日型重算这一天的目标", recalc, { recalc = it })
            Text("仅更改所选日期；未勾选时保留原目标快照。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
