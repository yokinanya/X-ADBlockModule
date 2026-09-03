package com.xadblock.module.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SubscriptionEntity::class,
        RuleEntity::class,
        BlockEventEntity::class,
        HeartbeatEntity::class
    ],
    version = 1,
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
                ).build().also { instance = it }
            }
        }
    }
}
