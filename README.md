# 食记 · FoodLog Android 1.0.0

[最新版 / Latest](https://github.com/lntanovo/foodlog-android) · [本版下载 / Download this version](https://github.com/lntanovo/foodlog-android/releases/tag/v1.0.0)

## 中文

首个离线饮食记录版本：食品库、记餐、身体与趋势、本地备份。 原生 Kotlin / Jetpack Compose / Room 应用，支持 Android 8.0+，无账号、云服务或网络权限。首次安装只有11种公共食品，没有个人记录。

在上方 Release 下载 `foodlog-1.0.0-debug.apk`，在安卓手机上打开安装。电脑体验可在 Android Studio 创建模拟器，再将 APK 拖入模拟器安装。食品页添加自己的标签，今日页选择食品和份量，保存已吃后计入摄入；在设置中定期导出完整备份。

构建使用 JDK17、SDK35、Build Tools35.0.0；本地设置 `JAVA_HOME` 和 `local.properties` 的 `sdk.dir`。Windows运行 `.\gradlew.bat assembleDebug`；测试运行 `.\gradlew.bat testDebugUnitTest lintDebug` 和 `python tools/test_migrate.py`。仪器测试应使用独立模拟器。仓库不包含SDK、预配置模拟器、私人数据或签名私钥。

此历史快照从保留归档导入，提交时间为整理时间。个人路径已移除；Python迁移工具与测试已改为公开独立输入和合成数据，Android应用代码保持与原始APK一致。APK使用与其他归档版本相同的测试签名；更新前请导出备份。当前未授予项目许可证。

## English

Initial offline diary: foods, meals, body records, trends and local backups. A native Kotlin / Jetpack Compose / Room app for Android 8.0+, with no account, cloud service or internet permission. A fresh install includes 11 public foods and no personal records.

Download `foodlog-1.0.0-debug.apk` from the release above and open it on Android. On a computer, create an Android Studio emulator and drag the APK into it. Add labels in Foods, enter quantities on Today and save meals as eaten to count intake. Export full backups regularly in Settings. The app UI is in Chinese.

Build with JDK17, SDK35 and Build Tools35.0.0. Configure `JAVA_HOME` and local `sdk.dir`, then run `./gradlew assembleDebug`; verify with `./gradlew testDebugUnitTest lintDebug` and `python tools/test_migrate.py`. Use an isolated emulator for instrumentation tests. SDKs, AVDs, personal data and signing keys are excluded.

This archived snapshot was imported during publication, not backdated. Personal paths were removed; Python migration helpers use explicit inputs and synthetic tests. Android app code still matches the original APK. Archived APKs share the same test signing identity. Export a backup before upgrading. No project license has been granted yet.
