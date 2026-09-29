package com.lirica.android.automation.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.lirica.android.common.logging.LiricaLog

/**
 * 真实应用标签与自然语言名称到包名解析器 (AppPackageResolver)
 *
 * 彻底解决自然语言应用名解析的两大隐患：
 * 1. 彻底废除未匹配时盲目返回中文原字符串作为包名的做法（避免触发底层必败的 Intent 异常）；
 * 2. 结合静态加速表与动态 PackageManager.queryIntentActivities 标签索引，支持拼音别名与中英文模糊匹配。
 */
class AppPackageResolver(
    private val context: Context? = null,
    private val customKnownPackages: Map<String, String>? = null,
    private val packageQueryRunner: (() -> Map<String, String>)? = null
) {
    private val dynamicAppCache = mutableMapOf<String, String>()

    companion object {
        /**
         * 常用核心应用加速索引与别名表（秒级冷启动直达）
         */
        val DEFAULT_KNOWN_APPS = mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "wx" to "com.tencent.mm",
            "微" to "com.tencent.mm",
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "qq" to "com.tencent.mobileqq",
            "腾讯qq" to "com.tencent.mobileqq",
            "淘宝" to "com.taobao.taobao",
            "tb" to "com.taobao.taobao",
            "美团" to "com.sankuai.meituan",
            "美团外卖" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "ele" to "me.ele",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "抖音" to "com.ss.android.ugc.aweme",
            "快手" to "com.smile.gifmaker",
            "小红书" to "com.xingin.xhs",
            "xhs" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "微博" to "com.sina.weibo",
            "新浪微博" to "com.sina.weibo",
            "京东" to "com.jingdong.app.mall",
            "jd" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "pdd" to "com.xunmeng.pinduoduo",
            "网易云音乐" to "com.netease.cloudmusic",
            "网易云" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "支付宝" to "com.eg.android.AlipayGphone",
            "zfb" to "com.eg.android.AlipayGphone",
            "系统设置" to "com.android.settings",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery3d"
        )
    }

    /**
     * 将用户输入的应用名称解析为真实 Android 包名。
     *
     * @param appNameOrPackage 自然语言应用名称或包名
     * @return 真实包名字符串；若未能识别且未在设备中找到，返回 null
     */
    fun resolve(appNameOrPackage: String): String? {
        val trimmed = appNameOrPackage.trim()
        if (trimmed.isEmpty()) return null

        // 1. 若输入本身已是标准包名（含 '.' 且无空格），直接返回
        if (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 3) {
            return trimmed
        }

        val lower = trimmed.lowercase()

        // 2. 静态已知加速映射表
        customKnownPackages?.get(lower)?.let { return it }
        DEFAULT_KNOWN_APPS[lower]?.let { return it }

        // 3. 内存动态缓存命中
        synchronized(dynamicAppCache) {
            dynamicAppCache[lower]?.let { return it }
        }

        // 4. 基于 PackageManager 查询系统实际已安装的启动器应用标签
        val installedApps = queryInstalledApps()
        synchronized(dynamicAppCache) {
            dynamicAppCache.putAll(installedApps)

            // 精确标签匹配
            dynamicAppCache[lower]?.let { return it }

            // 包含匹配（例如输入 "网易云" 匹配到已安装的 "网易云音乐"）
            val match = dynamicAppCache.entries.firstOrNull { (label, _) ->
                label.contains(lower) || lower.contains(label)
            }
            if (match != null) {
                return match.value
            }
        }

        // 5. 无法解析时返回 null，避免将中文乱码传给 Intent 启动器
        return null
    }

    private fun queryInstalledApps(): Map<String, String> {
        if (packageQueryRunner != null) {
            return packageQueryRunner.invoke()
        }

        val ctx = context ?: return emptyMap()
        return runCatching {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = pm.queryIntentActivities(intent, 0)
            val result = mutableMapOf<String, String>()
            for (resolveInfo in activities) {
                val pkg = resolveInfo.activityInfo?.packageName ?: continue
                val label = resolveInfo.loadLabel(pm).toString().trim().lowercase()
                if (label.isNotEmpty()) {
                    result[label] = pkg
                }
            }
            result
        }.getOrElse {
            LiricaLog.e("AppPackageResolver", "查询系统安装应用失败", it)
            emptyMap()
        }
    }
}
