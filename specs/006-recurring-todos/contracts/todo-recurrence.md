# Contract: 轮到判定纯函数（TodoRecurrence）

宿主：`app/src/main/kotlin/com/fivesec/app/util/TodoRecurrence.kt`（纯 JVM，零 Android import；util 叶子层不反向依赖业务层）。

## 函数签名

```kotlin
object TodoRecurrence {
    /** 今天是否轮到该条待办（惰性求值；进出均为 DateUtil.todayString 口径的 yyyy-MM-dd 字符串）。 */
    fun isDue(
        repeatType: Int,        // 0=每天 1=按星期几 2=每 N 天
        repeatDays: Int,        // 位掩码 bit0=周一 … bit6=周日（仅 type=1 有语义）
        intervalDays: Int,      // N（仅 type=2 有语义）
        lastCompletedDate: String, // 最近完成日，"" = 从未完成
        today: String,          // 今天（调用方经 DateUtil/TimeProvider 产出）
    ): Boolean
}
```

常量：`REPEAT_DAILY = 0`、`REPEAT_WEEKLY = 1`、`REPEAT_INTERVAL = 2`、`MIN_INTERVAL_DAYS = 2`、`MAX_INTERVAL_DAYS = 365`、`bitOf(dayOfWeek: Int) = 1 shl (dayOfWeek - 1)`（`dayOfWeek` 取 `DayOfWeek.value` 1..7）。

## 判定表（实现必须逐行对应测试）

| # | repeatType | 条件 | 期望 |
|---|---|---|---|
| 1 | 0 每天 | 任意 | true |
| 2 | 1 周几 | today 的 DayOfWeek 位在 repeatDays | true |
| 3 | 1 周几 | 位未命中（含 repeatDays=0，防御脏值） | false |
| 4 | 2 间隔 | lastCompletedDate 为空串（从未完成） | true（滚动：恒到期） |
| 5 | 2 间隔 | today == last（完成当天） | false（立即进灰显期） |
| 6 | 2 间隔 | 0 < daysBetween(last, today) < N | false |
| 7 | 2 间隔 | daysBetween(last, today) >= N（含远超） | true（复活且持续轮到） |
| 8 | 2 间隔 | today < last（时钟回拨，差值为负） | false（不崩、不反向清完成） |
| 9 | 2 间隔 | last 非空但解析失败（防御） | true（视同从未完成，宁可多提醒） |
| 10 | 任意 | today 解析失败（防御） | false（宁可不提醒不崩溃） |

## 测试要求

- `TodoRecurrenceTest`（纯 JUnit，无 Robolectric）：覆盖上表 1–8 全部行 + N 边界（N=2 与 N=365 的次日差一天/整年场景）+ 跨月/跨年日期差（如 01-31 → 02-0x）。
- 日期字符串一律字面量（如 `"2026-09-23"`），不读系统时钟——时区由 today 产出方负责，本函数零时钟依赖（CI UTC 口径免疫）。
