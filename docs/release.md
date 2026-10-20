# 安装、更新与签名

公开仓库：[lntanovo/foodlog-android](https://github.com/lntanovo/foodlog-android)。当前交付 **debug APK**：[foodlog-1.4.0-debug.apk](https://github.com/lntanovo/foodlog-android/releases/download/v1.4.0/foodlog-1.4.0-debug.apk)，applicationId `com.foodlog.app`，versionCode **7**，versionName **1.4.0**，minSdk26。各版本安装包与校验文件见 [GitHub Releases](https://github.com/lntanovo/foodlog-android/releases)。

1.4.0 延续 1.0.0 至 1.3.0 的同一签名。请直接覆盖安装，不要先卸载或清除应用数据。Room 仍为 v2，完整备份仍为 schema v3。已用合成数据验证 1.3.0 → 1.4.0 覆盖升级与重启后逐表内容保留，详见[验收记录](testing.md)。

1.4.0 登录后自动备份记录变更，新设备空白安装后登录同一账号自动恢复；两边都有不同记录时先核对。首次从 1.3 升级若提示差异，可选择“保留本机并备份…”把本机记录作为新版本保存，此后自动处理。日常本机记账仍可离线完成，退出账号不清除本机记录。换机前查看最近成功备份时间。未实现自动应用更新或冲突自动合并；云服务可能到期，务必另存独立完整备份，详见[账号与云备份](cloud-backup.md)。

**English:** Install 1.4.0 over the existing app without uninstalling or clearing data. The package, signing certificate, Room v2 and backup schema v3 remain stable. Signed-in edits back up automatically; a pristine new device restores after login. Existing divergent records require review. An upgrade from 1.3 may need one initial keep-local/cloud choice. Check successful cloud confirmation before switching devices and keep an independent local export.

1.2.1沿用本机1.0.0（versionCode 2）、1.1.0（versionCode 3）、1.2.0（versionCode 4）的debug签名，可直接覆盖升级；无需卸载旧版。Room结构仍为v2，新增业务字段保存在原JSON列；v1数据库仍通过显式 `MIGRATION_1_2` 升到v2，只新增常用餐表，保留食品、历史版本、餐食、草稿、身体记录、目标与来源记录。备份格式保持schemaVersion 3，v1/v2 JSON或GZIP备份及个人迁移文件继续支持。v3备份应通过新版应用恢复。

1.2.1修复趋势图日期截断：编辑或补记历史体重只保存所编辑日期的数据，不再改变图表截止日期。顶部明确显示“截至”日期，查看历史范围时提供“回到今天”；曲线注明日期范围与标准晨重点数，非标准称重保留在下方日期记录，并提示不计入晨重曲线与均值。

已用合成数据验证从1.2.0（versionCode 4）覆盖升级至1.2.1（versionCode 5），4条身体记录及其他全部业务表完整保留。构建、65项单元／数据库测试与Lint已通过；本次界面回归结果以 `docs/testing.md` 为准。

1.2.0增加底部“配额”入口：按参考体重和用户自己选择的碳蛋脂系数生成训练日、休息日、放纵日参考，也支持直接填写克数。放纵日可仅标记、不设配额。首页简化为三种日型、已记录/参考碳蛋脂和主要录入入口，次要营养与餐食操作按需展开。饮食标签独立于实际训练；参考体重独立于晨重；不自动采用图片中的经验系数。

新建计划保留已有日目标快照，切换标签只更新所选日期；重复选择同标签不重算。“配额 → 用于所选日期”确认后才按该日期生效的计划明确更新。未配置放纵日配额时不沿用训练日数值。旧记录和旧目标日型继续兼容，完整备份包含三类配额与日期标签，食品库不夹带这些个人设置。此前草稿、常用餐、复制昨天及备份恢复功能保留。详见README与数据格式说明。

将APK传入安卓手机，点开安装，按手机提示允许该文件来源。1.3.0 使用互联网权限提供可选云备份，不申请外部存储读写权限；导入导出使用系统文件选择器。分享同一APK不会分享个人数据或账号。用户自行转换的历史导入文件和导出的完整备份可能包含私人记录，应自行妥善保管，不随APK分享。

调试签名由本机Android默认debug.keystore提供，不在源码或交付包中。debug包可供试用，但正式长期分发应使用用户保管的release密钥。**签名身份不同的包不能直接覆盖升级**；切换签名前先完整备份，再卸载旧包、安装新签名包和恢复数据。

覆盖安装前仍建议在设置导出完整备份。设备验证范围为Android15模拟器，实体手机和不同厂商系统尚未验证；具体检查结果以 `docs/testing.md` 为准。

正式发布准备：

1. 用户在本机生成并妥善备份专用签名keystore，离线保存密码。不要提交私钥、密码或签名敏感路径。
2. 在本地未跟踪的Gradle配置或环境变量读取签名配置，给release buildType绑定signingConfig。
3. 每次发布递增versionCode，保留applicationId与原签名。建立新schema时添加显式Room Migration并测试旧库升级，禁止fallbackToDestructiveMigration。
4. 构建 `gradlew assembleRelease`，用apksigner verify及证书指纹核对，生成SHA256，并在保留真实记录的测试设备上进行覆盖安装。
5. 测试断网、杀进程、重启、文件选择器备份/恢复；保留原APK和安装升级测试记录。

公开源码与源码zip排除了工具链、Gradle缓存、build目录、签名材料及个人迁移文件。应用默认仅含11种公共食品种子。干净源码可直接运行普通业务测试；依赖可选私有迁移夹具的集成测试在缺少文件时跳过。若要核对自己的旧数据，请在本地使用转换工具准备导入文件，不将个人资料加入主应用assets或公开提交。
