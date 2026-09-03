package com.xadblock.module.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xadblock.module.data.SubscriptionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XADBlockTheme {
                val viewModel: MainViewModel = viewModel()
                XADBlockScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XADBlockScreen(viewModel: MainViewModel) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val heartbeat by viewModel.heartbeat.collectAsState()
    val recentBlocks by viewModel.recentBlocks.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val blockTotal by viewModel.blockTotal.collectAsState()
    val syncing by viewModel.syncing.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showLocalDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    if (showHistory) {
        HistoryScreen(
            viewModel = viewModel,
            onBack = { showHistory = false }
        )
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (text != null) {
                    viewModel.importLocalText(text)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("X-ADBlock") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    heartbeat = heartbeat,
                    blockTotal = blockTotal,
                    recentBlocks = recentBlocks.size,
                    syncing = syncing,
                    onSyncNow = { viewModel.syncNow() }
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("词库订阅（云端同步）", modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp).height(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("添加订阅")
                    }
                }
            }
            items(subscriptions, key = { "sub_${it.id}" }) { sub ->
                SubscriptionCard(
                    subscription = sub,
                    syncing = syncing,
                    onSync = { viewModel.syncOne(sub) },
                    onToggle = { enabled -> viewModel.setSubscriptionEnabled(sub, enabled) },
                    onDelete = { viewModel.deleteSubscription(sub) }
                )
            }
            item {
                SectionTitle("过滤设置")
            }
            item {
                val settings by viewModel.settings.collectAsState()
                SettingsCard(
                    settings = settings,
                    onUpdate = { viewModel.updateSettings(it) }
                )
            }
            item {
                SectionTitle("本地词库（$localCount 条）")
            }
            item {
                Card {
                    val padding = Modifier.padding(12.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "与云端词库合并生效；支持独立关键词、/正则/、\n多片段 ALL_OF（行内用\\u001F 分隔）。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { importLauncher.launch("*/*") }) {
                                Text("导入 TXT")
                            }
                            Button(onClick = { showLocalDialog = true }) {
                                Text("查看/清空")
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showHistory = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("屏蔽历史（${recentBlocks.size} 条）", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Text("查看全部 >", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, secondary ->
                viewModel.addSubscription(name, url, secondary)
                showAddDialog = false
            }
        )
    }
    if (showLocalDialog) {
        LocalRulesDialog(
            count = localCount,
            onDismiss = { showLocalDialog = false },
            onClear = {
                viewModel.removeLocalRules()
                showLocalDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val allHistory by viewModel.allHistory.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("屏蔽历史（${allHistory.size} 条）") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->
        if (allHistory.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "暂无屏蔽记录",
                    modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allHistory, key = { "evt_${it.id}" }) { event ->
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Text(
                                event.preview.ifBlank { "(空)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3
                            )
                            Text(
                                "${hotSourceName(event.sourceId)} · ${formatTime(event.ts)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    heartbeat: com.xadblock.module.data.HeartbeatEntity?,
    blockTotal: Long,
    recentBlocks: Int,
    syncing: Boolean,
    onSyncNow: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已屏蔽", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$blockTotal+",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                } else {
                    TextButton(onClick = onSyncNow) { Text("同步") }
                }
            }
            heartbeat?.let {
                Text(
                    "Hook 状态：${it.status} · ${it.process} · X ${it.targetVersion} · 快照 v${it.snapshotVersion} · ${formatTime(it.ts)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text(
                "Hook 未加载：请在 LSPosed 勾选 com.twitter.android 后重启 X",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

@Composable
private fun SettingsCard(
    settings: com.xadblock.module.data.SettingsStore.Settings,
    onUpdate: (com.xadblock.module.data.SettingsStore.Settings) -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsRow(
                title = "显示「已屏蔽」占位（便于验证；关闭则直接移除）",
                checked = settings.isMarkMode,
                onCheckedChange = { checked ->
                    onUpdate(settings.copy(
                        displayMode = if (checked) com.xadblock.module.data.Contract.DISPLAY_MODE_MARK
                        else com.xadblock.module.data.Contract.DISPLAY_MODE_REMOVE
                    ))
                }
            )
            SettingsRow(
                title = "同时过滤用户名",
                checked = settings.optUsername,
                onCheckedChange = { onUpdate(settings.copy(optUsername = it)) }
            )
            SettingsRow(
                title = "屏蔽带 emoji 的帖子",
                checked = settings.optEmoji,
                onCheckedChange = { onUpdate(settings.copy(optEmoji = it)) }
            )
            SettingsRow(
                title = "屏蔽带大量特殊符号/乱码的帖子",
                checked = settings.optSpecialChars,
                onCheckedChange = { onUpdate(settings.copy(optSpecialChars = it)) }
            )
            SettingsRow(
                title = "屏蔽 Grok 回复",
                checked = settings.optGrok,
                onCheckedChange = { onUpdate(settings.copy(optGrok = it)) }
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subscription.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                androidx.compose.material3.Switch(
                    checked = subscription.enabled,
                    onCheckedChange = onToggle
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除订阅")
                }
            }
            Text(
                buildString {
                    if (subscription.lastSyncAt > 0) {
                        append("上次同步：${formatTime(subscription.lastSyncAt)}")
                    }
                    append(" · 规则 ${subscription.ruleCount} 条")
                },
                style = MaterialTheme.typography.labelSmall
            )
            if (subscription.lastSyncStatus == "error") {
                Text(
                    "同步失败：${subscription.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            TextButton(onClick = onSync, enabled = !syncing) {
                Text("立即同步")
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, secondaryUrl: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var secondary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加词库订阅") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名称（可选）") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it },
                    label = { Text("HTTPS 词库 URL") }, singleLine = true)
                OutlinedTextField(value = secondary, onValueChange = { secondary = it },
                    label = { Text("备用 URL（可选）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, secondary) }, enabled = url.isNotBlank()) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun LocalRulesDialog(
    count: Int,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地词库") },
        text = { Text("当前本地规则 $count 条。导入 TXT 会追加规则；清空会移除所有本地规则（内置与订阅不受影响）。") },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text("清空规则") }
        }
    )
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

/** Friendly label for a rule source id (matches by keyword pattern is not possible here). */
private fun hotSourceName(sourceId: String): String {
    return when {
        sourceId == "emoji" -> "emoji 规则"
        sourceId == "grok" -> "Grok 回复"
        sourceId == "special-chars" -> "特殊符号"
        sourceId == "builtin" -> "内置词库"
        sourceId == "local" -> "本地词库"
        sourceId.startsWith("sub:") -> "云端订阅"
        else -> sourceId
    }
}
