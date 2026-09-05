package com.xadblock.module.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xadblock.module.XposedServiceState

@Composable
internal fun DashboardPage(
    viewModel: MainViewModel,
    serviceState: XposedServiceState,
    onOpenHistory: () -> Unit,
    onOpenBrowseHistory: () -> Unit
) {
    val history by viewModel.allHistory.collectAsState()
    val browseCount by viewModel.browseCount.collectAsState()
    val blockTotal by viewModel.blockTotal.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            WorkStatusCard(
                status = WorkStatus(
                    activated = serviceState.connected
                )
            )
        }
        item {
            ElevatedCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Metric(blockTotal.toString(), "累计屏蔽")
                    Metric(subscriptions.sumOf { it.ruleCount }.toString(), "云端规则")
                    Metric(localCount.toString(), "本地规则")
                }
            }
        }
        item { BrowseHistoryEntry(browseCount, onOpenBrowseHistory) }
        item { HistoryEntry(history.size, onOpenHistory) }
    }
}

@Composable
private fun BrowseHistoryEntry(count: Int, onClick: () -> Unit) {
    ElevatedCard {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = { Icon(Icons.Filled.Visibility, contentDescription = null) },
            headlineContent = { Text("浏览历史") },
            supportingContent = { Text("已记录 $count 条 · 保留 7 天") }
        )
    }
}

@Composable
private fun HistoryEntry(count: Int, onClick: () -> Unit) {
    ElevatedCard {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
            headlineContent = { Text("过滤历史") },
            supportingContent = { Text("最近 $count 条记录") }
        )
    }
}

private data class WorkStatus(
    val activated: Boolean
)

@Composable
private fun WorkStatusCard(status: WorkStatus) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (status.activated) {
                colors.primaryContainer
            } else {
                colors.errorContainer
            }
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = colors.surface, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (status.activated) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Error
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (status.activated) "模块已激活" else "模块未激活",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (status.activated) {
                        "LSPosed 已激活本模块"
                    } else {
                        "请在 LSPosed 中激活本模块"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RowScope.Metric(value: String, label: String) {
    Column(Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
