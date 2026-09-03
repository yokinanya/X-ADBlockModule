package com.xadblock.module.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xadblock.module.XAdApplication
import com.xadblock.module.XposedServiceState
import com.xadblock.module.data.AppDatabase
import com.xadblock.module.data.BlockEventEntity
import com.xadblock.module.data.HeartbeatEntity
import com.xadblock.module.data.RuleRepository
import com.xadblock.module.data.RuleSnapshotStore
import com.xadblock.module.data.SettingsStore
import com.xadblock.module.data.SubscriptionEntity
import com.xadblock.module.data.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val repository = RuleRepository(application)

    val subscriptions: StateFlow<List<SubscriptionEntity>> = db.subscriptionDao().all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val heartbeat: StateFlow<HeartbeatEntity?> = db.heartbeatDao().latest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentBlocks: StateFlow<List<BlockEventEntity>> = db.blockEventDao().recent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHistory: StateFlow<List<BlockEventEntity>> = db.blockEventDao().recent(2000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _localCount = MutableStateFlow(0)
    val localCount: StateFlow<Int> = _localCount

    private val _blockTotal = MutableStateFlow(0L)
    val blockTotal: StateFlow<Long> = _blockTotal

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _settings = MutableStateFlow(SettingsStore.load(application))
    val settings: StateFlow<SettingsStore.Settings> = _settings

    val serviceState: StateFlow<XposedServiceState> = XAdApplication.serviceState

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message

    data class UiMessage(val text: String, val isError: Boolean)

    init {
        viewModelScope.launch {
            db.ruleDao().allEnabledFlow().collect { rules ->
                _localCount.value = rules.count { it.sourceId == "local" }
            }
        }
        viewModelScope.launch {
            while (true) {
                val app = getApplication<Application>()
                val dbCount = withContext(Dispatchers.IO) {
                    db.blockEventDao().countAll().toLong()
                }
                _blockTotal.value = XAdApplication.hookStats(app) + dbCount
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun addSubscription(name: String, url: String, secondaryUrl: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.subscriptionDao().upsert(
                    SubscriptionEntity(
                        name = name.ifBlank { url },
                        url = url.trim(),
                        secondaryUrl = secondaryUrl.trim().ifBlank { null }
                    )
                )
            }
        }
    }

    fun deleteSubscription(subscription: SubscriptionEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.subscriptionDao().delete(subscription)
                    db.ruleDao().deleteBySource("sub:${subscription.id}")
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage("订阅已删除", false)
            } catch (failure: Throwable) {
                reportFailure("删除订阅失败", failure)
            }
        }
    }

    fun setSubscriptionEnabled(subscription: SubscriptionEntity, enabled: Boolean) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.subscriptionDao().upsert(subscription.copy(enabled = enabled))
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage(if (enabled) "订阅已启用" else "订阅已停用", false)
            } catch (failure: Throwable) {
                reportFailure("订阅状态保存失败", failure)
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val results = repository.syncAll(force = true)
                val failed = results.count { it.second.status == "error" }
                _message.value = if (failed == 0) {
                    UiMessage("词库同步完成", false)
                } else {
                    UiMessage("有 $failed 个订阅同步失败", true)
                }
            } catch (failure: Throwable) {
                reportFailure("同步失败", failure)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun syncOne(subscription: SubscriptionEntity) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val result = repository.syncOne(subscription, force = true)
                _message.value = if (result.status == "error") {
                    UiMessage("${subscription.name}：${result.error}", true)
                } else {
                    UiMessage("${subscription.name} 已同步", false)
                }
            } catch (failure: Throwable) {
                reportFailure("订阅同步失败", failure)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun importLocalText(text: String) {
        viewModelScope.launch {
            try {
                val result = repository.importLocalText(text)
                result.fold(
                    onSuccess = { count -> _message.value = UiMessage("已导入 $count 条本地规则", false) },
                    onFailure = { failure -> reportFailure("导入失败", failure) }
                )
            } catch (failure: Throwable) {
                reportFailure("导入失败", failure)
            }
        }
    }

    fun removeLocalRules() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.ruleDao().deleteBySource("local")
                    RuleSnapshotStore.rebuild(getApplication())
                }
                _message.value = UiMessage("本地规则已清空", false)
            } catch (failure: Throwable) {
                reportFailure("清空后发布规则失败", failure)
            }
        }
    }

    fun setEnqueueBackgroundSync(intervalHours: Long) {
        SyncWorker.schedulePeriodic(getApplication(), intervalHours)
    }

    fun updateSettings(newSettings: SettingsStore.Settings) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SettingsStore.save(getApplication(), newSettings)
                }
                _settings.value = newSettings
                _message.value = UiMessage("过滤设置已更新", false)
            } catch (failure: Throwable) {
                reportFailure("设置保存失败", failure)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    db.blockEventDao().clear()
                }
                _message.value = UiMessage("屏蔽历史已清空", false)
            } catch (failure: Throwable) {
                reportFailure("清空历史失败", failure)
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun reportFailure(prefix: String, failure: Throwable) {
        val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        _message.value = UiMessage("$prefix：$detail", true)
    }
}
