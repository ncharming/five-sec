# Data Model: 一次性待办与过期分类（specs/007）

## 表变更：`todos`（Room v6 → v7）

新增两列（`MIGRATION_6_7`，纯 ADD COLUMN，零数据迁移语句）：

| 列 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `createdAt` | TEXT NOT NULL | `''` | 创建日（yyyy-MM-dd，`DateUtil.todayString` 口径）。只在 INSERT 时写一次，永不改写。展示用：今日区副行；老条目空串显示「—」 |
| `dueDate` | TEXT NOT NULL | `''` | 一次性规则的有效期日（yyyy-MM-dd）。仅 `repeatType=3` 有语义：== 今天轮到；< 今天且未完成=过期；转回重复类清空 |

为什么两列分离：「一键改为今天」要求有效期可重写，而创建时间展示要求不可变——一个锚点满足不了两个不变量（006 的「锚点=lastCompletedDate」同理不适用于一次性：过期判定需要的是"哪天的失败"，与完成态无关）。

## 规则常量（TodoRecurrence）

- 新增 `REPEAT_ONCE = 3`（0=每天 / 1=周几 / 2=间隔 / 3=仅今天）。
- `TodoRule.ONCE = TodoRule(REPEAT_ONCE, 0, 0)`（repeatDays/intervalDays 无语义归零）。

## 判定纯逻辑（TodoRecurrence，签名扩展）

```kotlin
isDue(repeatType, repeatDays, intervalDays, lastCompletedDate, dueDate, today): Boolean
// REPEAT_ONCE 分支：dueDate 合法且 == today → true；空/非法/不等 → false（宁可不提醒）

lastMissedDueDate(repeatType, repeatDays, intervalDays, lastCompletedDate, dueDate, createdAt, today): String?
// 2026-10-04 修订后的过期统一源：最近一次「轮到了却没完成」且未补救的日子（null=不过期）
// 单次=有效期日（< 今天）；每天/从未完成的间隔=昨天（createdAt ≤ 昨天，老数据空创建日视同久已存在）；
// 周几=过去 7 天内最近的选中日（≥ createdAt，空集永无）；已完成的间隔=完成日+N 的复活日（< 今天）。
// 收口：候选日须 > lastCompletedDate（在候选日当天或之后完成过=已补救）。

isExpired(repeatType, repeatDays, intervalDays, lastCompletedDate, dueDate, createdAt, today): Boolean
// = lastMissedDueDate(...) != null——单次口径不变；重复类错过最近轮到日同样过期（2026-10 修订，
// 取代旧「重复类恒 false」）；已完成的不算过期（单次=完成待清理，重复类=完成日已覆盖错过日）
```

日期一律字符串口径（yyyy-MM-dd 字典序=时间序），解析失败防御为 false/isExpired 按未过期处理；时区责任在 today 产出方（TimeProvider + ZoneId），本文件零时钟读取。

## 生命周期状态机（一次性条目）

```
新建(仅今天) ──当天──┬─ 勾选完成 ──跨日──→ 惰性物理删除（两区都不出现）
                     └─ 未完成 ──跨日──→ 过期区（可：改为今天→回当天态 / 删除）

重复类（2026-10 修订）──错过最近轮到日──→ 过期区（记失败账）
  ├─ 今天仍轮到（每天/到期间隔/今日命中周几）→ 双区展示：今日区照常可勾，勾掉即离开过期区
  └─ 今天不轮到 → 只在过期区（可：修改 / 删除；下次轮到日自动回今日区双区展示）
```

## 惰性清理（仓库层，无后台任务）

`TodoDao.purgeCompletedOneOffs(today)`：
`DELETE FROM todos WHERE repeatType = 3 AND lastCompletedDate != '' AND lastCompletedDate != :today`

触发点：TodoRepository 内 observeAll 收集器每次发射后顺手执行（DELETE 触发 Room 重发 → 再收集 → 无匹配行，自稳定）。UI 侧双保险：VM 派生过滤「一次性 && 已完成 && 完成日≠今天」的行，物理删除前不露僵尸行。todos 表可删（历史红线只保护 interception_events）。

## 写入点（不变量汇总）

- INSERT（add）：createdAt=今天、dueDate=今天（仅今天）或 ''（重复类）。
- updateRecurrence：规则三列 + dueDate 同一条定向 UPDATE（转仅今天=今天；转回重复类=''）；绝不触碰 text/isEnabled/lastCompletedDate/createdAt。
- setDueDate（改为今天/复活）：仅重写 dueDate=今天。
- setCompletedDate：勾=今天、取消=''（既有语义不变；一次性勾选仅当天可达）。

## Room v7 与迁移

`AppDatabase` version=7；`MIGRATION_6_7` = 两条 `ALTER TABLE todos ADD COLUMN ... TEXT NOT NULL DEFAULT ''`；列定义与实体 schema 逐字一致（AppDatabaseMigrationTest 手建库守卫）。AppModule 注册完整迁移链。
