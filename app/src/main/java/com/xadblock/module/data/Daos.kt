package com.xadblock.module.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

const val BLOCK_EVENT_HISTORY_LIMIT = 500

/** Browsing history is capped both by age (7 days) and by row count. */
const val POST_VIEW_HISTORY_LIMIT = 2000
const val POST_VIEW_RETENTION_DAYS = 7L
const val POST_VIEW_RETENTION_MS = POST_VIEW_RETENTION_DAYS * 24 * 60 * 60 * 1000

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun all(): Flow<List<SubscriptionEntity>>

    @Query("SELECT COUNT(*) FROM subscriptions")
    fun count(): Int

    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun allOnce(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: SubscriptionEntity): Long

    @Query("UPDATE subscriptions SET lastSyncAt=:at, lastSyncStatus=:status, lastError=:error, ruleCount=:count WHERE id=:id")
    fun updateSync(id: Long, at: Long, status: String, error: String, count: Int)

    @Query("UPDATE subscriptions SET etag=:etag WHERE id=:id")
    fun updateEtag(id: Long, etag: String)

    @Delete
    fun delete(entity: SubscriptionEntity)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY sourceId, priority, id")
    fun allFlow(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled=1 ORDER BY priority, id")
    fun allEnabled(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE enabled=1 ORDER BY priority, id")
    fun allEnabledFlow(): Flow<List<RuleEntity>>

    @Query("SELECT COUNT(*) FROM rules WHERE enabled=1 AND sourceId=:sourceId")
    fun countEnabledFor(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM rules WHERE sourceId=:sourceId")
    fun countFor(sourceId: String): Int

    @Query("SELECT * FROM rules WHERE sourceId=:sourceId")
    fun allFor(sourceId: String): List<RuleEntity>

    @Query("DELETE FROM rules WHERE sourceId=:sourceId")
    fun deleteBySource(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<RuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: RuleEntity): Long

    @Query("UPDATE rules SET enabled=:enabled WHERE id=:id")
    fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE id=:id")
    fun delete(id: Long)

    @Transaction
    fun replaceBySource(sourceId: String, entities: List<RuleEntity>) {
        deleteBySource(sourceId)
        insertAll(entities)
    }
}

@Dao
interface BlockEventDao {
    @Query(
        """SELECT * FROM block_events
           WHERE postId IS NULL OR id IN (
               SELECT MAX(id) FROM block_events WHERE postId IS NOT NULL GROUP BY postId
           )
           ORDER BY id DESC LIMIT :limit"""
    )
    fun all(limit: Int): Flow<List<BlockEventEntity>>

    @Query(
        """SELECT * FROM block_events
           WHERE postId IS NULL OR id IN (
               SELECT MAX(id) FROM block_events WHERE postId IS NOT NULL GROUP BY postId
           )
           ORDER BY id DESC LIMIT :limit"""
    )
    fun recent(limit: Int): Flow<List<BlockEventEntity>>

    @Insert
    fun insert(entity: BlockEventEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM block_events WHERE postId=:postId LIMIT 1)")
    fun containsPost(postId: String): Boolean

    @Query("DELETE FROM block_events WHERE postId IS NOT NULL AND id NOT IN (SELECT MAX(id) FROM block_events WHERE postId IS NOT NULL GROUP BY postId)")
    fun removeDuplicatePosts()

    @Query("DELETE FROM block_events WHERE id NOT IN (SELECT id FROM block_events ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)

    @Query("DELETE FROM block_events")
    fun clear()

    @Query("SELECT COUNT(*) FROM block_events")
    fun countAll(): Int
}

@Dao
interface PostViewDao {
    @Query("SELECT * FROM post_views ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PostViewEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PostViewEntity)

    @Query("DELETE FROM post_views WHERE postId=:postId")
    fun delete(postId: String)

    @Query("DELETE FROM post_views WHERE ts < :cutoff")
    fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM post_views WHERE postId NOT IN (SELECT postId FROM post_views ORDER BY ts DESC LIMIT :keep)")
    fun trim(keep: Int)

    @Query("DELETE FROM post_views")
    fun clear()

    @Query("SELECT COUNT(*) FROM post_views")
    fun countAll(): Int

    @Query("SELECT COUNT(*) FROM post_views")
    fun countFlow(): Flow<Int>
}

@Dao
interface HeartbeatDao {
    @Insert
    fun insert(entity: HeartbeatEntity)

    @Query("SELECT * FROM heartbeats ORDER BY id DESC LIMIT 1")
    fun latest(): Flow<HeartbeatEntity?>

    @Query("SELECT * FROM heartbeats ORDER BY id DESC LIMIT 1")
    fun latestOnce(): HeartbeatEntity?

    @Query("DELETE FROM heartbeats")
    fun deleteAll()

    @Query("DELETE FROM heartbeats WHERE id NOT IN (SELECT id FROM heartbeats ORDER BY id DESC LIMIT :keep)")
    fun trim(keep: Int)
}
