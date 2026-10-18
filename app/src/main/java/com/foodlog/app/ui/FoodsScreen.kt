package com.foodlog.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.foodlog.app.data.dataJson
import kotlinx.serialization.encodeToString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foodlog.app.domain.*
import com.foodlog.app.data.Validation

private val PortionsSaver = Saver<List<Portion>,String>(
    save={dataJson.encodeToString(it)},
    restore={runCatching{dataJson.decodeFromString<List<Portion>>(it)}.getOrDefault(emptyList())}
)

@Composable fun FoodsScreen(vm: AppViewModel, data: Dataset) {
    var query by rememberSaveable{mutableStateOf("")};var archived by rememberSaveable{mutableStateOf(false)}
    var editingId by rememberSaveable{mutableStateOf<String?>(null)};var newFood by rememberSaveable{mutableStateOf(false)}
    val editing=data.foods.find{it.id==editingId}
    val foods=data.foods.filter{it.archived==archived && data.version(it).let{v->(v.name+v.brand).contains(query,true)}}.sortedWith(compareByDescending<Food>{it.favorite}.thenBy{data.version(it).name})
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("食品库",style=MaterialTheme.typography.headlineLarge);Text("你的常备食材，一次录入反复使用",style=MaterialTheme.typography.bodySmall)}
        item {Button(onClick={newFood=true},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Text("新增自定义食品")}}
        item {Field("搜索名称或品牌",query,{query=it})}
        item {Choices(listOf("在用食品","已归档"),if(archived) "已归档" else "在用食品",{archived=it=="已归档"})}
        if(foods.isEmpty()) item{Text("这里还没有食品。未知营养可留空，自定义数量不限。")}
        items(foods,key={it.id}) { food ->
            val v=data.version(food)
            Section(v.name, "${v.brand.ifBlank{"食品资料"}} · 第${v.versionNo}版") {
                Text("每 ${v.basisQuantity} ${if(v.basisUnit=="G") "g" else "ml"} · ${display(NutritionCalculator.energy(v).value)} kcal",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                Text("蛋白 ${display(v.nutrients.protein)} · 碳水 ${display(v.nutrients.carbs)} · 脂肪 ${display(v.nutrients.fat)} g",style=MaterialTheme.typography.bodySmall)
                Text(v.weighingBasis,style=MaterialTheme.typography.bodySmall)
                if(v.portions.isNotEmpty()) Text(v.portions.joinToString("；"){"${it.amount}${if(it.unit=="G") "g" else "ml"}/${it.name}${if(it.estimated) "（估算）" else ""}"},style=MaterialTheme.typography.bodySmall)
                Text("${NutritionCalculator.energy(v).source} · ${v.sourceText}",maxLines=3,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={vm.launch{vm.repo.flagFood(food.id,favorite=!food.favorite)}}){Icon(if(food.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,if(food.favorite) "取消收藏" else "收藏")}
                    TextButton(onClick={editingId=food.id}){Text("编辑新版本")}
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick={vm.launch{vm.repo.flagFood(food.id,archived=!food.archived)}}){Text(if(archived) "恢复" else "归档")}
                }
            }
        }
    }
    if(newFood || editing!=null) FoodEditor(vm,data,editing){newFood=false;editingId=null}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FoodEditor(vm: AppViewModel, data: Dataset, food: Food?, dismiss: ()->Unit) {
    val old=food?.let{data.version(it)}
    val foodId=rememberSaveable{food?.id?:uid()}
    var name by rememberSaveable{mutableStateOf(old?.name?:"")};var brand by rememberSaveable{mutableStateOf(old?.brand?:"")}
    var basis by rememberSaveable{mutableStateOf(old?.basisQuantity?:"100")};var unit by rememberSaveable{mutableStateOf(old?.basisUnit?:"G")}
    var kcal by rememberSaveable{mutableStateOf(old?.labelKcal?:old?.tableKcal?:"")};var kj by rememberSaveable{mutableStateOf(old?.labelKj?:"")}
    var protein by rememberSaveable{mutableStateOf(old?.nutrients?.protein?:"")};var carbs by rememberSaveable{mutableStateOf(old?.nutrients?.carbs?:"")}
    var fat by rememberSaveable{mutableStateOf(old?.nutrients?.fat?:"")};var sodium by rememberSaveable{mutableStateOf(old?.nutrients?.sodium?:"")};var fiber by rememberSaveable{mutableStateOf(old?.nutrients?.fiber?:"")}
    var density by rememberSaveable{mutableStateOf(old?.densityGPerMl?:"")};var weighing by rememberSaveable{mutableStateOf(old?.weighingBasis?:"实际可食重量")}
    val sources=linkedMapOf("PACKAGE_LABEL" to "包装标签","AVERAGE_ESTIMATE" to "食品平均估算","USER_INPUT" to "用户输入","CALCULATED" to "计算换算")
    var source by rememberSaveable{mutableStateOf(old?.sourceKind?:"USER_INPUT")};var sourceText by rememberSaveable{mutableStateOf(old?.sourceText?:"")};var url by rememberSaveable{mutableStateOf(old?.sourceUrl?:"")}
    var portions by rememberSaveable(stateSaver=PortionsSaver){mutableStateOf(old?.portions?:emptyList())};var error by rememberSaveable{mutableStateOf<String?>(null)}
    var saving by remember{mutableStateOf(false)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Scaffold(topBar={TopAppBar(title={Text(if(food==null) "新增食品" else "编辑食品新版本")},navigationIcon={IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"关闭")}})},bottomBar={Surface{Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!saving,onClick={runCatching {
                fun optional(s:String)=s.trim().ifEmpty{null}?.also{decimal(it)}
                val v=FoodVersion(foodId=foodId,versionNo=(old?.versionNo?:0)+1,name=name.trim(),brand=brand.trim(),basisQuantity=basis.trim(),basisUnit=unit,
                    labelKcal=if(source=="AVERAGE_ESTIMATE") null else optional(kcal),labelKj=optional(kj),tableKcal=if(source=="AVERAGE_ESTIMATE") optional(kcal) else null,
                    nutrients=Nutrients(protein=optional(protein),carbs=optional(carbs),fat=optional(fat),sodium=optional(sodium),fiber=optional(fiber)),portions=portions,
                    sourceKind=source,sourceText=sourceText.ifBlank{sources.getValue(source)},sourceUrl=url,weighingBasis=weighing,
                    densityGPerMl=density.trim().ifEmpty{null},estimatedFields=if(source=="AVERAGE_ESTIMATE") listOf("all") else old?.estimatedFields?:emptyList())
                Validation.food(v);saving=true;vm.launch{try{vm.repo.food(v,food);dismiss()}finally{saving=false}}
            }.onFailure{error=it.message}},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("保存食品${if(food==null) "" else "新版本"}")}
        }}}) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(food!=null) Text("新版本用于之后新增的餐食。已经保存的历史记录保留原快照。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                Field("食品名称 *",name,{name=it});Field("品牌（可选）",brand,{brand=it})
                Section("营养表基准", "这里填写包装表的基准，和本次吃多少分开。") {
                    Field("基准数量 *",basis,{basis=it},numeric=true);Choices(listOf("G","ML"),unit,{unit=it})
                    Field("热量 kcal（未知留空）",kcal,{kcal=it},numeric=true)
                    Field("能量 kJ（未知留空）",kj,{kj=it},numeric=true)
                    Text("优先采用已填 kcal；只有 kJ 时按4.184换算。",style=MaterialTheme.typography.bodySmall)
                    Field("蛋白质 g",protein,{protein=it},numeric=true);Field("碳水化合物 g",carbs,{carbs=it},numeric=true);Field("脂肪 g",fat,{fat=it},numeric=true)
                    Field("钠 mg",sodium,{sodium=it},numeric=true);Field("膳食纤维 g",fiber,{fiber=it},numeric=true)
                    Text("未知留空，真实零值填0。没有能量且碳蛋脂齐全时才按4/4/9估算。",style=MaterialTheme.typography.bodySmall)
                }
                Field("称重口径（干重、熟重、可食部等）",weighing,{weighing=it})
                Field("密度 g/ml（可选，必须有依据）",density,{density=it},numeric=true)
                Section("常用份量", "仅定义有依据的袋、盒、个或勺；份量最终换算成g或ml。") {
                    portions.forEachIndexed { index,p -> key(index) {
                        Field("份量名称（如袋、盒、个）",p.name,{v->portions=portions.mapIndexed{i,x->if(i==index)x.copy(name=v) else x}})
                        Field("每${p.name.ifBlank{"份"}}的数量",p.amount,{v->portions=portions.mapIndexed{i,x->if(i==index)x.copy(amount=v) else x}},numeric=true)
                        Choices(listOf("G","ML"),p.unit,{v->portions=portions.mapIndexed{i,x->if(i==index)x.copy(unit=v) else x}})
                        CheckRow("估算份量",p.estimated,{v->portions=portions.mapIndexed{i,x->if(i==index)x.copy(estimated=v) else x}})
                        TextButton(onClick={portions=portions.filterIndexed{i,_->i!=index}}){Text("移除此份量")}
                        HorizontalDivider()
                    } }
                    TextButton(onClick={portions=portions+Portion("","",unit)}){Text("＋ 添加一种份量")}
                }
                Section("信息来源") {
                    Choices(sources.values.toList(),sources[source]?:"用户输入",{label->source=sources.entries.first{it.value==label}.key})
                    Field("来源与估算说明",sourceText,{sourceText=it});Field("来源链接（可选）",url,{url=it})
                }
            }
        }
    }
}

