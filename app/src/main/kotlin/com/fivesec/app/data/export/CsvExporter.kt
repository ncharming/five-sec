package com.fivesec.app.data.export

import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.domain.model.UsageSession
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 本地数据导出 CSV 序列化（specs/017）：四段结构、UTF-8 带 BOM（Excel 直开不乱码）、
 * RFC 4180 转义（逗号/引号/换行包双引号、内部引号翻倍）；时间戳双列（epoch 毫秒 +
 * 显式 [ZoneId] 的本地可读形——导出文件在别的机器上看也要能对上时区语境）。
 *
 * 纯函数零 Android 依赖（与 DAO 快照读取解耦，单测直测）；段标题/表头/规则名是**数据内容**
 * 而非 UI 文案（导出文件给用户自己看/归档，保持自描述中文表头；strings.xml 约束不适用于
 * 生成数据）。导出只读——本类不改任何库内数据。
 */
object CsvExporter {

    private val READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private const val BOM = "﻿"

    fun build(
        events: List<InterceptionEvent>,
        sessions: List<UsageSession>,
        completions: List<TodoCompletion>,
        todos: List<Todo>,
        zone: ZoneId,
    ): String = buildString {
        append(BOM)
        appendSection("拦截事件", "时间戳,时间,应用,结局,完成锻炼")
        events.forEach { e ->
            appendLine(
                listOf(
                    e.timestamp.toString(),
                    readable(e.timestamp, zone),
                    e.packageName,
                    e.outcome.name,
                    if (e.exerciseCompleted) "是" else "否",
                ).joinToString(",") { escape(it) },
            )
        }
        appendSection("使用会话", "开始时间戳,开始时间,结束时间戳,结束时间,停留分钟,应用,回弹次数,结束原因")
        sessions.forEach { s ->
            appendLine(
                listOf(
                    s.startedAt.toString(),
                    readable(s.startedAt, zone),
                    s.endedAt.toString(),
                    readable(s.endedAt, zone),
                    ((s.durationMillis + 59_999) / 60_000).toString(),
                    s.packageName,
                    s.guardShownCount.toString(),
                    s.endReason.name,
                ).joinToString(",") { escape(it) },
            )
        }
        appendSection("待办完成", "完成日期,待办内容,过期补完")
        completions.forEach { c ->
            appendLine(
                listOf(
                    c.completedDate,
                    c.todoText,
                    if (c.wasExpired) "是" else "否",
                ).joinToString(",") { escape(it) },
            )
        }
        appendSection("待办清单（当前快照）", "内容,启用,重复规则,提醒时刻,创建日,有效期日")
        todos.forEach { t ->
            appendLine(
                listOf(
                    t.text,
                    if (t.isEnabled) "是" else "否",
                    ruleText(t),
                    t.reminderTimes,
                    t.createdAt,
                    t.dueDate,
                ).joinToString(",") { escape(it) },
            )
        }
    }

    private fun StringBuilder.appendSection(title: String, header: String) {
        appendLine()
        appendLine("# $title")
        appendLine(header)
    }

    private fun readable(epochMillis: Long, zone: ZoneId): String =
        READABLE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** RFC 4180：含逗号/引号/换行时整体包双引号，内部引号翻倍。 */
    private fun escape(field: String): String = when {
        field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ->
            "\"" + field.replace("\"", "\"\"") + "\""

        else -> field
    }

    /** 规则自描述文本（与编辑弹窗同一词汇表）：每天 / 每周一、三 / 每 N 天 / 单次。 */
    private fun ruleText(todo: Todo): String = when (todo.repeatType) {
        TodoRecurrence.REPEAT_WEEKLY -> {
            val days = DayOfWeek.entries.filter { todo.repeatDays and TodoRecurrence.bitOf(it) != 0 }
            if (days.isEmpty()) {
                "每周几"
            } else {
                "每" + days.joinToString("、") { dayName(it) }
            }
        }

        TodoRecurrence.REPEAT_INTERVAL -> "每 ${todo.intervalDays} 天"
        TodoRecurrence.REPEAT_ONCE -> "单次"
        else -> "每天"
    }

    private fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
}
