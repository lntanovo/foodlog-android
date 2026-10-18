package com.foodlog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import java.time.LocalDate

@Composable fun TrendScreen(vm: AppViewModel, data: Dataset, date: String) {
    var range by remember{mutableStateOf("30天")};var dialog by remember{mutableStateOf(false)}
    val end=LocalDate.parse(date);val count=range.removeSuffix("天").toInt()
    val days=(0 until count).map{end.minusDays(it.toLong())}
    val stats=Trends.window(data.bodies,end)
    val color=MaterialTheme.colorScheme.primary;val secondary=MaterialTheme.colorScheme.tertiary;val grid=MaterialTheme.colorScheme.outlineVariant
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Text("身体趋势",style=MaterialTheme.typography.headlineLarge);Text("看一段时间的变化，也容纳每天的波动。",style=MaterialTheme.typography.bodySmall)}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){DateButton(date,vm::date);Button(onClick={dialog=true}){Text("记录 / 编辑")}}}
        item {Choices(listOf("7天","30天","90天"),range,{range=it})}
        item {Section(if(stats.count==7) "7日平均体重" else "7日暂均值 · ${stats.count}/7天") {
            Text(stats.mean?.let{"${display(it,2)} kg"}?:"数据不足",style=MaterialTheme.typography.headlineLarge,color=color)
            Text("与前7日变化：${stats.change?.let{"${if(it.toBigDecimal().signum()>0) "+" else ""}${display(it,2)} kg"}?:"数据不足（需两个完整7日窗口）"}",style=MaterialTheme.typography.bodySmall)
            Text("仅使用当日及前6个自然日的标准晨重；非标准称重另列。",style=MaterialTheme.typography.bodySmall)
        }}
        item {Section("体重曲线", "● 实测晨重    ━ 7日均值；缺测处断开") {
            val ordered=days.reversed()
            val weights=ordered.map{d->data.bodies.find{it.date==d.toString() && it.standard}?.weight?.toFloat()}
            val means=ordered.map{Trends.window(data.bodies,it).mean?.toFloat()}
            val values=(weights+means).filterNotNull()
            if(values.isEmpty()) Text("还没有标准晨重，记录后在这里查看。") else {
                val min=values.min()-0.3f;val max=values.max()+0.3f
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${display(max.toString())} kg");Text("${display(min.toString())} kg")}
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    (0..3).forEach{val y=size.height*it/3;drawLine(grid,Offset(0f,y),Offset(size.width,y),1.dp.toPx())}
                    fun point(i:Int,v:Float)=Offset(size.width*i/(count-1),size.height*(1-(v-min)/(max-min)))
                    means.forEachIndexed { i,value->if(i>0 && value!=null && means[i-1]!=null) drawLine(secondary,point(i-1,means[i-1]!!),point(i,value),2.dp.toPx()) }
                    weights.forEachIndexed { i,value->if(value!=null){if(i>0 && weights[i-1]!=null) drawLine(color,point(i-1,weights[i-1]!!),point(i,value),1.dp.toPx());drawCircle(color,3.dp.toPx(),point(i,value))} }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(ordered.first().toString().substring(5));Text(ordered.last().toString().substring(5))}
            }
        }}
        item {Text("日期记录",style=MaterialTheme.typography.titleLarge)}
        items(days,key={it.toString()}) { d ->
            val body=data.bodies.find{it.date==d.toString()};val day=data.days.find{it.date==d.toString()};val mean=Trends.window(data.bodies,d)
            OutlinedCard(onClick={vm.date(d.toString());dialog=true},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(d.toString(),style=MaterialTheme.typography.titleSmall)
                    Text(body?.weight?.let{"${display(it)} kg${if(body.standard) " · 标准晨重" else " · 非标准称重（不计均值）"}"}?:"晨重缺测")
                    if(body?.waist!=null) Text("腰围 ${display(body.waist)} cm")
                    Text("${if(mean.count==7) "7日均值" else "暂均值 ${mean.count}/7"} ${mean.mean?.let{display(it,2)}?:"数据不足"} · ${trainingLabels[day?.training?:"UNKNOWN"]}",style=MaterialTheme.typography.bodySmall)
                    if(day?.hunger!=null) Text("饥饿感 ${day.hunger}/10 · 表现 ${day.performance?:"未填写"}",style=MaterialTheme.typography.bodySmall)
                    if(!body?.note.isNullOrBlank()) Text(body!!.note,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(dialog) BodyDayDialog(vm,data,date){dialog=false}
}
