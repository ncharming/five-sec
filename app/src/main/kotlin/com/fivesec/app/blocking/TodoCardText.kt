package com.fivesec.app.blocking

/**
 * 拦截卡片单条文本投影（纯函数，零 Android 依赖）。
 *
 * 多行待办（2026-09 口径修订，取代 specs/005 的「换行折叠为空格」）：编辑弹窗真实存 \n，
 * 详情弹窗按行渲染、待办页列表单行省略；覆盖层卡片取**首个非空行**再截 12 字——
 * 首行是用户按回车前写完的完整第一句，是 5 秒场景里的事实标题，后续行只在 App 内可见。
 * 独立成对象而非 BlockingOverlay 私有方法：覆盖层是 Android 视图类不可单测，
 * 可测的展示判定按项目惯例抽成纯逻辑直测。
 */
object TodoCardText {
    /** 卡片单条展示字符上限（specs/007：30→12，5 秒可读更克制；省略号不计入 12）。 */
    const val DISPLAY_MAX = 12

    /** 首个非空行 → 首尾空白裁剪 → 超限截断加省略号。输入恒非全空白（仓库 normalize 兜底）。 */
    fun project(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return if (firstLine.length > DISPLAY_MAX) firstLine.take(DISPLAY_MAX) + "…" else firstLine
    }
}
