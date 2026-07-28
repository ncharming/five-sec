# Data Model: App Launch Interceptor with 5-Second Exercise Prompt (五秒 / five-sec)

**Date**: 2026-07-27 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Phase 1 设计产出。包含持久化实体、瞬态（进程内）状态、以及拦截流程的状态机。仅描述数据结构与规则，不含实现代码。

---

## 持久化实体（Room）

### TargetApp（目标应用）

用户配置的"被拦截应用"清单条目。

| 字段 | 类型 | 说明 / 校验 |
|------|------|-------------|
| `packageName` | String（主键） | Android 包名，唯一标识；非空 |
| `isEnabled` | Boolean | 是否对该应用启用拦截（默认 `true`）；对应 FR-005 单应用开关 |
| `isDefault` | Boolean | 是否来自内置默认清单（用于区分"用户移除默认项"与"未添加"）；默认项为 `true` |
| `addedAt` | Long (epoch ms) | 加入时间 |

**关系**：与 `InterceptionEvent` 为一对多（一个 TargetApp 可有多次拦截事件，按 `packageName` 关联）。
**校验规则**：`packageName` 必须为设备上已安装应用的合法包名（添加时由 `PackageManager` 校验，未安装则拒绝添加）。
**种子数据（R4）**：`com.ss.android.ugc.aweme`、`com.xingin.xhs`、`tv.danmaku.bili`，`isDefault=true, isEnabled=true`。

### InterceptionEvent（拦截事件）

一次"用户尝试打开目标应用"的记录，供统计页（User Story 3）使用。

| 字段 | 类型 | 说明 / 校验 |
|------|------|-------------|
| `id` | Long（主键，自增） | 事件 ID |
| `packageName` | String | 被拦截的目标应用包名；外键关联 TargetApp |
| `timestamp` | Long (epoch ms) | 触发时间 |
| `exerciseCompleted` | Boolean | 是否走完了 5 秒倒计时（锻炼完成）；默认拦截页正常结束即 `true`，中断则为 `false` |
| `outcome` | Enum | `OPENED` / `CANCELED` / `INTERRUPTED`（用户选择打开 / 取消 / 被打断未选择） |

**校验规则**：`outcome` 为 `OPENED` 或 `CANCELED` 时 `exerciseCompleted` 应为 `true`（按钮解锁后才能选择）。
**保留策略**：本地保留近 N 天（如 90 天）用于统计，超出可清理；MVP 可暂不清理。

### Exercise（锻炼项，MVP 退化为常量）

MVP 仅"5 秒提肛"（FR-007），无需多实体存储；以领域常量表达：

| 属性 | 值（MVP） |
|------|-----------|
| `id` | `"kegel_5s"` |
| `name` | 提肛（凯格尔） |
| `durationSeconds` | 5 |
| `guidanceText` | 引导文案（如"收紧盆底肌，保持 5 秒"），来自字符串资源，便于国际化 |

> 未来若引入多锻炼（FR-007 之外的版本），再升级为持久化或配置化实体。

---

## 设置实体（DataStore，键值）

### AppSettings

| 键 | 类型 | 说明 | 默认 |
|----|------|------|------|
| `interception_globally_enabled` | Boolean | 全局总开关（FR-005） | `true` |
| `onboarding_completed` | Boolean | 是否完成首次权限引导 | `false` |
| `stats_retention_days` | Int | 事件保留天数 | `90` |

> 单应用开关存于 `TargetApp.isEnabled`（更自然的归属），而非 DataStore。

---

## 瞬态状态（进程内、不持久化）

### SuppressionEntry（抑制窗口条目，R3）

存放于 `InterceptionController` 的内存 Map（键 = 包名）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `packageName` | String | 目标包名（Map 键） |
| `expireAt` | Long (epoch ms) | 抑制过期时间；过期即移除 |

**规则**：用户选"打开"后写入 `(pkg, now+3000ms)`；AccessibilityService 命中该 pkg 且未过期 → 直接忽略。

### InterceptionSession（拦截会话，BlockingViewModel 内）

由 `BlockingActivity`/`BlockingViewModel` 持有，对应一次拦截的生命周期（状态机见下）。

---

## 拦截流程状态机（核心）

描述从"检测到目标应用进入前台"到"用户选择结束"的状态流转。对应 [contracts/interception-flow.md](contracts/interception-flow.md)。

```text
                ┌─────────────────────────────────────────────────────┐
  目标应用前台   ▼                                                     │
 ─────────▶ [DETECTED] ──(命中目标 & 未抑制 & 去抖通过)─▶ performGlobalAction(HOME)
                                                                │
                                                                ▼
                                                    [BLOCKING / 倒计时中]
                                                    • 展示 5 秒提肛引导
                                                    • "打开/取消" 锁定
                                                                │
                                              ┌───── 倒计时结束 ─┘
                                              ▼
                                   [CHOICE_UNLOCKED]（按钮可用）
                                              │
                          ┌─────── 选"打开" ──┴── 选"取消" ───────┐
                          ▼                                      ▼
            写抑制窗口(pkg, +3s)                          写 InterceptionEvent
            写 InterceptionEvent(outcome=OPENED)            (outcome=CANCELED)
            finish + 启动目标 LaunchIntent                  finish + 留在桌面
                          │                                      │
                          └──────────────► [TERMINATED] ◄────────┘
```

**状态**

| 状态 | 含义 | 进入条件 | 退出条件 |
|------|------|----------|----------|
| `DETECTED` | 服务收到目标包名前台事件 | `TYPE_WINDOW_STATE_CHANGED` 命中目标 | 命中+未抑制 → 进入 BLOCKING；抑制中 → 终止(忽略) |
| `BLOCKING`（倒计时中） | 拦截页显示，按钮锁定 | 启动 BlockingActivity | 5 秒倒计时结束 → CHOICE_UNLOCKED；被打断 → TERMINATED(INTERRUPTED) |
| `CHOICE_UNLOCKED` | "打开/取消"可点 | 倒计时结束 | 用户选择 → TERMINATED |
| `TERMINATED` | 会话结束 | 选择 / 打断 | — |

**关键不变量（校验）**：
- 仅 `CHOICE_UNLOCKED` 状态下 `outcome` 可被置为 `OPENED` 或 `CANCELED`（FR-006 强制 5 秒减速带）。
- 进入 `OPENED` 分支必须同时写入对应包名的 `SuppressionEntry`（R3 防重入）。
- `exerciseCompleted=true` 是 `OPENED`/`CANCELED` 的前置。

**被打断（INTERRUPTED）**：倒计时期间用户按 Home/息屏/系统抢占 → 会话终止，记 `outcome=INTERRUPTED, exerciseCompleted=false`，不重新拉起目标应用（用户已主动离开）。

---

## 实体关系总览

```text
TargetApp (1) ──< InterceptionEvent (N)        // 按 packageName 关联
TargetApp ── (AppSettings 全局开关共同决定是否拦截)
Exercise (常量, MVP) ── 被 BlockingSession 引用
SuppressionEntry / InterceptionSession ── 瞬态，进程内
```
