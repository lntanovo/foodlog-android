package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import java.time.LocalDate

@Composable fun TodayScreen(vm: AppViewModel, data: Dataset, date: String) {
    var bodyDialog by remember{mutableStateOf(false)}
    var secondary by remember{mutableStateOf(false)}
    val meals=data.meals.filter{it.date==date}
    val day=data.days.find{it.date==date}?:DayLog(date)
    val body=data.bodies.find{it.date==date}
    val target=data.targets.find{it.date==date}
    val posted=meals.filter{it.status=="POSTED"}
    val total=NutritionCalculator.aggregate(posted.flatMap{it.items},day.loggingStatus=="COMPLETE")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column { Text("食记",style=MaterialTheme.typography.headlineLarge);Text("认真记录，自在吃饭",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick={vm.today()}){Text("回到今天")}
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            OutlinedButton(onClick={vm.date(LocalDate.parse(date).minusDays(1).toString())}){Text("前一天")}
            DateButton(date,vm::date)
            OutlinedButton(onClick={vm.date(LocalDate.parse(date).plusDays(1).toString())}){Text("后一天")}
        }
        Section("已记录摄入", if(day.loggingStatus=="COMPLETE") "已手动确认今天记录完整" else "仅合计已吃记录 · 全天完整性尚未确认") {
            NutrientSummary(total.take(4),target?.nutrients)
            if(target==null) Text("${if(day.training=="UNKNOWN") "训练类型未填写；" else ""}尚无本日设定目标",style=MaterialTheme.typography.bodySmall)
            else Text("自设配额 · ${trainingLabels[target.dayType]} · 已固定本日快照",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={secondary=!secondary}){Text(if(secondary) "收起钠与纤维" else "查看钠与膳食纤维")}
            if(secondary) {Text("钠：${total[4].label(" mg")}");Text("膳食纤维：${total[5].label(" g")}")}
        }
        Section("身体与训练") {
            Text("${trainingLabels[day.training]}  ·  ${body?.weight?.let{"${display(it)} kg${if(body.standard) " 晨重" else " 非标准称重"}"}?:"晨重未记录"}")
            if(body?.waist!=null) Text("腰围 ${display(body.waist)} cm")
            if(day.note.isNotBlank()) Text(day.note,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={bodyDialog=true},modifier=Modifier.fillMaxWidth()){Text("记录晨重、腰围与训练")}
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(day.loggingStatus=="COMPLETE",{checked->vm.launch{vm.repo.day(day.copy(loggingStatus=if(checked) "COMPLETE" else if(posted.isEmpty()) "UNKNOWN" else "PARTIAL"))}})
                Text("今天已记录完整",style=MaterialTheme.typography.bodyMedium)
            }
        }
        mealTypes.filter{it!="早餐" || meals.any{m->m.type==it}}.forEach { type ->
            val entries=posted.filter{it.type==type}
            Section(type) {
                if(entries.isEmpty()) Text("未记录",color=MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { meal ->
                    meal.items.forEach { item ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(item.snapshot.name,modifier=Modifier.weight(1f))
                            Text(item.quantity?.let{"${display(it)} ${if(item.unit=="G") "g" else if(item.unit=="ML") "ml" else item.unit}${if(item.quantityStatus=="ESTIMATED") "（估算）" else ""}"}?:"份量待补",style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    val sum=NutritionCalculator.aggregate(meal.items)
                    Text("${sum[0].label(" kcal")} · P ${sum[1].label()} / C ${sum[2].label()} / F ${sum[3].label()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    if(meal.note.isNotBlank()) Text(meal.note,style=MaterialTheme.typography.bodySmall)
                    Row {TextButton(onClick={vm.edit(meal)}){Text("编辑")};TextButton(onClick={vm.delete(meal)}){Text("删除")}}
                    HorizontalDivider()
                }
            }
        }
        meals.filter{it.status=="DRAFT"}.forEach { draft ->
            Section("${draft.type}草稿", "草稿不计入摄入${if(draft.replacesMealId!=null) " · 正在修改已有餐食" else ""}") {
                Text(draft.items.joinToString("、"){it.snapshot.name}.ifBlank{"尚未选择食品"})
                Row { TextButton(onClick={vm.edit(draft)}){Text("继续填写")}; TextButton(onClick={vm.delete(draft)}){Text("丢弃草稿")} }
            }
        }
        Spacer(Modifier.height(86.dp))
    }
    if(bodyDialog) BodyDayDialog(vm,data,date){bodyDialog=false}
}

@Composable fun BodyDayDialog(vm: AppViewModel, data: Dataset, date: String, dismiss: ()->Unit) {
    val oldBody=data.bodies.find{it.date==date}?:BodyRecord(date)
    val oldDay=data.days.find{it.date==date}?:DayLog(date)
    var weight by remember{mutableStateOf(oldBody.weight?:"")};var waist by remember{mutableStateOf(oldBody.waist?:"")}
    var standard by remember{mutableStateOf(oldBody.standard)};var training by remember{mutableStateOf(oldDay.training)}
    var strength by remember{mutableStateOf(oldDay.strengthMinutes?.toString()?:"")};var badminton by remember{mutableStateOf(oldDay.badmintonMinutes?.toString()?:"")}
    var hunger by remember{mutableStateOf(oldDay.hunger?.toString()?:"")};var performance by remember{mutableStateOf(oldDay.performance?:"未填写")}
    var fatigue by remember{mutableStateOf(oldDay.fatigue)};var note by remember{mutableStateOf(oldDay.note)}
    var bodyNote by remember{mutableStateOf(oldBody.note)};var recalc by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    FormDialog("$date · 身体与训练",dismiss=dismiss,error=error,save={
        runCatching {
            fun positive(s:String)=s.trim().ifEmpty{null}?.also{decimal(it,true)}
            fun integer(s:String,max:Int)=s.trim().ifEmpty{null}?.let{v->v.toIntOrNull()?.also{require(it in 0..max){"数值应在0–${max}之间"}}?:error("请输入整数")}
            val b=oldBody.copy(weight=positive(weight),waist=positive(waist),standard=standard,note=bodyNote)
            val day=oldDay.copy(training=training,strengthMinutes=integer(strength,1440),badmintonMinutes=integer(badminton,1440),hunger=integer(hunger,10),performance=performance.takeUnless{it=="未填写"},fatigue=fatigue,note=note)
            vm.launch{vm.repo.day(day,b,recalc);dismiss()}
        }.onFailure{error=it.message}
    }) {
        Field("体重 kg（留空为未测）",weight,{weight=it},numeric=true)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(standard,{standard=it});Text("标准晨起空腹称重")}
        Field("腰围 cm（留空为未测）",waist,{waist=it},numeric=true)
        Field("称重备注",bodyNote,{bodyNote=it})
        Text("当天训练类型")
        Choices(trainingLabels.values.toList(),trainingLabels.getValue(training),{label->training=trainingLabels.entries.first{it.value==label}.key})
        Field("力量时长（分钟，可留空）",strength,{strength=it},numeric=true)
        Field("羽毛球时长（分钟，可留空）",badminton,{badminton=it},numeric=true)
        Field("饥饿感 0–10（可留空）",hunger,{hunger=it},numeric=true)
        Text("力量表现");Choices(listOf("未填写","提升","稳定","下降"),performance,{performance=it})
        Field("疲劳感",fatigue,{fatigue=it});Field("训练与饮食备注",note,{note=it})
        if(data.targets.any{it.date==date}) {
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(recalc,{recalc=it});Text("按新日型重算这一天的目标")}
            Text("仅更改所选日期；未勾选时保留原目标快照。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
