# Quickstart: 端到端验证指南（五秒 / five-sec）

**Date**: 2026-07-27 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

本文描述如何构建、安装并验证本特性可端到端工作。是**验证/运行指南**，不含完整实现代码（实现见 tasks.md 与实现阶段）。契约与数据模型细节见 [contracts/](contracts/) 与 [data-model.md](data-model.md)。

---

## 前置条件
- Android Studio（最新稳定版，支持 AGP/Compose/SDK 35）。
- 一台 **Android 真机**（推荐，因 AccessibilityService 与跨应用拦截在模拟器上行为可能失真；建议 minSdk 26+ 真机）。
- 真机上已安装至少一个默认目标应用（抖音 / 小红书 / B站 之一），用于实测拦截。
- *注：本项目尚未初始化 Gradle 工程脚手架；以下命令在 `/speckit-tasks` → `/speckit-implement` 生成实际工程后生效。*

## 构建 & 安装
```bash
# 调试构建并安装到已连接真机
./gradlew :app:installDebug
```
首次启动后按引导授予无障碍权限（见 [permissions-setup.md](contracts/permissions-setup.md)）。

## 验证场景

### V1：核心拦截流程（User Story 1 / FR-001/002/003/006）— 手工
1. 在设置中确认"抖音"已勾选、全局开关开。
2. 从桌面点击抖音图标。
3. **预期**：抖音被隐藏、出现拦截页，显示提肛引导与 5→0 倒计时；倒计时期间"打开/取消"置灰不可点。
4. 倒计时归零后按钮可用。
5. 点"取消" → 回到桌面，抖音未打开；统计页"今日拦截 +1、取消 +1"。
6. 再次点击抖音 → 拦截页 → 倒计时结束 → 点"打开" → 抖音正常打开（且不出现二次拦截）；统计"打开 +1"。
7. **判定**：SC-001（>95% 命中）、SC-002（p90<1s，体感）、SC-003（倒计时清晰可数）应满足。

### V2：自定义清单（User Story 2 / FR-004/005）— 手工
1. 添加"微博"到清单 → 打开微博应被拦截。
2. 移除"微博" → 打开微博不再被拦截。
3. 单独关闭对"B站"的拦截 → B站 放行，其它清单内应用仍拦截。
4. 全局总开关关闭 → 任何应用都不拦截；重新开启恢复。

### V3：统计与连击（User Story 3 / FR-009）— 手工
1. 触发若干次拦截并混合"打开/取消"。
2. 打开统计页，核对拦截次数与取消/打开分布、连续完成锻炼天数是否正确。

### V4：自动验证（仪器化测试）
- **BlockingScreen UI 测试**（Compose UI Test）：验证倒计时→按钮锁定→解锁→打开/取消的状态流转与不变量（见 [interception-flow.md](contracts/interception-flow.md)）。
- **纯逻辑单测**（JUnit + Robolectric）：去抖窗口、抑制窗口判定、倒计时状态机、统计聚合。
- **端到端**（UIAutomator）：用 `launchBrowser`/启动目标应用的方式触发系统事件，断言 `BlockingActivity` 出现、选择后目标应用状态正确。
```bash
./gradlew :app:testDebugUnitTest        # 单元/Robolectric
./gradlew :app:connectedDebugAndroidTest # 仪器化（需真机/模拟器）
```

## 边界与失败排查
- **拦截不生效**：检查无障碍服务是否仍开启（部分 OEM 重启后会关闭）、是否被"受限设置"/高级保护模式阻断、该应用是否在清单且 `isEnabled`、全局开关是否开。
- **点"打开"后又弹一次**：抑制窗口未生效（重入 bug）—— 检查 `InterceptionController` 的抑制写入与过期判定。
- **目标应用在覆盖层后闪现**：未先 `GLOBAL_ACTION_HOME` 即弹拦截页 —— 检查进入顺序。
- **耗电异常**：AccessibilityService 是否窄范围配置（仅 `typeWindowStateChanged` + 目标包名）。

## 完成判定
当 V1–V4 全部通过、且 [interception-flow.md](contracts/interception-flow.md) 与 [permissions-setup.md](contracts/permissions-setup.md) 的全部不变量成立时，视为本特性可交付。
