# Contract: 应用级今日统计卡片（品牌色）

**Spec**: [spec.md](../spec.md) · **Plan**: [plan.md](../plan.md) · **Data Model**: [data-model.md](../data-model.md)

定义 `StatsScreen` 中"每个目标应用一张今日统计卡片"的 UI/行为契约。覆盖 spec FR-007~012、User Story 2 的全部验收场景。本卡片与 001 既有总体统计卡片**并存**，互不影响。

## 卡片数据

每个目标应用一张 `AppTodayStats` 卡片（见 [data-model.md](../data-model.md)）：

| 字段 | 卡片呈现 |
|------|---------|
| `appName` | 卡片主标题（如"小红书"） |
| `todayInterceptions` | 指标①「今日拦截」的数值 |
| `todayOpened` | 指标②「今日打开」的数值 |
| `brandColor` | 卡片主题色（背景/容器底色） |

## 口径契约（FR-008 / FR-009，必须精确）

- **今日拦截** = 当日该应用全部 `interception_events`（OPENED + CANCELED + INTERRUPTED）。
- **今日打开** = 当日该应用 `outcome = OPENED` 的事件数。
- **今日**边界 = 设备系统时区、自然日 0:00（`DateUtil.startOfDayMillis`）。
- 恒等：`todayInterceptions ≥ todayOpened ≥ 0`。

## 品牌色契约（FR-011 / FR-012 / SC-005 / SC-007）

- 卡片主题色 = 该应用启动器图标的代表色（`AppBrandColorExtractor`，Palette 提取，按 `packageName` 缓存）。
- 不同应用的卡片主题色在视觉上**可明确区分**。
- **前景色自适应**：根据品牌色相对亮度（luminance）自动选黑/白前景文字，保证 WCAG AA 对比度（正常文本 ≥ 4.5:1）。
- **兜底**：提取失败或对比度不足 → 主题色回退品牌健康绿 `Primary`（`0xFF00A86B`），且仍保证文字可读。
- 提取在 `Dispatchers.IO` 进行；首次未就绪时卡片以兜底色或中性色占位渲染，**不阻塞页面**。

## 列表与空数据契约

- 卡片按目标应用清单渲染（每个 `TargetApp` 一张）；保留 001 既有总体统计区。
- 某应用今日无任何拦截 → 卡片仍渲染，显示「今日拦截 0 / 今日打开 0」（US2-4）。
- 目标清单为空 → 不渲染应用级卡片区（沿用既有空态逻辑）。

## 跨天契约（FR-010 / SC-006）

- 进入统计页时按当前时间计算 `startOfDay`，订阅今日查询；次日重新进入 → 新 `startOfDay` → 仅反映新一天数据。
- 已知限制（可接受）：单次会话内跨越午夜不主动翻日，与 001 既有全局统计行为一致。

## 验收映射

- US2-1 / US2-2（小红书/抖音今日数值正确）→ 口径契约
- US2-3（每卡用各自品牌色、可区分）→ 品牌色契约
- US2-4（无数据应用仍显示 0）→ 列表与空数据契约
- US2-5（跨天仅显示当日）→ 跨天契约
- US2-6（图标取色失败用兜底色不崩）→ 品牌色契约·兜底
