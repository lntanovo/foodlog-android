# 更新记录 / Changelog

每个版本都有 Git 标签、对应源码和独立 APK 下载。下列版本为本机开发测试签名构建。

Every version has a Git tag, source snapshot and downloadable APK. These are development builds signed with the same local debug key.

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

Git 历史从保留的各版本源码快照恢复，导入提交创建于发布整理期间，不代表原开发时间。公开快照去除了个人路径和记录，迁移测试使用合成数据；各版本 Android 应用代码与其原始 APK 对应，APK 字节未改动。示例界面参数不构成预设配额。

Git history was reconstructed from retained source archives. Import commits were created during publication, not backdated to development. Personal paths and records were removed and migration tests use synthetic data. Android application code corresponds to each original APK; APK bytes are unchanged. Screenshot values are examples, not preset targets.
