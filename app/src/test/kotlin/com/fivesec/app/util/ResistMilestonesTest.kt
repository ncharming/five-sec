package com.fivesec.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ResistMilestones] 谓词矩阵（specs/016）：50 / 100 的倍数；0 与夹缝值恒否。 */
class ResistMilestonesTest {

    @Test
    fun `五十次是首个里程碑`() {
        assertTrue(ResistMilestones.isMilestone(50))
    }

    @Test
    fun `一百的倍数是里程碑`() {
        assertTrue(ResistMilestones.isMilestone(100))
        assertTrue(ResistMilestones.isMilestone(200))
        assertTrue(ResistMilestones.isMilestone(300))
        assertTrue(ResistMilestones.isMilestone(1000))
    }

    @Test
    fun `夹缝值不是里程碑`() {
        assertFalse(ResistMilestones.isMilestone(49))
        assertFalse(ResistMilestones.isMilestone(51))
        assertFalse(ResistMilestones.isMilestone(99))
        assertFalse(ResistMilestones.isMilestone(101))
        assertFalse(ResistMilestones.isMilestone(150))
    }

    @Test
    fun `零与负数恒否——脏值防御`() {
        assertFalse(ResistMilestones.isMilestone(0))
        assertFalse(ResistMilestones.isMilestone(-100))
    }
}
