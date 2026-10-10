package com.fivesec.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 抵制率纯函数口径（specs/011）：四舍五入、分母 0 → null、INTERRUPTED 不进分母（由签名保证）。 */
class ResistRateTest {

    @Test
    fun `常规比例四舍五入取整`() {
        assertEquals(75, ResistRate.percent(canceled = 3, opened = 1))
        assertEquals(33, ResistRate.percent(canceled = 1, opened = 2))   // 33.33 → 33
        assertEquals(67, ResistRate.percent(canceled = 2, opened = 1))   // 66.67 → 67
        assertEquals(50, ResistRate.percent(canceled = 1, opened = 1))
    }

    @Test
    fun `全抵制为满分全打开为零分`() {
        assertEquals(100, ResistRate.percent(canceled = 4, opened = 0))
        assertEquals(0, ResistRate.percent(canceled = 0, opened = 4))
    }

    @Test
    fun `分母为零返回null而非零`() {
        assertNull(ResistRate.percent(canceled = 0, opened = 0))
    }

    @Test
    fun `打断不进分母由签名保证取消与打开之外的计数不存在`() {
        // INTERRUPTED 不可能传入本函数——分母只认真实选择，这里锁死唯一入口的语义
        assertEquals(100, ResistRate.percent(canceled = 2, opened = 0))
    }
}
