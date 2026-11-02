package com.fivesec.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [StayBudget] 判定矩阵（specs/015）：未设/未超/恰好触线/超出。 */
class StayBudgetTest {

    @Test
    fun `未设预算无警示`() {
        assertFalse(StayBudget.isSet(0))
        assertNull(StayBudget.overMinutes(todayStayMinutes = 500, budgetMinutes = 0))
    }

    @Test
    fun `累计未达预算无警示`() {
        assertTrue(StayBudget.isSet(60))
        assertNull(StayBudget.overMinutes(todayStayMinutes = 59, budgetMinutes = 60))
    }

    @Test
    fun `恰好触线返回零——调用方据此展示已达而非超出0分钟`() {
        assertEquals(0L, StayBudget.overMinutes(todayStayMinutes = 60, budgetMinutes = 60))
    }

    @Test
    fun `超出返回超出分钟数`() {
        assertEquals(5L, StayBudget.overMinutes(todayStayMinutes = 65, budgetMinutes = 60))
        assertEquals(120L, StayBudget.overMinutes(todayStayMinutes = 300, budgetMinutes = 180))
    }
}
