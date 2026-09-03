package com.xadblock.module.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal enum class MainDestination(val title: String) {
    SETTINGS("X-ADBlock"),
    SUBSCRIPTIONS("词库订阅"),
    LOCAL_RULES("本地规则"),
    HISTORY("屏蔽历史")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XADBlockApp(viewModel: MainViewModel) {
    val serviceState by viewModel.serviceState.collectAsState()
    val message by viewModel.message.collectAsState()
    val localCount by viewModel.localCount.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var destinationName by rememberSaveable { mutableStateOf(MainDestination.SETTINGS.name) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showLocalDialog by rememberSaveable { mutableStateOf(false) }
    val destination = MainDestination.valueOf(destinationName)
    val context = LocalContext.current

    BackHandler(destination != MainDestination.SETTINGS) {
        destinationName = MainDestination.SETTINGS.name
    }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text)
        viewModel.dismissMessage()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取所选文件")
            viewModel.importLocalText(text)
        } catch (failure: Throwable) {
            viewModel.reportFailure("导入失败", failure)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destination.title) },
                navigationIcon = {
                    if (destination != MainDestination.SETTINGS) {
                        IconButton(onClick = { destinationName = MainDestination.SETTINGS.name }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
                        }
                    }
                },
                actions = {
                    when (destination) {
                        MainDestination.SETTINGS -> {
                            IconButton(onClick = viewModel::syncNow, enabled = !syncing) {
                                if (syncing) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = "同步全部词库")
                                }
                            }
                        }
                        MainDestination.HISTORY -> {
                            IconButton(onClick = viewModel::clearHistory) {
                                Icon(Icons.Filled.Delete, contentDescription = "清空屏蔽历史")
                            }
                        }
                        else -> Unit
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "main-destination"
        ) { targetDestination ->
            MainDestinationPage(
                destination = targetDestination,
                viewModel = viewModel,
                serviceState = serviceState,
                onDestinationChange = { destinationName = it.name },
                onAddSubscription = { showAddDialog = true },
                onImportLocal = { importLauncher.launch("text/plain") },
                onShowLocalDetails = { showLocalDialog = true }
            )
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