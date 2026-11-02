# Spec: 本地数据导出 CSV（specs/017）

## 背景

产品把「历史数据是价值本身」写进红线（interception_events 只增不删），但卸载即全丢、换机无迁移
——用户对自己几年的自律流水没有任何持取手段。用户已拍板：**允许导出到本机文件**。SAF
（Storage Access Framework）让用户自选位置存一个 CSV，零新权限、零网络、不出设备——与
「数据只进不出」宪法兼容（出的只是用户主动选择的本地文件）。

## 需求

1. 设置页（拦截 Tab 底部）新增「导出数据」行：点击拉起系统文件选择器（SAF
   `ACTION_CREATE_DOCUMENT`，建议文件名 `five-sec-export-YYYY-MM-DD.csv`），用户确认后写入。
2. 导出内容**单文件 CSV（UTF-8 带 BOM**，Excel 直接打开不乱码），四段结构：
   - `# 拦截事件` 段：`timestamp, packageName, outcome, exerciseCompleted`（全部历史，升序）；
   - `# 使用会话` 段：`startedAt, endedAt, durationMinutes, packageName, guardShownCount, endReason`；
   - `# 待办完成` 段：`completedDate, todoText, wasExpired`；
   - `# 待办清单` 段：当前清单快照（`text, isEnabled, 规则描述, reminderTimes, createdAt, dueDate`）。
   时间戳导出**双列**：epoch 毫秒 + 本地时区可读形（`yyyy-MM-dd HH:mm:ss`，ZoneId 显式）。
3. 导出成功 toast「已导出」；失败（用户取消=静默、IO 失败）toast 中文文案；导出**绝不删改**任何库内数据。
4. 只导出不导入（v1 口径：导入涉及合并冲突与去重，超出本 spec；用户另行提案）。

## 方案

- **序列化纯逻辑**：`data/export/CsvExporter`——输入各表行列表 + ZoneId，输出整段 String
  （含 BOM、段头、表头、行转义：逗号/引号/换行按 RFC 4180 双引号转义）；单测覆盖转义与空表。
- **读取**：DAO 各加一次性全量 suspend 查询（`getAll*`——观察流复用不了，导出要快照）；
  经既有 repository 暴露（不新增写路径，红线无感）。
- **写入**：`InterceptScreen` `rememberLauncherForActivityResult(CreateDocument("text/csv"))` →
  VM `exportTo(uri)`（IO 线程组装 + `contentResolver.openOutputStream` 写入），结果一次性事件流。
- **隐私披露**：README 隐私节补一句导出文件的属主提示。

## 验收

- 导出后用文本编辑器/Excel 打开：四段齐全、中文无乱码、含逗号的待办文本被引号转义；
- 用户在文件选择器点取消：无 toast、无副作用；
- 导出前后统计页数字零变化（只读导出）。

## 红线沿用

零网络零权限新增（SAF 无需声明）；不改任何表结构；不删不改库内数据；「数据只进不出」修订为
「不联网不上传，本机文件导出经用户拍板允许」（README 隐私节同步）。
