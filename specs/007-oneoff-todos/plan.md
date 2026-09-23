# Plan: 一次性待办与过期分类（specs/007）

## 决策记录（/grill-me 质询拍板）

1. 一次性=第四种规则「仅今天」，只创建当天轮到；重复类永不进过期分类。
2. 过期条目操作=删除 + 一键改为今天；无迟到补勾。
3. 已完成一次性跨日=惰性物理删除（仓库收集器顺手清，无后台任务）。
4. 存量回填空串，老条目创建时间显示「—」，不伪造。
5. 规则编辑器四选一可双向转；dueDate（有效期）与 createdAt（展示）两列分离。
6. 「首页 12 字左对齐」=拦截覆盖层卡片（截 12 字+…、卡片内标题+条目左对齐）；待办页列表维持单行省略。

## 实现顺序（依赖驱动）

1. **domain/util 纯逻辑**：Todo 实体两列、TodoRule.ONCE、TodoRecurrence（REPEAT_ONCE、isDue 签名加 dueDate、新增 isExpired）。
2. **db**：Room v7、MIGRATION_6_7、TodoDao（updateRecurrence 加 dueDate 参、setDueDate、purgeCompletedOneOffs）、AppModule 注册。
3. **repository**：注入 TimeProvider；add 写 createdAt/dueDate；setRecurrence 同步写 dueDate；revive；收集器内惰性清理。
4. **VM**：UiState(todayRows, expiredRows) 分区派生 + 副行日期 + 僵尸行过滤；revive 转发。
5. **UI**：TodoScreen 两卡分区/过期行/四选一编辑器/行内日期；BlockingOverlay 12 字+左对齐；strings.xml。
6. **测试**：TodoRecurrenceTest（仅今天/过期判定表）、TodoViewModelTest（分区派生）、TodoRepositoryTest（dueDate 写入/revive/清理/快照）、AppDatabaseMigrationTest（v7）。
7. **收尾**：tasks.md 勾选、README 同步、assembleDebug + testDebugUnitTest、commit 推 master。

## 风险与对策

- isDue 签名变更波及三方调用点（VM/Repo/测试）→ 编译器全量暴露，逐点更新。
- 惰性 DELETE 触发 Room 重发的自稳定 → 收集器幂等（无匹配行即无操作），配仓库测试。
- Room 运行时 schema 校验 vs ALTER DEFAULT → 沿用 006 已验证的「ADD COLUMN ... NOT NULL DEFAULT ''」模式。
