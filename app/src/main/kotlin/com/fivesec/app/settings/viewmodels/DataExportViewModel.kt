package com.fivesec.app.settings.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.export.CsvExporter
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.data.repository.UsageSessionRepository
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地数据导出（specs/017）：SAF 选定位置后组装四段 CSV（[CsvExporter]）写入——
 * 只读导出（不删不改库内数据）、零新权限（SAF 无需声明）、零网络。
 * 结果一次性事件：成功/失败（中文文案在 UI 层资源呈现）；用户在文件选择器取消 = 无回调无打扰。
 */
@HiltViewModel
class DataExportViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val interceptionRepository: InterceptionRepository,
    private val todoRepository: TodoRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    sealed interface ExportEvent {
        data object Success : ExportEvent
        data class Failed(val reason: String?) : ExportEvent
    }

    private val _events = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ExportEvent> = _events.asSharedFlow()

    /** 建议文件名：five-sec-export-yyyy-MM-dd.csv（同日多次导出由 SAF 自动追加 (1)。 */
    fun suggestedFileName(): String =
        "five-sec-export-" + DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .format(java.time.Instant.ofEpochMilli(timeProvider.now()).atZone(ZoneId.systemDefault()).toLocalDate()) + ".csv"

    /** 组装并写入用户选定 URI：IO 全在后台线程；任何一步失败发 Failed（不崩不半写——SAF 临时文件语义由系统收口）。 */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val csv = CsvExporter.build(
                        events = interceptionRepository.exportSnapshot(),
                        sessions = usageSessionRepository.exportSnapshot(),
                        completions = todoRepository.completionsSnapshot(),
                        todos = todoRepository.allTodosOnce(),
                        zone = ZoneId.systemDefault(),
                    )
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开所选位置")
                }
            }.fold(
                onSuccess = { _events.tryEmit(ExportEvent.Success) },
                onFailure = { _events.tryEmit(ExportEvent.Failed(it.message)) },
            )
        }
    }
}
