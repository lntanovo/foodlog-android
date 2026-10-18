package com.foodlog.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Each aggregate is an independent row. Immutable versions and frozen meal items are
// versioned JSON values, never reconstructed through a join to the current food label.
@Entity(tableName = "foods") data class FoodRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "food_versions") data class VersionRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "meals") data class MealRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "day_logs") data class DayRow(@PrimaryKey val date: String, val json: String)
@Entity(tableName = "body_records") data class BodyRow(@PrimaryKey val date: String, val json: String)
@Entity(tableName = "target_plans") data class PlanRow(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "day_targets") data class TargetRow(@PrimaryKey val date: String, val json: String)
@Entity(tableName = "import_origins", primaryKeys = ["namespace", "sourceKey"]) data class OriginRow(val namespace: String, val sourceKey: String, val json: String)
@Entity(tableName = "metadata") data class MetaRow(@PrimaryKey val id: Int = 1, val json: String)

@Dao interface FoodLogDao {
    @Query("SELECT * FROM metadata") fun changes(): Flow<List<MetaRow>>
    @Query("SELECT * FROM metadata") suspend fun meta(): List<MetaRow>
    @Query("SELECT * FROM foods") suspend fun foods(): List<FoodRow>
    @Query("SELECT * FROM food_versions") suspend fun versions(): List<VersionRow>
    @Query("SELECT * FROM meals") suspend fun meals(): List<MealRow>
    @Query("SELECT * FROM day_logs") suspend fun days(): List<DayRow>
    @Query("SELECT * FROM body_records") suspend fun bodies(): List<BodyRow>
    @Query("SELECT * FROM target_plans") suspend fun plans(): List<PlanRow>
    @Query("SELECT * FROM day_targets") suspend fun targets(): List<TargetRow>
    @Query("SELECT * FROM import_origins") suspend fun origins(): List<OriginRow>
    @Upsert suspend fun put(row: FoodRow)
    @Upsert suspend fun put(row: VersionRow)
    @Upsert suspend fun put(row: MealRow)
    @Upsert suspend fun put(row: DayRow)
    @Upsert suspend fun put(row: BodyRow)
    @Upsert suspend fun put(row: PlanRow)
    @Upsert suspend fun put(row: TargetRow)
    @Upsert suspend fun put(row: OriginRow)
    @Upsert suspend fun put(row: MetaRow)
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: String)
    @Query("DELETE FROM foods") suspend fun clearFoods()
    @Query("DELETE FROM food_versions") suspend fun clearVersions()
    @Query("DELETE FROM meals") suspend fun clearMeals()
    @Query("DELETE FROM day_logs") suspend fun clearDays()
    @Query("DELETE FROM body_records") suspend fun clearBodies()
    @Query("DELETE FROM target_plans") suspend fun clearPlans()
    @Query("DELETE FROM day_targets") suspend fun clearTargets()
    @Query("DELETE FROM import_origins") suspend fun clearOrigins()
}
@Database(entities = [FoodRow::class,VersionRow::class,MealRow::class,DayRow::class,BodyRow::class,PlanRow::class,TargetRow::class,OriginRow::class,MetaRow::class], version = 1, exportSchema = true)
abstract class FoodLogDatabase: RoomDatabase() { abstract fun dao(): FoodLogDao }
