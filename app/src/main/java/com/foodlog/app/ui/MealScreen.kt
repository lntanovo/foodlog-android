package com.foodlog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MealScreen(vm: AppViewModel, data: Dataset, editor: MealEditor, snackbar: SnackbarHostState) {
    var query by remember{mutableStateOf("")}
    var picker by remember{mutableStateOf(editor.rows.isEmpty())}
    var assistant by remember{mutableStateOf<String?>(null)}
    var refresh by remember{mutableStateOf<Pair<EditableItem,FoodVersion>?>(null)}
    val busy by vm.busy.collectAsState()
    val valid=runCatching{editor.meal()}
    BackHandler {vm.closeEditor()}
    Scaffold(snackbarHost={SnackbarHost(snackbar)},topBar={TopAppBar(title={Text(if(editor.draft.replacesMealId!=null) "编辑这餐" else "记一餐")},navigationIcon={IconButton(onClick={vm.closeEditor()}){Icon(Icons.Outlined.ArrowBack,"返回并保留草稿")}})},
        bottomBar={Surface(shadowElevation=8.dp){Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
            valid.exceptionOrNull()?.message?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            Button(onClick={vm.saveMeal()},enabled=!busy && valid.isSuccess && editor.rows.isNotEmpty(),modifier=Modifier.fillMaxWidth().height(52.dp)){Text(if(busy) "保存中…" else "保存已吃")}
            Text("返回自动保留草稿；草稿不计入摄入。",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=6.dp))
        }}}
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("记录日期");DateButton(editor.draft.date,{value->vm.date(value);vm.modify{it.copy(draft=it.draft.copy(date=value))}})}
            Choices(mealTypes,editor.draft.type,{value->vm.modify{it.copy(draft=it.draft.copy(type=value))}})
            editor.rows.forEach { row -> key(row.id) {
                val v=row.snapshot
                val item=runCatching{row.item()}.getOrNull()
                Section(v.name,"营养表基准 ${v.basisQuantity}${if(v.basisUnit=="G") "g" else "ml"} · ${v.weighingBasis}") {
                    Field("本次份量（留空为待补）",row.amount,{amount->vm.modify{it.copy(rows=it.rows.map{r->if(r.id==row.id) r.copy(amount=amount) else r})}},numeric=true)
                    Choices(((if(v.densityGPerMl!=null) listOf("G","ML") else listOf(v.basisUnit))+v.portions.map{it.name}).distinct(),row.unit,{unit->vm.modify{it.copy(rows=it.rows.map{r->if(r.id==row.id) r.copy(unit=unit) else r})}})
                    v.portions.find{it.name==row.unit}?.let{Text("每${it.name} ${it.amount}${if(it.unit=="G") "g" else "ml"}${if(it.estimated) "（估算；称重可切换至g）" else ""}",style=MaterialTheme.typography.bodySmall)}
                    Text(item?.normalizedAmount?.let{"本次${if(item.quantityStatus=="ESTIMATED") "估算" else ""} ${display(it)}${if(v.basisUnit=="G") "g" else "ml"} · ${display(item.derived.kcal)} kcal"}?:"份量待补，营养暂未知",color=MaterialTheme.colorScheme.primary)
                    Text("${NutritionCalculator.energy(v).source} · ${v.sourceText}",style=MaterialTheme.typography.bodySmall,maxLines=3)
                    Row {
                        TextButton(onClick={vm.modify{it.copy(rows=it.rows.filterNot{r->r.id==row.id})}}){Text("移除")}
                        if(v.basisUnit=="G") TextButton(onClick={assistant=row.id}){Text("总重减骨重")}
                    }
                    val current=data.foods.find{it.id==v.foodId}?.let{data.version(it)}
                    if(current!=null && current.id!=v.id) TextButton(onClick={refresh=row to current}){Text("更新为当前食品数据…")}
                }
            } }
            OutlinedButton(onClick={picker=!picker},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Text(if(picker) "收起食品选择" else "继续添加食品")}
            if(picker) {
                Field("搜索食品或品牌",query,{query=it})
                val recent=data.meals.filter{it.status=="POSTED"}.sortedByDescending{it.updatedAt}.flatMap{it.items}.map{it.foodId}.distinct()
                val foods=data.foods.filter{!it.archived}.filter{data.version(it).let{v->(v.name+v.brand).contains(query,true)}}.sortedWith(compareByDescending<Food>{it.favorite}.thenBy{recent.indexOf(it.id).let{n->if(n<0) Int.MAX_VALUE else n}}.thenBy{data.version(it).name})
                foods.forEach { food ->
                    val v=data.version(food);val selected=editor.rows.any{it.snapshot.foodId==food.id}
                    OutlinedCard(onClick={vm.modify{e->e.copy(rows=if(selected) e.rows.filterNot{it.snapshot.foodId==food.id} else e.rows+EditableItem(snapshot=v))}},modifier=Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(selected,onCheckedChange=null)
                            Column(Modifier.weight(1f)){Text(v.name);Text("${v.basisQuantity}${if(v.basisUnit=="G") "g" else "ml"} · ${display(NutritionCalculator.energy(v).value)} kcal",style=MaterialTheme.typography.bodySmall)}
                            if(food.favorite) Icon(Icons.Outlined.Star,"收藏",tint=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if(foods.isEmpty()) Text("没有匹配食品，可先到食品页新增。")
            }
            Field("这餐备注",editor.draft.note,{note->vm.modify{it.copy(draft=it.draft.copy(note=note))}})
            if(valid.isSuccess && editor.rows.isNotEmpty()) Section("实时预览（尚未计入摄入）") { NutrientSummary(NutritionCalculator.aggregate(valid.getOrThrow().items).take(4)) }
            TextButton(onClick={vm.closeEditor(true)}){Text("丢弃此草稿")}
        }
    }
    assistant?.let { id ->
        var total by remember{mutableStateOf("")};var bone by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
        FormDialog("本次可食重量",confirm="填入本次份量",dismiss={assistant=null},error=error,save={runCatching{val amount=NutritionCalculator.edible(total,bone);vm.modify{it.copy(rows=it.rows.map{r->if(r.id==id) r.copy(amount=amount,unit="G") else r})};assistant=null}.onFailure{error=it.message}}) {
            Field("总重 g",total,{total=it},numeric=true);Field("骨重 g",bone,{bone=it},numeric=true)
            Text("只计算本次输入，不修改食品默认份量。")
        }
    }
    refresh?.let { (row,current) ->
        val previous=runCatching{row.item().derived}.getOrNull()
        val updated=runCatching{NutritionCalculator.item(current,row.amount.ifEmpty{null},if(row.unit in current.portions.map{it.name}+current.basisUnit) row.unit else current.basisUnit)}.getOrNull()
        AlertDialog(onDismissRequest={refresh=null},title={Text("替换这条历史快照？")},text={Text("${row.snapshot.name} → ${current.name}\n热量 ${display(previous?.kcal)} → ${display(updated?.derived?.kcal)} kcal\nP ${display(previous?.protein)} → ${display(updated?.derived?.protein)} g\nC ${display(previous?.carbs)} → ${display(updated?.derived?.carbs)} g\nF ${display(previous?.fat)} → ${display(updated?.derived?.fat)} g\n原份量 ${row.amount} ${row.unit}；新单位 ${updated?.unit?:current.basisUnit}。\n只在点击保存已吃后生效，请核对份量。")},confirmButton={TextButton(onClick={vm.modify{it.copy(rows=it.rows.map{r->if(r.id==row.id) r.copy(snapshot=current,unit=updated?.unit?:current.basisUnit) else r})};refresh=null}){Text("采用新数据")}},dismissButton={TextButton(onClick={refresh=null}){Text("保留原快照")}})
    }
}
