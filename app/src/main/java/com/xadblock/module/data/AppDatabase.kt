package com.xadblock.module.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        RuleEntity::class,
        BlockEventEntity::class,
        HeartbeatEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun ruleDao(): RuleDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun heartbeatDao(): HeartbeatDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xadblock.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_events ADD COLUMN matchedRule TEXT")
                database.execSQL("ALTER TABLE block_events ADD COLUMN author TEXT")
            }
        }
    }
}
