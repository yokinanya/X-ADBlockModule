package com.xadblock.module.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.BlockEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryPage(viewModel: MainViewModel) {
    val history by viewModel.allHistory.collectAsState()
    if (history.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("暂无屏蔽记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "命中规则后，最近的记录会显示在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(history, key = { "event_${it.id}" }) { event -> HistoryCard(event) }
    }
}

@Composable
private fun HistoryCard(event: BlockEventEntity) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(event.preview.ifBlank { "（空）" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                "${hotSourceName(event.sourceId)} · ${formatTime(event.ts)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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