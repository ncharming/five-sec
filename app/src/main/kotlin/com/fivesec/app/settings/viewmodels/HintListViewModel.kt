package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.datastore.BuiltinHintsSetting
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.domain.model.Hint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 自定义提示语管理页状态（specs/004-custom-hints）：
 * 添加的提示语并入随机池（与内置提示语合并，拦截栈为空时随机抽取展示）。
 *
 * 内置提示语开关（默认开启）：
 *  - 状态持久化经 [BuiltinHintsSetting]（DataStore），重启/刷新后保持；
 *  - 切换走「乐观更新 + 串行落盘」：开关即时跟手，保存按触发顺序经 [saveMutex] 串行执行，
 *    快速连续切换不会出现旧写入覆盖新写入；
 *  - 保存失败（存储异常）发 [saveFailed] 一次性事件提示，且最后一个在途保存落定后
 *    以存储实际值收敛 UI——失败值从未写入，收敛即回退到切换前状态，界面不与配置脱节。
 */
@HiltViewModel
class HintListViewModel @Inject constructor(
    private val hintRepository: HintRepository,
    private val builtinHintsSetting: BuiltinHintsSetting,
) : ViewModel() {

    /** 池列表（id 升序 = 添加顺序）。 */
    val hints: StateFlow<List<Hint>> =
        hintRepository.observePool().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _builtinEnabled = MutableStateFlow(true)
    val builtinEnabled: StateFlow<Boolean> = _builtinEnabled.asStateFlow()

    /** 保存失败一次性事件（UI 层 toast 提示，避免界面与实际配置不一致而无感知）。 */
    private val _saveFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveFailed: SharedFlow<Unit> = _saveFailed.asSharedFlow()

    /** 在途保存计数：>0 时忽略存储回显，防保存完成事件打断更新的乐观态（闪烁/回跳）。 */
    private val pendingSaves = AtomicInteger(0)
    private val saveMutex = Mutex()

    init {
        viewModelScope.launch {
            builtinHintsSetting.builtinHintsEnabled.collect { saved ->
                if (pendingSaves.get() == 0) _builtinEnabled.value = saved
            }
        }
    }

    /**
     * 切换内置提示语开关：仅作用于内置条目是否参与随机，不影响自定义池与拦截栈。
     * 并发语义见类 KDoc；IOException 之外的异常（如取消）不吞、照常向上抛。
     */
    fun setBuiltinEnabled(enabled: Boolean) {
        if (_builtinEnabled.value == enabled) return
        _builtinEnabled.value = enabled // 乐观更新：先跟手，落定后以存储为准收敛
        pendingSaves.incrementAndGet()
        viewModelScope.launch {
            try {
                saveMutex.withLock { builtinHintsSetting.setBuiltinHintsEnabled(enabled) }
            } catch (e: IOException) {
                _saveFailed.tryEmit(Unit) // DataStore 写失败的典型形态；状态回退统一交给 finally 收敛
            } finally {
                // 最后一个在途保存落定后以存储实际值收敛：成功 = 最后写入值，失败 = 回退切换前值
                if (pendingSaves.decrementAndGet() == 0) {
                    runCatching { _builtinEnabled.value = builtinHintsSetting.builtinHintsEnabled.first() }
                }
            }
        }
    }

    /** 添加：空白忽略；超长截断（与 Repository 入口校验一致，双保险）。 */
    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        hintRepository.addPoolHint(trimmed.take(HintRepository.MAX_HINT_LENGTH))
    }

    fun remove(id: Long) {
        hintRepository.removePoolHint(id)
    }
}
