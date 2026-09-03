package com.xadblock.module

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.xadblock.module.data.AppDatabase
import com.xadblock.module.data.KeywordsParser
import com.xadblock.module.data.ModuleLogger
import com.xadblock.module.data.RuleEntity
import com.xadblock.module.data.RuleSnapshotStore
import com.xadblock.module.data.SubscriptionEntity
import com.xadblock.module.data.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

data class XposedServiceState(
    val connected: Boolean = false,
    val apiVersion: Int = 0,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val error: String? = null
)

class XAdApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ModuleLogger.init(this)
        ModuleLogger.log("module app start v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                _serviceState.value = XposedServiceState(
                    connected = true,
                    apiVersion = service.apiVersion,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion
                )
                ModuleLogger.log(
                    "LibXposed service connected: ${service.frameworkName} " +
                        "${service.frameworkVersion} api=${service.apiVersion}"
                )
                scope.launch {
                    try {
                        RuleSnapshotStore.rebuild(this@XAdApplication)
                    } catch (failure: Throwable) {
                        _serviceState.value = _serviceState.value.copy(error = failure.message)
                        ModuleLogger.log("remote snapshot rebuild failed: $failure")
                    }
                }
            }

            override fun onServiceDied(service: XposedService) {
                if (xposedService !== service) return
                xposedService = null
                _serviceState.value = XposedServiceState(error = "LibXposed 服务已断开")
                ModuleLogger.log("LibXposed service disconnected")
            }
        })
        bootstrap()
    }

    /**
     * First-run setup: default subscription + builtin ruleset import + periodic
     * snapshot refresh (fallback of mutation-triggered rebuilds).
     */
    private fun bootstrap() {
        scope.launch {
            try {
                val db = AppDatabase.get(this@XAdApplication)
                val subDao = db.subscriptionDao()
                val ruleDao = db.ruleDao()
                if (subDao.count() == 0) {
                    subDao.upsert(
                        SubscriptionEntity(
                            name = "x-comment-blocker 公共词库",
                            url = DEFAULT_KEYWORDS_URL,
                            secondaryUrl = DEFAULT_KEYWORDS_CDN
                        )
                    )
                    ModuleLogger.log("bootstrap: default subscription created")
                }
                if (ruleDao.countFor("builtin") == 0) {
                    importBuiltin()
                    ModuleLogger.log("bootstrap: builtin rules imported")
                }
                try {
                    RuleSnapshotStore.rebuild(this@XAdApplication)
                    ModuleLogger.log("bootstrap: snapshot rebuilt, rules=" +
                            ruleDao.allEnabled().size)
                } catch (failure: Throwable) {
                    _serviceState.value = _serviceState.value.copy(error = failure.message)
                    ModuleLogger.log("bootstrap: remote snapshot unavailable: $failure")
                }
                SyncWorker.schedulePeriodic(this@XAdApplication, 24)
                SyncWorker.runOnce(this@XAdApplication)
            } catch (failure: Throwable) {
                ModuleLogger.log("bootstrap failed: $failure")
            }
        }
    }

    private suspend fun importBuiltin() {
        val text = try {
            assets.open("builtin_keywords.txt").bufferedReader().readText()
        } catch (ignored: Throwable) {
            return
        }
        val specs = KeywordsParser.parseText(text)
        if (specs.isEmpty()) return
        AppDatabase.get(this).ruleDao().insertAll(
            specs.map { RuleEntity(sourceId = "builtin", kind = it.kind, pattern = it.pattern) }
        )
    }

    companion object {
        private val _serviceState = MutableStateFlow(XposedServiceState())
        val serviceState: StateFlow<XposedServiceState> = _serviceState

        @Volatile
        private var xposedService: XposedService? = null

        fun requireXposedService(): XposedService {
            return xposedService
                ?: throw IllegalStateException("LibXposed Service 未连接，请确认已安装并启用 LSPosed API 102")
        }

        fun remotePreferences(group: String): SharedPreferences {
            val service = requireXposedService()
            check(service.apiVersion >= 102) {
                "当前 LSPosed API 为 ${service.apiVersion}，需要 API 102"
            }
            return service.getRemotePreferences(group)
        }

        const val DEFAULT_KEYWORDS_URL =
            "https://raw.githubusercontent.com/amahteru/x-comment-blocker/main/keywords.txt"
        const val DEFAULT_KEYWORDS_CDN =
            "https://fastly.jsdelivr.net/gh/amahteru/x-comment-blocker@main/keywords.txt"

        fun preferences(context: Context): android.content.SharedPreferences =
            context.getSharedPreferences("xadblock_hook", Context.MODE_PRIVATE)

        fun hookStats(context: Context): Long =
            preferences(context).getLong("hook_total_blocks", 0L)

        fun recordBlockCount(context: Context, count: Long) {
            preferences(context).edit()
                .putLong("hook_total_blocks", hookStats(context) + count)
                .apply()
        }
    }
}
