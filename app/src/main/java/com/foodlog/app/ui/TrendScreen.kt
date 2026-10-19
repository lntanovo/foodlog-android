package com.foodlog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

@Composable fun TrendScreen(vm: AppViewModel, data: Dataset, date: String) {
    var range by rememberSaveable { mutableStateOf("30天") }
    var dialog by rememberSaveable { mutableStateOf(false) }
    var editingDate by rememberSaveable { mutableStateOf(date) }
    val end = LocalDate.parse(date)
    val today = LocalDate.now(ZoneId.of(data.meta.timezone)).toString()
    val count = range.removeSuffix("天").toInt()
    val days = (0 until count).map { end.minusDays(it.toLong()) }
    val stats = Trends.window(data.bodies, end)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().testTag("trend-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("身体趋势", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("留意长期变化，也容纳每天的波动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("截至", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DateButton(date, vm::date, Modifier.heightIn(min = 48.dp))
                        }
                        FilledTonalButton(onClick = { editingDate = date; dialog = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("记录 / 编辑") }
                    }
                    if (date != today) TextButton(onClick = vm::today, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("trend-today")) { Text("回到今天") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (stats.count == 7) "7日平均体重" else "7日暂均值", style = MaterialTheme.typography.titleSmall)
                            Text("${stats.count} / 7 天晨重", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(stats.mean?.let { "${display(it, 2)} kg" } ?: "数据不足", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(stats.change?.let { "较前7日 ${if (it.toBigDecimal().signum() > 0) "+" else ""}${display(it, 2)} kg" } ?: "与前7日变化：需两个完整7日窗口", style = MaterialTheme.typography.bodySmall)
                        Text("${end.minusDays(6)} 至 $date · 仅计标准晨重", style = MaterialTheme.typography.bodySmall)
                        if (stats.count in 1..6) Text("暂均值来自已有记录，补足7天后再比较。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Section("体重曲线") {
                    Choices(listOf("7天", "30天", "90天"), range, { range = it })
                    WeightChart(data, days.reversed())
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("● 标准晨重", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("━ 完整7日均值", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text("┄ 暂均值（不足7天） · 晨重缺测处断开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("日期记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("点按修改", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(days, key = { it.toString() }) { d ->
                val body = data.bodies.find { it.date == d.toString() }
                val day = data.days.find { it.date == d.toString() }
                val mean = Trends.window(data.bodies, d)
                OutlinedCard(onClick = { editingDate = d.toString(); dialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("trend-day-$d")) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(d.toString(), style = MaterialTheme.typography.titleSmall)
                            Text(body?.weight?.let { "${display(it)} kg" } ?: "晨重缺测", style = MaterialTheme.typography.titleSmall, color = if (body?.weight != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${if (body?.weight != null) if (body.standard) "标准晨重" else "非标准称重（不计均值）" else "未称重"} · ${trainingLabels[day?.training ?: "UNKNOWN"]}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (body?.waist != null) Text("腰围 ${display(body.waist)} cm", style = MaterialTheme.typography.bodySmall)
                        Text("${if (mean.count == 7) "7日均值" else "暂均值 ${mean.count}/7"} ${mean.mean?.let { "${display(it, 2)} kg" } ?: "数据不足"}", style = MaterialTheme.typography.bodySmall)
                        if (day?.hunger != null) Text("饥饿感 ${day.hunger}/10 · 表现 ${day.performance ?: "未填写"}", style = MaterialTheme.typography.bodySmall)
                        if (!body?.note.isNullOrBlank()) Text(body!!.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (dialog) BodyDayDialog(vm, data, editingDate) { dialog = false }
}

@Composable private fun WeightChart(data: Dataset, ordered: List<LocalDate>) {
    val weights = ordered.map { d -> data.bodies.find { it.date == d.toString() && it.standard }?.weight?.toDoubleOrNull() }
    val windows = ordered.map { Trends.window(data.bodies, it) }
    val means = windows.map { it.mean?.toDoubleOrNull() }
    val values = (weights + means).filterNotNull().filter { it.isFinite() }
    val count = weights.count { it != null && it.isFinite() }
    val nonstandard = data.bodies.count { !it.standard && it.weight != null && LocalDate.parse(it.date) in ordered.first()..ordered.last() }
    Text("${ordered.first()} 至 ${ordered.last()} · ${count}次标准晨重", modifier = Modifier.testTag("trend-range-summary"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (nonstandard > 0) Text("另有${nonstandard}次非标准称重，仅在下方日期记录中展示，不计入晨重曲线与均值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (values.isEmpty()) {
        Text("还没有标准晨重，记录后在这里查看。", modifier = Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val padding = max(.3, max(values.max() - values.min(), abs(values.max()) * .01) * .15)
    val low = values.min() - padding
    val high = values.max() + padding
    val color = MaterialTheme.colorScheme.primary
    val average = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(180.dp).widthIn(min = 40.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(display(high.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("kg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(display(low.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.weight(1f).height(180.dp).testTag("weight-chart").semantics { contentDescription = "${ordered.first()}至${ordered.last()}体重趋势，${count}次标准晨重。实线为完整7日均值，虚线为不足7天的暂均值；每天的具体数值见下方日期记录。" }) {
            val inset = 4.dp.toPx()
            (0..3).forEach { val y = inset + (size.height - inset * 2) * it / 3; drawLine(grid, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx()) }
            fun point(i: Int, v: Double) = Offset(inset + (size.width - inset * 2) * i / (ordered.size - 1), inset + (size.height - inset * 2) * (1 - (v - low) / (high - low)).toFloat())
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            means.forEachIndexed { i, value ->
                if (value != null && value.isFinite()) {
                    val full = windows[i].count == 7
                    if (i > 0 && means[i - 1] != null && means[i - 1]!!.isFinite()) {
                        drawLine(average.copy(alpha = if (full && windows[i - 1].count == 7) 1f else .7f), point(i - 1, means[i - 1]!!), point(i, value), 2.5.dp.toPx(), pathEffect = if (full && windows[i - 1].count == 7) null else dash)
                    }
                    if (!full) drawCircle(average, 2.5.dp.toPx(), point(i, value), style = Stroke(1.dp.toPx()))
                }
            }
            weights.forEachIndexed { i, value ->
                if (value != null && value.isFinite()) {
                    if (i > 0 && weights[i - 1] != null && weights[i - 1]!!.isFinite()) drawLine(color.copy(alpha = .45f), point(i - 1, weights[i - 1]!!), point(i, value), 1.dp.toPx())
                    drawCircle(color, 3.dp.toPx(), point(i, value))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(ordered.first().toString().substring(5), style = MaterialTheme.typography.labelSmall)
        Text(ordered.last().toString().substring(5), style = MaterialTheme.typography.labelSmall)
    }
}
