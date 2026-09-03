package com.xadblock.module.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xadblock.module.XAdApplication

/**
 * Receives block events and heartbeats broadcast by the injected X process.
 * The channel is a package-scoped exported broadcast (the ContentProvider route
 * is blocked on Android 11+ by package visibility).
 */
class HookBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) return
        // DB access must never run on the main thread; offload with goAsync.
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                when (intent.action) {
                    Contract.ACTION_BLOCK_EVENTS -> handleBlockEvents(context, intent)
                    Contract.ACTION_HEARTBEAT -> handleHeartbeat(context, intent)
                }
            } catch (failure: Throwable) {
                ModuleLogger.log("receiver error: $failure")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xadblock-receiver").apply { isDaemon = true }
        }
    }

    private fun handleBlockEvents(context: Context, intent: Intent) {
        val count = intent.getIntExtra(Contract.EXTRA_COUNT, 0)
        if (count > 0) {
            XAdApplication.recordBlockCount(context, count.toLong())
            ModuleLogger.log("block events received: $count")
        }
        val payload = intent.getStringExtra(Contract.EXTRA_ITEMS) ?: return
        if (payload.isEmpty()) return
        val db = AppDatabase.get(context)
        payload.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            try {
                db.blockEventDao().insert(
                    BlockEventEntity(
                        sourceId = parts[0],
                        preview = parts[1].take(200),
                        postId = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                    )
                )
            } catch (ignored: Throwable) {
            }
        }
        try {
            db.blockEventDao().trim()
        } catch (ignored: Throwable) {
        }
    }

    private fun handleHeartbeat(context: Context, intent: Intent) {
        val db = AppDatabase.get(context)
        val heartbeat = HeartbeatEntity(
            process = intent.getStringExtra(Contract.EXTRA_PROCESS) ?: "?",
            status = intent.getStringExtra(Contract.EXTRA_STATUS) ?: "?",
            snapshotVersion = intent.getLongExtra(Contract.EXTRA_SNAPSHOT_VERSION, 0L),
            targetVersion = intent.getStringExtra(Contract.EXTRA_TARGET_VERSION) ?: ""
        )
        db.heartbeatDao().deleteAll()
        db.heartbeatDao().insert(heartbeat)
        db.heartbeatDao().trim(20)
        ModuleLogger.log("heartbeat: ${heartbeat.status} proc=${heartbeat.process} " +
                "snap=${heartbeat.snapshotVersion} x=${heartbeat.targetVersion}")
        intent.getStringExtra(Contract.EXTRA_LOG)?.let { lines ->
            lines.lineSequence().forEach { ModuleLogger.log("hook|$it") }
        }
    }
}
