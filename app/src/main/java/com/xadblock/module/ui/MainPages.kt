package com.xadblock.module.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.Contract
import com.xadblock.module.data.RuleEntity
import com.xadblock.module.data.SubscriptionEntity
import kotlinx.coroutines.launch

private const val LOCAL_SOURCE = "local"

private enum class RuleFilter(val label: String) {
    ALL("全部"),
    LOCAL("本地"),
    CLOUD("云端"),
    LITERAL("关键词"),
    REGEX("正则"),
    ALL_OF("全部匹配")
}

private data class LocalActions(
    val onToggle: () -> Unit,
    val onAdd: () -> Unit,
    val onImport: () -> Unit
)

private data class SubscriptionActions(
    val onToggleExpanded: () -> Unit,
    val onEdit: () -> Unit,
    val onSync: () -> Unit,
    val onDelete: () -> Unit,
    val onEnabledChange: (Boolean) -> Unit
)

internal data class RulesPageActions(
    val onAddSubscription: () -> Unit,
    val onEditSubscription: (SubscriptionEntity) -> Unit,
    val onAddLocal: () -> Unit,
    val onImportLocal: () -> Unit
)

private data class SubscriptionUi(
    val subscription: SubscriptionEntity,
    val expanded: Boolean,
    val syncing: Boolean
)

private data class RulesContentState(
    val subscriptions: List<SubscriptionEntity>,
    val groupedRules: Map<String, List<RuleEntity>>,
    val syncing: Boolean,
    val expanded: MutableMap<String, Boolean>,
    val filterActive: Boolean
)

private data class SubscriptionActionContext(
    val state: RulesContentState,
    val viewModel: MainViewModel,
    val actions: RulesPageActions
)

private data class RuleHeaderUi(val title: String, val detail: String, val expanded: Boolean)

@Composable
internal fun RulesPage(
    viewModel: MainViewModel,
    actions: RulesPageActions
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val rules by viewModel.rules.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(RuleFilter.ALL.name) }
    val expanded = remember { mutableStateMapOf(LOCAL_SOURCE to true) }
    val filter = RuleFilter.valueOf(filterName)
    val filteredRules = remember(rules, query, filter) { filterRules(rules, query, filter) }
    val groupedRules = remember(filteredRules) { filteredRules.groupBy(RuleEntity::sourceId) }
    val state = RulesContentState(
        subscriptions,
        groupedRules,
        syncing,
        expanded,
        query.isNotBlank() || filter != RuleFilter.ALL
    )
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val showBackToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "rule_search") {
                RuleSearchToolbar(query, filter, { query = it }, { filterName = it.name })
            }
            localRuleItems(state, viewModel, actions)
            cloudRuleItems(state, viewModel, actions)
        }
        if (showBackToTop) {
            FloatingActionButton(
                onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "回到顶部")
            }
        }
    }
}

@Composable
private fun RuleSearchToolbar(
    query: String,
    filter: RuleFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (RuleFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索规则") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清除搜索")
                    }
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RuleFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { onFilterChange(option) },
                    label = { Text(option.label) }
                )
            }
        }
    }
}

private fun filterRules(
    rules: List<RuleEntity>,
    query: String,
    filter: RuleFilter
): List<RuleEntity> {
    val term = query.trim()
    return rules.filter { rule ->
        val matchesType = when (filter) {
            RuleFilter.ALL -> true
            RuleFilter.LOCAL -> rule.sourceId == LOCAL_SOURCE
            RuleFilter.CLOUD -> rule.sourceId.startsWith("sub:")
            RuleFilter.LITERAL -> rule.kind == Contract.KIND_LITERAL
            RuleFilter.REGEX -> rule.kind == Contract.KIND_REGEX
            RuleFilter.ALL_OF -> rule.kind == Contract.KIND_ALL_OF
        }
        val matchesQuery = term.isBlank() || rule.pattern.contains(term, ignoreCase = true)
        matchesType && matchesQuery
    }
}

private fun LazyListScope.localRuleItems(
    state: RulesContentState,
    viewModel: MainViewModel,
    actions: RulesPageActions
) {
    val localRules = state.groupedRules[LOCAL_SOURCE].orEmpty()
    item(key = "local_header") {
        LocalRulesHeader(
            count = localRules.size,
            expanded = state.expanded[LOCAL_SOURCE] == true,
            actions = LocalActions(
                onToggle = { state.expanded.toggle(LOCAL_SOURCE) },
                onAdd = actions.onAddLocal,
                onImport = actions.onImportLocal
            )
        )
    }
    if (state.expanded[LOCAL_SOURCE] != true) return
    if (localRules.isEmpty()) item { EmptyRuleRow(if (state.filterActive) "没有匹配的本地规则" else "还没有本地规则") }
    items(localRules, key = { "local_${it.id}" }) { rule ->
        RuleRow(rule, true, viewModel::deleteLocalRule)
    }
}

private fun LazyListScope.cloudRuleItems(
    state: RulesContentState,
    viewModel: MainViewModel,
    actions: RulesPageActions
) {
    item(key = "cloud_title") { CloudSectionTitle(actions.onAddSubscription) }
    val context = SubscriptionActionContext(state, viewModel, actions)
    val visibleSubscriptions = state.subscriptions.filter { subscription ->
        !state.filterActive || state.groupedRules["sub:${subscription.id}"].orEmpty().isNotEmpty()
    }
    visibleSubscriptions.forEach { subscription ->
        val sourceId = "sub:${subscription.id}"
        val sourceRules = state.groupedRules[sourceId].orEmpty()
        item(key = "subscription_${subscription.id}") {
            SubscriptionHeader(
                SubscriptionUi(subscription, state.expanded[sourceId] == true, state.syncing),
                subscriptionActions(sourceId, subscription, context)
            )
        }
        if (state.expanded[sourceId] == true) {
            if (sourceRules.isEmpty()) item { EmptyRuleRow("尚未获取词条") }
            items(sourceRules, key = { "rule_${it.id}" }) { rule -> RuleRow(rule, false) {} }
        }
    }
    if (visibleSubscriptions.isEmpty()) {
        item { EmptyRuleRow(if (state.filterActive) "没有匹配的云端规则" else "还没有云端规则") }
    }
}

private fun subscriptionActions(
    sourceId: String,
    subscription: SubscriptionEntity,
    context: SubscriptionActionContext
) = SubscriptionActions(
    onToggleExpanded = { context.state.expanded.toggle(sourceId) },
    onEdit = { context.actions.onEditSubscription(subscription) },
    onSync = { context.viewModel.syncOne(subscription) },
    onDelete = { context.viewModel.deleteSubscription(subscription) },
    onEnabledChange = { context.viewModel.setSubscriptionEnabled(subscription, it) }
)

private fun MutableMap<String, Boolean>.toggle(key: String) {
    this[key] = this[key] != true
}

@Composable
private fun LocalRulesHeader(count: Int, expanded: Boolean, actions: LocalActions) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RuleHeaderRow(RuleHeaderUi("本地规则", "$count 条", expanded), actions.onToggle)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
                TextButton(onClick = actions.onImport) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Text("导入 TXT")
                }
            }
        }
    }
}

@Composable
private fun CloudSectionTitle(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("云端规则", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加")
        }
    }
}

@Composable
private fun SubscriptionHeader(
    model: SubscriptionUi,
    actions: SubscriptionActions
) {
    val subscription = model.subscription
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${subscription.ruleCount} 条 · ${subscription.url}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(subscription.enabled, actions.onEnabledChange)
                ExpandButton(model.expanded, actions.onToggleExpanded)
            }
            if (subscription.lastSyncStatus == "error") {
                Text(subscription.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = actions.onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("编辑")
                }
                TextButton(onClick = actions.onSync, enabled = !model.syncing) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("同步")
                }
                IconButton(onClick = actions.onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除${subscription.name}")
                }
            }
        }
    }
}

@Composable
private fun RuleHeaderRow(model: RuleHeaderUi, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(model.title, style = MaterialTheme.typography.titleMedium)
            Text(model.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExpandButton(model.expanded, onToggle)
    }
}

@Composable
private fun ExpandButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "收起词条" else "展开词条"
        )
    }
}

@Composable
private fun RuleRow(rule: RuleEntity, deletable: Boolean, onDelete: (RuleEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(rule.pattern, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ruleKindName(rule.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (deletable) {
                IconButton(onClick = { onDelete(rule) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除规则")
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun EmptyRuleRow(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

private fun ruleKindName(kind: String): String = when (kind) {
    "regex" -> "正则"
    "all_of" -> "全部匹配"
    else -> "关键词"
}
