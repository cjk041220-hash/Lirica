package com.lirica.android.automation.backend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuExecutionBackendTest {

    @Test
    fun testIsAvailable_whenBinderDeadOrNoPermission_returnsFalse() = runTest {
        val backend1 = ShizukuExecutionBackend(
            isBinderAliveProvider = { false },
            permissionChecker = { true }
        )
        assertFalse(backend1.isAvailable())

        val backend2 = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { false }
        )
        assertFalse(backend2.isAvailable())
    }

    @Test
    fun testIsAvailable_whenReady_returnsTrue() = runTest {
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true }
        )
        assertTrue(backend.isAvailable())
    }

    @Test
    fun testClick_executesInputTapCommand() = runTest {
        val executedCommands = mutableListOf<String>()
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true },
            shellExecutor = { cmd ->
                executedCommands.add(cmd)
                true
            }
        )

        val result = backend.click(540f, 1200f)
        assertTrue(result)
        assertEquals(listOf("input tap 540 1200"), executedCommands)
    }

    @Test
    fun testLongClick_executesInputSwipeStationaryCommand() = runTest {
        val executedCommands = mutableListOf<String>()
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true },
            shellExecutor = { cmd ->
                executedCommands.add(cmd)
                true
            }
        )

        val result = backend.longClick(300f, 600f, 1500L)
        assertTrue(result)
        assertEquals(listOf("input swipe 300 600 300 600 1500"), executedCommands)
    }

    @Test
    fun testSwipe_executesInputSwipeCommand() = runTest {
        val executedCommands = mutableListOf<String>()
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true },
            shellExecutor = { cmd ->
                executedCommands.add(cmd)
                true
            }
        )

        val result = backend.swipe(100f, 200f, 300f, 400f, 500L)
        assertTrue(result)
        assertEquals(listOf("input swipe 100 200 300 400 500"), executedCommands)
    }

    @Test
    fun testSendKeycode_executesInputKeyeventCommand() = runTest {
        val executedCommands = mutableListOf<String>()
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true },
            shellExecutor = { cmd ->
                executedCommands.add(cmd)
                true
            }
        )

        val result = backend.sendKeycode(4) // KEYCODE_BACK
        assertTrue(result)
        assertEquals(listOf("input keyevent 4"), executedCommands)
    }

    @Test
    fun testInputText_escapesSpacesAndSpecialChars() = runTest {
        val executedCommands = mutableListOf<String>()
        val backend = ShizukuExecutionBackend(
            isBinderAliveProvider = { true },
            permissionChecker = { true },
            shellExecutor = { cmd ->
                executedCommands.add(cmd)
                true
            }
        )

        val result = backend.inputText("hello world 123")
        assertTrue(result)
        assertEquals(listOf("input text \"hello%sworld%s123\""), executedCommands)
    }
}
