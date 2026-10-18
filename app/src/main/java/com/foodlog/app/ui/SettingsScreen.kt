package com.foodlog.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foodlog.app.data.*
import com.foodlog.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@Composable fun SettingsScreen(vm: AppViewModel, data: Dataset) {
    val context=LocalContext.current
    var profile by remember{mutableStateOf(false)};var target by remember{mutableStateOf(false)}
    var pendingExport by remember{mutableStateOf<String?>(null)};var exportIsFull by remember{mutableStateOf(false)}
    var expectedKind by remember{mutableStateOf("FULL_BACKUP")};var pendingImport by remember{mutableStateOf<Dataset?>(null)}
    var libraryDialog by remember{mutableStateOf(false)};var selected by remember{mutableStateOf(emptySet<String>())}
    var importing by remember{mutableStateOf(false)}
    val dark by vm.dark.collectAsState()
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) vm.launch {
            val text=pendingExport?:error("导出会话已结束，请重新导出")
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(text.toByteArray(Charsets.UTF_8))}?:error("无法写入目标文件") }
            if(exportIsFull) vm.repo.markExport()
            pendingExport=null;vm.notices.send(Notice("文件已导出"))
        }
    }
    fun write(text: String, filename: String, full: Boolean = false) { pendingExport=text;exportIsFull=full;export.launch(filename) }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) vm.launch {
            val text=withContext(Dispatchers.IO) {context.contentResolver.openInputStream(uri)?.use{stream->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val read=stream.read(buffer);if(read<0)break;require(output.size()+read<=30_000_000){"文件超过30MB限制"};output.write(buffer,0,read)}
                output.toByteArray().toString(Charsets.UTF_8)
            }?:error("无法读取文件")}
            val incoming=withContext(Dispatchers.Default){vm.repo.parse(text)}
            require(incoming.kind==expectedKind) { "文件类型不符，请使用对应的恢复/食品库/旧数据入口" }
            pendingImport=incoming
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("设置",style=MaterialTheme.typography.headlineLarge)
        Text("数据留在本机，生活由你设定。",style=MaterialTheme.typography.bodySmall)
        Section("个人资料与自设目标") {
            Text(data.meta.profile.name.ifBlank{"尚未填写个人资料"})
            OutlinedButton(onClick={profile=true},modifier=Modifier.fillMaxWidth()){Text("个人资料与业务时区")}
            val plan=data.plans.maxWithOrNull(compareBy<TargetPlan>{it.effectiveFrom}.thenBy{it.createdAt})
            Text(plan?.let{"最近计划自 ${it.effectiveFrom} 起生效 · ${if(it.mode=="PER_KG") "按参考体重系数" else "直接填写碳蛋脂"}"}?:"尚未设置目标；可以只记录，不设置配额。",style=MaterialTheme.typography.bodySmall)
            Button(onClick={target=true},modifier=Modifier.fillMaxWidth()){Text("设置训练日 / 休息日目标")}
        }
        Section("数据备份与恢复", "卸载可能丢失本地数据，请定期保存完整备份。") {
            Text("最近完整导出：${data.meta.lastExport?.let{java.time.Instant.parse(it).atZone(ZoneId.of(data.meta.timezone)).toLocalDateTime().toString().replace('T',' ')}?:"尚未导出"}",style=MaterialTheme.typography.bodySmall)
            Button(onClick={vm.launch{write(vm.repo.export(false),"食记_完整备份_${LocalDate.now(ZoneId.of(data.meta.timezone))}.json",true)}},modifier=Modifier.fillMaxWidth()){Text("导出完整备份")}
            OutlinedButton(onClick={expectedKind="FULL_BACKUP";open.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("从完整备份恢复…")}
            OutlinedButton(onClick={vm.launch{val f=vm.repo.latestRecovery()?:error("还没有恢复前自动备份");write(withContext(Dispatchers.IO){f.readText()},"食记_恢复前备份.json")}},modifier=Modifier.fillMaxWidth()){Text("取出最近的恢复前自动备份")}
        }
        Section("食品库与旧数据") {
            OutlinedButton(onClick={selected=data.foods.filter{!it.archived}.map{it.id}.toSet();libraryDialog=true},modifier=Modifier.fillMaxWidth()){Text("选择食品并导出分享文件…")}
            OutlinedButton(onClick={expectedKind="FOOD_LIBRARY";open.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("导入食品库…")}
            OutlinedButton(onClick={expectedKind="LEGACY_IMPORT";open.launch(arrayOf("application/json","text/plain","application/octet-stream"))},modifier=Modifier.fillMaxWidth()){Text("导入电脑转换的旧记录…")}
            Text("旧Excel先使用工程中的 migrate_legacy.py 转换，再把生成的JSON传入手机。导入时可核对日期、数量及冲突。",style=MaterialTheme.typography.bodySmall)
        }
        Section("显示与关于") {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text("深色外观");Switch(dark?:androidx.compose.foundation.isSystemInDarkTheme(),{vm.theme(it)})}
            Text("食记 1.0.0 · 本地离线版",style=MaterialTheme.typography.titleSmall)
            Text("公共食品种子11种；标签优先，平均估算与待补项单独保留。完整备份包含全部文字数据，不包含包装照片附件。所有目标均为自设配额。",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(profile) ProfileDialog(vm,data){profile=false}
    if(target) TargetDialog(vm,data){target=false}
    if(libraryDialog) FormDialog("选择要分享的食品",confirm="导出所选食品",dismiss={libraryDialog=false},save={vm.launch{require(selected.isNotEmpty()){"请至少选择一种食品"};write(vm.repo.export(true,selected),"食记_食品库.json");libraryDialog=false}}) {
        Text("文件只包含所选食品及份量定义。")
        data.foods.forEach { f -> Row(verticalAlignment=Alignment.CenterVertically){Checkbox(f.id in selected,{checked->selected=if(checked) selected+f.id else selected-f.id});Text(data.version(f).name)} }
    }
    pendingImport?.let { incoming ->
        var replacements by remember(incoming){mutableStateOf(emptySet<String>())}
        var plans by remember(incoming){mutableStateOf(false)}
        val conflicts=vm.repo.conflicts(data,incoming)
        val dates=(incoming.meals.map{it.date}+incoming.bodies.map{it.date}).sorted()
        val matched=incoming.origins.count{o->data.origins.any{it.namespace==o.namespace && it.key==o.key && it.fingerprint==o.fingerprint}}
        AlertDialog(onDismissRequest={if(!importing) pendingImport=null},title={Text(if(incoming.kind=="FULL_BACKUP") "预览完整恢复" else "预览导入")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("食品 ${incoming.foods.size} · 版本 ${incoming.versions.size}\n餐食 ${incoming.meals.size} · 明细 ${incoming.meals.sumOf{it.items.size}}\n身体记录 ${incoming.bodies.size} · 日志 ${incoming.days.size}\n目标计划 ${incoming.plans.size} · 日目标 ${incoming.targets.size}")
                if(dates.isNotEmpty()) Text("日期 ${dates.first()} 至 ${dates.last()}")
                Text("份量待补 ${incoming.meals.sumOf{m->m.items.count{it.quantity==null}}} 项")
                if(incoming.kind=="FULL_BACKUP") Text("恢复将替换当前全部数据。提交前会自动保存本机恢复前备份，可从设置取出。",color=MaterialTheme.colorScheme.primary)
                else if(incoming.kind=="LEGACY_IMPORT") {
                    Text("已匹配来源 $matched 项，重复项默认跳过。")
                    if(conflicts.isEmpty()) Text("未发现日期冲突。")
                    conflicts.sorted().forEach { day ->
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(day in replacements,{checked->replacements=if(checked) replacements+day else replacements-day});Text("替换 $day 的整日饮食及导入的身体/训练数据")}
                    }
                    if(conflicts.isNotEmpty()) Text("未勾选的冲突日期会跳过；替换会移除该日现有餐食（包括本机手动新增）。")
                    if(incoming.plans.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically){Checkbox(plans,{plans=it});Text("同时导入个人历史自设配额")}
                }
                incoming.report.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
            }
        },confirmButton={TextButton(enabled=!importing,onClick={importing=true;vm.launch{
            try {
                if(incoming.kind=="FULL_BACKUP") vm.repo.restore(incoming) else {if(replacements.isNotEmpty()) vm.repo.recoveryBackup();vm.repo.merge(incoming,replacements,plans)}
                vm.notices.send(Notice("${if(incoming.kind=="FULL_BACKUP") "恢复" else "导入"}完成"));pendingImport=null
            } finally {importing=false}
        }}){Text(if(importing) "处理中…" else if(incoming.kind=="FULL_BACKUP") "确认替换并恢复" else "确认导入")}},dismissButton={TextButton(enabled=!importing,onClick={pendingImport=null}){Text("取消")}})
    }
}

@Composable fun ProfileDialog(vm: AppViewModel, data: Dataset, dismiss: ()->Unit) {
    var name by remember{mutableStateOf(data.meta.profile.name)};var height by remember{mutableStateOf(data.meta.profile.height?:"")};var age by remember{mutableStateOf(data.meta.profile.age?.toString()?:"")}
    var sex by remember{mutableStateOf(data.meta.profile.sex)};var timezone by remember{mutableStateOf(data.meta.timezone)};var error by remember{mutableStateOf<String?>(null)}
    FormDialog("个人资料（均可留空）",dismiss=dismiss,error=error,save={runCatching{
        ZoneId.of(timezone);val h=height.trim().ifEmpty{null}?.also{decimal(it,true)}
        val a=if(age.isBlank()) null else age.toIntOrNull()?.also{require(it in 1..150){"年龄须为1–150整数"}}?:error("请输入有效年龄")
        vm.launch{vm.repo.profile(Profile(name,h,a,sex),timezone);dismiss()}
    }.onFailure{error=it.message}}) {
        Field("称呼",name,{name=it});Field("身高 cm",height,{height=it},numeric=true);Field("年龄",age,{age=it},numeric=true)
        Choices(listOf("未填写","男","女"),sex.ifBlank{"未填写"},{sex=if(it=="未填写") "" else it})
        Field("业务时区",timezone,{timezone=it});Text("已有餐食日期保持原样。参考体重在目标中单独设置。",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable fun TargetDialog(vm: AppViewModel, data: Dataset, dismiss: ()->Unit) {
    val old=data.plans.maxWithOrNull(compareBy<TargetPlan>{it.effectiveFrom}.thenBy{it.createdAt})
    var mode by remember{mutableStateOf(old?.mode?:"DIRECT_MACROS")}
    var reference by remember{mutableStateOf(old?.referenceWeight?:"")};var effective by remember{mutableStateOf(LocalDate.now(ZoneId.of(data.meta.timezone)).toString())}
    var tp by remember{mutableStateOf(old?.training?.protein?:"")};var tc by remember{mutableStateOf(old?.training?.carbs?:"")};var tf by remember{mutableStateOf(old?.training?.fat?:"")}
    var rp by remember{mutableStateOf(old?.rest?.protein?:"")};var rc by remember{mutableStateOf(old?.rest?.carbs?:"")};var rf by remember{mutableStateOf(old?.rest?.fat?:"")}
    var error by remember{mutableStateOf<String?>(null)}
    FormDialog("新建自设配额计划",dismiss=dismiss,error=error,save={runCatching{
        listOf(tp,tc,tf,rp,rc,rf).forEach{decimal(it)};if(mode=="PER_KG") decimal(reference,true)
        LocalDate.parse(effective)
        val plan=TargetPlan(effectiveFrom=effective,mode=mode,referenceWeight=reference.ifBlank{null},training=Macros(tp,tc,tf),rest=Macros(rp,rc,rf))
        vm.launch{vm.repo.plan(plan);dismiss()}
    }.onFailure{error=it.message}}) {
        Text("自行设定的记录配额，不代表实测每日能量消耗。",style=MaterialTheme.typography.bodySmall)
        Choices(listOf("直接填写克数","按参考体重系数"),if(mode=="PER_KG") "按参考体重系数" else "直接填写克数",{mode=if(it=="按参考体重系数") "PER_KG" else "DIRECT_MACROS";tp="";tc="";tf="";rp="";rc="";rf=""})
        Row(verticalAlignment=Alignment.CenterVertically){Text("生效日期");DateButton(effective,{effective=it})}
        if(mode=="PER_KG") Field("目标参考体重 kg（不是晨重）",reference,{reference=it},numeric=true)
        val unit=if(mode=="PER_KG") "g/kg" else "g"
        Text("训练日（力量、羽毛球、两练）")
        Field("碳水 $unit",tc,{tc=it},numeric=true);Field("蛋白质 $unit",tp,{tp=it},numeric=true);Field("脂肪 $unit",tf,{tf=it},numeric=true)
        Text("休息日")
        Field("碳水 $unit",rc,{rc=it},numeric=true);Field("蛋白质 $unit",rp,{rp=it},numeric=true);Field("脂肪 $unit",rf,{rf=it},numeric=true)
        Text("新计划用于生效日期起尚未固定目标的日期。已有日目标保留；如需重算，在当天身体与训练记录中明确勾选。",style=MaterialTheme.typography.bodySmall)
    }
}
