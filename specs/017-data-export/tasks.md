# Tasks: 本地数据导出 CSV（specs/017）

- [x] 1. 纯逻辑：`data/export/CsvExporter`（BOM/四段结构/表头/RFC 4180 转义/时间双列格式化，显式 ZoneId）+ 单测（转义、空表、段序、双列、规则自描述）
- [x] 2. DAO：三事件表一次性全量快照查询（`getAllOrdered` 升序）+ 各既有 DAO 测试文件覆盖（排序/全量语义由 fake/真实库测试与导出测试共同锁定）
- [x] 3. 仓库：快照读取暴露（InterceptionRepository/UsageSessionRepository.exportSnapshot + TodoRepository.completionsSnapshot，todos 复用 allTodosOnce）
- [x] 4. UI/VM：设置页「导出数据」行 + `CreateDocument("text/csv")` launcher + DataExportViewModel（IO 组装写入、成功/失败一次性事件、取消静默）+ strings.xml
- [x] 5. 测试：CsvExporterTest 全绿；五个假 DAO 同步新接口
- [x] 6. 文档：README（隐私节导出属主提示 + 文档目录 014-017）+ AGENTS（红线措辞「本机文件导出经用户拍板允许」）+ quickstart 手测场景
- [x] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
