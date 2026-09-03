package com.xadblock.module

import android.app.Application
import android.content.Context
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

class XAdApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ModuleLogger.init(this)
        ModuleLogger.log("module app start v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
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
                RuleSnapshotStore.rebuild(this@XAdApplication)
                ModuleLogger.log("bootstrap: snapshot rebuilt, rules=" +
                        ruleDao.allEnabled().size)
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
