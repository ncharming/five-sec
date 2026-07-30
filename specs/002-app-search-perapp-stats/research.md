# Research: 添加应用搜索 + 应用级今日统计（品牌色展示）

**Date**: 2026-07-30 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

基于对既有代码（`AppListViewModel` / `AppListScreen` / `PackageUtil` / `StatsViewModel` / `StatsScreen` / `InterceptionEventDao` / `InterceptionRepository` / `Color.kt` / `build.gradle.kts`）的实际研读，记录四项关键技术决策。

---

## R1 — 添加应用搜索：性能瓶颈与过滤策略

**Decision**：把"枚举已安装应用"从主线程移到 IO 协程并在 ViewModel 内缓存为 `StateFlow`；搜索过滤在内存列表上做纯函数过滤（名称不分大小写 + 包名子串）。搜索交互复用既有添加选择器（`AlertDialog`），在其顶部加一个 `TextField`，不新建屏幕。

**Rationale**：
- 现状 `AppListScreen` 用 `remember { viewModel.installedApps() }` 同步调用 `PackageUtil.installedUserApps(pm)`，后者对每个已安装应用调用 `pm.getApplicationLabel()`。在 200+ 应用的设备上，**真正慢的是这次枚举本身**（数十~上百毫秒且在主线程，可能掉帧/ANR），而非随后的过滤。
- 因此 SC-001（0.5s 过滤）的关键不是"过滤算法"，而是"不让枚举阻塞 UI + 过滤在内存中即时完成"。把枚举放 `Dispatchers.IO`、结果缓存后，过滤是纯内存 `List.filter`，200 条量级在 1ms 级，远低于 0.5s。
- 过滤做成纯函数（输入：候选列表 + 关键词；输出：过滤后列表）便于单测，覆盖大小写/包名/空状态/去重。

**Alternatives considered**：
- *全屏搜索页*：搜索 UX 更好，但需新增屏幕与导航，改动面大、超出最小改动。MVP 在既有 Dialog 内加搜索已满足全部验收场景（FR-001~006）。留待未来优化。
- *ContentProvider/全局搜索*：过度设计，候选本就是本机已安装应用，无需跨进程。

---

## R2 — 应用品牌色：图标提取 vs 固定色表

**Decision**：采用 **`androidx.palette` 从应用启动器图标运行时提取代表色**（`Palette.getDominantColor` 为主，`getVibrantColor` 为辅），按 `packageName` 缓存在内存；提取失败或对比度不足时回退到品牌健康绿 `Primary`（`0xFF00A86B`，来自 `Color.kt`）。

**Rationale**：
- 用户需求"按照他们应用的 app 颜色"最忠实的来源就是该应用自身的图标主色；Palette 是 Android 官方从 `Drawable/Bitmap` 提取配色方案的标准库。
- 纯图标提取是**零维护、覆盖任意应用**的方案——用户添加任何应用都能得到贴合的品牌色，无需维护一张"知名应用→颜色"的表（小红书红/抖音黑/B站粉等）。这与"用户可自由配置任意应用"的 001 核心能力契合。
- `PackageUtil.icon(pm, pkg)` 已能拿到应用图标 `Drawable`，仅需 `drawable.toBitmap()` → `Palette.from(bitmap).generate()`，链路清晰。
- 提取必须在 `Dispatchers.IO`（Bitmap/Palette 操作不可阻塞主线程）；结果缓存避免重复提取。

**对比度/可访问性处理**：
- 卡片用品牌色作为背景（或浅色容器底），文字根据品牌色**相对亮度（luminance）**自动选黑/白前景，保证 WCAG AA（≥ 4.5:1）。极浅/极深品牌色若仍不达标，回退健康绿（FR-012 / SC-007）。

**Alternatives considered**：
- *固定品牌色表（小红书=红、抖音=黑…）+ 其余图标提取兜底*：对头部应用"色更正"，但要维护色表、新增/改名应用会失真，维护成本与本特性的轻量目标不符。**留作未来增强**，若用户更偏好"头部应用色更准"可在实现阶段切换。
- *用户手填颜色*：增加配置负担，违背"自动贴合应用色"的预期，否决。

---

## R3 — 应用级今日统计：复用事件表，零迁移

**Decision**：**不新增/不改 Room 实体与表**，应用级今日统计直接从既有 `interception_events` 表聚合。新增 2 个 DAO 查询：
- `observeTodayCountByPackage(startOfDay, packageName)` → 该应用今日全部事件数（今日拦截）
- `observeTodayCountByPackageAndOutcome(startOfDay, packageName, outcome)` → 该应用今日 `OPENED` 数（今日打开）

Repository 暴露 `observeAppTodayStats(startOfDay): Flow<List<AppTodayStats>>`，把目标应用清单与上述查询合并，产出每应用 `{packageName, appName, brandColor, todayInterceptions, todayOpened}`。

**Rationale**：
- 既有 `InterceptionEventDao` 已有全局今日查询（`observeTodayCount` / `observeTodayCountByOutcome`），加 `packageName` 维度是自然扩展，查询模式一致。
- 既有 `AppStatistics` 实体是**全量累计**（`totalInterceptions`/`cancellations`/`completedExercises`/`cancellationRate`），且**不含 `opened` 字段**，无法直接表达"今日 + 打开次数"。强行加字段+迁移代价高于直接查事件表。
- 今日数据天然适合"按时间窗实时聚合"，事件表是最细粒度的单一事实来源（single source of truth），避免全量表与今日聚合口径不一致。

**口径确认**（对齐 spec FR-009）：
- 今日拦截 = 当日该应用全部 `interception_events`（OPENED + CANCELED + INTERRUPTED）
- 今日打开 = 当日该应用 `outcome = OPENED` 的事件

**Alternatives considered**：
- *给 `AppStatistics` 加 `todayOpened`/日维度字段 + 每日重置逻辑*：需要定时任务做日切重置、引入迁移与新的一致性 bug 面，远超必要复杂度，否决。

---

## R4 — "今日"边界与跨天刷新

**Decision**：复用既有 `DateUtil.startOfDayMillis(now)`（设备系统时区、自然日 0:00）作为今日下界。统计订阅在 ViewModel 创建时计算 `startOfDay` 并通过 Flow 订阅；用户次日重新进入页面会创建新 ViewModel、得到新的 `startOfDay`，自动仅反映新一天数据（满足 SC-006）。

**Rationale**：
- 与既有全局统计（`StatsViewModel` 已用同一 `startOfDay`）行为一致，零额外逻辑。
- "停留页面跨越午夜自动刷新"在 MVP 不做（既有全局统计同样不做；UI Automator/真机次日复测即可验证 SC-006）。

**Known limitation（可接受，非阻塞）**：单次会话内若长时间停留在统计页跨越午夜，指标不会主动翻日——这与既有行为一致，记为后续可选增强（监听 `ACTION_DATE_CHANGED` 或定时重算）。

---

## R5 — Palette 依赖引入

**Decision**：在 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 新增 `androidx.palette:palette-ktx`（`androidx.palette:palette-ktx` 是社区常用 Kotlin 扩展别名；若无则直接用 `androidx.palette:palette`）。

**Rationale**：当前 `app/build.gradle.kts` 依赖清单**不含 Palette**（已确认），而品牌色提取（R2）依赖它。引入是新增能力的必要依赖，体积小、官方维护、`minSdk 26` 全支持。

**Alternatives considered**：
- *手写颜色量化/平均色*：可避免新依赖，但要自行处理 Bitmap 采样、边缘色、对比度，质量与稳定性不及官方 Palette，得不偿失。
