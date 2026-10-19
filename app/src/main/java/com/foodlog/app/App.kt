package com.foodlog.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.foodlog.app.data.*
import kotlinx.coroutines.flow.map

val Context.preferences by preferencesDataStore(name="ui_preferences")
class FoodLogApplication: Application() {
    val repository by lazy { Repository(this,Room.databaseBuilder(this,FoodLogDatabase::class.java,"foodlog.db").addMigrations(MIGRATION_1_2).build()) }
    val cloudSessions by lazy { com.foodlog.app.cloud.CloudSessionManager(this) }
}
