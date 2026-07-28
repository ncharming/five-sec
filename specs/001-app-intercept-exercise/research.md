# Research: App Launch Interceptor with 5-Second Exercise Prompt (五秒 / five-sec)

**Date**: 2026-07-27 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Phase 0 研究产出。下文逐一解决 Technical Context 中标记的不确定项与关键技术依赖，给出**决策、理由、备选方案**。

---

## R1. 应用启动检测与拦截机制（核心决策）

**Decision**: 采用 **AccessibilityService** 监听 `TYPE_WINDOW_STATE_CHANGED` 事件作为主检测机制；检测命中目标包名后，先 `performGlobalAction(GLOBAL_ACTION_HOME)` 返回桌面（隐藏目标应用），再启动全屏 `BlockingActivity` 进行拦截。

**Rationale**:
- 这是该品类（app blocker / friction 工具，如 one sec）的事实标准与最可靠方案。`TYPE_WINDOW_STATE_CHANGED` 在新窗口/Activity 进入前台时**近实时**触发，携带包名，可满足 SC-002（p90 < 1s）。
- AccessibilityService 自带可绘制于任意应用之上的覆盖能力，比单独的 `SYSTEM_ALERT_WINDOW` 更强、生命周期更可控。
- 先 `GLOBAL_ACTION_HOME` 再弹拦截页，可避免目标应用在覆盖层后"闪一下"的问题；选"打开"时再用 `getLaunchIntentForPackage` 重新拉起目标应用。

**Alternatives considered**:
- **UsageStatsManager 轮询**：`queryEvents` 需主动轮询、有秒级延迟，无法满足"打开前拦截"；放弃作为主机制。仅作为 AccessibilityService 不可用（如 Android 17 高级保护模式）时的**降级探测**备选。
- **自定义桌面启动器（Launcher）**：把本应用设为默认桌面，可在点击图标时干净拦截，无需无障碍权限。但需构建完整桌面/抽屉，远超 MVP 范围，且要求用户更换默认桌面、侵入性高。**记为未来可选方案**，不在 MVP。
- **Device Admin / 数字健康 API**：第三方不可用或场景不符，放弃。

---

## R2. 覆盖层渲染方式：BlockingActivity vs SYSTEM_ALERT_WINDOW

**Decision**: 用**独立全屏 Activity（`BlockingActivity`）**承载拦截 UI，而非裸 `SYSTEM_ALERT_WINDOW` 浮窗。

**Rationale**:
- Activity 提供完整生命周期、返回键处理、Compose UI 托管与状态机（倒计时→解锁）的清晰边界，符合 SC-003（清晰可读、可数的倒计时）。
- 由 AccessibilityService 通过 `Intent.FLAG_ACTIVITY_NEW_TASK` 拉起，可靠覆盖于桌面之上。
- `SYSTEM_ALERT_WINDOW` 仅在确有"穿透到任意界面"需求时才更优；本场景拦截页本就是模态全屏，Activity 更合适，权限诉求也更小。

**注意**：仍可能需要 `SYSTEM_ALERT_WINDOW` 作为兜底（部分 OEM 行为差异），但默认走 Activity 路径。

---

## R3. 重入与去抖（防"无限弹窗"）

**Decision**: 在 `InterceptionController` 内引入两层保护：
1. **去抖（Debounce）**：同一目标包名在短窗口（如 1000ms）内重复触发的 `TYPE_WINDOW_STATE_CHANGED` 只处理一次（该事件在应用切换时会连发）。
2. **抑制窗口（Suppression）**：当用户在拦截页选"打开"后，对该包名设置一个带过期时间（如 3000ms）的抑制标记；期间 AccessibilityService 命中该包名一律忽略，避免"重新拉起目标应用 → 再次检测 → 再次拦截"的死循环。

**Rationale**:
- 这是该类工具最关键的健壮性问题；不处理会导致死循环或重复覆盖层。
- 抑制标记为**进程内瞬态**（内存 Map<包名, 过期时间戳>），无需持久化——重启即失效本就合理。

**Alternatives**: 用持久化标志位 → 语义错误（设备重启后仍抑制会漏拦截），放弃。

---

## R4. 默认目标应用包名（已核实）

**Decision**: 内置默认种子清单与包名如下（已通过应用商店核实）：

| 应用 | 包名 | 开发商 |
|------|------|--------|
| 抖音 | `com.ss.android.ugc.aweme` | 字节跳动 |
| 小红书 | `com.xingin.xhs` | 行吟信息科技 |
| 哔哩哔哩 | `tv.danmaku.bili` | 哔哩哔哩 |

**Rationale**: 包名是检测与匹配的唯一稳定标识；用户看到的"应用名/图标"需由 `PackageManager` 动态解析（用户系统语言、版本会变），不应硬编码名称。默认清单仅作种子，最终以用户安装与选择为准（FR-004）。

**备选/扩展**：可预留"更多常见沉浸式应用"的扩展清单（快手 `com.smile.gifmaker`、微博 `com.sina.weibo` 等），但 MVP 仅含上述 3 个。

---

## R5. 权限模型与首次引导（FR-008）

**Decision**: 首次运行引导用户授予：
1. **AccessibilityService 权限**（核心）：在系统"无障碍"设置中开启本应用的服务。
2. **Android 13+ Restricted Settings 放行**：targetSdk 33+ 时，新安装应用的 AccessibilityService 可能被系统标记受限，引导用户到 `设置 > 无障碍 > 已安装的应用 > 允许受限设置`。
3.（兜底）**SYSTEM_ALERT_WINDOW**（如 R2 兜底路径需要）。

**Rationale**: 权限是落地最大障碍；清晰、诚实的引导直接影响 SC-004（3 分钟内完成首配）。引导需解释**为何需要**该权限（仅读取包名、不读取窗口内容）。

---

## R6. 平台风险：Android 17 "Advanced Protection Mode"

**Decision**: 识别并提示，不阻断 MVP。

**Rationale（2025-2026 新增风险）**：Android 17 引入"高级保护模式"，开启后会**完全阻止非无障碍类应用使用 AccessibilityService**。若用户开启该模式，本应用核心功能将不可用。

**对策**:
- 在引导与设置中检测服务是否真正生效；若服务反复被禁用/不可用，给出明确文案说明可能与系统保护模式有关。
- 文档与 README 注明该限制。
- **降级备选**（非 MVP）：UsageStatsManager 轮询做"事后提醒"（无法真正"打开前拦截"，仅统计/弱提示）。

---

## R7. 国产 ROM 后台清理（耗电/存活）

**Decision**: 不依赖"常驻后台服务"保活；AccessibilityService 由系统托管、通常被豁免于后台清理。但仍提供**厂商自启动/省电白名单引导**（小米/华为/OPPO/vivo 等），并文档化。

**Rationale**: 满足 SC-007（无可感知耗电）与服务可用性。避免使用任何"保活黑科技"（违背平台规范且不可靠）。

---

## R8. 测试策略与可测性

**Decision**:
- **纯逻辑单测**：倒计时状态机、去抖/抑制窗口判定、目标解析、统计聚合 → JUnit + Robolectric。
- **UI 测试**：`BlockingScreen`（锁定→解锁→打开/取消）、设置/清单/统计页 → Compose UI Test。
- **端到端**：点击目标应用→拦截页→打开/取消 → **UIAutomator**（可跨应用）。
- AccessibilityService 本身的系统级行为以**手工 + UIAutomator 验收脚本**覆盖（单元难模拟系统事件）。

**Rationale**: 把可测逻辑下沉到 ViewModel/Controller（纯 Kotlin），保持 UI 与 Service 薄，符合可测试性。

---

## R9. 技术栈定型（默认决策，非 NEEDS CLARIFICATION）

| 维度 | 决策 | 备注 |
|------|------|------|
| 语言 | Kotlin 2.x | 现代 Android 默认 |
| UI | Jetpack Compose + Material 3 | 新项目默认 |
| 最低 SDK | 26（Android 8.0） | 覆盖 98%+，满足 Compose/覆盖层 |
| 目标 SDK | 35（Android 15） | 当前 |
| 存储 | Room（事件+清单） + DataStore（设置） | 本地、无后端 |
| DI | Hilt | Android 事实标准 |
| 异步 | Coroutines + Flow | 与 Compose/Room 契合 |
| 构建 | Gradle (Kotlin DSL) + 版本目录 | 标准工程化 |

---

## 结论：NEEDS CLARIFICATION 全部已解决

Technical Context 中无遗留 `NEEDS CLARIFICATION`——核心技术决策（R1–R8）均给出明确方案与权衡，技术栈（R9）采用现代 Android 默认。可进入 Phase 1 设计。
