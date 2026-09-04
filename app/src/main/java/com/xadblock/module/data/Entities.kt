package com.xadblock.module.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val secondaryUrl: String? = null,
    val enabled: Boolean = true,
    val lastSyncAt: Long = 0,
    val lastSyncStatus: String = "",
    val lastError: String = "",
    val ruleCount: Int = 0,
    val etag: String = ""
)

@Entity(
    tableName = "rules",
    indices = [Index(value = ["sourceId", "kind", "pattern"], unique = true)]
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val kind: String,
    val pattern: String,
    val priority: Int = 80,
    val enabled: Boolean = true
)

@Entity(tableName = "block_events")
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val sourceId: String,
    val preview: String,
    val postId: String?,
    val matchedRule: String? = null,
    val author: String? = null
)

@Entity(tableName = "heartbeats")
data class HeartbeatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val process: String,
    val status: String,
    val snapshotVersion: Long,
    val targetVersion: String
)
