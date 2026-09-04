package com.xadblock.module.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.BlockEventEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HistoryDisplay(val preview: String, val matchedRule: String?)

@Composable
internal fun HistoryPage(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onAddWhitelist: (String) -> Unit = {}
) {
    val history by viewModel.allHistory.collectAsState()
    if (history.isEmpty()) {
        Column(
            modifier = modifier.padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("暂无过滤历史", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(history, key = { it.id }) { event ->
            HistoryCard(event, onAddWhitelist)
        }
    }
}

@Composable
private fun HistoryCard(event: BlockEventEntity, onAddWhitelist: (String) -> Unit) {
    val display = historyDisplay(event)
    val author = event.author?.takeIf { it.isNotBlank() }
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HistoryTag(sourceName(event.sourceId))
                Text(
                    formatTime(event.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                display.preview.ifBlank { "（空内容）" },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            display.matchedRule?.let {
                Text(
                    "匹配词条：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            author?.let {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "用户：$it",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { onAddWhitelist(it) }) {
                        Text("加入白名单")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private fun historyDisplay(event: BlockEventEntity): HistoryDisplay {
    val explicitRule = event.matchedRule?.takeIf { it.isNotBlank() }
    if (explicitRule != null) return HistoryDisplay(event.preview, explicitRule)
    val prefix = "[匹配:"
    if (!event.preview.startsWith(prefix)) return HistoryDisplay(event.preview, null)
    val end = event.preview.indexOf("] ", prefix.length)
    if (end < 0) return HistoryDisplay(event.preview, null)
    return HistoryDisplay(
        preview = event.preview.substring(end + 2),
        matchedRule = event.preview.substring(prefix.length, end).takeIf { it.isNotBlank() }
    )
}

private fun sourceName(sourceId: String): String = when {
    sourceId == "emoji" -> "Emoji 内容"
    sourceId == "grok" -> "Grok 回复"
    sourceId == "special-chars" -> "异常符号"
    sourceId == "local" -> "本地规则"
    sourceId.startsWith("sub:") -> "云端规则"
    sourceId == "builtin" -> "内置规则"
    sourceId == "?" -> "其他规则"
    else -> sourceId.ifBlank { "其他规则" }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
