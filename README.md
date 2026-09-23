# 五秒 (five-sec)

打开抖音、小红书、B站等沉浸式应用前，先弹窗拦截，引导一次 5 秒提肛（凯格尔）锻炼，并问你是否真的要打开——给无意识的刷手机加一道"减速带"，顺带完成一次日常微锻炼。

## 工作原理

1. 通过 **无障碍服务（AccessibilityService）** 检测目标应用进入前台。
2. 命中后直接弹出全屏拦截覆盖层（`TYPE_ACCESSIBILITY_OVERLAY` 窗口，非 Activity——规避 OEM 对后台启动界面的静默拦截）。
3. 拦截页强制 **5 秒提肛倒计时**，期间"打开/取消"按钮锁定；页面同时展示一条提示语（内置+自定义按顺序**循环**轮换）和**今日待办**卡片。
4. 倒计时结束后可选择：
   - **打开**：在 5 秒抑制窗口内重新启动目标应用（避免二次拦截）。
   - **取消**：留在桌面。
5. 所有数据本地存储（Room + DataStore），**无需账号、无需联网**。

## 每日待办（specs/005-daily-todos）

底部四个 Tab：**待办 / 拦截 / 提示语 / 统计**，默认落在「待办」。

- **待办页**（specs/007 起分两区）：「**今日待办**」卡维护一切未过期条目——新增、重命名、删除、启用/停用、当天勾选，按重复规则自动回到未完成（惰性求值，无需手动重置）；「**过期待办 (n)**」卡收纳一次性条目的失败存量（按有效期日升序，最早失败在前），可「改为今天」重试或删除，**无迟到补勾**；无过期时第二卡不显示。每行标题下副行显示纯日期（今日区=创建日，v7 前老条目显示「—」；过期区=有效期日）。上限 20 条（两区共享）、每条 ≤200 字（列表单行省略展示，点条目弹窗看全文；拦截卡片每条仅展示前 12 字）。**重复规则四选一**：每天（默认）/ 按星期几（多选周一~周日，仅选中日轮到）/ 每 N 天（2–365，滚动节奏：从未完成立即可做、完成后隔 N 天再次出现、到期持续提醒直到完成）/ **仅今天**（一次性：只在有效期日当天轮到，当天完成跨日自动清理，当天没完成跨日进过期分类）；不轮到的日子条目灰显「今天不用做」不可勾、不计进度、不上拦截卡片；切换规则不清完成状态、不改创建时间；重复类错过当天不进过期分类（顺延到下次轮到）。
- **拦截时机提醒**：任一目标应用触发拦截时，覆盖层会用一块紧凑卡片展示「今日待办 2/5」与未完成条目（最多 3 条，每条截前 12 字，卡片内左对齐）；全部完成显示"今日待办已全部完成 ✓"；没有启用待办时整块隐藏。卡片只读，勾选回待办页完成。
- 待办**不进统计页、不产生任何通知**；提醒只发生在拦截弹出的那一刻。

## 自定义提示语

拦截页的提示语有两层来源（specs/004-custom-hints 引入，specs/005-daily-todos 改为循环）：

- **内置提示语**：7 条固定文案，只读；「提示语」页内置区标题行右侧有启停开关（默认开启，持久化到 DataStore），关闭后内置条目**退出循环序列**（仅剩自定义池参与轮换，池也为空时拦截页提示语位置为空）。
- **自定义提示语池**：「提示语」Tab 中添加（≤30 字），与（启用的）内置合成单一序列，**每次拦截按顺序轮换展示一条**，到末尾回到开头；轮换进度（游标）持久化，进程重启后续接而非归零。
- 早期版本在拦截页"留一句话"的一次性提示已退役；升级时这些文字会自动并入池中继续参与轮换（数据零丢失）。

## 技术栈

Kotlin · Jetpack Compose + Material 3 · Hilt · Room · DataStore · Coroutines/Flow · Navigation-Compose
minSdk 26（Android 8.0）/ targetSdk 35，单 `app` 模块。

## 构建

> 首次使用需先生成 Gradle Wrapper（仓库未提交 wrapper jar）：
> ```bash
> gradle wrapper            # 或直接用 Android Studio 打开本项目自动生成
> ```

```bash
./gradlew :app:assembleDebug            # 构建
./gradlew :app:installDebug             # 安装到真机
./gradlew :app:testDebugUnitTest        # JVM 单元测试（含 Robolectric）
./gradlew :app:connectedDebugAndroidTest# 仪器化测试（需真机/模拟器）
```

## CI（GitHub Actions）

仓库已包含 [.github/workflows/android-build.yml](.github/workflows/android-build.yml)：推送到 main/master、提 PR，或在 Actions 页面手动「Run workflow」时，会自动构建 debug APK 并运行单元测试。

- **无需 Android Studio / 本地 SDK**：CI 在 ubuntu runner 上配置 JDK 17 + Android SDK + Gradle 8.9，并直接用 `gradle` 构建（不依赖 wrapper jar）。
- **获取 APK**：构建完成后，在该次 Action 运行页底部的 **Artifacts** 下载 `five-sec-debug-apk`，解压得到 `app-debug.apk`，传到手机安装即可。
- **查看测试**：失败时可下载 `unit-test-results`（JUnit XML）排查。

## 首次使用

1. 安装后启动，按引导开启「五秒 · 应用拦截」无障碍服务。
   - Android 12+ 会直达该服务的开关详情页，拨开开关并确认即可，无需在长列表里翻找；
   - Android 13+ 若被「受限设置」拦截，到 **无障碍 > 已安装的应用 > 允许受限设置**。
2. 默认已内置抖音、小红书、B站三个目标应用；在「拦截」Tab 可管理总开关、无障碍状态与目标应用增删。
3. 在「待办」Tab 添加你的每日任务，之后每次拦截都会顺带提醒你今天还剩什么没做。
4. 点击桌面抖音图标即可触发拦截页。

## 统计与数据留存

- 统计页顶部为**今日概览**（今日拦截/取消/打开、连续完成天数）；
- 当天没有任何拦截记录时统计页**照常渲染**，各项以 0 值展示（查询始终执行，"零"是有效数据而非空态缺失）；
- 下方为**各应用历史数据**：按 **日 / 周 / 月 / 年** 自然周期切换（周从周一起算）；周可筛本周/上周，月可筛当年 1 月至今，年可筛最早记录年份至今，不提供未来周期；每个清单应用一张品牌色卡片展示所选周期内的拦截/打开/取消次数；
- 所有拦截记录自首次使用起**本地完整留存**（只增不删，升级走正式数据库迁移），除卸载应用外不会丢失；统计由事件流水实时聚合，卸载后数据清空重新累计。

### 一键开启与自动恢复（可选，推荐）

普通应用无法自行开启无障碍服务；一次性执行下面的 adb 授权后，本应用可在页面内「一键开启」，并在每次打开应用时自动恢复被系统后台清理关闭的服务：

```bash
adb shell pm grant com.fivesec.app android.permission.WRITE_SECURE_SETTINGS
```

- 该权限只用于写入本应用的无障碍开关（`ENABLED_ACCESSIBILITY_SERVICES`），不会触碰其他安全设置；
- 卸载重装后需重新执行一次；不授权则始终跳系统设置手动开启（原有行为不变）。

## ⚠️ 平台限制

- **Android 17「高级保护模式」** 开启后，可能完全阻止非无障碍类应用使用 AccessibilityService，届时核心拦截功能不可用。
- **国产 ROM**（小米/华为/OPPO/vivo）可能清理后台；如发现服务被关闭，请将本应用加入「自启动 / 省电白名单」，或使用上面的 adb 授权实现打开应用自动恢复。
- 列出已安装应用使用了 `QUERY_ALL_PACKAGES`；适用于自用/侧载/国内商店分发。若上架 Google Play 需另行评估该权限与无障碍权限的合规性。

## 隐私

- 仅读取应用包名以判断哪个应用进入前台，**绝不读取窗口内容**。
- 拦截日志（应用、时间、是否完成锻炼、打开/取消）仅存于本机，不上传、不联网。

## 文档

设计文档位于 `specs/` 目录：

- [001-app-intercept-exercise](specs/001-app-intercept-exercise/spec.md)：拦截 + 5 秒锻炼（[plan](specs/001-app-intercept-exercise/plan.md)、[data-model](specs/001-app-intercept-exercise/data-model.md)、[quickstart](specs/001-app-intercept-exercise/quickstart.md)）
- [005-daily-todos](specs/005-daily-todos/spec.md)：每日待办与拦截流程打通 + 信息架构重构 + 提示语循环（[plan](specs/005-daily-todos/plan.md)、[data-model](specs/005-daily-todos/data-model.md)、[quickstart](specs/005-daily-todos/quickstart.md)）
- [007-oneoff-todos](specs/007-oneoff-todos/spec.md)：一次性待办「仅今天」+ 过期分类 + 创建时间 + 覆盖层卡片 12 字左对齐（[plan](specs/007-oneoff-todos/plan.md)、[data-model](specs/007-oneoff-todos/data-model.md)、[quickstart](specs/007-oneoff-todos/quickstart.md)）
