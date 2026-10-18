package com.foodlog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.foodlog.app.domain.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MealScreen(vm: AppViewModel, data: Dataset, editor: MealEditor, snackbar: SnackbarHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable(editor.draft.id) { mutableStateOf(editor.rows.isEmpty()) }
    var assistant by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshId by rememberSaveable { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()
    val draftStatus by vm.draftStatus.collectAsState()
    val valid = remember(editor) { runCatching { editor.meal() } }
    val canPost = valid.getOrNull()?.items?.any { it.quantity==null || decimal(it.quantity)>java.math.BigDecimal.ZERO } == true
    val recent = remember(data.meals) { data.meals.asSequence().filter{it.status=="POSTED"}.sortedByDescending{it.updatedAt}.flatMap{it.items.asSequence()}.map{it.foodId}.distinct().toList().withIndex().associate{it.value to it.index} }
    val foods = remember(data.foods,data.versions,query,recent) {
        val versions=data.versions.associateBy{it.id}
        data.foods.filter{!it.archived}.filter{versions.getValue(it.activeVersionId).let{v->(v.name+v.brand).contains(query,true)}}
            .sortedWith(compareByDescending<Food>{it.favorite}.thenBy{recent[it.id]?:Int.MAX_VALUE}.thenBy{versions.getValue(it.activeVersionId).name})
    }
    BackHandler { if(!busy) vm.closeEditor() }
    Scaffold(
        snackbarHost={SnackbarHost(snackbar)},
        topBar={TopAppBar(title={Column{Text(if(editor.draft.replacesMealId!=null) "编辑这餐" else "记一餐");Text(draftStatus,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},navigationIcon={IconButton(onClick={vm.closeEditor()},enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回并保留草稿")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background))},
        bottomBar={Surface(shadowElevation=8.dp){Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal=20.dp,vertical=12.dp).widthIn(max=760.dp)) {
            Button(onClick={vm.saveMeal()},enabled=!busy && canPost,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(if(busy) "保存中…" else "保存已吃")}
            Text("草稿不计入摄入 · 返回可稍后继续",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp))
        }}}
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(),contentAlignment=Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max=760.dp).fillMaxSize().testTag("meal-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item("meal-date") {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("记录日期",style=MaterialTheme.typography.labelLarge);DateButton(editor.draft.date,{value->vm.date(value);vm.modify{it.copy(draft=it.draft.copy(date=value))}})}
                    Choices(mealTypes,editor.draft.type,{value->vm.modify{it.copy(draft=it.draft.copy(type=value))}})
                }
                items(editor.rows,key={"item-${it.id}"}) { row ->
                    val v=row.snapshot
                    val result=runCatching{row.item()}
                    val item=result.getOrNull()
                    Section(v.name,"营养表基准 ${v.basisQuantity}${unitName(v.basisUnit)} · ${v.weighingBasis}") {
                        Field("本次份量（留空为待补）",row.amount,{vm.amount(row.id,it)},numeric=true)
                        Choices(((if(v.densityGPerMl!=null) listOf("G","ML") else listOf(v.basisUnit))+v.portions.map{it.name}).distinct(),row.unit,{vm.unit(row.id,it)})
                        v.portions.find{it.name==row.unit}?.let{Text("每${it.name} ${it.amount}${unitName(it.unit)}${if(it.estimated) " · 估算份量" else ""}",style=MaterialTheme.typography.bodySmall)}
                        when {
                            result.isFailure -> Text("请完成有效数字；当前输入会保留为草稿",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                            item?.normalizedAmount==null -> Text("份量待补 · 营养暂未知",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                            else -> Text("${if(item.quantityStatus=="ESTIMATED") "约 " else ""}${display(item.normalizedAmount)}${unitName(v.basisUnit)} · ${display(item.derived.kcal)} kcal",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleSmall)
                        }
                        if(row.unit in listOf("G","ML")) CheckRow("份量为估算",row.estimated,{value->vm.modify{it.copy(rows=it.rows.map{r->if(r.id==row.id)r.copy(estimated=value)else r})}})
                        Text("${NutritionCalculator.energy(v).source} · ${v.sourceText}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={vm.modify{it.copy(rows=it.rows.filterNot{r->r.id==row.id})}}){Icon(Icons.Outlined.Close,null,Modifier.size(16.dp));Text("移除")}
                            if(v.basisUnit=="G") TextButton(onClick={assistant=row.id}){Text("总重减骨重")}
                        }
                        val current=data.foods.find{it.id==v.foodId}?.let{data.version(it)}
                        if(current!=null && current.id!=v.id) TextButton(onClick={refreshId=row.id}){Text("更新为当前食品数据…")}
                    }
                }
                item("picker-toggle") {
                    OutlinedButton(onClick={picker=!picker},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Icon(if(picker) Icons.Outlined.ExpandLess else Icons.Outlined.Add,null);Text(if(picker) "收起食品选择" else "继续添加食品")}
                }
                if(picker) {
                    item("search-foods"){Field("搜索食品或品牌",query,{query=it});Text("收藏和最近使用优先",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    items(foods,key={"choose-${it.id}"}) { food ->
                        val v=data.version(food);val selected=editor.rows.any{it.snapshot.foodId==food.id}
                        OutlinedCard(onClick={vm.modify{e->e.copy(rows=if(selected) e.rows.filterNot{it.snapshot.foodId==food.id} else e.rows+EditableItem(snapshot=v))}},modifier=Modifier.fillMaxWidth().testTag("choose-food-${food.seedId?:food.id}")) {
                            Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(selected,onCheckedChange=null)
                                Column(Modifier.weight(1f)){Text(v.name,style=MaterialTheme.typography.titleSmall);Text("${v.basisQuantity}${unitName(v.basisUnit)} · ${display(NutritionCalculator.energy(v).value)} kcal",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                if(food.favorite) Icon(Icons.Outlined.Star,"收藏",tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(20.dp))
                            }
                        }
                    }
                    if(foods.isEmpty()) item("empty-search"){Text("没有匹配食品，可先到食品页新增。")}
                }
                item("meal-note"){Field("这餐备注",editor.draft.note,{note->vm.modify{it.copy(draft=it.draft.copy(note=note))}})}
                if(valid.isSuccess && editor.rows.isNotEmpty()) item("meal-preview") {Section("本餐预览","保存已吃后才计入当天") {NutrientSummary(NutritionCalculator.aggregate(valid.getOrThrow().items).take(4))}}
                item("discard-draft"){TextButton(onClick={vm.closeEditor(true)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("丢弃此草稿")}}
            }
        }
    }
    assistant?.let { id ->
        var total by rememberSaveable(id){mutableStateOf("")};var bone by rememberSaveable(id){mutableStateOf("")};var error by rememberSaveable(id){mutableStateOf<String?>(null)}
        FormDialog("本次可食重量",confirm="填入本次份量",dismiss={assistant=null},error=error,save={runCatching{val amount=NutritionCalculator.edible(total,bone);vm.modify{it.copy(rows=it.rows.map{r->if(r.id==id) r.copy(amount=amount,unit="G",estimated=false) else r})};assistant=null}.onFailure{error=it.message}}) {
            Field("总重 g",total,{total=it},numeric=true);Field("骨重 g",bone,{bone=it},numeric=true)
            Text("只计算本次输入，不修改食品默认份量。",style=MaterialTheme.typography.bodySmall)
        }
    }
    refreshId?.let { id ->
        val row=editor.rows.find{it.id==id}
        val current=row?.let{r->data.foods.find{it.id==r.snapshot.foodId}?.let{data.version(it)}}
        if(row!=null && current!=null) {
            val previous=runCatching{row.item()}.getOrNull()
            val updated=runCatching{adoptFoodVersion(row,current)}
            val candidate=updated.getOrNull()
            val names=listOf("热量 kcal","蛋白质 g","碳水 g","脂肪 g","钠 mg","纤维 g")
            FormDialog("替换这条历史快照？",confirm="采用新数据",dismiss={refreshId=null},error=updated.exceptionOrNull()?.message,save={
                if(candidate!=null){vm.modify{it.copy(rows=it.rows.map{r->if(r.id==id) r.copy(snapshot=current,amount=candidate.quantity?:"",unit=candidate.unit,estimated=candidate.quantityStatus=="ESTIMATED") else r})};refreshId=null}
            }) {
                Text("${row.snapshot.name} → ${current.name}")
                names.forEachIndexed{i,name->Text("$name：${display(previous?.derived?.values()?.get(i))} → ${display(candidate?.derived?.values()?.get(i))}",style=MaterialTheme.typography.bodySmall)}
                Text("份量：${row.amount} ${unitName(row.unit)} → ${candidate?.quantity?:"待补"} ${candidate?.let{unitName(it.unit)}?:""}")
                Text("请核对新基准与份量；只在保存已吃后更新这条餐食。",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun unitName(unit: String)=when(unit){"G"->"g";"ML"->"ml";else->unit}
private fun adoptFoodVersion(row: EditableItem,current: FoodVersion): MealItem {
    val quantity=row.amount.trim().ifEmpty{null}
    if(row.unit==current.basisUnit || current.portions.any{it.name==row.unit} || (row.unit in listOf("G","ML") && current.densityGPerMl!=null))
        return NutritionCalculator.item(current,quantity,row.unit,row.id,row.estimated)
    val oldAmount=NutritionCalculator.normalize(row.snapshot,quantity,row.unit)
    val normalized=NutritionCalculator.normalize(current,oldAmount,row.snapshot.basisUnit)
    return NutritionCalculator.item(current,normalized,current.basisUnit,row.id,row.estimated)
}
