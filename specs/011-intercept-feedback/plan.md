# Plan: 拦截反馈三件套（specs/011）

## 决策记录

1. **成功态而非「拦截页加今日第 N 次」**：009 确立「待办卡是覆盖层唯一缓冲内容」——往缓冲区塞数字有提示语复活之嫌且增加决策前认知负担；成功态发生在**选择之后**（决策已完成，纯奖励瞬间），完全不碰缓冲区契约。拍板：反馈放在选择之后。
2. **展示在 HOME 之前**：既有契约「先 HOME 再撤层」的唯一风险是撤层早于 HOME 导致目标应用闪现——成功态全程保持覆盖层遮挡（目标应用在后面不可见），0.8 秒后才进 Finished 触发 HOME，顺序契约天然保持。另一方案（先 HOME 再在桌面上展示成功态）需要把 250ms 撤层延迟拉长到 800ms+ 且成功态与桌面背景叠加视觉不可控，弃。
3. **800ms 常量**：与去抖 DEBOUNCE_MS 同量级的「体感一瞬」——长到能看清「第 N 次」，短到不觉得被拦住去路。不做设置项（本功能是性格不是配置）。
4. **Resisted 是「已决定」态**：进入即锁定 CANCELED。打断（服务被拆/无障碍被关）只跳过剩余展示，绝不翻案成 INTERRUPTED——用户已完成 5 秒锻炼并做出了选择，事件语义（exerciseCompleted=true）不能被反馈层改写。
5. **计数用内存镜像而非实时查询**：覆盖层状态机是同步调用（cancel() 在主线程），DB 查询不可行；仓库 init 从事件表播种「今日 CANCELED 计数」+ 每次取消 +1 + 日期串不等归零——与 TodoRepository.todayTodos 的「内存快照供主线程同步读」同一架构先例。镜像与事件表的瞬时差（≤1 次、800ms 窗口）记录进 data-model 边界。
6. **抵制率排除 INTERRUPTED**：打断是「未完成选择」，不是一次抵制机会——分母只有 CANCELED+OPENED。分母 0 显示「—」而非 0%（无选择 ≠ 零抵制，空态契约的精确表达）。
7. **抵制率放主页今日拦截卡而非二级页**：它是今日结果指标，与双 Hero 同卡；插在发丝线与「取消/打开」小字之间（视觉次级但语义一等：品牌绿加粗）。
8. **24 桶聚合在 Kotlin 不在 SQL**：SQLite `strftime('localtime')` 的时区行为在 CI（UTC）与真机（CST）会分岔——Robolectric 下不可控；纯函数 `HourDistribution.of(timestamps, zone)` 显式 ZoneId、可直接单测，与 AGENTS「日期边界带 ZoneId 参数」约定一致。数据量个人级（年级别数千行 timestamp），内存分桶无压力。
9. **时段分布计入 INTERRUPTED**：与「拦截次数」总数同口径（分子是「被拦下来几次」不是「做了几次选择」），峰值洞察才不被打断事件扭曲。
10. **峰值并列取最前**：indexOfFirst 语义，简单且确定。
11. **震动 60ms 一次性**：与 010 提醒的闹钟式波形长震拉开感官等级——成功态是「轻拍肩」，不是「闹钟」。复用既有 VIBRATE 权限，失败静默（振动器不可用不阻断回桌面）。
12. **无 schema 变更**：三件全是读侧增强，Room 恒 v10；`interception_events` 只新增只读查询。今日拦截卡结构改动属「计划内改动」（本 spec 即记录）。

## 实现顺序（依赖驱动）

1. **纯逻辑**：`util/ResistRate.kt`（percent 纯函数）+ `util/HourDistribution.kt`（24 桶纯函数，ZoneId 注入）——先于一切，配单测。
2. **状态机**：`BlockingViewModel` 新增 `Resisted` 态与 `resistCountProvider` 注入；更新 KDoc 不变量；扩 `BlockingViewModelTest`。
3. **数据层**：`InterceptionEventDao` 新增 `countByOutcomeSince`（一次性 suspend）与 `observeTimestampsBetween`（半开区间 Flow）。
4. **仓库**：`InterceptionRepository` 注入 TimeProvider、新增抵制镜像（init 播种 + `nextResistedOrdinal` + `reseedResistedMirror`）与 `observeTimestampsBetween` 转发；新建 `InterceptionRepositoryTest`。
5. **覆盖层**：`BlockingOverlay` 构造注入 provider、render(Resisted) 成功态（文案/计数副行/按钮置灰/一次性震动）、Finished(CANCELED) 保持成功文案（防撤层窗口闪回「请选择」）。
6. **接线**：服务创建 overlay 时传 provider lambda（求值时取 today，跨零点正确）；onBlockingFinished 零改动（顺序契约不动）。
7. **统计**：`StatsViewModel` 新增 `hourDistribution` 流（flatMapLatest 周期切换）；`StatsScreen` 今日拦截卡抵制率行 + 拦截二级页时段分布卡（Canvas 24 柱）。
8. **文案**：strings.xml 新增 8 条（成功态 2 / 抵制率 2 / 时段分布 4）。
9. **收尾**：DAO 测试补区间查询用例；README（工作原理/统计段）与 AGENTS（术语表三词）同步；tasks.md 勾选；assembleDebug + testDebugUnitTest；commit 推 master。

## 风险与对策

- **既有测试破坏面**：`BlockingViewModelTest` 的「解锁后选择取消」断言立即 Finished——行为变更后按新迁移路径更新（cancel → Resisted → 800ms → Finished），这是 spec 驱动的计划内测试更新。
- **overlay 手动构造 VM 形态**：provider 经构造链注入（服务 → overlay → VM），不引入 Hilt（009 口径：拦截 UI 保持手动构造形态）。
- **镜像线程安全**：nextResistedOrdinal 主线程调用、播种在 Default dispatcher——synchronized(resistLock) 互斥；@Volatile 不够（读改写非原子）。
- **Canvas 空数据除零**：max=0 时柱高全 0、只画基线；barWidth 计算 24 恒定桶无除零路径。
- **震动在无振动器设备**：getSystemService 可空返回，try/catch 兜底，静默降级。
