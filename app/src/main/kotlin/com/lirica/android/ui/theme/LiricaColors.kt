package com.lirica.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Lirica 暮夜星紫 (Dusk Velvet Violet) 官方调色板
 *
 * 专为单用户、常驻夜间陪伴设计的四阶丝绒夜色体系。
 * 融合沉静包容的年上沉稳底色与灵动温润的星紫光晕。
 */
object LiricaColors {
    // 基础环境与底色 (深邃丝绒夜色，极低炫光)
    val BackgroundDeep = Color(0xFF0D0C13)
    val BackgroundSurface = Color(0xFF15131E)
    val CardSurface = Color(0xFF1A1726)
    val CardBorder = Color(0x1FB4A5F5)

    // HerLight 她的光核心色谱
    val VioletCore = Color(0xFFA895F8)       // 主光核 (薰衣草柔紫)
    val VioletLight = Color(0xFFC9BFFE)      // 星尘高光 (星尘淡紫)
    val VioletDeep = Color(0xFF7755F5)       // 幻紫基底 (深邃幻紫)
    val HaloLight = Color(0x40A895F8)        // 呼吸弥散光晕
    val AmbientGlow = Color(0x24876EF0)      // 环境柔和投光

    // 交互与状态色
    val Accent = Color(0xFFA895F8)
    val AccentGlow = Color(0x60A895F8)
    val Danger = Color(0xFFFF6B6B)           // 告警/打断/电量焦虑
    val Warning = Color(0xFFFF9F1C)          // 自愈重试/关注
    val Success = Color(0xFF69DB7C)          // 任务完成/电量饱满
    val NightDim = Color(0x306E6A85)

    // 文字阶梯
    val TextMain = Color(0xFFEFEFF6)         // 正文
    val TextSecondary = Color(0xFFA6A3BD)    // 次要说明与时间
    val TextDim = Color(0xFF6E6A85)          // 占位与微弱沉淀

    // 气泡特化
    val BubbleUser = Color(0xFF222030)
    val BubbleHerGlow = Color(0x14A895F8)
    val BubbleHerBorder = Color(0x38A895F8)

    // 时光回响 (被记得的证据)
    val EchoHighlight = Color(0x30A895F8)
    val EchoDot = Color(0xFFC9BFFE)
}
