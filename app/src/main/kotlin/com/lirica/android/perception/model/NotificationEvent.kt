package com.lirica.android.perception.model

/**
 * 结构化通知有效载荷
 * @param id 通知全局唯一标识
 * @param packageName 发送通知的应用包名
 * @param title 通知标题
 * @param content 通知主体内容 (经过限长截断)
 * @param postTime 发送时间戳 (毫秒)
 * @param category 通知分类
 * @param isClearable 是否可由用户划掉清除
 * @param subText 次要说明或副标题
 */
data class NotificationEvent(
    val id: String,
    val packageName: String,
    val title: String,
    val content: String,
    val postTime: Long,
    val category: NotificationCategory,
    val isClearable: Boolean = true,
    val subText: String? = null
)
