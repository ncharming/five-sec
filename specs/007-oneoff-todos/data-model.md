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

isExpired(repeatType, dueDate, lastCompletedDate, today): Boolean
// 仅今天 && dueDate 合法 && dueDate < today && lastCompletedDate 为空
// 重复类恒 false；已完成的一次性不算过期（那是"完成待清理"，不是"失败"）
```

日期一律字符串口径（yyyy-MM-dd 字典序=时间序），解析失败防御为 false/isExpired 按未过期处理；时区责任在 today 产出方（TimeProvider + ZoneId），本文件零时钟读取。

## 生命周期状态机（一次性条目）

```
新建(仅今天) ──当天──┬─ 勾选完成 ──跨日──→ 惰性物理删除（两区都不出现）
                     └─ 未完成 ──跨日──→ 过期区（可：改为今天→回当天态 / 删除）
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
