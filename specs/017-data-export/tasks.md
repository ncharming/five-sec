# Tasks: 本地数据导出 CSV（specs/017）

- [ ] 1. 纯逻辑：`data/export/CsvExporter`（BOM/四段结构/表头/RFC 4180 转义/时间双列格式化，显式 ZoneId）+ 单测（转义、空表、段序）
- [ ] 2. DAO：三事件表与 todos 的一次性全量 suspend 快照查询（`getAll*`）+ DAO 测试（升序/全量）
- [ ] 3. 仓库：快照读取暴露（InterceptionRepository/UsageSessionRepository/TodoRepository 各一）
- [ ] 4. UI/VM：设置页「导出数据」行 + `CreateDocument("text/csv")` launcher + VM 导出（IO 组装写入、成功/失败一次性事件、取消静默）+ strings.xml
- [ ] 5. 测试：CsvExporterTest 全绿；快照查询 DAO 测试
- [ ] 6. 文档：README（功能段+隐私节导出属主提示+文档目录）+ AGENTS（术语表与红线措辞「不联网不上传，本机导出已拍板允许」）+ quickstart 手测场景
- [ ] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
