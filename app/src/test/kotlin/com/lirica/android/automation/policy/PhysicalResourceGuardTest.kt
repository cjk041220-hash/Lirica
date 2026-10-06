package com.lirica.android.automation.policy

import com.lirica.android.perception.model.HardwareEnvironmentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理资源熔断回归（M5.0 §7.3 热功耗与电池健康保护）。
 */
class PhysicalResourceGuardTest {

    private fun guard(
        temperature: Float? = null,
        level: Int? = null,
        charging: Boolean = false
    ): PhysicalResourceGuard = PhysicalResourceGuard(
        stateProvider = {
            HardwareEnvironmentState(
                isCharging = charging,
                batteryTemperatureCelsius = temperature,
                batteryLevelPercent = level
            )
        }
    )

    @Test
    fun testNormalConditionsAllowEverything() {
        val verdict = guard(temperature = 35f, level = 80).evaluate()
        assertEquals(PhysicalGuardVerdict.Allow, verdict)
        assertTrue(guard(temperature = 35f, level = 80).isVisionAllowed())
    }

    @Test
    fun testThermalFuseAt42CelsiusBlocksVision() {
        val verdict = guard(temperature = 42f, level = 80).evaluate()
        assertTrue(">= 42℃ 必须熔断截屏与漫游（§7.3 硬性条款）", verdict is PhysicalGuardVerdict.BlockVision)
        assertEquals(PhysicalResourceGuard.RULE_THERMAL_VISION_FUSE, (verdict as PhysicalGuardVerdict.BlockVision).rule)
        assertFalse(guard(temperature = 42f, level = 80).isVisionAllowed())
        // 但轻量动作仍放行（方案只要求熔断视觉重通道）
        assertTrue(guard(temperature = 42f, level = 80).isAutomationAllowed())
    }

    @Test
    fun testCriticalThermalFuseStopsAllAutomation() {
        val verdict = guard(temperature = 49f, level = 80).evaluate()
        assertTrue(verdict is PhysicalGuardVerdict.BlockAll)
        assertFalse(guard(temperature = 49f, level = 80).isAutomationAllowed())
    }

    @Test
    fun testLowBatteryBlocksVisionOnlyWhenNotCharging() {
        assertTrue(guard(level = 19, charging = false).evaluate() is PhysicalGuardVerdict.BlockVision)
        assertEquals(
            "充电中不应因低电量限流",
            PhysicalGuardVerdict.Allow,
            guard(level = 19, charging = true).evaluate()
        )
        assertEquals(
            "恰好 20% 不算低电量（边界必须闭合）",
            PhysicalGuardVerdict.Allow,
            guard(level = 20, charging = false).evaluate()
        )
    }

    @Test
    fun testUnknownSignalsAreTreatedAsUnknownNotSafe() {
        // 用 null 表达"设备没上报"，判定为允许但绝不伪造 25℃/100% ——
        // 真正的防线是这里不会把 null 当成"安全值"去做任何限流之外的动作
        assertEquals(PhysicalGuardVerdict.Allow, guard().evaluate())
        assertEquals(PhysicalGuardVerdict.Allow, guard(temperature = null, level = null).evaluate())
    }

    @Test
    fun testThresholdsAreConfigurable() {
        val custom = PhysicalResourceGuard(
            stateProvider = { HardwareEnvironmentState(batteryTemperatureCelsius = 40f) },
            thresholds = PhysicalGuardThresholds(maxBatteryTemperatureCelsius = 38f)
        )
        assertTrue(custom.evaluate() is PhysicalGuardVerdict.BlockVision)
    }
}
