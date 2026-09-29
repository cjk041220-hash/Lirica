package com.lirica.android.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.lirica.android.service.KeepAliveService

/**
 * 系统唤醒广播接收器 (BootReceiver)
 *
 * 监听设备开机与应用升级替换事件，自动拉起前台保活服务 KeepAliveService，
 * 恢复伴生体常驻生命周期。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Triggering KeepAliveService wake-up from action: $action")
                try {
                    val serviceIntent = Intent(context, KeepAliveService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start KeepAliveService from BootReceiver", e)
                }
            }
            else -> {
                Log.d(TAG, "Ignoring unrelated broadcast action: $action")
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
