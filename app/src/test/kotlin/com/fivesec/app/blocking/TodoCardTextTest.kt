package com.fivesec.app.blocking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TodoCardText 单测（多行待办口径修订，2026-09）：卡片永远输出单行——
 * 取首个非空行、裁首尾空白、超 12 字截断加省略号（省略号不计入 12）。
 */
class TodoCardTextTest {

    @Test
    fun `无换行长文本截断到12字加省略号`() {
        assertEquals("一二三四五六七八九十一二…", TodoCardText.project("一二三四五六七八九十一二三四五"))
    }

    @Test
    fun `恰好12字不加省略号`() {
        val twelve = "一二三四五六七八九十一二"
        assertEquals(twelve, TodoCardText.project(twelve))
    }

    @Test
    fun `多行文本只取首行`() {
        assertEquals("买牛奶", TodoCardText.project("买牛奶\n还有酸奶\n第三行"))
    }

    @Test
    fun `首行带首尾空白被裁剪`() {
        assertEquals("买牛奶", TodoCardText.project("  买牛奶  \n第二行"))
    }

    @Test
    fun `首行为空白行时跳到首个非空行`() {
        assertEquals("第二行内容", TodoCardText.project("\n \n第二行内容\n第三行"))
    }

    @Test
    fun `多行首行超长只截首行`() {
        assertEquals("一二三四五六七八九十一二…", TodoCardText.project("一二三四五六七八九十一二三四五\n第二行"))
    }
}
