# Contract: Interception Flow（拦截流程行为/UI 契约）

**Date**: 2026-07-27 | **Spec**: [spec.md](../spec.md) | **Data Model**: [data-model.md](../data-model.md)

本应用面向用户与系统的核心"接口"是**拦截流程本身**。本文定义其可观测的行为契约（状态、输入、输出、时序），不含实现代码。对应 FR-001/002/003/006 与 User Story 1。

---

## 触发条件（系统侧入口）

| 条件 | 行为 |
|------|------|
| 收到 `TYPE_WINDOW_STATE_CHANGED`，包名 ∈ 目标清单，且**全局开关开**，且该包 `TargetApp.isEnabled=true`，且**未在抑制窗口内**，且**通过去抖** | 进入拦截流程（见下） |
| 上述任一不满足 | 不拦截（放行） |

## 拦截流程契约（`BlockingActivity`）

### 进入
- 服务先 `performGlobalAction(GLOBAL_ACTION_HOME)`（返回桌面，隐藏目标应用），随后以 `FLAG_ACTIVITY_NEW_TASK` 启动 `BlockingActivity`，传入目标 `packageName`。
- 时序目标：从用户点击目标图标 → 拦截页可见可读，p90 < 1 秒（SC-002）。

### 拦截页可见元素（UI 契约）

| 区域 | 内容 | 状态依赖 |
|------|------|----------|
| 标题 | "是否打开 {应用名}？"（应用名由 PackageManager 解析） | 始终 |
| 锻炼引导 | 提肛引导文案 + 大号倒计时数字 5→0 | 倒计时进行中 |
| "打开"按钮 | 解锁前禁用（置灰/不可点） | `BLOCKING`→禁用；`CHOICE_UNLOCKED`→可用 |
| "取消"按钮 | 同上 | 同上 |

### 状态与输入输出（与 data-model 状态机一致）

| 当前状态 | 用户/系统输入 | 输出 / 副作用 | 下一状态 |
|----------|---------------|---------------|----------|
| `BLOCKING` | 点击"打开"/"取消" | 忽略（按钮锁定） | `BLOCKING` |
| `BLOCKING` | 倒计时归零 | 启用两个按钮；倒计时数字停在 0 | `CHOICE_UNLOCKED` |
| `CHOICE_UNLOCKED` | 点击"取消" | 写 `InterceptionEvent(outcome=CANCELED, exerciseCompleted=true)`；finish；留在桌面 | `TERMINATED` |
| `CHOICE_UNLOCKED` | 点击"打开" | 写抑制窗口(pkg, +3s)；写 `InterceptionEvent(outcome=OPENED, exerciseCompleted=true)`；finish；启动目标 LaunchIntent | `TERMINATED` |
| `BLOCKING` | Home/息屏/系统抢占 | 写 `InterceptionEvent(outcome=INTERRUPTED, exerciseCompleted=false)`；不重拉目标 | `TERMINATED` |

### 不变量（契约校验，必须成立）
1. `OPENED`/`CANCELED` 只能在 `CHOICE_UNLOCKED` 后产生（强制 5 秒减速带，FR-006）。
2. `OPENED` 必伴随写入同包名抑制窗口（防重入，R3）。
3. 倒计时数字必须单调 5→0 且用户可见可数（SC-003）；不得跳过或回绕。
4. 选择"打开"后，目标应用**必须**被重新启动；选择"取消"后，目标应用**不得**留在前台。

---

## 全局开关与单应用开关契约

- **全局总开关 = 关**：拦截流程永不触发（即便包名在清单内）。
- **某应用 `isEnabled = false`**：该应用不触发拦截，其它清单内应用照常。
- 任一开关变化**立即生效**，无需重启应用。

## 性能契约
- AccessibilityService 单事件处理 < 16ms（主线程无阻塞）。
- 拦截页倒计时 60fps。
- 日常后台无可感知额外耗电（SC-007）。
