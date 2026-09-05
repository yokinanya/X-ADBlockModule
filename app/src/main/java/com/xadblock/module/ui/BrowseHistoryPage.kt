package com.xadblock.module.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.Contract
import com.xadblock.module.data.PostViewEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browsing history: every post opened in X, newest first. The search box stays pinned at
 * the top; tapping a card reopens the post in X (browser as fallback).
 */
@Composable
internal fun BrowseHistoryPage(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val query by viewModel.browseQuery.collectAsState()
    val results by viewModel.browseResults.collectAsState()
    val stored by viewModel.browseHistory.collectAsState()
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        BrowseSearchField(query, viewModel::setBrowseQuery)
        when {
            stored.isEmpty() -> BrowseEmptyState(
                title = "暂无浏览记录",
                hint = "在 X 中点开帖子后会自动记录，最多保留 7 天"
            )
            results.isEmpty() -> BrowseEmptyState(
                title = "没有匹配的记录",
                hint = "换个关键字试试，搜索会匹配正文、用户名和链接"
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results, key = { it.postId }) { entry ->
                    BrowseHistoryCard(
                        entry = entry,
                        onOpen = {
                            openPost(context, entry.url).onFailure { failure ->
                                viewModel.reportFailure("无法打开链接", failure)
                            }
                        },
                        onCopy = {
                            copyLink(context, entry.url)
                            viewModel.notifyInfo("链接已复制")
                        },
                        onDelete = { viewModel.deleteBrowseEntry(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        singleLine = true,
        label = { Text("搜索浏览历史") },
        placeholder = { Text("帖子正文、用户名或链接") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                }
            }
        }
    )
}

@Composable
private fun BrowseHistoryCard(
    entry: PostViewEntity,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorLabel(entry),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatViewTime(entry.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                entry.preview.ifBlank { "（无正文）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.url,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制链接")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "删除这条记录")
                }
            }
        }
    }
}

@Composable
private fun BrowseEmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun authorLabel(entry: PostViewEntity): String {
    val name = entry.authorName.trim()
    val handle = entry.author.trim()
    return when {
        handle.isEmpty() -> name.ifEmpty { "未知用户" }
        name.isEmpty() || name == handle || name == "@" + handle -> "@" + handle
        else -> name + " @" + handle
    }
}

/** Opens the post in X when installed, otherwise hands the link to the browser. */
private fun openPost(context: Context, url: String): Result<Unit> = runCatching {
    val uri = Uri.parse(url)
    val inApp = Intent(Intent.ACTION_VIEW, uri)
        .setPackage(Contract.TARGET_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(inApp)
    } catch (missing: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("X post", url))
}

private fun formatViewTime(timestamp: Long): String {
    if (timestamp <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
