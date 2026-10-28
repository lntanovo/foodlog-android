# 更新记录 / Changelog

已发布版本有 Git 标签、对应源码和独立 APK 下载。下列版本为本机开发测试签名构建，历史版本继续保留。

Published versions have Git tags, source snapshots and downloadable APKs. Previous releases remain available. These are development builds signed with the same local debug key.

## [1.4.1](https://github.com/lntanovo/foodlog-android/releases/tag/v1.4.1)

- 设置新增数据诊断，区分已吃与草稿，按日期查看历史数量，显示备份状态与本机数据集信息。
- 可浏览全部恢复前保护副本，校验预览后导出指定副本或明确恢复；不自动清理旧副本。
- 本地与云端恢复显示新增、移除、变更及餐食明细，同数量不同内容也能识别。
- 本地恢复预览增加修订校验：期间出现新记录时刷新差异并要求重新确认；中断后仍需手动确认。
- versionCode 8；保留原包名、签名链、Room v2 与备份格式 v3。
- 此版本提供诊断与恢复辅助，尚未确认此前 vivo 餐食历史异常的根因。

- Added local diagnostics with posted/draft counts, historical dates and backup state.
- Browse, validate, preview and export any retained pre-restore recovery file without deleting older copies.
- Review added, removed and changed records before local/cloud restores, including equal-count content changes.
- Guard local restores against edits made after preview; interrupted restores return to explicit confirmation.
- Version code 8; unchanged package, signing identity, Room v2 and backup schema v3. The previously reported vivo issue remains unconfirmed.

## [1.4.0](https://github.com/lntanovo/foodlog-android/releases/tag/v1.4.0)

- 登录后自动备份餐食、体重、配额、食品与常用餐的修改；保留本机记录、离线队列和历史版本。
- 新设备空白安装登录同一账号后自动恢复；本机与云端都有修改时先核对，避免自动覆盖。
- 首页与设置显示状态；恢复前保护副本、账号/修订号校验、云端原子比较后新增、同一快照重试防重复。
- versionCode 7；包名、签名、Room v2 和业务备份格式 v3 保持兼容。

- Automatically back up signed-in changes to meals, body records, quotas, foods and templates; retain local records, offline work and historical snapshots.
- Restore a pristine new device after signing into the same account; review divergent local/cloud edits before choosing a version.
- Show progress in Today/Settings; protect restores with local recovery files and revision/account checks. Atomically compare cloud heads and retry immutable snapshots without duplicates.
- Version code 7; stable package/signature, Room v2 and business backup schema v3.

## [1.3.0](https://github.com/lntanovo/foodlog-android/releases/tag/v1.3.0)

- 新增可选用户名密码登录、私有不可变版本云备份和跨设备预览恢复；登录、退出与网络失败不影响本机记录。
- 恢复前保护副本加入文件落盘、校验与原子完成；锁内核对本机修订号及账号，防止过期预览替换后续编辑。
- 加密保存令牌、串行刷新、重建后手动重试中断任务；不自动合并、注册或检查更新。
- versionCode 6，Room v2 与备份格式 v3 保持兼容，同签名覆盖安装保留记录。

- Added optional username/password accounts, private immutable cloud backup versions and reviewed cross-device restore. Local records remain independent of login and networking.
- Strengthened pre-restore protection with fsync, validation and atomic completion; verify local revision and account inside the restore lock.
- Encrypt session tokens, serialize refresh and resume interrupted work only on explicit user action. No automatic merging, registration or app update checks.
- Version code 6; Room v2 and backup schema v3 remain compatible, with same-signature in-place updates.

## [1.2.1](https://github.com/lntanovo/foodlog-android/releases/tag/v1.2.1)

- 修复编辑或补记历史体重时，趋势图截止日期跟随记录日期变化、较新记录因而不显示的问题。
- 趋势顶部明确显示“截至”日期，查看历史范围时可“回到今天”。
- 曲线显示日期范围与标准晨重点数；非标准称重保留在日期记录中，并提示不计入曲线与均值。
- versionCode 升为 5；数据库结构与备份格式不变，同签名覆盖升级保留记录。

- Fixed historical weigh-in edits changing the trend end date and hiding newer records from the displayed range.
- Show an explicit chart end date and a return-to-today action when viewing a historical range.
- State the chart range and standard morning-weight sample count. Retain nonstandard weigh-ins in date records with a note that they are excluded from the chart and averages.
- Raised versionCode to 5. Database and backup formats are unchanged; same-signature updates preserve records.

## [1.2.0](https://github.com/lntanovo/foodlog-android/releases/tag/v1.2.0)

- 新增底部配额页；分别配置训练日、休息日、放纵日的体重系数或每日克数。
- 首页选择日型并显示碳水、蛋白质、脂肪的已记录量与参考值。
- 参考快照按日期保留；明确操作才更新指定日期，实际训练与饮食标签独立。
- 精简首页、餐食菜单和设置页，统一明暗配色。
- 新备份格式 v3，兼容 v1/v2；Room 仍为 v2。

- Added a dedicated Targets tab with training, rest and flex day targets in g/kg or grams.
- Show recorded carbohydrate, protein and fat alongside the selected day's reference values.
- Preserve historical target snapshots and separate dietary labels from actual exercise logs.
- Simplified the home screen, meal menus and settings with consistent light/dark colors.
- Backup format v3 reads v1/v2 files; the Room database remains at v2.

## [1.1.0](https://github.com/lntanovo/foodlog-android/releases/tag/v1.1.0)

- 常用餐、复制昨天：先生成草稿，确认已吃后才计入摄入。
- 保留未完成的小数输入；切换单位保持实际份量与估算标记。
- 修复保存并发、删除撤销和文件操作恢复问题。
- 增加压缩备份与显式 Room v1→v2 迁移；调整首页和趋势图。

- Added reusable meals and copying yesterday's posted meals into drafts.
- Preserve unfinished decimal input and physical quantities when changing units.
- Improved concurrent saves, deletion undo and recovery of file operations.
- Added compressed backups, explicit Room v1→v2 migration and UI refinements.

## [1.0.0](https://github.com/lntanovo/foodlog-android/releases/tag/v1.0.0)

- 首版离线 Android 应用，包含 11 种公共食品、自定义食品、不可变营养快照。
- 记餐、编辑、草稿、身体与训练记录、体重趋势和个人配额。
- 本地备份恢复、食品库分享与可选旧格式迁移。

- Initial offline Android app with 11 public foods, custom foods and immutable nutrition snapshots.
- Meal logging, editing, drafts, body/exercise records, weight trends and personal targets.
- Local backup/restore, food-library sharing and optional legacy-format import.

## 归档说明 / Archive notes

1.0.0、1.1.0、1.2.0 的 Git 历史从保留的源码快照恢复，导入提交创建于发布整理期间，不代表原开发时间。1.2.1 及后续版本使用正常开发提交。公开快照去除了个人路径和记录，迁移测试使用合成数据；各版本 Android 应用代码与其原始 APK 对应，APK 字节未改动。示例界面参数不构成预设配额。

Git history for 1.0.0, 1.1.0 and 1.2.0 was reconstructed from retained source archives. Import commits were created during publication, not backdated to development. Version 1.2.1 and later use regular development commits. Personal paths and records were removed and migration tests use synthetic data. Android application code corresponds to each original APK; APK bytes are unchanged. Screenshot values are examples, not preset targets.
