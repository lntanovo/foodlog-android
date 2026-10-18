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
    fun draftItem(): MealItem = runCatching { item() }.getOrElse {
        NutritionCalculator.item(snapshot,null,unit,id).copy(draftQuantityText=amount.take(256))
    }
}
data class MealEditor(val draft: Meal, val rows: List<EditableItem>) {
    fun meal() = draft.copy(items=rows.map{it.item()})
    fun draftMeal() = draft.copy(status="DRAFT",items=rows.map{it.draftItem()})
}
data class Notice(val text: String, val undo: DeletedMealBundle? = null)
data class QuickMealActionState(val id: String = "", val running: Boolean = false, val completed: Boolean = false, val error: String? = null)

class AppViewModel(application: Application, private val saved: SavedStateHandle): AndroidViewModel(application) {
    val repo=(application as FoodLogApplication).repository
    val data=MutableStateFlow<Dataset?>(null)
    val loadingError=MutableStateFlow<String?>(null)
    val selectedDate=saved.getStateFlow("selectedDate",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())
    val tab=saved.getStateFlow("tab",0)
    val editor=MutableStateFlow<MealEditor?>(null)
    val notices=Channel<Notice>(Channel.BUFFERED)
    val busy=MutableStateFlow(false)
    val draftStatus=MutableStateFlow("草稿已保存")
    val quickMealAction=MutableStateFlow(QuickMealActionState())
    val dark=application.preferences.data.map{it[booleanPreferencesKey("dark")]}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private var draftJob: Job?=null
    init { start() }
    fun start() { launch { try {
        loadingError.value=null;repo.initialize()
        repo.state.collect{
            if(data.value==null && saved.get<String>("selectedDate")==null) saved["selectedDate"]=LocalDate.now(ZoneId.of(it.meta.timezone)).toString()
            data.value=it
            if(editor.value==null) saved.get<String>("editingDraftId")?.let { id ->
                it.meals.find { meal -> meal.id==id && meal.status=="DRAFT" }?.let(::restoreEditor)
            }
        }
    } catch(e:CancellationException){throw e} catch(e:Exception) {loadingError.value=e.message?:"数据库读取失败"} } }
    fun launch(block: suspend ()->Unit) = viewModelScope.launch {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { notices.send(Notice(e.message?:"操作失败，请重试")) }
    }
    fun runQuickMealAction(id: String, block: suspend () -> Unit) {
        val current=quickMealAction.value
        if(current.running || (current.id==id && current.completed)) return
        if(!quickMealAction.compareAndSet(current,QuickMealActionState(id=id,running=true))) return
        viewModelScope.launch {
            try { block();quickMealAction.value=QuickMealActionState(id=id,completed=true) }
            catch(e:CancellationException){throw e}
            catch(e:Exception){quickMealAction.value=QuickMealActionState(id=id,error=e.message?:"操作失败，请重试")}
        }
    }
    fun date(date: String) { LocalDate.parse(date); saved["selectedDate"]=date; launch{repo.ensureTarget(date)} }
    fun tab(value: Int) { saved["tab"]=value }
    fun today() { date(LocalDate.now(ZoneId.of(data.value?.meta?.timezone?:"Asia/Shanghai")).toString()) }
    fun theme(value: Boolean) { launch{getApplication<Application>().preferences.edit{it[booleanPreferencesKey("dark")]=value}} }
    fun edit(meal: Meal? = null) {
        if(busy.value) return
        val d=data.value?:return
        val draft=when {
            meal==null -> Meal(date=selectedDate.value)
            meal.status=="DRAFT" -> meal
            else -> d.meals.find{it.status=="DRAFT" && it.replacesMealId==meal.id} ?: meal.copy(id=uid(),status="DRAFT",replacesMealId=meal.id)
        }
        saved["editingDraftId"]=draft.id
        restoreEditor(draft)
        autosave()
    }
    private fun restoreEditor(draft: Meal) {
        editor.value=MealEditor(draft,draft.items.map{EditableItem(it.id,it.snapshot,it.draftQuantityText?:it.quantity?:"",it.unit,it.quantityStatus=="ESTIMATED")})
    }
    fun modify(block: (MealEditor)->MealEditor) { if(busy.value) return;editor.value=editor.value?.let(block); autosave() }
    fun amount(id: String, amount: String) {
        if(amount.length>256) { launch { notices.send(Notice("份量输入过长，请检查数字")) };return }
        modify { e -> e.copy(rows=e.rows.map { row -> if(row.id==id) row.copy(amount=amount,estimated=if(row.unit in listOf("G","ML")) false else row.estimated) else row }) }
    }
    fun unit(id: String, unit: String) {
        if(busy.value) return
        val row=editor.value?.rows?.find{it.id==id}?:return
        if(row.unit==unit) return
        runCatching {
            val converted=NutritionCalculator.convertQuantity(row.snapshot,row.amount.trim().ifEmpty{null},row.unit,unit)
            val estimated=row.item().quantityStatus=="ESTIMATED"
            modify { e -> e.copy(rows=e.rows.map { if(it.id==id) it.copy(amount=converted?:"",unit=unit,estimated=estimated) else it }) }
        }.onFailure { launch { notices.send(Notice("请先完成有效份量，再切换单位；原输入已保留")) } }
    }
    private fun autosave() {
        draftJob?.cancel()
        val current=editor.value?:return
        draftStatus.value="正在保存草稿…"
        draftJob=launch {
            delay(250)
            try { repo.meal(current.draftMeal(),false);draftStatus.value="草稿已保存" }
            catch(e:CancellationException){throw e}
            catch(e:Exception){draftStatus.value="草稿保存失败，请重试";throw e}
        }
    }
    fun closeEditor(discard: Boolean = false) {
        if(!busy.compareAndSet(false,true)) return
        draftJob?.cancel()
        launch {
            try {
            val current=editor.value?:return@launch
            if(discard || (current.rows.isEmpty() && current.draft.note.isBlank())) repo.discardDraft(current.draft.id) else repo.meal(current.draftMeal(),false)
            saved["editingDraftId"]=null
            editor.value=null
            } finally { busy.value=false }
        }
    }
    fun saveMeal() {
        if(!busy.compareAndSet(false,true)) return
        draftJob?.cancel()
        launch {
            try { val meal=editor.value?.meal()?:return@launch; repo.meal(meal,true);saved["editingDraftId"]=null;editor.value=null;notices.send(Notice("已保存这餐")) }
            finally { busy.value=false }
        }
    }
    fun delete(meal: Meal) { launch { val deleted=repo.deleteMeal(meal.id); notices.send(Notice("已删除${meal.type}",deleted)) } }
}
