package com.iknalos.dataguard

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class UsageCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val repo = DataUsageRepository(ctx)
        if (!repo.hasUsageAccess()) return Result.success()

        val prefs = Prefs(ctx)
        val cycleStart = Prefs.cycleStart(prefs.cycleDay)
        val used = repo.getMobileBytes(cycleStart, System.currentTimeMillis())
        val capBytes = (prefs.capGb * 1e9).toLong()
        if (capBytes <= 0) return Result.success()

        val pct = (used * 100 / capBytes).toInt()
        val cycleKey = cycleStart.toString()
        val lastNotified = prefs.lastNotified(cycleKey)
        val crossed = THRESHOLDS.filter { pct >= it && it > lastNotified }.maxOrNull()
        if (crossed != null) {
            prefs.setLastNotified(cycleKey, crossed)
            val title = if (crossed >= 100) "Mobile data cap reached!"
                        else "$crossed% of mobile data used"
            Notifier.notify(
                ctx, title,
                "${Format.bytes(used)} of ${Format.bytes(capBytes)} used this cycle."
            )
        }
        return Result.success()
    }

    companion object {
        private val THRESHOLDS = listOf(50, 80, 95, 100)
    }
}
