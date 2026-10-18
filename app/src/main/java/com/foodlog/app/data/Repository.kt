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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.decodeFromStream
import java.io.*
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

const val MAX_BACKUP_BYTES: Long = 256L * 1024 * 1024
private class LimitedCountingOutput : OutputStream() {
    private var count=0L
    override fun write(b: Int) { add(1) }
    override fun write(b: ByteArray, off: Int, len: Int) { add(len) }
    private fun add(size: Int) {
        count+=size
        require(count<=MAX_BACKUP_BYTES) { "备份解压后不能超过256MiB，请先减少数据或分开导出食品库" }
    }
}
private class LimitedInput(input: InputStream) : FilterInputStream(input) {
    private var count=0L
    override fun read(): Int = super.read().also { if(it>=0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b,off,len).also { if(it>0) add(it) }
    private fun add(size: Int) {
        count+=size
        require(count<=MAX_BACKUP_BYTES) { "备份解压后不能超过256MiB" }
    }
}

class Repository(private val context: Context, val db: FoodLogDatabase) {
    private val dao = db.dao()
    private val lock = Mutex()
    @Volatile private var cached: Dataset? = null
    val state = dao.changes().map { snapshot() }
    private suspend fun <T> transaction(block: suspend () -> T): T = try {
        db.withTransaction { block() }
    } catch (error: Throwable) {
        cached=null
        throw error
    }

    suspend fun initialize() = lock.withLock {
        transaction {
            if(dao.meta().isEmpty()) {
                val seed = dataJson.decodeFromString<Dataset>(context.assets.open("foods_seed_v1.json").bufferedReader().use { it.readText() })
                Validation.dataset(seed)
                persist(seed.copy(kind="FULL_BACKUP",meta=Meta()))
            }
        }
    }
    suspend fun snapshot(): Dataset = transaction {
        val meta=dao.meta().firstOrNull()?.let { dataJson.decodeFromString<Meta>(it.json) }
        cached?.takeIf { it.meta==meta } ?: read().also { cached=it }
    }
    private suspend fun read() = Dataset(
        meta=dao.meta().firstOrNull()?.let{dataJson.decodeFromString<Meta>(it.json)}?:Meta(),
        foods=dao.foods().map{dataJson.decodeFromString<Food>(it.json)},
        versions=dao.versions().map{dataJson.decodeFromString<FoodVersion>(it.json)},
        meals=dao.meals().map{dataJson.decodeFromString<Meal>(it.json)},
        days=dao.days().map{dataJson.decodeFromString<DayLog>(it.json)},
        bodies=dao.bodies().map{dataJson.decodeFromString<BodyRecord>(it.json)},
        plans=dao.plans().map{dataJson.decodeFromString<TargetPlan>(it.json)},
        targets=dao.targets().map{dataJson.decodeFromString<DayTarget>(it.json)},
        origins=dao.origins().map{dataJson.decodeFromString<ImportOrigin>(it.json)},
        templates=dao.templates().map{dataJson.decodeFromString<MealTemplate>(it.json)}
    )
    private suspend fun persist(d: Dataset, old: Dataset? = null) {
        val oldFoods=old?.foods?.associateBy { it.id }.orEmpty()
        val oldVersions=old?.versions?.associateBy { it.id }.orEmpty()
        val oldMeals=old?.meals?.associateBy { it.id }.orEmpty()
        val oldDays=old?.days?.associateBy { it.date }.orEmpty()
        val oldBodies=old?.bodies?.associateBy { it.date }.orEmpty()
        val oldPlans=old?.plans?.associateBy { it.id }.orEmpty()
        val oldTargets=old?.targets?.associateBy { it.date }.orEmpty()
        val oldOrigins=old?.origins?.associateBy { it.namespace to it.key }.orEmpty()
        val oldTemplates=old?.templates?.associateBy { it.id }.orEmpty()
        d.foods.filter { oldFoods[it.id] != it }.forEach { dao.put(FoodRow(it.id,dataJson.encodeToString(it))) }
        d.versions.filter { oldVersions[it.id] != it }.forEach { dao.put(VersionRow(it.id,dataJson.encodeToString(it))) }
        val ids = d.meals.map{it.id}.toSet()
        oldMeals.keys.filter { it !in ids }.forEach { dao.deleteMeal(it) }
        d.meals.filter { oldMeals[it.id] != it }.forEach { dao.put(MealRow(it.id,dataJson.encodeToString(it))) }
        d.days.filter { oldDays[it.date] != it }.forEach { dao.put(DayRow(it.date,dataJson.encodeToString(it))) }
        d.bodies.filter { oldBodies[it.date] != it }.forEach { dao.put(BodyRow(it.date,dataJson.encodeToString(it))) }
        d.plans.filter { oldPlans[it.id] != it }.forEach { dao.put(PlanRow(it.id,dataJson.encodeToString(it))) }
        val targetDates=d.targets.map { it.date }.toSet()
        oldTargets.keys.filter { it !in targetDates }.forEach { dao.deleteTarget(it) }
        d.targets.filter { oldTargets[it.date] != it }.forEach { dao.put(TargetRow(it.date,dataJson.encodeToString(it))) }
        val originKeys=d.origins.map { it.namespace to it.key }.toSet()
        oldOrigins.keys.filter { it !in originKeys }.forEach { dao.deleteOrigin(it.first,it.second) }
        d.origins.filter { oldOrigins[it.namespace to it.key] != it }.forEach { dao.put(OriginRow(it.namespace,it.key,dataJson.encodeToString(it))) }
        val templateIds=d.templates.map { it.id }.toSet()
        oldTemplates.keys.filter { it !in templateIds }.forEach { dao.deleteTemplate(it) }
        d.templates.filter { oldTemplates[it.id] != it }.forEach { dao.put(TemplateRow(it.id,dataJson.encodeToString(it))) }
        val meta=d.meta.copy(revision=d.meta.revision+1)
        dao.put(MetaRow(json=dataJson.encodeToString(meta)))
        cached=d.copy(schemaVersion=3,appVersion="1.2.0",meta=meta)
    }
    private suspend fun mutate(block: (Dataset)->Dataset) = lock.withLock {
        transaction { val old=read(); val next=block(old); Validation.dataset(next); if(next!=old) persist(next,old) }
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
    private fun completedItems(items: List<MealItem>): List<MealItem> = items.map { i ->
        i.draftQuantityText?.let { raw ->
            require(runCatching { decimal(raw) }.isSuccess) { "请填写完整的份量后保存已吃" }
            NutritionCalculator.item(i.snapshot,raw,i.unit,i.id,i.quantityStatus=="ESTIMATED")
        } ?: i
    }.filter { it.quantity == null || decimal(it.quantity)>java.math.BigDecimal.ZERO }

    // Draft autosaves touch only the current meal and its referenced labels, independent of history size.
    suspend fun meal(meal: Meal, post: Boolean) = lock.withLock {
        transaction {
            val clean=meal.copy(status=if(post) "POSTED" else "DRAFT",
                items=if(post) completedItems(meal.items) else meal.items.filter { it.quantity==null || decimal(it.quantity)>java.math.BigDecimal.ZERO },updatedAt=now())
            require(!post || clean.items.isNotEmpty()) { "请至少添加一种非零食品" }
            val parent=meal.replacesMealId?.let { dao.meal(it)?.let { row -> dataJson.decodeFromString<Meal>(row.json) } }
            if(meal.replacesMealId!=null) require(parent?.status=="POSTED") { "草稿引用断裂" }
            val saved=if(post) clean.copy(id=meal.replacesMealId?:meal.id,replacesMealId=null) else clean
            val versions=dao.versions(saved.items.map { it.foodVersionId }.distinct()).map { dataJson.decodeFromString<FoodVersion>(it.json) }.associateBy { it.id }
            Validation.meal(saved,versions,setOfNotNull(parent?.id))
            if(meal.id!=saved.id) {
                dao.deleteMeal(meal.id)
                dao.origins().map { dataJson.decodeFromString<ImportOrigin>(it.json) }.filter { it.entityId==meal.id }.forEach { dao.deleteOrigin(it.namespace,it.key) }
            }
            dao.put(MealRow(saved.id,dataJson.encodeToString(saved)))
            bumpRevision { d -> d.copy(meals=d.meals.filterNot { it.id==meal.id || it.id==saved.id }+saved,
                origins=if(meal.id==saved.id) d.origins else d.origins.filterNot { it.entityId==meal.id }) }
        }
    }
    private suspend fun bumpRevision(change: (Dataset)->Dataset = { it }) {
        val meta=dao.meta().firstOrNull()?.let { dataJson.decodeFromString<Meta>(it.json) }?:Meta()
        val next=meta.copy(revision=meta.revision+1)
        dao.put(MetaRow(json=dataJson.encodeToString(next)))
        cached=cached?.takeIf { it.meta==meta }?.let { change(it).copy(meta=next) }
    }
    suspend fun deleteMeal(id: String): DeletedMealBundle = lock.withLock {
        transaction {
            val d=read(); val meal=d.meals.first { it.id==id }
            val drafts=d.meals.filter { it.replacesMealId==id }
            val removed=(drafts.map { it.id }+id).toSet()
            val bundle=DeletedMealBundle(meal,drafts,d.origins.filter { it.entityId in removed })
            val next=d.copy(meals=d.meals.filterNot { it.id in removed },origins=d.origins.filterNot { it.entityId in removed })
            Validation.dataset(next); persist(next,d); bundle
        }
    }
    suspend fun discardDraft(id: String) = lock.withLock {
        transaction {
            val draft=dao.meal(id)?.let { dataJson.decodeFromString<Meal>(it.json) }?:return@transaction
            if(draft.status!="DRAFT") return@transaction
            dao.deleteMeal(id)
            dao.origins().map { dataJson.decodeFromString<ImportOrigin>(it.json) }.filter { it.entityId==id }
                .forEach { dao.deleteOrigin(it.namespace,it.key) }
            bumpRevision { it.copy(meals=it.meals.filterNot { meal -> meal.id==id },origins=it.origins.filterNot { origin -> origin.entityId==id }) }
        }
    }
    suspend fun undoDeletion(bundle: DeletedMealBundle) = mutate { d ->
        val meals=bundle.drafts+bundle.meal; val ids=meals.map { it.id }.toSet()
        val keys=bundle.origins.map { it.namespace to it.key }.toSet()
        require(d.meals.none { it.id in ids }) { "这些餐食已恢复，请勿重复撤销" }
        d.copy(meals=d.meals+meals,origins=d.origins.filterNot { (it.namespace to it.key) in keys }+bundle.origins)
    }
    suspend fun undoMeal(meal: Meal) = mutate { it.copy(meals=it.meals.filterNot{m->m.id==meal.id}+meal) }
    suspend fun updateLoggingStatus(date: String, status: String) = mutate { d ->
        require(status in listOf("UNKNOWN","PARTIAL","COMPLETE")) { "记录完整性无效" }
        val day=d.days.find { it.date==date }?:DayLog(date)
        d.copy(days=d.days.filterNot { it.date==date }+day.copy(loggingStatus=status))
    }
    suspend fun day(day: DayLog, body: BodyRecord? = null, recalculateTarget: Boolean = false) = mutate { d ->
        // A body/training editor may hold an older copy; changing exercise must not clear a diet label.
        val saved=day.copy(dietDayType=d.days.find { it.date==day.date }?.dietDayType ?: day.dietDayType)
        val shouldFix=recalculateTarget || d.targets.none { it.date==day.date }
        val target=if(shouldFix) NutritionCalculator.targetOrNull(d.planFor(day.date),day.date,effectiveDietDayType(saved)) else null
        d.copy(days=d.days.filterNot{it.date==day.date}+saved,
            bodies=if(body==null) d.bodies else d.bodies.filterNot{it.date==body.date}+body,
            targets=if(shouldFix) d.targets.filterNot { it.date==day.date }+listOfNotNull(target) else d.targets)
    }
    /** Selecting the same label is idempotent and retains the frozen historical reference. */
    suspend fun selectDietDayType(date: String, type: String): DayTarget? {
        require(type in dietDayTypeLabels) { "饮食日型无效" }
        var selected: DayTarget?=null
        mutate { d ->
            val day=d.days.find { it.date==date }?:DayLog(date)
            val old=d.targets.find { it.date==date }
            val oldType=old?.dayType?.let { if(it in listOf("STRENGTH","BADMINTON","BOTH")) "TRAINING" else it }
            selected=if(effectiveDietDayType(day)==type && oldType==type) old
                else NutritionCalculator.targetOrNull(d.planFor(date),date,type)
            d.copy(days=d.days.filterNot { it.date==date }+day.copy(dietDayType=type),
                targets=d.targets.filterNot { it.date==date }+listOfNotNull(selected))
        }
        return selected
    }
    /** Explicitly reapply the plan effective on this one date; all other snapshots remain frozen. */
    suspend fun recalculateTarget(date: String, type: String? = null): DayTarget? {
        require(type==null || type in dietDayTypeLabels) { "饮食日型无效" }
        var target: DayTarget?=null
        mutate { d ->
            val day=d.days.find { it.date==date }?:DayLog(date)
            val selected=if(type==null) day else day.copy(dietDayType=type)
            target=NutritionCalculator.targetOrNull(d.planFor(date),date,effectiveDietDayType(selected))
            d.copy(days=if(type==null) d.days else d.days.filterNot { it.date==date }+selected,
                targets=d.targets.filterNot { it.date==date }+listOfNotNull(target))
        }
        return target
    }
    suspend fun ensureTarget(date: String) = lock.withLock {
        transaction {
            if(dao.target(date)!=null) return@transaction
            val day=dao.day(date)?.let { dataJson.decodeFromString<DayLog>(it.json) }?:return@transaction
            val type=effectiveDietDayType(day)?:return@transaction
            val plan=dao.plans().map { dataJson.decodeFromString<TargetPlan>(it.json) }.filter { it.effectiveFrom<=date }
                .maxWithOrNull(compareBy<TargetPlan>{it.effectiveFrom}.thenBy{it.createdAt})?:return@transaction
            val target=NutritionCalculator.targetOrNull(plan,date,type)?:return@transaction
            dao.put(TargetRow(date,dataJson.encodeToString(target)))
            bumpRevision { it.copy(targets=it.targets.filterNot { old -> old.date==date }+target) }
        }
    }
    suspend fun plan(plan: TargetPlan) = mutate { d -> d.copy(plans=d.plans+plan) }
    suspend fun profile(profile: Profile, timezone: String) = mutate { it.copy(meta=it.meta.copy(profile=profile,timezone=timezone)) }
    suspend fun markExport() = mutate { it.copy(meta=it.meta.copy(lastExport=now())) }
    suspend fun saveTemplate(name: String, meal: Meal): MealTemplate {
        val template=MealTemplate(name=name.trim(),type=meal.type,items=completedItems(meal.items).map { it.copy(id=uid()) })
        mutate { d -> d.copy(templates=d.templates+template) }
        return template
    }
    suspend fun deleteTemplate(id: String) = mutate { d -> d.copy(templates=d.templates.filterNot { it.id==id }) }
    suspend fun applyTemplate(id: String, date: String): Meal {
        lateinit var draft: Meal
        mutate { d ->
            val template=d.templates.first { it.id==id }
            draft=Meal(date=date,type=template.type,items=template.items.map { it.copy(id=uid(),draftQuantityText=null) },note="来自常用餐：${template.name}")
            d.copy(meals=d.meals+draft)
        }
        return draft
    }
    suspend fun copyPreviousDay(date: String): List<Meal> {
        val previous=LocalDate.parse(date).minusDays(1).toString()
        var copies=emptyList<Meal>()
        mutate { d ->
            copies=d.meals.filter { it.date==previous && it.status=="POSTED" && it.items.isNotEmpty() }
                .map { source -> Meal(date=date,type=source.type,items=source.items.map { it.copy(id=uid(),draftQuantityText=null) },
                    note="复制自 $previous"+(if(source.note.isBlank()) "" else "\n${source.note}")) }
            d.copy(meals=d.meals+copies)
        }
        return copies
    }

    private fun upgraded(d: Dataset): Dataset {
        Validation.dataset(d)
        return d.copy(schemaVersion=3,appVersion="1.2.0")
    }
    fun parse(text: String): Dataset {
        require(text.length.toLong()<=MAX_BACKUP_BYTES) { "备份解压后不能超过256MiB" }
        return decodeBackup(text.byteInputStream())
    }
    private suspend fun exportDataset(library: Boolean, selected: Set<String>): Dataset {
        val d=snapshot()
        val out=if(library) {
            val foods=d.foods.filter{it.id in selected}
            // Include only current selected labels, with no personal identity or usage history.
            Dataset(kind="FOOD_LIBRARY",foods=foods.map{it.copy(favorite=false)},versions=d.versions.filter{v->foods.any{it.activeVersionId==v.id}})
        } else d.copy(exportedAt=now())
        Validation.dataset(out)
        return out
    }
    suspend fun export(library: Boolean, selected: Set<String> = emptySet()): String = withContext(Dispatchers.IO) {
        val out=exportDataset(library,selected)
        checkBackupSize(out)
        dataJson.encodeToString(out)
    }
    @OptIn(ExperimentalSerializationApi::class)
    private fun checkBackupSize(d: Dataset) {
        dataJson.encodeToStream(d,LimitedCountingOutput())
    }
    /** JSON is streamed through gzip. Size is checked before emitting any bytes to the destination. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun writeBackup(output: OutputStream, library: Boolean = false, selected: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        val out=exportDataset(library,selected)
        checkBackupSize(out)
        GZIPOutputStream(output).use { dataJson.encodeToStream(out,it) }
    }
    suspend fun readBackup(input: InputStream): Dataset = withContext(Dispatchers.IO) { decodeBackup(input) }
    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeBackup(input: InputStream): Dataset {
        val buffered=if(input is BufferedInputStream) input else input.buffered()
        buffered.mark(2)
        val gzip=buffered.read()==0x1f && buffered.read()==0x8b
        buffered.reset()
        val decoded=if(gzip) GZIPInputStream(buffered) else buffered
        return LimitedInput(decoded).use { upgraded(dataJson.decodeFromStream<Dataset>(it)) }
    }
    suspend fun recoveryBackup(): File = withContext(Dispatchers.IO) {
        val dir=File(context.filesDir,"recovery").apply{mkdirs()}
        File(dir,"before-restore-${Instant.now().toEpochMilli()}.json.gz").also { file ->
            try { file.outputStream().use { writeBackup(it) } }
            catch(error: Exception) { file.delete(); throw error }
        }
    }
    suspend fun restore(d: Dataset): File = lock.withLock {
        require(d.kind=="FULL_BACKUP"); Validation.dataset(d)
        val recovery = recoveryBackup()
        transaction {
            dao.clearFoods();dao.clearVersions();dao.clearMeals();dao.clearDays();dao.clearBodies();dao.clearPlans();dao.clearTargets();dao.clearOrigins();dao.clearTemplates()
            persist(d.copy(schemaVersion=3,appVersion="1.2.0"))
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
