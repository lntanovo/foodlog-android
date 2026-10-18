# 安装、更新与签名

当前交付 **debug APK**，applicationId `com.foodlog.app`，versionCode 2，versionName 1.0.0，minSdk26。APK SHA256见deliverables/SHA256SUMS.txt。公开源码与安装包见 https://github.com/lntanovo/foodlog-android 与 GitHub Releases。构建阶段的versionCode1用于覆盖升级测试，未作为交付版本分发。

将APK传入安卓手机，点开安装，按手机提示允许该文件来源。应用不申请互联网或外部存储读写权限；导入导出使用系统文件选择器。分享同一APK不会分享个人数据。`your-private-import.json`和以后导出的完整备份只应本人保存。

调试签名由本机Android默认debug.keystore提供，不在源码或交付包中。debug包可供试用，但正式长期分发应使用用户保管的release密钥。**签名身份不同的包不能直接覆盖升级**；切换签名前先完整备份，再卸载旧包、安装新签名包和恢复数据。

正式发布准备：

1. 用户在本机生成并妥善备份专用签名keystore，离线保存密码。不要提交私钥、密码或签名敏感路径。
2. 在本地未跟踪的Gradle配置或环境变量读取签名配置，给release buildType绑定signingConfig。
3. 每次发布递增versionCode，保留applicationId与原签名。建立新schema时添加显式Room Migration并测试旧库升级，禁止fallbackToDestructiveMigration。
4. 构建 `gradlew assembleRelease`，用apksigner verify及证书指纹核对，生成SHA256，并在保留真实记录的测试设备上进行覆盖安装。
5. 测试断网、杀进程、重启、文件选择器备份/恢复；保留原APK和安装升级测试记录。

源码zip排除了工具链、Gradle缓存、build目录、签名材料及个人迁移文件。它是公共源码快照，默认公共种子11种。若要从干净源码运行个人迁移回归，需先在原项目运行转换器生成测试导入文件；不将个人资料补进主应用assets。
