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
        HeartbeatEntity::class,
        PostViewEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun ruleDao(): RuleDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun heartbeatDao(): HeartbeatDao
    abstract fun postViewDao(): PostViewDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xadblock.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE block_events ADD COLUMN matchedRule TEXT")
                database.execSQL("ALTER TABLE block_events ADD COLUMN author TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `post_views` (" +
                        "`postId` TEXT NOT NULL, `url` TEXT NOT NULL, `author` TEXT NOT NULL, " +
                        "`authorName` TEXT NOT NULL, `preview` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`postId`))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_post_views_ts` ON `post_views` (`ts`)")
            }
        }
    }
}
