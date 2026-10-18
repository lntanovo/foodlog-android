package com.foodlog.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.*
import com.foodlog.app.*
import com.foodlog.app.domain.*
import com.foodlog.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneId

data class EditableItem(val id: String = uid(), val snapshot: FoodVersion, val amount: String = "", val unit: String = snapshot.basisUnit, val estimated: Boolean = false) {
    fun item() = NutritionCalculator.item(snapshot,amount.trim().ifEmpty{null},unit,id,estimated)
}
data class MealEditor(val draft: Meal, val rows: List<EditableItem>) {
    fun meal() = draft.copy(items=rows.map{it.item()})
}
data class Notice(val text: String, val undo: Meal? = null)

class AppViewModel(application: Application, private val saved: SavedStateHandle): AndroidViewModel(application) {
    val repo=(application as FoodLogApplication).repository
    val data=MutableStateFlow<Dataset?>(null)
    val loadingError=MutableStateFlow<String?>(null)
    val selectedDate=saved.getStateFlow("selectedDate",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())
    val tab=saved.getStateFlow("tab",0)
    val editor=MutableStateFlow<MealEditor?>(null)
    val notices=Channel<Notice>(Channel.BUFFERED)
    val busy=MutableStateFlow(false)
    val dark=application.preferences.data.map{it[booleanPreferencesKey("dark")]}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private var draftJob: Job?=null
    init { start() }
    fun start() { launch { try {
        loadingError.value=null;repo.initialize()
        repo.state.collect{
            if(data.value==null && saved.get<String>("selectedDate")==null) saved["selectedDate"]=LocalDate.now(ZoneId.of(it.meta.timezone)).toString()
            data.value=it
        }
    } catch(e:CancellationException){throw e} catch(e:Exception) {loadingError.value=e.message?:"数据库读取失败"} } }
    fun launch(block: suspend ()->Unit) = viewModelScope.launch {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { notices.send(Notice(e.message?:"操作失败，请重试")) }
    }
    fun date(date: String) { LocalDate.parse(date); saved["selectedDate"]=date; launch{repo.ensureTarget(date)} }
    fun tab(value: Int) { saved["tab"]=value }
    fun today() { date(LocalDate.now(ZoneId.of(data.value?.meta?.timezone?:"Asia/Shanghai")).toString()) }
    fun theme(value: Boolean) { launch{getApplication<Application>().preferences.edit{it[booleanPreferencesKey("dark")]=value}} }
    fun edit(meal: Meal? = null) {
        val d=data.value?:return
        val draft=when {
            meal==null -> Meal(date=selectedDate.value)
            meal.status=="DRAFT" -> meal
            else -> d.meals.find{it.status=="DRAFT" && it.replacesMealId==meal.id} ?: meal.copy(id=uid(),status="DRAFT",replacesMealId=meal.id)
        }
        editor.value=MealEditor(draft,draft.items.map{EditableItem(it.id,it.snapshot,it.quantity?:"",it.unit,it.quantityStatus=="ESTIMATED")})
        autosave()
    }
    fun modify(block: (MealEditor)->MealEditor) { editor.value=editor.value?.let(block); autosave() }
    private fun autosave() {
        draftJob?.cancel()
        val current=editor.value?:return
        draftJob=launch { delay(250); runCatching{current.meal()}.getOrNull()?.let{repo.meal(it,false)} }
    }
    fun closeEditor(discard: Boolean = false) {
        draftJob?.cancel()
        launch {
            val current=editor.value?:return@launch
            if(discard) repo.deleteMeal(current.draft.id) else repo.meal(current.meal(),false)
            editor.value=null
        }
    }
    fun saveMeal() {
        draftJob?.cancel()
        launch {
            busy.value=true
            try { val meal=editor.value?.meal()?:return@launch; repo.meal(meal,true);editor.value=null;notices.send(Notice("已保存这餐")) }
            finally { busy.value=false }
        }
    }
    fun delete(meal: Meal) { launch { repo.deleteMeal(meal.id); notices.send(Notice("已删除${meal.type}",meal)) } }
}
