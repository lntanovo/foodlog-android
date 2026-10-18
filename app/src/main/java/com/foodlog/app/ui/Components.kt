package com.foodlog.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*
import java.time.LocalDate

@Composable fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.()->Unit) {
    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            if(subtitle!=null) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
@Composable fun Field(label: String, value: String, change: (String)->Unit, modifier: Modifier=Modifier, numeric: Boolean=false, supporting: String?=null) {
    OutlinedTextField(value,change,modifier.fillMaxWidth().testTag(label),label={Text(label)},singleLine=true,
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText=if(supporting!=null) {{Text(supporting)}} else null)
}
@Composable fun Choices(values: List<String>, selected: String, onSelect: (String)->Unit) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        values.forEach{FilterChip(selected==it,onClick={if(selected!=it) onSelect(it)},label={Text(if(it=="G") "g" else if(it=="ML") "ml" else it)},modifier=Modifier.heightIn(min=48.dp))}
    }
}
@Composable fun CheckRow(label: String, checked: Boolean, change: (Boolean)->Unit, modifier: Modifier=Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(checked,role=Role.Checkbox,onValueChange=change),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked,onCheckedChange=null)
        Text(label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(start=8.dp).weight(1f))
    }
}
@Composable fun DateButton(date: String, onChange: (String)->Unit, modifier: Modifier=Modifier) {
    val context=LocalContext.current
    TextButton(onClick={val d=LocalDate.parse(date);DatePickerDialog(context,{_,y,m,day->onChange(LocalDate.of(y,m+1,day).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()},modifier=modifier){Text(date)}
}
@Composable fun FormDialog(title: String, confirm: String="保存", dismiss: ()->Unit, save: ()->Unit, error: String?=null, content: @Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            content()
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick=save){Text(confirm)}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
@Composable fun NutrientSummary(totals: List<MetricTotal>, target: Nutrients?=null) {
    val names=listOf("热量","蛋白质","碳水","脂肪","钠","膳食纤维")
    totals.forEachIndexed { i,t ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(names[i],style=MaterialTheme.typography.bodyMedium); Text(t.label(if(i==0) " kcal" else if(i==4) " mg" else " g"),style=MaterialTheme.typography.titleMedium) }
            val goal=target?.values()?.get(i)
            if(goal!=null) Column(horizontalAlignment=androidx.compose.ui.Alignment.End) {
                Text("设定目标 ${display(goal)}",style=MaterialTheme.typography.bodySmall)
                if(t.complete) { val delta=goal.toBigDecimal()-t.known!!.toBigDecimal();Text(if(delta.signum()>=0) "差额 ${display(delta.toPlainString())}" else "超出 ${display(delta.abs().toPlainString())}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
                else Text("待记录补全",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(i<totals.lastIndex) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
    }
}
