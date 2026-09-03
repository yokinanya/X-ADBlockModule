package com.xadblock.module.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xadblock.module.XAdApplication
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
            withContext(Dispatchers.IO) {
                db.subscriptionDao().delete(subscription)
                db.ruleDao().deleteBySource("sub:${subscription.id}")
            }
            RuleSnapshotStore.rebuild(getApplication())
        }
    }

    fun setSubscriptionEnabled(subscription: SubscriptionEntity, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.subscriptionDao().upsert(subscription.copy(enabled = enabled))
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                repository.syncAll(force = true)
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
                repository.syncOne(subscription, force = true)
            } finally {
                _syncing.value = false
            }
        }
    }

    fun importLocalText(text: String) {
        viewModelScope.launch {
            try {
                repository.importLocalText(text)
            } catch (ignored: Throwable) {
                // imported text failed to parse; localCount stays unchanged
            }
        }
    }

    fun removeLocalRules() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.ruleDao().deleteBySource("local")
            }
            RuleSnapshotStore.rebuild(getApplication())
        }
    }

    fun setEnqueueBackgroundSync(intervalHours: Long) {
        SyncWorker.schedulePeriodic(getApplication(), intervalHours)
    }

    fun updateSettings(newSettings: SettingsStore.Settings) {
        _settings.value = newSettings
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SettingsStore.save(getApplication(), newSettings)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.blockEventDao().clear()
            }
        }
    }
}
