package com.lirica.android.perception

import java.lang.ref.WeakReference

/**
 * 无障碍服务实例安全代理
 *
 * 采用弱引用持有运行中的无障碍服务实例，为 GestureExecutor 与截帧组件提供系统底层能力，防内存泄漏。
 */
object AccessibilityServiceHolder {

    @Volatile
    private var serviceRef: WeakReference<LiricaAccessibilityService>? = null

    internal fun register(service: LiricaAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    internal fun unregister() {
        serviceRef?.clear()
        serviceRef = null
    }

    fun get(): LiricaAccessibilityService? = serviceRef?.get()

    val isConnected: Boolean get() = get() != null
}
