# 验收记录

执行日期：2026-10-18（Asia/Shanghai）。Windows、Temurin17.0.20.1+1、SDK35、Gradle Wrapper8.11.1。模拟设备Pixel6 / Android15 API35 x86_64，WHPX加速，Wi-Fi与移动数据均关闭。

## 实际结果

| 检查 | 结果 |
|---|---|
| Wrapper assembleDebug | 成功，生成可签名验证的APK，versionCode2 |
| 纯Kotlin业务回归 | 24项通过，0失败/跳过 |
| Robolectric + 实际Room数据库 | 7项通过，0失败/跳过 |
| Android模拟器仪器/UI | 5项通过，0失败/跳过 |
| Python只读迁移回归 | 4项通过 |
| lintDebug | 成功，无Error；存在固定依赖有新版本的提示 |
| 断网运行 | 模拟器关闭Wi-Fi和数据后执行UI流程通过；主APK无INTERNET权限 |
| 强制停止 → 重启 | 实际SQLite业务记录逐表比较一致 |
| 同签名versionCode1 → 2覆盖安装 | adb install -r成功，全部业务记录逐表一致 |
| APK公共种子与隐私检查 | assets仅foods_seed_v1.json，11种食品，无私人餐食、体重和目标 |

覆盖安装详情见 `deliverables/upgrade-check.json`。测试先在模拟器创建独立测试餐食，再强制停止、重启并覆盖升级；不是在用户真实手机上操作。保留安装前后版本号、各表行数与业务内容SHA256。

## 核心回归覆盖

营养计算覆盖荞麦面60g/2袋、牛奶1盒250g、脆麦片18g、两款蛋白粉30g、鸡蛋50g、番茄150g、混合餐累计、自定义标签300kcal优先于4/4/9、无能量时294kcal宏量估算、g/ml拒绝无依据转换、密度换算、未知纤维/真实零脂肪、未知份量、先累计后舍入和非法输入。

体重覆盖14个连续自然日、两个独立7日窗口、缺测及非标准称重排除。目标覆盖个人系数算术、训练未填写、独立参考体重和历史日目标快照保持。

Room覆盖首次安装无私人记录、种子幂等、草稿不计入、编辑替换同ID、零数量过滤、食品新版本不改旧餐快照、删除撤销、同日体重更新、完整备份往返及重复恢复、非法版本拒绝、食品库不泄露个人资料、实际旧文件导入去重/冲突替换，以及关闭磁盘数据库后重新打开仍保留餐食。

模拟器流程覆盖四页离线导航、自定义食品创建及新版本、选择食品→填写60g→旋转→保存→修改为120g、历史日期旋转和跨页面保持、真实旧数据导入预览及第二次导入跳过、完整备份导出→移除测试餐→预览恢复→确认替换。

文件流程测试由Espresso回传SAF选择结果到专用测试ContentProvider，执行真实JSON文件读写、ActivityResult回调、预览与Room事务。测试提供器和个人迁移夹具仅编入 **androidTest测试APK**，不会进入交付主APK。不同厂商系统文件选择器自身界面尚未逐一验证。

## 迁移核对 / Migration checks

公开迁移测试使用合成数据，个人输入与核对报告不随源码分发。
Public migration tests use synthetic data. Personal input and audit reports are excluded.

## 复跑

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
python tools/test_migrate.py
```

测试报告：app/build/reports/tests/testDebugUnitTest/、app/build/reports/androidTests/connected/debug/、app/build/reports/lint-results-debug.html。个人迁移测试依赖单独的deliverables/your-private-import.json；公开源码压缩包不含该私有文件，缺少时相应个人数据集成测试会跳过，普通业务测试仍可执行。

## 尚未执行/范围边界

- 用户实体手机、Android8至14及其他厂商系统的运行/文件选择器兼容测试。
- 首次发布Room v1，目前没有更早线上schema可迁移；未来升级必须新增显式Migration与保留数据测试，禁止清库回退。
- 正式release签名及其升级链路；本次仅验证debug同签名升级。
- P1/P2未实现：模板、复制昨天、整餐估算、CSV、包装照片、识别、账号同步等。

早期构建/测试失败均在开发过程中修复；此处结论以最终源码的成功构建和测试结果为准，不将编译成功等同于实体手机验证。
