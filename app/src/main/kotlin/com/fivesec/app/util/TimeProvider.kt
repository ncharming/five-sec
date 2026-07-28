package com.fivesec.app.util

import javax.inject.Inject

/** 可注入的时钟，便于测试替换为确定性时间源。 */
fun interface TimeProvider {
    fun now(): Long
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
