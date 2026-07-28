# 五秒 (five-sec)

打开抖音、小红书、B站等沉浸式应用前，先弹窗拦截，引导一次 5 秒提肛（凯格尔）锻炼，并问你是否真的要打开——给无意识的刷手机加一道"减速带"，顺带完成一次日常微锻炼。

## 工作原理

1. 通过 **无障碍服务（AccessibilityService）** 检测目标应用进入前台。
2. 命中后先返回桌面隐藏目标，再弹出全屏拦截页 `BlockingActivity`。
3. 拦截页强制 **5 秒提肛倒计时**，期间"打开/取消"按钮锁定。
4. 倒计时结束后可选择：
   - **打开**：在短暂抑制窗口内重新启动目标应用（避免二次拦截）。
   - **取消**：留在桌面。
5. 所有数据本地存储（Room + DataStore），**无需账号、无需联网**。

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

1. 安装后启动，按引导到 **系统设置 > 无障碍** 开启「五秒 · 应用拦截」服务。
   - Android 13+ 若被「受限设置」拦截，到 **无障碍 > 已安装的应用 > 允许受限设置**。
2. 默认已内置抖音、小红书、B站三个目标应用；可在「拦截应用清单」增删。
3. 点击桌面抖音图标即可触发拦截页。

## ⚠️ 平台限制

- **Android 17「高级保护模式」** 开启后，可能完全阻止非无障碍类应用使用 AccessibilityService，届时核心拦截功能不可用。
- **国产 ROM**（小米/华为/OPPO/vivo）可能清理后台；如发现服务被关闭，请将本应用加入「自启动 / 省电白名单」。
- 列出已安装应用使用了 `QUERY_ALL_PACKAGES`；适用于自用/侧载/国内商店分发。若上架 Google Play 需另行评估该权限与无障碍权限的合规性。

## 隐私

- 仅读取应用包名以判断哪个应用进入前台，**绝不读取窗口内容**。
- 拦截日志（应用、时间、是否完成锻炼、打开/取消）仅存于本机，不上传、不联网。

## 文档

设计文档位于 `specs/001-app-intercept-exercise/`：[spec.md](specs/001-app-intercept-exercise/spec.md)、[plan.md](specs/001-app-intercept-exercise/plan.md)、[research.md](specs/001-app-intercept-exercise/research.md)、[data-model.md](specs/001-app-intercept-exercise/data-model.md)、[quickstart.md](specs/001-app-intercept-exercise/quickstart.md)。
