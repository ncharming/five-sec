# Research: 拦截页自定义提示语（栈式一次性提示 + 自定义提示语池）

**Date**: 2026-09-12 | **Spec**: [spec.md](spec.md)

## 1. 存储选型：Room 新表 vs DataStore Preferences

| 维度 | Room `hints` 表 | DataStore（JSON 编码字符串） |
|---|---|---|
| 栈操作（弹出最新 + 删除特定条目） | 天然支持（`ORDER BY id` + `DELETE WHERE id=`） | 需整串读→改→写，自实现 LIFO |
| Flow 观察列表 | DAO Flow 自动失效重发 | map + 手动等值 |
| 序列化 | 无需（列即字段） | 需 JSON；项目无 kotlinx.serialization，`org.json` 在纯 JVM 单测是 android stub（抛异常），测试被迫全走 Robolectric |
| 迁移成本 | v3→v4 `CREATE TABLE`（一行） | 无 schema 但有编解码债 |
| 项目先例 | 统计/清单均在 Room | DataStore 仅存标量设置（开关/布尔/Int） |

**结论：Room**。与现状分工一致（结构化数据 Room、标量设置 DataStore），栈的"取最新+删该条"正是关系型最舒服的操作，且 DAO 接口可直接手写 fake 做纯 JVM 测试。

## 2. 栈序实现：自增 id 即序（无独立 position 列）

入栈 = `INSERT`（id 单调递增），栈顶 = `ORDER BY id` 的最后一行，出栈 = `DELETE` 该行。

- 免去维护 `position` 的重排/原子性问题；
- 代价：栈行删除后 id 不回收——无关紧要（id 仅用于排序与定位删除，无展示语义）；
- 同一时刻两次快速保存：SQLite 单写者 + 自增 PK 保证全序。

## 3. 覆盖层创建路径的同步消费（关键约束）

`BlockingOverlay` 在 `onAccessibilityEvent`（主线程）同步构造，提示文本必须**当场可得**（FR-012），不能挂起等 IO。采用 `InterceptionController` 已验证的**快照模式**：

```text
init: scope.launch { dao.observe(stack).collect { 快照 = it } }   // 后台预热
take: synchronized(锁) { 弹快照栈顶 } + scope.launch { dao.delete(id) }  // 内存同步，落库异步
```

### 3.1 竞态：observe 重发导致"栈顶复活"

`take` 内存弹栈后、异步 `delete` 落库前，若其它写入（如同时点保存触发 insert）使 observe 重发**仍含已消费行**的列表，快照被覆盖 → 同一栈顶被下一次拦截再次消费（违反 FR-03 一次性）。

**防护**：`consumedPending` id 集合——`take` 时标记；collect 时 `entries.filter { it.id !in consumedPending }`，且以最新 DB 列表 `retainAll` 收敛 pending（删除落库后的重发自然清掉）。锁内完成，闭环。

**残余窗口分析**：`take` 与 collect 在锁内互斥；即使 collect 先用旧 DB 状态覆盖，pending 过滤保证已消费条目不复活。最坏效应是快照短暂滞后一拍（下一次 collect 修正），不影响正确性。

### 3.2 备选被否方案

- **runBlocking 删库**：主线程 IO，禁止。
- **消费移到覆盖层 dismiss**：中断路径（`onUnbind`/进程被杀）不可靠，且违反 spec"展示即消费"（用户已批准）。
- **内存消费 + 重启时对账**：把一致性推迟到重启，引入两处真源，更复杂。

## 4. 覆盖层输入控件：传统 View 直加（不引入 Compose）

`BlockingOverlay` 是 `WindowManager + TYPE_ACCESSIBILITY_OVERLAY` 的传统 View 树。在该上下文嵌入 `ComposeView` 需要 `ViewTreeLifecycleOwner`/`SavedStateRegistryOwner` 手工装配（Service 窗口无天然宿主），成本与风险远超收益。**结论：直接用 `EditText` + `Button`**，与现有 TextView/Button 同构。

- 30 字硬截断：`InputFilter.LengthFilter(30)`（字符计数，中文 1 字 = 1，符合 spec 口径）；
- 空白校验：点击保存时 `trim().isNotEmpty()`，失败以反馈 TextView 提示（无需 TextWatcher 动态启停按钮）；
- 反馈展示：保存成功后反馈 TextView 显示文案并 `postDelayed` 复位（复用覆盖层既有 main-looper scope/post 语义）；
- 输入框**不受** 5 秒按钮锁定约束（`render()` 只切按钮态，FR-009）；未保存草稿随覆盖层移除自然丢弃（FR-002）。

软键盘注意：全屏 overlay 窗口需 `FLAG_ALT_FOCUSABLE_IM` 处理？——实测口径：`TYPE_ACCESSIBILITY_OVERLAY` 默认可聚焦即可弹软键盘；若 OEM 异常，quickstart 手测记录（低风险，输入是增强能力，失败不阻断拦截主流程）。

## 5. 随机池语义

- 栈空时 `(内置 7 条 + pool 全部).random()`——`kotlin.collections.random()` 均匀分布，与现状内置随机行为一致（不避重复，spec Assumptions 已确认）；
- 池为空时退化为现状（纯内置 7 条），零回归；
- `builtinHints` 由服务侧从 `resources.getStringArray` 取出传入 `takeNextHint`——`HintRepository` 不持资源引用，保持纯数据层可测性。

## 6. 测试策略

| 层 | 方式 | 覆盖 |
|---|---|---|
| `HintDao` | Robolectric in-memory Room | 按 kind 观察、id 升序、按 id 删除 |
| `HintRepository` | fake DAO（手写实现 @Dao 接口 + MutableStateFlow）纯 JVM | LIFO、栈空回落、合并池随机（种子/穷举）、防复活竞态、持久化调用转发 |
| `HintListViewModel` | 假仓库或直接 fake DAO | 30 字截断、空白拒绝、remove 转发 |
| 迁移 v3→v4 | 既有 `AppDatabaseMigrationTest` 模式扩展 | 旧表数据保留、hints 表可用 |
| 覆盖层 UI | quickstart 手测 | 输入、保存反馈、倒计时解锁互不干扰 |

`HintDao` 设计为最小接口（3 个方法），fake 可 50 行内实现，无需 mock 框架（项目亦无此依赖）。

## 7. 风险与开放问题

- **软键盘在无障碍覆盖层上的表现**：个别 ROM 可能限制非 Activity 窗口弹 IME。缓解：quickstart 场景 C 真机验证；即便极端不可用，仅损失"当场输入"能力，管理页（Compose Activity 内）与栈展示链路不受影响。
- **take 与 Flow collect 初值竞态**：进程刚起、快照未就绪即拦截 → 栈内容临时按空处理（回落随机池）。可接受：与现状行为一致，不错误展示；下一次拦截恢复正常。极小概率且自愈。
