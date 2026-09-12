package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.domain.model.Hint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 自定义提示语管理页状态（specs/004-custom-hints）：
 * 添加的提示语并入随机池（与内置提示语合并，拦截栈为空时随机抽取展示）。
 */
@HiltViewModel
class HintListViewModel @Inject constructor(
    private val hintRepository: HintRepository,
) : ViewModel() {

    /** 池列表（id 升序 = 添加顺序）。 */
    val hints: StateFlow<List<Hint>> =
        hintRepository.observePool().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
