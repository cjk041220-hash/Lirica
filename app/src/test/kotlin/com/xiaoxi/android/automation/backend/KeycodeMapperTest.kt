package com.xiaoxi.android.automation.backend

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
    fun testResolveKeyCode_numericString() {
        assertEquals(KeyEvent.KEYCODE_ENTER, KeycodeMapper.resolveKeyCode("66"))
        assertEquals(KeyEvent.KEYCODE_BACK, KeycodeMapper.resolveKeyCode("4"))
    }

    @Test
    fun testResolveKeyCode_invalidOrBlank_returnsNull() {
        assertNull(KeycodeMapper.resolveKeyCode(""))
        assertNull(KeycodeMapper.resolveKeyCode("   "))
        assertNull(KeycodeMapper.resolveKeyCode("NON_EXISTENT_KEY"))
    }
}
