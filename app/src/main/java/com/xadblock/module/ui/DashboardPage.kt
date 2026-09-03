package com.xadblock.module.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.XposedServiceState
import com.xadblock.module.data.BlockEventEntity
import com.xadblock.module.data.HeartbeatEntity
import com.xadblock.module.data.SubscriptionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DashboardPage(
    viewModel: MainViewModel,
    serviceState: XposedServiceState,
    onDestinationChange: (MainDestination) -> Unit
) {
    val heartbeat by viewModel.heartbeat.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val recentBlocks by viewModel.recentBlocks.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val blockTotal by viewModel.blockTotal.collectAsState()
    val syncing by viewModel.syncing.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StatusCard(
                heartbeat = heartbeat,
                serviceState = serviceState,
                blockTotal = blockTotal,
                recentBlocks = recentBlocks.size,
                syncing = syncing,
                onSyncNow = viewModel::syncNow
            )
        }
        item {
            Text(
                "工作区",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        item {
            ElevatedCard {
                OverviewLink(
                    icon = Icons.Filled.LibraryBooks,
                    title = "词库订阅",
                    detail = "${subscriptions.count { it.enabled }} 个启用 · ${subscriptions.size} 个来源",
                    onClick = { onDestinationChange(MainDestination.SUBSCRIPTIONS) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                OverviewLink(
                    icon = Icons.Filled.Folder,
                    title = "本地规则",
                    detail = "$localCount 条规则与云端词库合并生效",
                    onClick = { onDestinationChange(MainDestination.LOCAL_RULES) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                OverviewLink(
                    icon = Icons.Filled.FilterAlt,
                    title = "过滤策略",
                    detail = "调整占位显示、用户名和特殊内容匹配",
                    onClick = { onDestinationChange(MainDestination.SETTINGS) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                OverviewLink(
                    icon = Icons.Filled.History,
                    title = "屏蔽历史",
                    detail = "最近记录 ${recentBlocks.size} 条",
                    onClick = { onDestinationChange(MainDestination.HISTORY) }
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    heartbeat: HeartbeatEntity?,
    serviceState: XposedServiceState,
    blockTotal: Long,
    recentBlocks: Int,
    syncing: Boolean,
    onSyncNow: () -> Unit
) {
    val connected = serviceState.connected
    val statusColor = if (connected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = if (connected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Icon(
                        imageVector = if (connected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("LibXposed 服务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connected) "已连接 · API ${serviceState.apiVersion}"
                        else "未连接 · 请确认 LSPosed API 102 已启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (syncing) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    IconButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "立即同步")
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("已屏蔽", "$blockTotal+")
                Metric("最近记录", recentBlocks.toString())
            }
            heartbeat?.let {
                Text(
                    "Hook ${it.status} · ${it.process} · X ${it.targetVersion} · 快照 v${it.snapshotVersion} · ${formatTime(it.ts)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } ?: Text(
                "目标进程尚未上报心跳，请在 LSPosed 勾选 com.twitter.android 后重启 X。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            serviceState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RowScope.Metric(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverviewLink(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "打开$title")
        }
    )
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}