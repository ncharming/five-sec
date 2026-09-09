# Quickstart: 各应用历史数据（日/周/月/年）+ 统计模块去冗余

**Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

端到端验证指南。自动化验证（CI / 本地）：

```bash
./gradlew :app:testDebugUnitTest        # 全部单测（含新增 DateUtil/DAO/VM 用例）
./gradlew :app:assembleDebug            # 编译
```

## 前置

1. 真机/模拟器安装 debug 包（`./gradlew :app:installDebug`）。
2. 清单中已有至少 2 个应用（默认抖音/小红书/B站）。

## 场景验证

### 场景 A：四档切换与计数正确性（US1 / SC-001 / SC-002）

1. 制造数据：今天触发小红书拦截 2 次（1 打开 1 取消）、抖音拦截 1 次（打开）。
2. 打开统计页：默认"日"档 → 小红书卡 拦截 2 / 打开 1 / 取消 1；抖音卡 拦截 1 / 打开 1 / 取消 0。
3. 依次切 周/月/年：每档计数 ≥ 日档（历史累积），切换即时无感延迟。
4. 顶部四卡数字在任何档位下保持"今日"口径，不随档位变化（FR-004）。

### 场景 B：周期边界（US1 场景 3 / SC-004）

1.（自动化为主）单测 `DateUtilTest` 验证：周日输入 → 周起点为当期周一；月初/年初/12-31 跨年输入 → 边界正确。
2. 手测抽检：若今天为周日，"周"档计数应包含本周一以来的全部事件。

### 场景 C：历史完整性 + 升级不丢数据（US2 / SC-002）

1. 在旧版本（v2，master 当前提交）正常使用数日产生数据，安装新版本覆盖升级（`adb install -r`）。
2. 打开统计页"年"档：全部历史记录参与计数；顶部连续天数不回退。
3.（自动化锚点）`InterceptionEventDaoTest` 插入跨月/跨年事件核对四档聚合。

### 场景 D：去冗余（US3）

1. 覆盖升级后发生一次新拦截：仅事件表新增记录（无汇总表写入）。
2. 统计页所有数字正常（单一数据源实时聚合）。
3.（可选）`adb shell "run-as com.fivesec.app sqlite3 databases/five_sec.db '.tables'"` 确认 `app_statistics` 已不存在、`interception_events` 仍在。

### 场景 E：回归（FR-004 / SC-003）

1. 顶部今日四卡与改造前同数据下逐项一致（可对照旧版本截图）。
2. 既有测试全绿：`./gradlew :app:testDebugUnitTest`。

## 通过标准

- A~E 全部场景观察结果与预期一致；
- 单测/编译全绿（CI 触发于 push master 或手动 Run workflow）。

## Notes（T013 数据留存审计记录，2026-09-09）

- 检索 `DELETE FROM interception_events | eventDao.delete | deleteFromEvents` → **0 匹配**（事件表无删除路径）
- 检索 `fallbackToDestructiveMigration | createFromAsset` → **0 匹配**（无破坏性迁移/资产库覆盖）
- 事件表唯一写入点：`InterceptionRepository.record() → eventDao.insert(event)`（去冗余后已收窄为仅此一处）
- v3 迁移 `MIGRATION_2_3` 仅 `DROP TABLE app_statistics`，不触碰 `interception_events`
