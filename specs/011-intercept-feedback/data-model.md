# Data Model: 拦截反馈三件套（specs/011）

## 数据库：零变更

Room 恒 **v11**（现状版本——2026-10-04 的 `todo_completions.wasExpired` 列；AGENTS 架构树的 v10 表述已顺手同步），无迁移、无新表、无新列。`interception_events` 只新增**只读**查询（红线：本表只增不删，本功能连写路径都不碰）：

```sql
-- 一次性（suspend）：今日抵制镜像播种用
SELECT COUNT(*) FROM interception_events
WHERE timestamp >= :startOfDay AND outcome = :outcome

-- 响应式（Flow）：时段分布数据源，半开区间 [rangeStart, rangeEnd)
SELECT timestamp FROM interception_events
WHERE timestamp >= :rangeStart AND timestamp < :rangeEnd
ORDER BY timestamp ASC
```

## 抵制序号内存镜像（InterceptionRepository 内）

```
resistLock: Any                          // 互斥锁（播种 Default 线程 × 调用主线程）
├─ resistedDateKey: String               // "yyyy-MM-dd"；空串 = 尚未播种/未使用
└─ resistedCount: Int                    // 该日已取消次数（镜像值）
```

写入点（恰好两个，互斥内）：

| 入口 | 时机 | 行为 |
|---|---|---|
| `reseedResistedMirror(now)` | init 后台一次（+测试显式调） | 查事件表今日 CANCELED 计数 n；同日取 max(现值, n)，未使用（key 空）直接置 n；跨日/回拨保留现值 |
| `nextResistedOrdinal(today)` | 用户点「取消」瞬间（主线程同步） | key != today 先归零；count+1；返回新值 |

**一致性边界**（记录性约定）：事件本体在成功态展示结束后异步落库，镜像可能领先事件表最多 1 次（800ms 窗口）；进程重启后 init 播种自动对齐。统计页抵制率走事件表实时聚合——两者长期一致、瞬时差 ≤1，不构成 bug。

## 新增纯函数（util，零 Android import）

| 函数 | 签名 | 语义 |
|---|---|---|
| `ResistRate.percent` | `(canceled: Int, opened: Int) -> Int?` | round(canceled×100/(canceled+opened))；分母 ≤0 返回 null（无选择） |
| `HourDistribution.of` | `(timestamps: List<Long>, zone: ZoneId) -> IntArray(24)` | 各事件按 zone 的本地小时分桶；`BUCKETS = 24` |

## 状态机扩展（BlockingViewModel.UiState）

```
CountingDown(n) ──5s──▶ ChoiceUnlocked ──open()──▶ Finished(OPENED)
                            │
                            └─cancel()─▶ Resisted(count) ──800ms──▶ Finished(CANCELED)
                                           │
                                           └─markInterrupted()──▶ Finished(CANCELED)  // 提前收尾，结局不翻案
CountingDown/ChoiceUnlocked ──markInterrupted()──▶ Finished(INTERRUPTED)  // 既有路径不变
```

- `Resisted.count`：展示用今日序号（含本次），由 provider 在 cancel() 时同步求值。
- `resistCountProvider: () -> Int`：构造注入（服务 → overlay → VM）；默认 `{ 1 }` 仅供测试便利。

## UI 层新增（无状态，全部由 VM/流驱动）

- 覆盖层：`resistCountLine: TextView`（16sp，onSurfaceVariant，GONE↔VISIBLE）。
- 统计主页今日拦截卡：抵制率行（`ResistRate.percent(ui.canceled, ui.opened)`，UI 层纯派生，不进 StatsUi）。
- 拦截二级页：`hourDistribution: StateFlow<List<Int>>`（24 桶），`selectedPeriod.flatMapLatest` 重订阅，与 appRangeStats 同模式。
