package com.xadblock.module.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.XposedServiceState
import com.xadblock.module.data.Contract

private data class ToggleSetting(
    val title: String,
    val description: String,
    val icon: ImageVector
)

private data class SettingsLink(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val destination: MainDestination
)

private data class StatusOverview(
    val connected: Boolean,
    val title: String,
    val serviceDetail: String,
    val heartbeatDetail: String,
    val blockedCount: Long,
    val enabledSubscriptions: Int,
    val localRules: Int,
    val syncing: Boolean
)

private val SettingsMaxWidth = 1040.dp
private val WideLayoutBreakpoint = 840.dp
private val SectionShape = RoundedCornerShape(8.dp)

@Composable
internal fun SettingsPage(
    viewModel: MainViewModel,
    serviceState: XposedServiceState,
    onDestinationChange: (MainDestination) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= WideLayoutBreakpoint
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 20.dp, vertical = 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = SettingsMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                StatusSection(viewModel, serviceState)
                SettingsContent(viewModel, onDestinationChange, wideLayout)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusSection(viewModel: MainViewModel, serviceState: XposedServiceState) {
    val heartbeat by viewModel.heartbeat.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val blockTotal by viewModel.blockTotal.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val connected = serviceState.connected
    val overview = StatusOverview(
        connected = connected,
        title = if (connected) "过滤服务已连接" else "等待 LSPosed 服务",
        serviceDetail = if (connected) "LibXposed API ${serviceState.apiVersion}" else "确认模块已启用并勾选 X",
        heartbeatDetail = heartbeat?.let {
            "Hook ${it.status} · X ${it.targetVersion} · 快照 v${it.snapshotVersion}"
        } ?: "等待目标进程上报运行状态",
        blockedCount = blockTotal,
        enabledSubscriptions = subscriptions.count { it.enabled },
        localRules = localCount,
        syncing = syncing
    )
    StatusPanel(overview, viewModel::syncNow)
}

@Composable
private fun StatusPanel(overview: StatusOverview, onSync: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (overview.connected) colors.primaryContainer else colors.errorContainer
    val contentColor = if (overview.connected) colors.onPrimaryContainer else colors.onErrorContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusHeader(overview, onSync)
            HorizontalDivider(color = contentColor.copy(alpha = 0.2f))
            StatusMetrics(overview)
            Text(
                overview.heartbeatDetail,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.76f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusHeader(overview: StatusOverview, onSync: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(48.dp), CircleShape, color = MaterialTheme.colorScheme.surface) {
            Icon(
                imageVector = if (overview.connected) Icons.Filled.CheckCircle else Icons.Filled.LinkOff,
                contentDescription = null,
                modifier = Modifier.padding(12.dp)
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(overview.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(overview.serviceDetail, style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalIconButton(onClick = onSync, enabled = !overview.syncing) {
            if (overview.syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Refresh, contentDescription = "同步全部词库")
        }
    }
}

@Composable
private fun StatusMetrics(overview: StatusOverview) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusMetric(overview.blockedCount.toString(), "累计屏蔽")
        StatusMetric(overview.enabledSubscriptions.toString(), "启用订阅")
        StatusMetric(overview.localRules.toString(), "本地规则")
    }
}

@Composable
private fun SettingsContent(
    viewModel: MainViewModel,
    onDestinationChange: (MainDestination) -> Unit,
    wideLayout: Boolean
) {
    if (!wideLayout) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            DisplayModeSection(viewModel)
            MatchOptionsSection(viewModel)
            RuleDataSection(viewModel, onDestinationChange)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            DisplayModeSection(viewModel)
            MatchOptionsSection(viewModel)
        }
        Column(Modifier.weight(1f)) {
            RuleDataSection(viewModel, onDestinationChange)
        }
    }
}

@Composable
private fun RowScope.StatusMetric(value: String, label: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.72f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsSection("过滤行为", "选择规则命中后如何处理评论或帖子") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = settings.displayMode == Contract.DISPLAY_MODE_REMOVE,
                onClick = {
                    viewModel.updateSettings(settings.copy(displayMode = Contract.DISPLAY_MODE_REMOVE))
                },
                shape = SegmentedButtonDefaults.itemShape(0, 2)
            ) { Text("直接移除") }
            SegmentedButton(
                selected = settings.displayMode == Contract.DISPLAY_MODE_MARK,
                onClick = {
                    viewModel.updateSettings(settings.copy(displayMode = Contract.DISPLAY_MODE_MARK))
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2)
            ) { Text("保留占位") }
        }
        Text(
            if (settings.displayMode == Contract.DISPLAY_MODE_REMOVE) "命中项会直接从列表中移除"
            else "命中项保留位置并显示屏蔽标记",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MatchOptionsSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    SettingsSection("匹配范围", "这些开关会与关键词和正则规则共同生效") {
        SettingToggle(ToggleSetting("检查用户名", "同时匹配昵称与用户标识", Icons.Filled.PersonSearch), settings.optUsername) {
            viewModel.updateSettings(settings.copy(optUsername = it))
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingToggle(ToggleSetting("Emoji 内容", "命中包含 Emoji 的可疑内容", Icons.Filled.EmojiEmotions), settings.optEmoji) {
            viewModel.updateSettings(settings.copy(optEmoji = it))
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingToggle(ToggleSetting("异常符号", "识别符号密度过高或乱码内容", Icons.Filled.DataObject), settings.optSpecialChars) {
            viewModel.updateSettings(settings.copy(optSpecialChars = it))
        }
        HorizontalDivider(Modifier.padding(start = 56.dp))
        SettingToggle(ToggleSetting("Grok 回复", "自动命中由 Grok 生成的回复", Icons.Filled.AutoAwesome), settings.optGrok) {
            viewModel.updateSettings(settings.copy(optGrok = it))
        }
    }
}

@Composable
private fun RuleDataSection(
    viewModel: MainViewModel,
    onDestinationChange: (MainDestination) -> Unit
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val recentBlocks by viewModel.recentBlocks.collectAsState()
    val links = listOf(
        SettingsLink("词库订阅", "${subscriptions.count { it.enabled }} 个启用，共 ${subscriptions.size} 个来源", Icons.AutoMirrored.Filled.LibraryBooks, MainDestination.SUBSCRIPTIONS),
        SettingsLink("本地规则", "$localCount 条规则，与订阅词库合并生效", Icons.Filled.Folder, MainDestination.LOCAL_RULES),
        SettingsLink("屏蔽历史", "查看最近 ${recentBlocks.size} 条命中记录", Icons.Filled.History, MainDestination.HISTORY)
    )
    SettingsSection("规则与数据", "管理规则来源并检查实际命中记录") {
        links.forEachIndexed { index, link ->
            SettingsNavigationItem(link) { onDestinationChange(link.destination) }
            if (index != links.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = SectionShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), content = { content() })
        }
    }
}

@Composable
private fun SettingToggle(
    setting: ToggleSetting,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.toggleable(checked, role = Role.Switch) { onCheckedChange(it) },
        leadingContent = { Icon(setting.icon, contentDescription = null) },
        headlineContent = { Text(setting.title) },
        supportingContent = { Text(setting.description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) }
    )
}

@Composable
private fun SettingsNavigationItem(link: SettingsLink, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(link.icon, contentDescription = null) },
        headlineContent = { Text(link.title) },
        supportingContent = { Text(link.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "打开${link.title}")
        }
    )
}