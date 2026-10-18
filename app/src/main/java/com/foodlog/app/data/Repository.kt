package com.foodlog.app.data

import android.content.Context
import androidx.room.withTransaction
import com.foodlog.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant

class Repository(private val context: Context, val db: FoodLogDatabase) {
    private val dao = db.dao()
    private val lock = Mutex()
    val state = dao.changes().map { snapshot() }

    suspend fun initialize() = lock.withLock {
        db.withTransaction {
            if(dao.meta().isEmpty()) {
                val seed = dataJson.decodeFromString<Dataset>(context.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
                Validation.dataset(seed)
                persist(seed.copy(kind="FULL_BACKUP",meta=Meta()))
            }
        }
    }
    suspend fun snapshot(): Dataset = db.withTransaction { read() }
    private suspend fun read() = Dataset(
        meta=dao.meta().firstOrNull()?.let{dataJson.decodeFromString<Meta>(it.json)}?:Meta(),
        foods=dao.foods().map{dataJson.decodeFromString<Food>(it.json)},
        versions=dao.versions().map{dataJson.decodeFromString<FoodVersion>(it.json)},
        meals=dao.meals().map{dataJson.decodeFromString<Meal>(it.json)},
        days=dao.days().map{dataJson.decodeFromString<DayLog>(it.json)},
        bodies=dao.bodies().map{dataJson.decodeFromString<BodyRecord>(it.json)},
        plans=dao.plans().map{dataJson.decodeFromString<TargetPlan>(it.json)},
        targets=dao.targets().map{dataJson.decodeFromString<DayTarget>(it.json)},
        origins=dao.origins().map{dataJson.decodeFromString<ImportOrigin>(it.json)}
    )
    private suspend fun persist(d: Dataset) {
        d.foods.forEach { dao.put(FoodRow(it.id,dataJson.encodeToString(it))) }
        d.versions.forEach { dao.put(VersionRow(it.id,dataJson.encodeToString(it))) }
        val ids = d.meals.map{it.id}.toSet()
        dao.meals().filter{it.id !in ids}.forEach{dao.deleteMeal(it.id)}
        d.meals.forEach { dao.put(MealRow(it.id,dataJson.encodeToString(it))) }
        d.days.forEach { dao.put(DayRow(it.date,dataJson.encodeToString(it))) }
        d.bodies.forEach { dao.put(BodyRow(it.date,dataJson.encodeToString(it))) }
        d.plans.forEach { dao.put(PlanRow(it.id,dataJson.encodeToString(it))) }
        d.targets.forEach { dao.put(TargetRow(it.date,dataJson.encodeToString(it))) }
        dao.clearOrigins()
        d.origins.forEach { dao.put(OriginRow(it.namespace,it.key,dataJson.encodeToString(it))) }
        dao.put(MetaRow(json=dataJson.encodeToString(d.meta.copy(revision=d.meta.revision+1))))
    }
    private suspend fun mutate(block: (Dataset)->Dataset) = lock.withLock {
        db.withTransaction { val next=block(read()); Validation.dataset(next); persist(next) }
    }
    suspend fun food(v: FoodVersion, existing: Food? = null) {
        Validation.food(v)
        mutate { d ->
            require(d.versions.none{it.id==v.id}) { "版本已存在" }
            val food= existing?.let { d.foods.first{f->f.id==it.id}.copy(activeVersionId=v.id,updatedAt=now()) } ?: Food(id=v.foodId,activeVersionId=v.id)
            d.copy(foods=d.foods.filterNot{it.id==food.id}+food,versions=d.versions+v)
        }
    }
    suspend fun flagFood(id: String, favorite: Boolean? = null, archived: Boolean? = null) = mutate { d ->
        d.copy(foods=d.foods.map{if(it.id==id) it.copy(favorite=favorite?:it.favorite,archived=archived?:it.archived,updatedAt=now()) else it})
    }
    suspend fun meal(meal: Meal, post: Boolean) = mutate { d ->
        val clean=meal.copy(status=if(post) "POSTED" else "DRAFT", items=meal.items.filter { it.quantity==null || decimal(it.quantity)>java.math.BigDecimal.ZERO },updatedAt=now())
        require(!post || clean.items.isNotEmpty()) { "请至少添加一种非零食品" }
        val saved=if(post) clean.copy(id=meal.replacesMealId?:meal.id,replacesMealId=null) else clean
        val remove=setOf(meal.id,saved.id)
        d.copy(meals=d.meals.filterNot{it.id in remove}+saved)
    }
    suspend fun deleteMeal(id: String) = mutate { d ->
        val removed=d.meals.filter{it.id==id || it.replacesMealId==id}.map{it.id}.toSet()
        d.copy(meals=d.meals.filterNot{it.id in removed},origins=d.origins.filterNot{it.entityId in removed})
    }
    suspend fun undoMeal(meal: Meal) = mutate { it.copy(meals=it.meals.filterNot{m->m.id==meal.id}+meal) }
    suspend fun day(day: DayLog, body: BodyRecord? = null, recalculateTarget: Boolean = false) = mutate { d ->
        val plan=d.plans.filter{it.effectiveFrom<=day.date}.maxWithOrNull(compareBy<TargetPlan>{it.effectiveFrom}.thenBy{it.createdAt})
        val shouldFix=day.training!="UNKNOWN" && plan!=null && (recalculateTarget || d.targets.none{it.date==day.date})
        d.copy(days=d.days.filterNot{it.date==day.date}+day,
            bodies=if(body==null) d.bodies else d.bodies.filterNot{it.date==body.date}+body,
            targets=if(shouldFix) d.targets.filterNot{it.date==day.date}+NutritionCalculator.target(plan!!,day.date,day.training) else d.targets)
    }
    suspend fun ensureTarget(date: String) {
        val d=snapshot(); val day=d.days.find{it.date==date}?:return
        if(d.targets.none{it.date==date} && day.training!="UNKNOWN" && d.plans.any{it.effectiveFrom<=date}) day(day)
    }
    suspend fun plan(plan: TargetPlan) = mutate { d -> d.copy(plans=d.plans+plan) }
    suspend fun profile(profile: Profile, timezone: String) = mutate { it.copy(meta=it.meta.copy(profile=profile,timezone=timezone)) }
    suspend fun markExport() = mutate { it.copy(meta=it.meta.copy(lastExport=now())) }
    fun parse(text: String): Dataset { require(text.length<=30_000_000) { "文件过大（限30MB）" }; return dataJson.decodeFromString<Dataset>(text).also(Validation::dataset) }
    suspend fun export(library: Boolean, selected: Set<String> = emptySet()): String {
        val d=snapshot()
        val out=if(library) {
            val foods=d.foods.filter{it.id in selected}
            // Include only current selected labels, with no personal identity or usage history.
            Dataset(kind="FOOD_LIBRARY",foods=foods.map{it.copy(favorite=false)},versions=d.versions.filter{v->foods.any{it.activeVersionId==v.id}})
        } else d.copy(exportedAt=now())
        Validation.dataset(out)
        return dataJson.encodeToString(out)
    }
    suspend fun recoveryBackup(): File = withContext(Dispatchers.IO) {
        val dir=File(context.filesDir,"recovery").apply{mkdirs()}
        File(dir,"before-restore-${Instant.now().toEpochMilli()}.json").also{ it.writeText(dataJson.encodeToString(snapshot())) }
    }
    suspend fun restore(d: Dataset): File = lock.withLock {
        require(d.kind=="FULL_BACKUP"); Validation.dataset(d)
        val recovery = recoveryBackup()
        db.withTransaction {
            dao.clearFoods();dao.clearVersions();dao.clearMeals();dao.clearDays();dao.clearBodies();dao.clearPlans();dao.clearTargets();dao.clearOrigins()
            persist(d)
        }
        recovery
    }
    fun latestRecovery(): File? = File(context.filesDir,"recovery").listFiles()?.maxByOrNull{it.lastModified()}
    fun conflicts(current: Dataset, incoming: Dataset): Set<String> {
        val old=current.origins.associateBy{it.namespace+":"+it.key}
        val changedEntities=incoming.origins.filter { o-> old[o.namespace+":"+o.key]?.let { it.fingerprint!=o.fingerprint }?:false }.map{it.entityId}.toSet()
        val dates= mutableSetOf<String>()
        incoming.meals.forEach { m ->
            val origins=incoming.origins.filter{it.entityId==m.id}
            val already=origins.isNotEmpty() && origins.all{old[it.namespace+":"+it.key]?.fingerprint==it.fingerprint}
            if(m.id in changedEntities || (!already && current.meals.any{it.date==m.date && it.status=="POSTED"})) dates+=m.date
        }
        incoming.bodies.forEach { b->
            val origins=incoming.origins.filter{it.entityId==b.date && it.key.startsWith("body/")}
            if(current.bodies.any{it.date==b.date} && origins.any{old[it.namespace+":"+it.key]?.fingerprint!=it.fingerprint}) dates+=b.date
        }
        incoming.days.forEach { day ->
            val origins=incoming.origins.filter{it.entityId==day.date && it.key.startsWith("day/")}
            if(current.days.any{it.date==day.date} && origins.any{old[it.namespace+":"+it.key]?.fingerprint!=it.fingerprint}) dates+=day.date
        }
        return dates
    }
    suspend fun merge(incoming: Dataset, replaceDays: Set<String> = emptySet(), includePlans: Boolean = false) = mutate { d ->
        require(incoming.kind in listOf("LEGACY_IMPORT","FOOD_LIBRARY")); Validation.dataset(incoming)
        val conflicts=conflicts(d,incoming)
        val old=d.origins.associateBy{it.namespace+":"+it.key}
        fun importEntity(id: String, date: String): Boolean {
            if(date in replaceDays) return true
            if(date in conflicts) return false
            val origins=incoming.origins.filter{it.entityId==id}
            return origins.isEmpty() || origins.any { old[it.namespace+":"+it.key] == null }
        }
        val meals=incoming.meals.filter{importEntity(it.id,it.date)}
        val bodies=incoming.bodies.filter{importEntity(it.date,it.date)}
        val days=incoming.days.filter{importEntity(it.date,it.date)}
        incoming.versions.forEach { v -> require(d.versions.find{it.id==v.id}?.let{it==v}?:true) { "同ID食品版本内容冲突，已取消导入" } }
        val keptMeals=d.meals.filterNot{it.date in replaceDays}; val removed=d.meals.filter{it.date in replaceDays}.map{it.id}.toSet()
        val importedIds=(meals.map{it.id}+bodies.map{it.date}+days.map{it.date}).toSet()
        val plans=if(includePlans) incoming.plans.filter{p->d.plans.none{it.id==p.id}} else emptyList()
        d.copy(
            foods=d.foods+incoming.foods.filter{f->d.foods.none{it.id==f.id}},
            versions=d.versions+incoming.versions.filter{v->d.versions.none{it.id==v.id}},
            meals=keptMeals.filterNot{m->meals.any{it.id==m.id}}+meals,
            bodies=d.bodies.filterNot{b->bodies.any{it.date==b.date}}+bodies,
            days=d.days.filterNot{day->days.any{it.date==day.date}}+days,
            plans=d.plans+plans,
            origins=(d.origins.filterNot{it.entityId in removed || (it.entityId in replaceDays && (it.key.startsWith("body/") || it.key.startsWith("day/"))) }+incoming.origins.filter{it.entityId in importedIds || (includePlans && plans.any{p->p.id==it.entityId})}).distinctBy{it.namespace+":"+it.key}
        )
    }
}
