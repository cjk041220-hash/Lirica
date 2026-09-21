package com.xiaoxi.android.proactivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xiaoxi.android.database.dao.ScheduledAlarmDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

interface SystemAlarmScheduler {
    fun scheduleAlarm(alarmId: String, triggerAtMs: Long)
    fun cancelAlarm(alarmId: String)
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
        val pendingIntent = PendingIntent.getBroadcast(context, alarmId.hashCode(), intent, flags)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
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
        val pendingIntent = PendingIntent.getBroadcast(context, alarmId.hashCode(), intent, flags)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    companion object {
        const val ACTION_SCHEDULE_WAKE = "com.xiaoxi.android.action.SCHEDULE_WAKE"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
    }
}

class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AndroidSystemAlarmScheduler.ACTION_SCHEDULE_WAKE) {
            val alarmId = intent.getStringExtra(AndroidSystemAlarmScheduler.EXTRA_ALARM_ID) ?: return
            val app = context.applicationContext as? com.xiaoxi.android.XiaoxiApplication ?: return
            val pendingResult = goAsync()
            val scope = CoroutineScope(Dispatchers.IO)
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
