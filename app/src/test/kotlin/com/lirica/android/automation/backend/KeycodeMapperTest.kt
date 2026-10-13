package com.lirica.android.automation.backend

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeycodeMapperTest {

    @Test
    fun testResolveKeyCode_symbolicNames() {
        assertEquals(KeyEvent.KEYCODE_BACK, KeycodeMapper.resolveKeyCode("BACK"))
        assertEquals(KeyEvent.KEYCODE_BACK, KeycodeMapper.resolveKeyCode("back"))
        assertEquals(KeyEvent.KEYCODE_BACK, KeycodeMapper.resolveKeyCode("返回"))
        assertEquals(KeyEvent.KEYCODE_HOME, KeycodeMapper.resolveKeyCode("HOME"))
        assertEquals(KeyEvent.KEYCODE_HOME, KeycodeMapper.resolveKeyCode("主页"))
        assertEquals(KeyEvent.KEYCODE_ENTER, KeycodeMapper.resolveKeyCode("ENTER"))
        assertEquals(KeyEvent.KEYCODE_ENTER, KeycodeMapper.resolveKeyCode("回车"))
        assertEquals(KeyEvent.KEYCODE_DEL, KeycodeMapper.resolveKeyCode("DEL"))
        assertEquals(KeyEvent.KEYCODE_DEL, KeycodeMapper.resolveKeyCode("DELETE"))
        assertEquals(KeyEvent.KEYCODE_SEARCH, KeycodeMapper.resolveKeyCode("SEARCH"))
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH, KeycodeMapper.resolveKeyCode("RECENTS"))
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, KeycodeMapper.resolveKeyCode("VOLUME_UP"))
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, KeycodeMapper.resolveKeyCode("VOLUME_DOWN"))
        assertEquals(KeyEvent.KEYCODE_POWER, KeycodeMapper.resolveKeyCode("POWER"))
    }

    @Test
    fun testResolveKeyCode_returnMapsToEnter_notBack() {
        // M6.0：键盘语义 RETURN=Enter。旧实现误映射为 BACK，模型按直觉使用
        // "RETURN" 会打出相反方向的动作（体检报告 P3-6①）。
        assertEquals(KeyEvent.KEYCODE_ENTER, KeycodeMapper.resolveKeyCode("RETURN"))
    }

    @Test
    fun testResolveKeyCode_numericString_rejected() {
        // M6.0 P0-1：数字键码直通已删除。键码注入面收敛到符号白名单——
        // "66"→ENTER、"4"→BACK 的旧断言守护的是被判定为缺陷的行为（闸门
        // 无法对任意键码做文本级审查），按 fail-closed 原则反转为 null。
        assertNull(KeycodeMapper.resolveKeyCode("66"))
        assertNull(KeycodeMapper.resolveKeyCode("4"))
        assertNull(KeycodeMapper.resolveKeyCode("-1"))
    }

    @Test
    fun testResolveKeyCode_invalidOrBlank_returnsNull() {
        assertNull(KeycodeMapper.resolveKeyCode(""))
        assertNull(KeycodeMapper.resolveKeyCode("   "))
        assertNull(KeycodeMapper.resolveKeyCode("NON_EXISTENT_KEY"))
    }
}
