package com.fivesec.app.util

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时调试日志：同时写 Logcat(tag=FSDBG) 与 App 内部文件，便于无 USB 时从手机导出。
 * 全部入口带 [DBG-FS] 前缀，确认根因后连同调用点整体删除。
 */
object DebugLog {
    private const val TAG = "FSDBG"
    private const val FILE_NAME = "fivesec_debug.log"
    private const val MAX_CHARS = 50_000

    @Synchronized
    fun log(context: Context, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.d(TAG, message)
        try {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val detail = if (throwable != null) " :: ${throwable.javaClass.name}: ${throwable.message}" else ""
            context.openFileOutput(FILE_NAME, Context.MODE_APPEND).use { out ->
                out.write("$ts $message$detail\n".toByteArray())
            }
        } catch (_: Exception) {
            // 日志失败不得影响主流程
        }
    }

    fun read(context: Context): String = try {
        context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }
            .let { if (it.length > MAX_CHARS) it.takeLast(MAX_CHARS) else it }
    } catch (_: Exception) {
        "(空)"
    }

    fun clear(context: Context) {
        try { context.deleteFile(FILE_NAME) } catch (_: Exception) {}
    }
}
