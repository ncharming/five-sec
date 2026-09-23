package com.fivesec.app.data.datastore

import com.fivesec.app.data.repository.HintCursorStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * [HintCursorStore] 的 DataStore 实现（specs/005-daily-todos）：
 * 循环游标存进既有 `five_sec_settings` 库（新键），不引入第二种持久化栈。
 * 端口化的唯一目的是让 HintRepositoryTest 保持纯 JVM（fake StateFlow 即可，无需 Robolectric）。
 */
@Singleton
class DataStoreHintCursorStore @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : HintCursorStore {

    override fun observeCursor(): Flow<Int> = settingsDataStore.hintCursor

    override suspend fun writeCursor(value: Int) = settingsDataStore.setHintCursor(value)
}
