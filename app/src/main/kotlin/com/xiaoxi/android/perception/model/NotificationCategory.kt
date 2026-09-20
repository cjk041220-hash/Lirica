package com.xiaoxi.android.perception.model

/**
 * 通知业务与语义分类
 */
enum class NotificationCategory {
    /** 社交与即时通讯 (微信、QQ、短信、钉钉、飞书等) */
    COMMUNICATION,
    /** 日程与待办提醒 (日历、备忘、待办、闹钟) */
    SCHEDULE,
    /** 外卖、出行与物流 (美团外卖、饿了么、滴滴出行、高德、12306、快递) */
    TRAVEL_LOGISTICS,
    /** 安全与身份认证 (验证码、2FA、安全警报) */
    SECURITY,
    /** 系统高优警报 */
    SYSTEM_ALERT,
    /** 其他通过过滤的普通有效通知 */
    OTHER
}
