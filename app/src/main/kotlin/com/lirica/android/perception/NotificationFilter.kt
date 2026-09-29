package com.lirica.android.perception

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.lirica.android.perception.model.NotificationCategory
import com.lirica.android.perception.model.NotificationEvent

/**
 * 低能耗关键通知过滤与结构化提取器
 * 过滤常驻/下载进度/空通知，实施严格字符截断与语义分类
 */
class NotificationFilter {

    companion object {
        const val MAX_CONTENT_LENGTH = 256
        const val MAX_TITLE_LENGTH = 128

        private val COMMUNICATION_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "org.telegram.messenger",
            "com.whatsapp"
        )

        private val TRAVEL_PACKAGES = setOf(
            "com.sankuai.meituan",
            "me.ele",
            "com.sdu.didi.psnger",
            "com.autonavi.minimap",
            "com.MobileTicket",
            "com.cainiao.wireless",
            "com.sf.activity"
        )

        private val SCHEDULE_PACKAGES = setOf(
            "com.android.calendar",
            "com.google.android.calendar",
            "com.miui.calendar",
            "com.huawei.calendar",
            "com.bbk.calendar"
        )
    }

    /**
     * 过滤 StatusBarNotification 并提取结构化通知事件
     * @return 符合保留条件的通知返回结构化事件，否则返回 null (快速丢弃)
     */
    fun filterAndExtract(sbn: StatusBarNotification?): NotificationEvent? {
        if (sbn == null) return null

        // 1. 过滤常驻通知 (正在播放、前台常驻服务等，高耗能噪音)
        if (sbn.isOngoing) {
            return null
        }

        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        // 2. 过滤下载进度条与不确定进度通知
        val maxProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (maxProgress > 0 || isIndeterminate) {
            return null
        }

        // 3. 提取标题与内容
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val rawSubText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()

        // 空通知过滤
        if (rawTitle.isEmpty() && rawText.isEmpty()) {
            return null
        }

        // 4. 严格限长截断，避免长文本导致内存或后续 LLM 开销膨胀
        val title = rawTitle.take(MAX_TITLE_LENGTH)
        val content = rawText.take(MAX_CONTENT_LENGTH)
        val subText = rawSubText?.take(MAX_TITLE_LENGTH)

        // 5. 语义分类判定
        val category = classifyCategory(sbn.packageName, title, content)

        // 5.1 安全与验证码类通知物理丢弃：Lirica 仅做生活感知，绝不接触任何凭证类信息
        if (category == NotificationCategory.SECURITY) {
            return null
        }

        val eventId = "${sbn.packageName}_${sbn.id}_${sbn.postTime}"

        return NotificationEvent(
            id = eventId,
            packageName = sbn.packageName,
            title = title,
            content = content,
            postTime = sbn.postTime,
            category = category,
            isClearable = sbn.isClearable,
            subText = subText
        )
    }

    private fun classifyCategory(packageName: String, title: String, content: String): NotificationCategory {
        val fullText = "$title $content"

        // 优先检查安全与验证码
        // 2026-09-27 审计 P1 修复：补「校验码」与宽松 "code"/"口令" 启发式，宁枉勿纵——
        // 含这些关键词的通知一律物理丢弃，绝不接触任何凭证类信息
        if (fullText.contains("验证码") ||
            fullText.contains("校验码") ||
            fullText.contains("动态口令") ||
            fullText.contains("动态码") ||
            fullText.contains("口令") ||
            fullText.contains("验证代码") ||
            fullText.contains("verification code", ignoreCase = true) ||
            (fullText.contains("verification", ignoreCase = true) && fullText.contains("code", ignoreCase = true)) ||
            fullText.contains("OTP", ignoreCase = true) ||
            fullText.contains("2FA", ignoreCase = true)
        ) {
            return NotificationCategory.SECURITY
        }

        // 社交通讯
        if (COMMUNICATION_PACKAGES.contains(packageName)) {
            return NotificationCategory.COMMUNICATION
        }

        // 日程与待办
        if (SCHEDULE_PACKAGES.contains(packageName) ||
            fullText.contains("日程") ||
            fullText.contains("会议") ||
            fullText.contains("待办") ||
            fullText.contains("闹钟") ||
            fullText.contains("提醒")
        ) {
            return NotificationCategory.SCHEDULE
        }

        // 外卖、出行与物流
        if (TRAVEL_PACKAGES.contains(packageName) ||
            fullText.contains("骑手") ||
            fullText.contains("送达") ||
            fullText.contains("外卖") ||
            fullText.contains("航班") ||
            fullText.contains("登机") ||
            fullText.contains("动车") ||
            fullText.contains("火车") ||
            fullText.contains("取件") ||
            fullText.contains("快递")
        ) {
            return NotificationCategory.TRAVEL_LOGISTICS
        }

        return NotificationCategory.OTHER
    }
}
