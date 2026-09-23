# Research: 一次性待办与过期分类（specs/007）

## 现状事实（代码勘察结论）

- `todos` 表 v6 无创建日列：006 设计时「从未完成恒轮到，无需创建日列」——一次性规则打破该前提（过期归因需要"哪天的失败"）。
- `TodoRecurrence.isDue` 被 VM（灰显）、Repository（覆盖层快照）、单测三方共用；签名需加 `dueDate`，编译器暴露全部调用点。
- 覆盖层卡片现状：截前 30 字（`BlockingOverlay.TODO_DISPLAY_MAX`）、`gravity=CENTER`，TextView 程序化布局。
- `TodoDao.updateRecurrence` 是定向 UPDATE（防并发覆盖），扩展 dueDate 必须同语句写入以维持原子性。
- 仓库测试/VM 测试用手控 fake DAO（StateFlow），DAO 新方法需同步实现。
- Gradle：本机 `D:\JAVA\gradle-8.9`；仓库不提交 wrapper jar，CI 直接用 gradle 8.9。

## 方案否决记录

- **dueDate 复用 lastCompletedDate/createdAt 单列**：复活要改有效期、展示要创建时间不可变——单列无法同时满足两个不变量，否决。
- **过期条目保留启用开关**：过期条目无"明天"语义，开关只剩误导，否决（停用一次性跨日同样过期）。
- **完成历史留存（仅 UI 隐藏）**：v1 无任何历史界面，只会积累死数据；与「今天的完成即可」的心智不符，否决。
- **迟到补勾**：用户质询中明确否决（补勾=自欺）。
- **定时清理任务**：违背项目「惰性求值、无清理后台」架构共识，否决。

## 时区/日期口径

- 全部沿用 `DateUtil.todayString`（yyyy-MM-dd，设备本地时区）字符串口径；字典序=时间序，比较零解析开销；解析仅作脏数据防御。
- CI 在 UTC 跑：测试日期一律字面量/同 API 派生（沿用 005/006 模式）。
