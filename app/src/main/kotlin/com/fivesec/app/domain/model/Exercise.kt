package com.fivesec.app.domain.model

/** MVP 中锻炼内容固定为 5 秒提肛（凯格尔）。未来可扩展为可轮换的微锻炼。 */
object Exercise {
    const val ID = "kegel_5s"
    const val NAME = "提肛"
    const val DURATION_SECONDS = 5
}
