package com.martin.veillebt.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.martin.veillebt.data.local.model.BraceletEntity

@Database(entities = [BraceletEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun braceletDao(): BraceletDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "veille_bt_database"
                )
                    // .fallbackToDestructiveMigration() // À utiliser avec prudence pendant le développement
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
