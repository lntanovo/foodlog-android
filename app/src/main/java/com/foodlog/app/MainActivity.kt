package com.foodlog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
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
    val cloud: CloudBackupViewModel = viewModel()
    val transfer: TransferViewModel = viewModel()
    val cloudBusy by cloud.busy.collectAsStateWithLifecycle()
    val transferBusy by transfer.busy.collectAsStateWithLifecycle()
    val transferTask by transfer.task.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as FoodLogApplication
    val owner = LocalLifecycleOwner.current
    val interactionBusy = editor != null || cloudBusy || transferBusy || transferTask != null
    SideEffect { app.automaticBackup.interactiveBusy = interactionBusy }
    LaunchedEffect(interactionBusy) { if (!interactionBusy) app.automaticBackup.kick() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) app.automaticBackup.kick() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); app.automaticBackup.interactiveBusy = false }
    }
    val snackbar=remember{SnackbarHostState()}
    val isDark=dark?:isSystemInDarkTheme()
    val view=LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window,view).apply {
                isAppearanceLightStatusBars=!isDark
                isAppearanceLightNavigationBars=!isDark
            }
        }
    }
    val scheme=if(isDark) darkColorScheme(
        primary=Color(0xFFADD9BE),onPrimary=Color(0xFF0B3925),primaryContainer=Color(0xFF234D37),onPrimaryContainer=Color(0xFFC7EDD6),
        secondary=Color(0xFFBBCDBE),secondaryContainer=Color(0xFF314A39),onSecondaryContainer=Color(0xFFD7EBDC),
        tertiary=Color(0xFFDAC5A5),background=Color(0xFF111A15),surface=Color(0xFF19241D),surfaceVariant=Color(0xFF2B3830),
        surfaceContainer=Color(0xFF202D24),surfaceContainerLowest=Color(0xFF0B140F),surfaceContainerLow=Color(0xFF152019),
        surfaceContainerHigh=Color(0xFF25342A),surfaceContainerHighest=Color(0xFF2D3E31),
        onSurfaceVariant=Color(0xFFBECBBF),outline=Color(0xFF8C9B8E),outlineVariant=Color(0xFF3A4A3E)
    ) else lightColorScheme(
        primary=Color(0xFF286346),onPrimary=Color.White,primaryContainer=Color(0xFFD5EBDB),onPrimaryContainer=Color(0xFF153E2A),
        secondary=Color(0xFF536A58),secondaryContainer=Color(0xFFE4EEDF),onSecondaryContainer=Color(0xFF284731),
        tertiary=Color(0xFF856A42),background=Color(0xFFF3F5EF),surface=Color(0xFFFEFFF9),surfaceVariant=Color(0xFFE5EAE1),
        surfaceContainer=Color(0xFFEBF0E6),surfaceContainerLowest=Color(0xFFFEFFF9),surfaceContainerLow=Color(0xFFF6F8F1),
        surfaceContainerHigh=Color(0xFFE5ECE1),surfaceContainerHighest=Color(0xFFDDE6D9),
        onSurfaceVariant=Color(0xFF445247),outline=Color(0xFF6E7D70),outlineVariant=Color(0xFFD6DFD2)
    )
    MaterialTheme(colorScheme=scheme,shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp))) {
        LaunchedEffect(Unit) { for(notice in vm.notices) { val result=snackbar.showSnackbar(notice.text,actionLabel=if(notice.undo!=null) "撤销" else null,duration=SnackbarDuration.Short);if(result==SnackbarResult.ActionPerformed) notice.undo?.let{vm.launch{vm.repo.undoDeletion(it)}} } }
        LaunchedEffect(date,data?.plans?.size,data?.days?.find{it.date==date}?.training,data?.days?.find{it.date==date}?.dietDayType) { if(data!=null) vm.launch{vm.repo.ensureTarget(date)} }
        Surface(Modifier.fillMaxSize()) {
            if(data==null) Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){
                if(loadingError==null) CircularProgressIndicator() else Column(Modifier.padding(24.dp)) {Text("无法读取本机记录");Text(loadingError!!);Button(onClick={vm.start()}){Text("重试")}}
            }
            else if(editor!=null) MealScreen(vm,data!!,editor!!,snackbar)
            else Scaffold(
                snackbarHost={SnackbarHost(snackbar)},
                bottomBar={NavigationBar(containerColor=MaterialTheme.colorScheme.surface) { listOf("今日","食品","趋势","配额","设置").forEachIndexed { i,name ->
                    val icon=listOf(Icons.Outlined.Restaurant,Icons.Outlined.Kitchen,Icons.Outlined.ShowChart,Icons.Outlined.Tune,Icons.Outlined.Settings)[i]
                    NavigationBarItem(tab==i,{vm.tab(i)},icon={Icon(icon,name)},label={Text(name)})
                } }},
                floatingActionButton={if(tab==0) ExtendedFloatingActionButton(onClick={vm.edit()},modifier=Modifier.semantics{contentDescription="记一餐"},containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary,icon={Icon(Icons.Outlined.Add,null)},text={Text("记一餐")})}
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=900.dp).fillMaxSize()) {
                    when(tab) {
                        0->TodayScreen(vm,data!!,date)
                        1->FoodsScreen(vm,data!!)
                        2->TrendScreen(vm,data!!,date)
                        3->QuotaScreen(vm,data!!)
                        else->SettingsScreen(vm,data!!)
                    }
                    }
                }
            }
        }
    }
}
