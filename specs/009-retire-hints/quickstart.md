# Quickstart: 提示语功能退役手测

**Date**: 2026-09-24 | **Spec**: [spec.md](spec.md) | **契约**: [contracts/todo-card-overlay.md](contracts/todo-card-overlay.md)

真机 `adb install -r` 覆盖升级验证（对齐仓库集成验证惯例）。覆盖 spec SC-001~SC-004。

## 前置

1. 门①② 绿：`./gradlew :app:assembleDebug` / `:app:testDebugUnitTest`。
2. 设备已装**旧版**（含提示语功能），准备数据：
   - 「提示语」Tab 添加 3 条自定义提示语（记为 A/B/C）；
   - 「待办」Tab：建 2 条「每天」+ 1 条「每周几」（选一个**不是今天**的星期几）。
3. 覆盖安装新版：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。

## 场景

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| S1 | 有未完成待办（SC-001） | 点开抖音触发拦截，连续 3 次 | 标题 → 今日待办 D/T（○ 未完成 ≤3 行、每条 ≤12 字）→ 倒计时行 → 按钮；**无提示语行**；3 次拦截均无任何轮换文本（含自定义 A/B/C 与内置文案） |
| S2 | 全部完成（SC-001） | 待办页勾完今日轮到的全部条目后触发拦截 | 「今日待办已全部完成 ✓」绿色标题行，条目区隐藏 |
| S3 | 无启用条目（SC-001） | 停用/删除全部待办后触发拦截 | 「还没有今日待办 · 打开『五秒』添加」；**待办卡不再消失**，倒计时/按钮行为不变 |
| S4 | 有启用但今日不轮到（SC-001） | 仅保留 1 条「每周几」（X≠今天）后触发拦截 | 「今天没有轮到的待办」（不是引导添加文案） |
| S5 | Tab 收窄（SC-003） | 启动应用；杀进程后重进 | 底部导航仅 待办/统计/设置 三项，默认待办；全应用遍历无提示语入口与文案；杀进程恢复正常（恢复兜底不崩） |
| S6 | 数据留存（SC-002） | S5 后查待办列表与统计页 | 条目、勾选状态、拦截/任务统计数字与升级前一致 |
| S7 | hints 行留存（SC-002） | `adb shell run-as com.fivesec.app sqlite3 /data/data/com.fivesec.app/databases/five_sec.db "SELECT COUNT(*) FROM hints;"`（部分 ROM 无 sqlite3 二进制时跳过，以 `AppDatabaseMigrationTest` v8→v9 用例为准） | 返回 3（A/B/C 原样保留，仅不再展示） |

## 附：升级瞬检

- 旧版提示语页正开着时覆盖安装 → 新版启动直接落 3 Tab，无迁移弹窗/报错（spec Edge Cases：无"功能下线"交互）。
- 覆盖层四态（S1–S4）下标题/待办卡/倒计时间距无跳变、无残留空白（spacer 常驻规则）。
