# Contract: 统计页 UI（档位切换与应用卡片）

**Date**: 2026-09-09 | **Consumers**: `StatsScreen`、ViewModel 测试

## ViewModel 对外状态

```kotlin
data class AppRangeStatsUi(
    val packageName: String,
    val appName: String,
    val brandColorArgb: Int,
    val interceptions: Int,
    val opened: Int,
    val canceled: Int,
)

class StatsViewModel {
    val selectedRange: StateFlow<StatsRange>        // 默认 DAY
    fun selectRange(range: StatsRange)              // 档位切换入口
    val availablePeriods: StateFlow<List<StatsPeriod>> // 当前档位筛选项
    val selectedPeriod: StateFlow<StatsPeriod>      // 默认最新周期
    val appRangeStats: StateFlow<List<AppRangeStatsUi>>  // 当前档位的清单应用聚合
    val ui: StateFlow<StatsUi>                      // 顶部四卡，契约不变
}
```

**约定**：
- `appRangeStats` 顺序与内容跟随 `target_apps` 清单（清单内应用全部展示，无数据也显示 0——FR-006）。
- 品牌色回填机制沿用现状（`brandColors` 后台提取 + FALLBACK）。
- 顶部 `ui` 流的输入（`observeStats(startOfDay)`、`observeActiveDays()`）**一行不改**（FR-004 回归锚点）。

## UI 布局契约

```text
统计页
├── 顶部：今日拦截 / 今日取消+今日打开 / 连续完成天数        （不变）
    └── 各应用历史数据（标题）
        ├── [日][周][月][年]  SingleChoiceSegmentedButtonRow   （新增，默认选中"日"）
        ├── 周期筛选 FilterChip 横向滚动行（日档隐藏）
        └── AppRangeStatCard × N                                  （沿用品牌色卡片样式）
```

**交互约定**：
- 点击档位 → `selectRange(range)` → `appRangeStats` 即时更新（FR-009）。
- 点击周期 → `selectPeriod(period)` → 只重订阅 `[start, end)`；日档不展示筛选。
- 卡片指标标签统一为通用文案「拦截 / 打开 / 取消」（FR-007）；数值为当前档位周期计数。
- 档位切换不滚动页面、不影响顶部区域。

## 文案契约（strings.xml）

| key | 值 | 用途 |
|---|---|---|
| `stats_app_history_section` | 各应用历史数据 | 区块标题（替换 `stats_app_today_section`） |
| `stats_range_day` | 日 | 档位 |
| `stats_range_week` | 周 | 档位 |
| `stats_range_month` | 月 | 档位 |
| `stats_range_year` | 年 | 档位 |
| `stats_metric_intercepted` | 拦截 | 应用卡通用指标 |
| `stats_metric_opened` | 打开 | 应用卡通用指标 |
| `stats_metric_canceled` | 取消 | 应用卡通用指标 |

顶部四卡沿用既有 `stats_today_*` 与 `stats_streak` 字符串，**不改动**。
