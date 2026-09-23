# Contracts: 统计页 UI

**Feature Branch**: `008-todo-stats`

## 页面结构（StatsScreen 页内三态）

```
StatsScreen（rememberSaveable 存当前页；BackHandler 非主页时回主页）
├── MAIN 主页
│   ├── PageHeader(统计 / 每一次停留，都算数)          ← 不动
│   ├── 今日拦截三列卡（拦截/取消/打开）                ← 不动（锚点）
│   ├── 连击卡                                          ← 不动
│   ├── 今日任务三列卡（任务 x / 完成 y / 过期 n）      ← 新增
│   └── 两张单行入口卡（图标 + 标题 + › 箭头，整卡可点）
├── APP_HISTORY 应用拦截统计二级页
│   ├── 页头：返回箭头 + 标题「应用拦截统计」
│   ├── 日/周/月/年分段 + 周期 Chip                    ← 原样搬迁
│   └── 按应用卡（真实图标 + 拦截/取消/打开三列）       ← 原样搬迁
└── TODO_HISTORY 任务完成统计二级页
    ├── 页头：返回箭头 + 标题「任务完成统计」
    ├── 日/周/月/年分段 + 周期 Chip                    ← 与拦截二级页共享选择状态
    ├── 总完成次数卡（大数字 + 「本期完成」）
    └── 按条目卡：文本快照（单行省略）+ 右侧「完成 N 次」，次数降序
```

## 今日任务三数口径（契约级）

| 数 | 定义 | 反例（MUST NOT 计入） |
|---|---|---|
| 任务 x | isEnabled && isDue(今天)——与覆盖层 D/T 分母同源谓词 | 停用条目；间隔完成当天（灰显期）；过期条目；未来 dueDate 一次性 |
| 完成 y | x 中 lastCompletedDate == 今天 | 昨天完成的（跨日惰性失效） |
| 过期 n | isExpired（一次性 && dueDate<今天 && 未完成），含停用 | 重复类条目（永不进过期口径） |

## 状态与交互契约

- 二级页返回：左上返回箭头 与 系统返回手势/键 等价（均回主页，不退出 App、不切 Tab）。
- 底部 Tab 栏在三态全程可见；切走再切回，二级页现场保留（Tab 状态仓）。
- 档位/周期选择在两个二级页间共享：切页不重置（同一时间视角）；离开统计 Tab 再回来不重置（沿用现状）。
- 周期 Chip：周=本周/上周，月=当年 1 月至今，年=最早数据年至今（两表最早取更早）；日档位无 Chip。
- 空态：周期无完成 → 总次数卡显示 0，条目列表区显示「本期暂无完成记录」；拦截二级页应用卡照常渲染 0 值（specs/002 口径）。
- 主页入口卡进入二级页时档位维持当前选择（默认「日」）。

## 文案（strings.xml）

| key | 值 |
|---|---|
| stats_today_tasks | 任务 |
| stats_today_tasks_done | 完成 |
| stats_today_tasks_expired | 过期 |
| stats_entry_intercept_history | 应用拦截统计 |
| stats_entry_todo_history | 任务完成统计 |
| stats_todo_period_total | 本期完成 |
| stats_todo_item_count | 完成 %1$d 次 |
| stats_todo_empty | 本期暂无完成记录 |
| stats_back | 返回（contentDescription） |

## 非目标（界面层 MUST NOT）

- 不在历史页展示完成率/百分比/应做数/历史过期数；
- 不在覆盖层/待办页加任何统计入口或数字；
- 不动今日拦截卡与连击卡的任何像素与数据管道。
