package com.xadblock.module.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Orchestrates subscription syncs and local-rule management on top of Room. */
class RuleRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.ruleDao()
    private val subDao = db.subscriptionDao()

    suspend fun syncAll(force: Boolean = false): List<Pair<SubscriptionEntity, RuleSync.SyncResult>> {
        return withContext(Dispatchers.IO) {
            subDao.allOnce().filter { it.enabled }.map { sub ->
                sub to syncOne(sub, force)
            }
        }
    }

    /**
     * Downloads this subscription's keyword file, parses it and atomically swaps
     * its rules. GitHub-first with optional CDN fallback; honors ETags (304 → ok).
     */
    suspend fun syncOne(sub: SubscriptionEntity, force: Boolean = false): RuleSync.SyncResult {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            var text: String? = null
            var newEtag: String? = null

            val urlAttempts = buildList {
                add(sub.url)
                if (!sub.secondaryUrl.isNullOrBlank()) add(sub.secondaryUrl)
            }
            for (url in urlAttempts) {
                try {
                    val pair = RuleSync.download(url, sub.etag)
                    text = pair.first
                    newEtag = pair.second
                    break
                } catch (failure: Throwable) {
                    lastError = failure
                }
            }
        if (lastError != null) {
            // All URL attempts failed (network/HTTP).
            val message = lastError?.message ?: "unknown failure"
            subDao.updateSync(sub.id, System.currentTimeMillis(), "error", message, 0)
            ModuleLogger.log("sync error sub=${sub.id} url=${sub.url} msg=$message")
            return@withContext RuleSync.SyncResult("error", message)
        }
        if (text == null) {
            // HTTP 304 Not Modified: upstream unchanged, existing rules stay valid.
            val existing = dao.countFor("sub:${sub.id}")
            subDao.updateSync(sub.id, System.currentTimeMillis(), "ok", "未变更(304)", existing)
            ModuleLogger.log("sync 304 sub=${sub.id} rules=$existing")
            return@withContext RuleSync.SyncResult("ok", ruleCount = existing)
        }

            val specs = KeywordsParser.parseText(text)
            if (specs.isEmpty()) {
                subDao.updateSync(sub.id, System.currentTimeMillis(), "error", "词库解析失败：0 条有效规则", 0)
                return@withContext RuleSync.SyncResult("error", "词库解析失败：0 条有效规则")
            }

            val sourceId = "sub:${sub.id}"
            dao.replaceBySource(sourceId, specs.map { RuleEntity(sourceId = sourceId, kind = it.kind, pattern = it.pattern) })

            if (!newEtag.isNullOrEmpty()) {
                subDao.updateEtag(sub.id, newEtag)
            }
            subDao.updateSync(sub.id, System.currentTimeMillis(), "ok", "", specs.size)
            RuleSnapshotStore.rebuild(context)
            ModuleLogger.log("sync ok sub=${sub.id} rules=${specs.size}")
            RuleSync.SyncResult("ok", ruleCount = specs.size)
        }
    }

    private suspend fun repoSourceSwap(sourceId: String, specs: List<RuleSpec>) {
        withContext(Dispatchers.IO) {
            dao.replaceBySource(sourceId, specs.map { RuleEntity(sourceId = sourceId, kind = it.kind, pattern = it.pattern) })
        }
    }

    suspend fun importLocalText(text: String): Result<Int> {
        val specs = KeywordsParser.parseText(text)
        if (specs.isEmpty()) {
            return Result.failure(IOException("没有解析出有效规则"))
        }
        withContext(Dispatchers.IO) {
            dao.insertAll(specs.map { RuleEntity(sourceId = "local", kind = it.kind, pattern = it.pattern, priority = 90) })
        }
        RuleSnapshotStore.rebuild(context)
        return Result.success(specs.size)
    }

    suspend fun exportLocalText(): String {
        return withContext(Dispatchers.IO) {
            dao.allFor("local").joinToString("\n") { it.pattern }
        }
    }
}
