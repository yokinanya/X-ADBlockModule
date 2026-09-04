package com.xadblock.module.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xadblock.module.data.Contract
import com.xadblock.module.data.SubscriptionEntity

internal data class SubscriptionDraft(
    val name: String,
    val url: String,
    val secondaryUrl: String
)

private data class EditorFieldState(
    val value: String,
    val label: String,
    val isError: Boolean = false
)

internal data class LocalRuleDraft(val kind: String, val value: String)

@Composable
internal fun SubscriptionDialog(
    subscription: SubscriptionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionDraft) -> Unit
) {
    var name by remember(subscription) { mutableStateOf(subscription?.name.orEmpty()) }
    var url by remember(subscription) { mutableStateOf(subscription?.url.orEmpty()) }
    var secondaryUrl by remember(subscription) { mutableStateOf(subscription?.secondaryUrl.orEmpty()) }
    val primaryValid = url.startsWith("https://")
    val secondaryValid = secondaryUrl.isBlank() || secondaryUrl.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (subscription == null) "添加云端规则" else "编辑云端规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorField(EditorFieldState(name, "名称（可选）")) { name = it }
                EditorField(
                    EditorFieldState(url, "HTTPS URL", url.isNotBlank() && !url.startsWith("https://"))
                ) { url = it }
                EditorField(
                    EditorFieldState(secondaryUrl, "备用 URL（可选）", !secondaryValid)
                ) { secondaryUrl = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(SubscriptionDraft(name.trim(), url.trim(), secondaryUrl.trim())) },
                enabled = primaryValid && secondaryValid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EditorField(
    state: EditorFieldState,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = state.value,
        onValueChange = onValueChange,
        label = { Text(state.label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = state.isError
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddLocalRuleDialog(onDismiss: () -> Unit, onConfirm: (LocalRuleDraft) -> Unit) {
    var value by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Contract.KIND_LITERAL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加本地规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = kind == Contract.KIND_LITERAL,
                        onClick = { kind = Contract.KIND_LITERAL },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("关键词") }
                    SegmentedButton(
                        selected = kind == Contract.KIND_REGEX,
                        onClick = { kind = Contract.KIND_REGEX },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("正则") }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (kind == Contract.KIND_REGEX) "正则表达式" else "关键词") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(LocalRuleDraft(kind, value)) },
                enabled = value.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
