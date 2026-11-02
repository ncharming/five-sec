package com.fivesec.app.data.export

import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.domain.model.UsageSession
import com.fivesec.app.domain.model.UsageSessionEndReason
import com.fivesec.app.util.TodoRecurrence
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CsvExporter] 序列化矩阵（specs/017）：四段结构与段序、BOM、RFC 4180 转义
 * （逗号/引号/换行）、时间双列（显式 ZoneId——CI UTC 与本地跑结果一致）、空表段。
 */
class CsvExporterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String, time: String): Long =
        LocalDate.parse(date).atTime(time.split(":")[0].toInt(), time.split(":")[1].toInt())
            .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `BOM 打头且四段按序`() {
        val csv = CsvExporter.build(
            events = listOf(
                InterceptionEvent(
                    packageName = "com.ss.android.ugc.aweme",
                    timestamp = millis("2026-11-01", "20:00"),
                    exerciseCompleted = true,
                    outcome = InterceptionOutcome.CANCELED,
                ),
            ),
            sessions = emptyList(),
            completions = emptyList(),
            todos = emptyList(),
            zone = zone,
        )
        assertTrue(csv.startsWith("﻿"))
        val sections = csv.split("# ")
        assertEquals(5, sections.size) // BOM 头 + 四段
        assertTrue(sections[1].startsWith("拦截事件"))
        assertTrue(sections[2].startsWith("使用会话"))
        assertTrue(sections[3].startsWith("待办完成"))
        assertTrue(sections[4].startsWith("待办清单（当前快照）"))
    }

    @Test
    fun `事件行时间双列与本地可读形`() {
        val csv = CsvExporter.build(
            events = listOf(
                InterceptionEvent(
                    packageName = "tv.danmaku.bili",
                    timestamp = millis("2026-11-01", "08:30"),
                    exerciseCompleted = false,
                    outcome = InterceptionOutcome.INTERRUPTED,
                ),
            ),
            sessions = emptyList(),
            completions = emptyList(),
            todos = emptyList(),
            zone = zone,
        )
        val dataLine = csv.lineSequence().first { it.contains("2026-11-01 08:30:00") }
        assertTrue(dataLine.contains(millis("2026-11-01", "08:30").toString())) // epoch 双列同现
        assertTrue(dataLine.contains("tv.danmaku.bili"))
        assertTrue(dataLine.contains("INTERRUPTED"))
        assertTrue(dataLine.endsWith("否"))
    }

    @Test
    fun `含逗号引号换行的待办文本被转义`() {
        val csv = CsvExporter.build(
            events = emptyList(),
            sessions = emptyList(),
            completions = emptyList(),
            todos = listOf(
                Todo(id = 1, text = "读书,20页\n\"重点\"章节", createdAt = "2026-11-01"),
            ),
            zone = zone,
        )
        // RFC 4180：整段包引号、内部引号翻倍、换行保留在引号内（物理行被拆开是正常的——按整串断言）
        assertTrue(csv.contains("\"读书,20页\n\"\"重点\"\"章节\""))
        assertTrue(csv.contains("2026-11-01")) // 同行其余列照常
    }

    @Test
    fun `会话停留分钟向上取整`() {
        val csv = CsvExporter.build(
            events = emptyList(),
            sessions = listOf(
                UsageSession(
                    packageName = "com.xingin.xhs",
                    startedAt = 0L,
                    endedAt = 90_001L, // 1.5 分钟 → 2
                    durationMillis = 90_001L,
                    guardShownCount = 1,
                    endReason = UsageSessionEndReason.GUARD_ENDED,
                ),
            ),
            completions = emptyList(),
            todos = emptyList(),
            zone = zone,
        )
        assertTrue(csv.lineSequence().any { it.contains(",2,tv") || (it.contains("com.xingin.xhs") && it.contains(",2,")) })
    }

    @Test
    fun `完成事件带过期补完标记与规则自描述`() {
        val csv = CsvExporter.build(
            events = emptyList(),
            sessions = emptyList(),
            completions = listOf(
                TodoCompletion(todoId = 1, todoText = "俯卧撑", completedDate = "2026-11-01", wasExpired = true),
            ),
            todos = listOf(
                Todo(
                    id = 2,
                    text = "每周一三",
                    repeatType = TodoRecurrence.REPEAT_WEEKLY,
                    repeatDays = TodoRecurrence.weeklyMask(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY)),
                    createdAt = "2026-10-01",
                    reminderTimes = "20:00,21:00",
                ),
            ),
            zone = zone,
        )
        assertTrue(csv.contains("俯卧撑"))
        assertTrue(csv.lineSequence().any { it.endsWith("是") && it.contains("俯卧撑") }) // 过期补完=是
        assertTrue(csv.contains("每周一、周三")) // 规则自描述：天名顿号连接
        assertTrue(csv.contains("20:00,21:00")) // 列内逗号被整体转义，原始内容仍完整可读
    }

    @Test
    fun `空表只有段头与表头无数据行`() {
        val csv = CsvExporter.build(emptyList(), emptyList(), emptyList(), emptyList(), zone)
        assertEquals(4, csv.lineSequence().count { it.startsWith("# ") })
        assertEquals(4, csv.lineSequence().count { it.contains("时间戳") || it.startsWith("完成日期") || it.startsWith("内容,") })
        // 无任何数据行：总行数 = 1(BOM行) + 4×(段头+表头) + 3(段间空行) ± 末行换行
        assertTrue(csv.lineSequence().count() in 12..13)
    }
}
