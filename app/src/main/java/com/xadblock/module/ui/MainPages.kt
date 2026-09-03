package com.xadblock.module.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.XposedServiceState
import com.xadblock.module.data.Contract
import com.xadblock.module.data.SubscriptionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MainDestinationPage(
    destination: MainDestination,
    viewModel: MainViewModel,
    serviceState: XposedServiceState,
    onDestinationChange: (MainDestination) -> Unit,
    onAddSubscription: () -> Unit,
    onImportLocal: () -> Unit,
    onShowLocalDetails: () -> Unit
) {
    when (destination) {
        MainDestination.SETTINGS -> SettingsPage(viewModel, serviceState, onDestinationChange)
        MainDestination.SUBSCRIPTIONS -> SubscriptionsPage(viewModel, onAddSubscription)
        MainDestination.LOCAL_RULES -> LocalRulesPage(viewModel, onImportLocal, onShowLocalDetails)
        MainDestination.HISTORY -> HistoryPage(viewModel)
    }
}

@Composable
private fun SubscriptionsPage(viewModel: MainViewModel, onAddSubscription: () -> Unit) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionHeader("云端词库", "同步后自动发布到目标进程") {
                Button(onClick = onAddSubscription) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
            }
        }
        if (subscriptions.isEmpty()) {
            item { EmptyState("还没有词库订阅", "添加一个 HTTPS 词库 URL 开始同步") }
        }
        items(subscriptions, key = { "subscription_${it.id}" }) { subscription ->
            SubscriptionCard(
                subscription,
                syncing,
                onSync = { viewModel.syncOne(subscription) },
                onToggle = { viewModel.setSubscriptionEnabled(subscription, it) },
                onDelete = { viewModel.deleteSubscription(subscription) }
            )
        }
    }
}

@Composable
private fun LocalRulesPage(
    viewModel: MainViewModel,
    onImportLocal: () -> Unit,
    onShowLocalDetails: () -> Unit
) {
    val localCount by viewModel.localCount.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SectionHeader("本地词库", "$localCount 条规则") }
        item {
            ElevatedCard {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("与云端词库合并生效，支持关键词、/正则/和多片段 ALL_OF。")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onImportLocal) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null)
                            Text("导入 TXT")
                        }
                        TextButton(onClick = onShowLocalDetails) { Text("查看或清空") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    syncing: Boolean,
    onSync: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subscription.url,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = subscription.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                }
            }
            Text(
                buildString {
                    if (subscription.lastSyncAt > 0) append("上次同步 ${formatTime(subscription.lastSyncAt)} · ")
                    append("规则 ${subscription.ruleCount} 条")
                },
                style = MaterialTheme.typography.labelMedium
            )
            if (subscription.lastSyncStatus == "error") {
                Text("同步失败：${subscription.lastError}", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text("立即同步")
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

private fun hotSourceName(sourceId: String): String = when {
    sourceId == "emoji" -> "emoji 规则"
    sourceId == "grok" -> "Grok 回复"
    sourceId == "special-chars" -> "特殊符号"
    sourceId == "builtin" -> "内置词库"
    sourceId == "local" -> "本地词库"
    sourceId.startsWith("sub:") -> "云端订阅"
    else -> sourceId
}