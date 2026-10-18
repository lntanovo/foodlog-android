package com.foodlog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foodlog.app.ui.*

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge();setContent{FoodLogApp()} }
}
@Composable fun FoodLogApp(vm: AppViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val loadingError by vm.loadingError.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val snackbar=remember{SnackbarHostState()}
    val isDark=dark?:isSystemInDarkTheme()
    val scheme=if(isDark) darkColorScheme(primary=Color(0xFFAACFB5),background=Color(0xFF151D19),surface=Color(0xFF202A24),secondaryContainer=Color(0xFF354D3D)) else lightColorScheme(primary=Color(0xFF326A50),onPrimary=Color.White,background=Color(0xFFF4F5EF),surface=Color(0xFFFEFFF9),secondaryContainer=Color(0xFFDCEBD9),onSecondaryContainer=Color(0xFF284B37))
    MaterialTheme(colorScheme=scheme) {
        LaunchedEffect(Unit) { for(notice in vm.notices) { val result=snackbar.showSnackbar(notice.text,actionLabel=if(notice.undo!=null) "撤销" else null,duration=SnackbarDuration.Short);if(result==SnackbarResult.ActionPerformed) notice.undo?.let{vm.repo.undoMeal(it)} } }
        LaunchedEffect(date,data?.plans?.size,data?.days?.find{it.date==date}?.training) { if(data!=null) vm.launch{vm.repo.ensureTarget(date)} }
        Surface(Modifier.fillMaxSize()) {
            if(data==null) Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){
                if(loadingError==null) CircularProgressIndicator() else Column(Modifier.padding(24.dp)) {Text("无法读取本机记录");Text(loadingError!!);Button(onClick={vm.start()}){Text("重试")}}
            }
            else if(editor!=null) MealScreen(vm,data!!,editor!!,snackbar)
            else Scaffold(
                snackbarHost={SnackbarHost(snackbar)},
                bottomBar={NavigationBar { listOf("今日","食品","趋势","设置").forEachIndexed { i,name ->
                    val icon=listOf(Icons.Outlined.Restaurant,Icons.Outlined.Kitchen,Icons.Outlined.ShowChart,Icons.Outlined.Settings)[i]
                    NavigationBarItem(tab==i,{vm.tab(i)},icon={Icon(icon,name)},label={Text(name)})
                } }},
                floatingActionButton={if(tab==0) ExtendedFloatingActionButton(onClick={vm.edit()},modifier=Modifier.semantics{contentDescription="记一餐"},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,icon={Icon(Icons.Outlined.Add,null)},text={Text("记一餐")})}
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when(tab) {
                        0->TodayScreen(vm,data!!,date)
                        1->FoodsScreen(vm,data!!)
                        2->TrendScreen(vm,data!!,date)
                        else->SettingsScreen(vm,data!!)
                    }
                }
            }
        }
    }
}
