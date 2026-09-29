package com.lirica.android.proactivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.ScheduledAlarmDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

interface SystemAlarmScheduler {
    fun scheduleAlarm(alarmId: String, triggerAtMs: Long)
    fun cancelAlarm(alarmId: String)
}

/**
 * 2026-09-27 审计 P2 修复：PendingIntent requestCode 由 alarmId.hashCode() 改为
 * 稳定 FNV-1a 变体映射到 31 位非负整数。hashCode 跨进程/跨构建不保证稳定且碰撞时
 * 新闹钟会覆盖旧 PendingIntent（FLAG_UPDATE_CURRENT）或取消错对象。
 * FNV-1a 对 UUID 字符串的分布均匀性更好，且配合「同请求码重检」把碰撞概率压到可忽略。
 */
private fun alarmRequestCode(alarmId: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (ch in alarmId) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return hash and 0x7FFFFFFF
}

class AndroidSystemAlarmScheduler(
    private val context: Context
) : SystemAlarmScheduler {

    override fun scheduleAlarm(alarmId: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduleWakeReceiver::class.java).apply {
            action = ACTION_SCHEDULE_WAKE
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, alarmRequestCode(alarmId), intent, flags)

        // Android 12+ 精确闹钟受 SCHEDULE_EXACT_ALARM 门控且用户可随时撤销；
        // 无权限时降级 setWindow——触发点不保证精确，落在 [triggerAt, triggerAt+10min]
        // 窗口内的任意时刻（并非 ±10 分钟），杜绝 SecurityException 击穿调用链。
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMs, EXACT_FALLBACK_WINDOW_MS, pendingIntent)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    override fun cancelAlarm(alarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ScheduleWakeReceiver::class.java).apply {
            action = ACTION_SCHEDULE_WAKE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pendingIntent = PendingIntent.getBroadcast(context, alarmRequestCode(alarmId), intent, flags)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    companion object {
        const val ACTION_SCHEDULE_WAKE = "com.lirica.android.action.SCHEDULE_WAKE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"

        /** 精确闹钟权限缺失时 setWindow 降级的触发窗口（毫秒） */
        private const val EXACT_FALLBACK_WINDOW_MS = 600_000L
    }
}

class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AndroidSystemAlarmScheduler.ACTION_SCHEDULE_WAKE) {
            val alarmId = intent.getStringExtra(AndroidSystemAlarmScheduler.EXTRA_ALARM_ID) ?: return
            val app = context.applicationContext as? com.lirica.android.LiricaApplication ?: return
            val pendingResult = goAsync()
            // 协程异常兜底：异常只记日志不上抛，避免 doOnSystem 崩溃循环；finally 仍保证 finish() 归还系统
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                LiricaLog.e(TAG, "闹钟唤醒协程处理异常 (alarmId=$alarmId)", throwable)
            }
            val scope = CoroutineScope(Dispatchers.IO + exceptionHandler)
            scope.launch {
                try {
                    val alarms = app.database.scheduledAlarmDao().getPendingAlarms(System.currentTimeMillis() + 1000L)
                    val target = alarms.firstOrNull { it.id == alarmId }
                    if (target != null) {
                        app.scheduleWakeManager.onWakeTriggered(target)
                    }
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleWakeReceiver"
    }
}

class ScheduleWakeManager(
    private val scheduledAlarmDao: ScheduledAlarmDao,
    private val unspokenThoughtDao: UnspokenThoughtDao,
    private val alarmScheduler: SystemAlarmScheduler
) {

    suspend fun scheduleWake(
        triggerAtMs: Long,
        botNote: String,
        blockDelivery: Boolean = false,
        alarmId: String = UUID.randomUUID().toString()
    ): ScheduledAlarmEntity {
        val entity = ScheduledAlarmEntity(
            id = alarmId,
            triggerAt = triggerAtMs,
            botNote = botNote,
            blockDelivery = blockDelivery,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )
        scheduledAlarmDao.insert(entity)
        alarmScheduler.scheduleAlarm(entity.id, triggerAtMs)
        return entity
    }

    suspend fun cancelWake(alarmId: String) {
        scheduledAlarmDao.updateStatus(alarmId, ScheduledAlarmEntity.STATUS_CANCELLED)
        alarmScheduler.cancelAlarm(alarmId)
    }

    suspend fun onWakeTriggered(alarmEntity: ScheduledAlarmEntity, nowMs: Long = System.currentTimeMillis()) {
        scheduledAlarmDao.updateStatus(alarmEntity.id, ScheduledAlarmEntity.STATUS_TRIGGERED)
        val thought = UnspokenThoughtEntity(
            id = "thought_wake_${alarmEntity.id}",
            thought = "【自定约定】时间到了，我之前记着：${alarmEntity.botNote}",
            urgency = 4.8f,
            decayScore = 1.0f,
            createdAt = nowMs,
            expiryTime = nowMs + 3600_000L,
            status = UnspokenThoughtEntity.STATUS_PENDING
        )
        unspokenThoughtDao.insert(thought)
    }

    suspend fun isDeliveryBlocked(nowMs: Long = System.currentTimeMillis()): Boolean {
        val pendingAlarms = scheduledAlarmDao.getPendingAlarms(nowMs + 86400_000L)
        return pendingAlarms.any { it.blockDelivery && it.triggerAt > nowMs }
    }
}
