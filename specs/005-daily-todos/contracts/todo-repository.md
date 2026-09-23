# Contract: TodoRepository（待办存储与快照）

**Date**: 2026-09-23 | **Spec**: [spec.md](spec.md) | **Data Model**: [data-model.md](../data-model.md)

## 职责

待办数据的唯一聚合点：对上（待办页 ViewModel）提供 Flow 观察与 suspend 写入；对下（无障碍服务）提供主线程同步快照。模式与 `HintRepository`/`InterceptionController` 一致——后台收集 + 内存快照 + 锁保护。

## API

```kotlin
@Singleton
class TodoRepository @Inject constructor(private val todoDao: TodoDao) {
    /** 服务侧主线程同步调用：启用条目（id 升序）映射为覆盖层快照行。 */
    fun todayTodos(today: String): List<TodayTodo>

    /** 待办页观察：全部条目（含停用），id 升序。 */
    fun observeAll(): Flow<List<Todo>>

    /** 新增。校验失败/超限返回 Result.failure（中文文案）。 */
    suspend fun add(text: String): Result<Unit>

    /** 重命名。同一校验。 */
    suspend fun rename(id: Long, text: String): Result<Unit>

    suspend fun remove(id: Long)
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 勾选写 today，取消写 ""；调用方（VM）负责传入当天口径。 */
    suspend fun setCompleted(id: Long, today: String, completed: Boolean)

    companion object {
        const val MAX_TODOS = 20
        const val MAX_TEXT_LENGTH = 30
    }
}
```

## 不变量

1. **快照一致性**：`todayTodos` 在锁内读快照并映射，写路径（DAO Flow 重发）与读路径互斥；快照行与 DB 最终一致（后台收集为最终一致，与 hints 相同）。
2. **完成判定纯函数**：`isDone = lastCompletedDate == today`——不含任何时钟读取；`today` 由调用方经 `DateUtil.todayString(TimeProvider.now())` 产出（可测性红线：纯逻辑不直接读系统时钟）。
3. **写入校验单一入口**：`add`/`rename` 共用 normalize（trim → 空白拒 → `take(30)`），与 `HintRepository` 同一口径。
4. **上限单一来源**：`MAX_TODOS = 20` 由 Repository 常量承载，UI 名额行与 Repository 校验共用。
5. **定向 UPDATE**：rename/setEnabled/setCompleted 使用 DAO 定向 SQL（非读-改-写整实体），避免并发勾选/停用互相覆盖。

## 错误处理

- `add`/`rename`：空白 → `IllegalArgumentException("待办内容不能为空")`；超限 → `IllegalStateException("最多可添加20条待办，请删除后重试")`；DAO 异常 → `runCatching` 兜底转 `Result.failure`。
- UI 侧前置拦截（名额满时"+"直接弹提示）是第一道防线，Repository 校验是第二道（双保险，与 AppList 同构）。

## 测试锚点（TodoRepositoryTest，纯 JVM fake DAO）

- `todayTodos只含启用项且完成态按日期映射`（昨日完成 → false；今日完成 → true）
- `add空白拒绝超长截断`
- `add达上限20后拒绝`
- `setCompleted勾选写当日日期取消清空`
- `rename与remove与setEnabled转发定向SQL`
