# AGENTS.md — five-sec（五秒）

## 项目概述

五秒（five-sec）是纯离线的 Android 自律工具：无障碍服务检测到目标应用（默认抖音/小红书/B站）进入前台时，弹全屏 `TYPE_ACCESSIBILITY_OVERLAY` 覆盖层强制 5 秒提肛倒计时，结束后用户选「打开」（写抑制窗口放行）或「取消」（回桌面），每次拦截写入本地事件流水供统计页实时聚合；拦截页同时展示提示语（内置+自定义循环轮换）与今日待办卡片，把减速带时机变成每日任务的提醒位；无账号、无网络、无后端，数据全在设备本地。

## 常用命令

前置：JDK 17（CI 用 Temurin）+ Android SDK（platform-35、build-tools 35.0.0）+ 真机或模拟器；无 API key、无签名、无网络配置。

```bash
# ---- setup（首次克隆）----
gradle wrapper                   # ⚠️ 仓库不提交 gradle-wrapper.jar，直接跑 ./gradlew 会失败；生成一次后即可用
                                 # （不想装本地 Gradle，用 Android Studio 打开项目也会自动生成 wrapper）
./gradlew :app:assembleDebug     # 编译 + 出 debug APK
./gradlew :app:installDebug      # 安装到已连接设备

# 可选：授权后应用内可「一键开启」无障碍服务，并自动恢复被 ROM 后台清理关闭的服务
adb shell pm grant com.fivesec.app android.permission.WRITE_SECURE_SETTINGS

# ---- build / test ----
./gradlew :app:assembleDebug             # 交付门槛 ①（见「验证标准」）
./gradlew :app:testDebugUnitTest         # 交付门槛 ②：JUnit4 + Robolectric；首次运行会下载 android-all 包，慢是正常的
./gradlew :app:connectedDebugAndroidTest # 仪器化测试，需真机/模拟器（androidTest/ 已配置、暂无用例）

# ---- lint ----
# 没有 ktlint/detekt/自定义 lint 门禁；代码规范靠「代码规范」+ 评审把关
```

遇到「幽灵」编译错误（代码明明正确）先 `gradle clean`：`gradle.properties` 为规避 KSP 缓存问题关掉了 KSP/Kotlin 增量缓存，但残留 build 目录仍可能出旧错误。

## 技术栈声明

**用什么：**

- Kotlin 2.0.21 · Jetpack Compose + Material 3 · Hilt · Room · DataStore · Coroutines/Flow · Navigation-Compose；测试用 JUnit4 + Robolectric。
- 单 `:app` 模块（`com.fivesec.app`），minSdk 26 / target+compileSdk 35 / JVM 17。
- Gradle 8.9（`gradle/wrapper/gradle-wrapper.properties`）、AGP 8.7.3；所有依赖版本集中在 `gradle/libs.versions.toml` 声明。

**禁止用什么：** 任何网络/埋点/分析依赖（纯离线是产品宪法，见「安全红线」）；第二个 Gradle module；拆分或引入上表之外的新框架（先提案再动手）。

**架构与模块边界**（单模块，包即分层，根 `app/src/main/kotlin/com/fivesec/app/`）：

```
interception/   拦截决策与无障碍服务（AppBlockerAccessibilityService → InterceptionController → CooldownGate）
blocking/       拦截覆盖层 UI + 5 秒状态机 + 今日待办卡片（BlockingOverlay → BlockingViewModel）
settings/       用户界面：ui/（Home/Todo/Intercept/HintList/Stats/Onboarding Screen）+ viewmodels/
data/           db/（Room：AppDatabase v6 + DAO）、datastore/（SettingsDataStore、DataStoreHintCursorStore）、repository/、seed/
domain/model/   纯 Kotlin 领域模型（TargetApp、InterceptionEvent、InterceptionOutcome、Exercise、Hint、Todo、AppSettings）
di/             AppModule：唯一的 Hilt @Module（DB、DAO、TimeProvider、HintCursorStore、应用级 CoroutineScope）
util/           叶子工具：TimeProvider、DateUtil、PackageUtil、AppBrandColorExtractor、AccessibilityPermissionHelper
ui/theme/       Compose 主题 token（与 colors.xml 的 brand_* 同源）
```

依赖方向：`ui → viewmodels → repository → db/datastore → domain.model`；`domain.model` 被各层引用但自身零 Android import；`util` 是叶子，不反向依赖业务层。

硬边界（代码评审会拒）：

- ViewModel 不直接持有 DAO，一律经 repository。
- `interception_events` 表只有一个写入点：`InterceptionRepository.record()`。统计 = 事件流水实时聚合，禁止重新引入预聚合表或双写（v3 已删掉 `app_statistics`，别复活它）。
- 判定纯逻辑放 `CooldownGate`/`BlockingViewModel` 这类无 Android 依赖的类，服务里只做事件路由与窗口管理。
- `AccessibilityService` 由系统创建，不能用 `@AndroidEntryPoint`，走 Hilt `@EntryPoint` 取依赖（见 `AppBlockerAccessibilityService`）；`BlockingOverlay` 手动构造 `BlockingViewModel`（非 Hilt），改拦截 UI 时保持这个形态。
- 拦截层是 WindowManager overlay，不是 Activity——OEM（如 ColorOS）会静默拦截后台 `startActivity`，别「重构」回 Activity。

## 代码规范

**格式与命名：**

- 每个 类/文件 顶部写中文 KDoc，说「为什么」和设计约束（如「规避 ColorOS 后台启动拦截」），不是复述代码。
- 用户可见文案进 `strings.xml`（中文为第一语言），Compose 与 overlay 都引用资源，不硬编码。
- 魔法数字进 `companion object` 常量；跨线程快照字段标 `@Volatile`（如 `InterceptionController` 后台收集、前台读取）。
- 测试方法名用中文反引号句子直陈行为（`周日起点返回当期周一` 风格）；单元测试放 `app/src/test/kotlin/`，包路径镜像主代码。

**错误处理：**

- 面向用户的失败用 `Result<Unit>` + 中文错误文案（见 `TargetAppRepository.addNewApp`）。
- overlay `addView` 失败降级为 `INTERRUPTED`：宁可放弃拦截也不闪退。

**结构与响应式约定：**

- ViewModel 暴露 `StateFlow`：私有 `MutableStateFlow` + 公开 `asStateFlow()`；派生流用 `combine` + `stateIn(Eagerly)`；按状态重订阅查询用 `flatMapLatest`（见 `StatsViewModel.appRangeStats`）。
- 可测试的时序逻辑一律注入 `TimeProvider` 取时间，禁止在纯逻辑里直接 `System.currentTimeMillis()`；日期边界计算集中在 `DateUtil`/`StatsRange`，带 `ZoneId` 参数（默认 `systemDefault()`）。
- 事件查询区间是半开 `[start, end)`；周按 ISO 周一起算。

**测试写法：**

- 覆盖重点是无 Android 依赖的纯逻辑：状态机（`BlockingViewModelTest`）、日期边界（`DateUtilTest`：周日/跨年/短月/时区）、DAO 聚合（`InterceptionEventDaoTest`：in-memory Room + Robolectric，跨周期插数据核对四档计数）、数据库迁移（`AppDatabaseMigrationTest`：手工按旧版本 schema 建库插数据 → 用新版 Room 打开触发迁移 → 断言数据保留；`exportSchema = false`，没有 schema json，迁移回归全靠这个手建库模式）。
- 时区陷阱：CI 在 UTC 跑，日期断言必须显式传 `ZoneId`（历史 bug，commit fe46089）；Robolectric 夹具要给全 `ResolveInfo` 字段，缺字段会 NPE。
- 集成/端到端验证走 specs 各目录的 `quickstart.md` 手测场景（真机 `adb install -r` 覆盖升级验证迁移与留存）。

## Git 规范

- 主分支 `master`：每次修改完代码直接 commit 并推送到 master；CI 绿为前提。
- Commit 格式：Conventional Commits + 中文主题 —— `feat(stats): 日周月年周期筛选`、`fix(test): …`、`docs(specs): …`、`chore: …`。
- 新功能走 spec-kit 流程：`specs/NNN-名称/` 下 spec → plan/research/data-model/contracts/quickstart → tasks.md，实现时逐项勾选 tasks.md 复选框，行为变更同步 README。
- PR 描述要求：写清动机（为什么改）、改动面（动了哪些模块/表/流程）、自检结果（对照「验证标准」逐项报告）。
- CI（`.github/workflows/android-build.yml`）：推 master、提 PR、手动 dispatch 触发；在 ubuntu runner 上直接用 `gradle` 8.9（不依赖 wrapper jar），只跑「验证标准」第 1、2 项，产物 `five-sec-debug-apk`。

## 验证标准

改动「完成」的定义 —— 以下全部满足才算 done，缺一即未完成：

1. `./gradlew :app:assembleDebug` 编译通过。
2. `./gradlew :app:testDebugUnitTest` 全绿；时序/日期/聚合/迁移的行为改动必须有对应测试（新增或更新）。
3. 未对 `interception_events` 引入 DELETE 或破坏性迁移；改了 DB schema 就新增正式 `Migration` 并配 `AppDatabaseMigrationTest`。
4. 统计页顶部「今日四卡」（拦截/取消/打开/连续天数）回归锚点未被触碰（计划内改动除外）。
5. tasks.md 复选框与 README/spec 口径一致；行为变更已同步进 README。
6. 推送后 CI 绿（CI 跑的正是第 1、2 项）。

## 安全红线

- **不提交密钥**：keystore/jks/local.properties/google-services.json 永不入库（`.gitignore` 已覆盖）；本应用无签名密钥需求，不要引入。
- **不运行破坏性命令**：禁止 `fallbackToDestructiveMigration`；禁止对 `interception_events` 做 DELETE/清库——这张表只增不删，历史数据是产品价值本身。
- **不擅自升级依赖**：版本号只在 `gradle/libs.versions.toml` 改；Kotlin/AGP/Gradle/compileSdk 的升降级必须先与用户确认。
- **数据只进不出**：不联网、不埋点、不上传；只读包名判断前台，绝不读取窗口内容。新增任何网络/分析依赖等于违背产品宪法（FR-013）。
- `WRITE_SECURE_SETTINGS` 只用于写入本应用的无障碍开关（且保留用户已开启的其他无障碍服务），不触碰其他安全设置。
- `QUERY_ALL_PACKAGES` 与无障碍权限仅适用于自用/侧载/国内商店分发；为「合规」顺手删权限或改拦截逻辑前先与用户确认。
- 不得拦截本应用自身与系统必备应用；必须保证用户随时能完全关闭拦截（FR-014）。

## 协作约束

工具无关的行为约束（无论用哪个 agent/IDE/命令行都适用）：

- **代码优先于文档**：`specs/001` 的 data-model/状态机已落后于实现（现为 overlay 而非 `BlockingActivity`——`BlockingActivity` 已不存在；抑制窗口是 5s（`SUPPRESSION_MS=5_000`）而非文档写的 3s）——行为以代码为准，改完顺手同步文档。
- 动无障碍服务事件逻辑前，先读 `AppBlockerAccessibilityService` 的事件处理注释并跑 `InterceptionControllerTest`；三个放行标记有优先级语义：`userOpenedPkg` > `suppressedPkg` > 去抖冷却，切到别的目标应用会清除 `userOpenedPkg`。
- 带注释的防御性写法不要「简化」掉：如 `StatsRange.availablePeriods` 生成月份周期必须先 `withDayOfMonth(1)` 再 `withMonth(m)`——31 号直接换月会在短月抛异常。
- 拦截流程时序是固定契约：「取消/打断」先 `GLOBAL_ACTION_HOME` 再延迟 250ms 撤 overlay，顺序反了目标应用会闪现；「打开」时目标一直在 overlay 后面运行，撤掉即见，不重复发 LaunchIntent。
- 国产 ROM（ColorOS 等）后台清理无障碍服务是常态而非 bug：无 `WRITE_SECURE_SETTINGS` 授权时只能引导用户手动开，别试图用其他手段拉起服务；Android 13+ 受限设置会拦住无障碍开关，需引导「无障碍 → 已安装的应用 → 允许受限设置」；Android 17「高级保护模式」可能整体禁用第三方无障碍服务（README 平台限制）。
- `.specify/memory/constitution.md` 是未填充的占位模板——spec-kit 的 Constitution Check 恒为 PASS，不要把「宪法」当真实门禁。
- 本地 pwsh/cmd 控制台以 GBK 解码 UTF-8 源文件时中文会显示乱码，文件本身没问题；读写源码一律按 UTF-8。

## 附录：领域术语表

- **拦截（Interception）**：目标应用进前台 → 弹覆盖层的完整动作；一次拦截记录为一条 `InterceptionEvent`。
- **目标应用（TargetApp）**：拦截清单条目（包名 + 友好名 + 单应用开关），上限 3 个；首次启动由 `DefaultAppSeed` 写入抖音/小红书/哔哩哔哩。
- **Outcome**：`OPENED`（选打开）/ `CANCELED`（选取消）/ `INTERRUPTED`（倒计时被打断未选择）；前两者必须完成 5 秒锻炼（`exerciseCompleted=true`）。
- **减速带**：产品核心性格——倒计时期间按钮置灰锁定，`OPENED/CANCELED` 只能从 `ChoiceUnlocked` 状态进入（`BlockingViewModel` 不变量）。
- **抑制（Suppression）**：选「打开」后临时放行该应用的重启，防止回到目标应用时二次拦截；`userOpenedPkg`（使用期间永久放行）与 `suppressedPkg`（立即生效）是服务内两个不同字段。
- **去抖（Debounce）**：`TYPE_WINDOW_STATE_CHANGED` 连发防重复弹窗，窗口 `DEBOUNCE_MS=800`；两者都实现在纯类 `CooldownGate`，常量在 `InterceptionController`。
- **覆盖层（Overlay）**：`TYPE_ACCESSIBILITY_OVERLAY` 全屏窗口，由无障碍服务绘制，始终浅色。
- **待办（Todo）**：固定每日清单条目（标题 ≤200 字 + 启用开关 + `lastCompletedDate` + 重复规则三列），上限 20 条；完成判定 = `lastCompletedDate == 今天`（惰性重置，无清理任务）；重复规则三选一（006）：每天（默认）/ 按星期几（位掩码 bit0=周一…bit6=周日）/ 每 N 天（2–365 滚动，锚点=`lastCompletedDate`，切换规则不清锚点——判定纯逻辑在 `util/TodoRecurrence`）；不轮到的日子待办页灰显禁勾、覆盖层卡片与 D/T 分母只含「轮到且启用」条目（`todayTodos` 快照过滤兑现）；v1 不进统计页、无通知；覆盖层只读展示（每条截前 30 字），勾选仅在待办页（列表单行省略、点条目弹只读全文弹窗）。
- **循环游标（HintCursor）**：提示语展示为「内置（资源数组顺序）+ 池（id 升序）」单一序列的循环，游标持久化在 DataStore（`hint_cycle_cursor`），进程重启续接；序列增删后取模继续，不承诺严格不重不漏。004 的栈式一次性提示已退役（存量行经 MIGRATION_4_5 改挂 pool）。
- **档位/周期（StatsRange/StatsPeriod）**：统计页 日/周/月/年 自然周期（周一起算、不含未来周期；月=当年 1 月至今，年=最早事件年至今）；顶部四卡（今日拦截/取消/打开/连续天数）与档位无关。
- **连击（Streak）**：连续完成 5 秒锻炼的天数；今天未完成但昨天连续则不断连。
- **品牌色**：从应用图标提取的卡片主色（`AppBrandColorExtractor` + Palette），回退健康绿 `FALLBACK_BRAND_ARGB`。
