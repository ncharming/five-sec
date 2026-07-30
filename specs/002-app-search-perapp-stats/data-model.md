# Data Model: 添加应用搜索 + 应用级今日统计（品牌色展示）

**Date**: 2026-07-30 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

本特性 **不新增、不修改任何 Room 实体或表，无数据库迁移**。应用级今日统计从既有 `interception_events` 表实时聚合；应用品牌色为运行时派生值（图标提取 + 内存缓存，不落库）。仅描述新增/扩展的查询、派生视图与辅助类型。

---

## 既有持久化实体（本特性不改，仅引用）

| 实体 | 表 | 本特性用途 |
|------|----|-----------|
| `TargetApp` | `target_apps` | 提供目标应用清单（`packageName`/`appName`），作为应用级统计卡片与搜索去重的来源 |
| `InterceptionEvent` | `interception_events` | 今日应用级统计的**唯一数据来源**（按 `packageName + timestamp` 聚合） |
| `InterceptionOutcome`（枚举） | — | `OPENED` 用于"今日打开"计数 |
| `AppStatistics` | `app_statistics` | 全量累计统计（本特性不改、不读，保留 001 既有行为） |

---

## 新增 DAO 查询（扩展 `InterceptionEventDao`）

| 方法签名 | 返回 | 说明 |
|---------|------|------|
| `observeTodayCountByPackage(startOfDay: Long, packageName: String)` | `Flow<Int>` | 该应用 `timestamp >= startOfDay` 的全部事件数 = **今日拦截** |
| `observeTodayCountByPackageAndOutcome(startOfDay: Long, packageName: String, outcome: InterceptionOutcome)` | `Flow<Int>` | 该应用今日 `outcome = OPENED` 的事件数 = **今日打开**（调用时传 `OPENED`） |

**校验/规则**：
- 时间下界 `startOfDay` 由 `DateUtil.startOfDayMillis(now)` 计算（系统时区、自然日 0:00，与既有全局查询同源）。
- `todayInterceptions ≥ todayOpened` 恒成立（打开是拦截的子集）。

---

## 新增派生视图（瞬态、不持久化）

### AppTodayStats（今日应用统计）

按目标应用聚合的当日统计视图，每个目标应用一条，供 `StatsScreen` 渲染卡片。

| 字段 | 类型 | 说明 / 校验 |
|------|------|-------------|
| `packageName` | String | 目标应用包名；关联 `TargetApp` |
| `appName` | String | 应用友好名称（取自 `TargetApp.appName`），卡片主标题 |
| `brandColor` | Color（Compose） | 该应用品牌色（见下 `AppBrandColor`），卡片主题色；非空（必有兜底值） |
| `todayInterceptions` | Int | 今日拦截次数（当日该应用全部事件数）；非负 |
| `todayOpened` | Int | 今日打开次数（当日 `OPENED` 数）；非负，≤ `todayInterceptions` |

**来源**：由 `InterceptionRepository.observeAppTodayStats(startOfDay): Flow<List<AppTodayStats>>` 产出——把 `TargetAppRepository.observeAll()` 与上述 2 个 DAO 查询合并计算。`brandColor` 由 `AppBrandColorExtractor` 异步注入（见下）。

### AppBrandColor（应用品牌色，派生属性）

| 字段 | 类型 | 说明 |
|------|------|------|
| `packageName` | String | Map 键 |
| `color` | Color（Compose） | 从该应用启动器图标提取的代表色；失败/对比度不足时为兜底健康绿 |

**规则**：
- 由 `AppBrandColorExtractor`（`@Inject @Singleton`）持有按 `packageName` 的内存缓存（首次提取后常驻）。
- 提取链：`PackageUtil.icon(pm, pkg)` → `drawable.toBitmap()` → `Palette.from(bitmap).generate()` → `getDominantColor()`（`getVibrantColor()` 为备选）→ 转 Compose `Color`；全程在 `Dispatchers.IO`。
- **兜底**：图标取不到、Palette 提取失败、或相对亮度导致与黑/白前景均不达 WCAG AA（≥ 4.5:1）时，回退 `Primary`（`0xFF00A86B`，见 `Color.kt`）。
- **缓存失效**：应用卸载/重装会改变图标；MVP 不主动失效（用户重进页面/重启进程即刷新），记为已知限制。

---

## 搜索相关数据（既有 `PackageUtil.InstalledApp` 复用，不改结构）

| 项 | 说明 |
|----|------|
| `InstalledApp(packageName, label)` | 既有数据类；候选来源为 `PackageUtil.installedUserApps(pm)` |
| 加载方式（改） | 由"主线程同步"改为 `Dispatchers.IO` 协程加载，结果在 `AppListViewModel` 内缓存为 `StateFlow` |
| 过滤函数（新增，纯函数） | 输入 `(candidates, targetPackageNames, query)` → 输出过滤+去重后的列表。规则：`query` 空时返回全部未添加项；非空时按 `label` 不分大小写包含、**或** `packageName` 包含 `query` 匹配；已在 `targetPackageNames` 中的项置灰（标记 `isAdded=true`）而非删除，以满足 FR-005"标识为不可重复添加" |
| 空状态 | 过滤结果为空时 UI 显示"未找到匹配的应用"（FR-004） |

---

## 数据流总览

```text
[添加应用搜索]
PackageManager ──(IO)──▶ AppListViewModel.cachedInstalledApps (StateFlow)
                              │
                  filter(query, targetPackages)  ◀── 搜索框 query（纯内存过滤）
                              │
                         AppListScreen 选择器（LazyColumn + 搜索 TextField）
                              │ 选择
                  AppListViewModel.add() ──▶ TargetAppRepository.addNewApp()（含 3 上限，001 既有）

[应用级今日统计]
interception_events ──(observeTodayCountByPackage / …ByPackageAndOutcome=OPENED)──▶ InterceptionRepository.observeAppTodayStats(startOfDay)
                                                                                        │ (+) TargetApp.observeAll()
                                                                                        ▼
                                                          AppTodayStats[packageName, appName, brandColor, todayInterceptions, todayOpened]
                                                                                        │ brandColor 由 AppBrandColorExtractor 异步注入
                                                                                        ▼
                                                                  StatsScreen 每应用一张品牌色卡片
```
