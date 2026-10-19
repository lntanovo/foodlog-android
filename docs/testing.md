# 1.2.1 验收记录

修复趋势页编辑历史体重时意外更改图表截止日期的问题。执行日期：2026-10-19（Asia/Shanghai）；versionCode 5，Room v2 与备份格式 v3 保持兼容。

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 成功；65项单元/数据库测试通过，0失败/跳过。
- Android15独立测试模拟器定向验证3组趋势流程：四天标准晨重在编辑最早一天、保存、取消和Activity重建后仍完整显示；主动筛选截止日期后“回到今天”恢复4点；非标准称重保留原记录、显示排除说明且不进入曲线，全部为非标准时显示空态。
- 1.2.0（code4）→1.2.1（code5）同签名覆盖安装成功。强制停止/重启及升级前后逐表比较，4条合成身体记录、餐食草稿、食品、日期、计划和目标数据全部一致；Room版本仍为2。
- 本次未更改数据库结构或体重统计规则；不补造缺测体重，图表依旧只包含标准晨重。实体手机未逐一验证。

Version 1.2.1 passed 65 unit/database tests, Lint/build checks and three targeted emulator trend flows. Editing an earlier record preserves the chart cutoff through save, cancel and recreation. Explicit filtering, returning to today and excluding nonstandard readings were checked. A same-signature upgrade from 1.2.0 preserved all synthetic business rows, including four body records. Room remains v2 and the backup format remains v3.

已检查[连续四天合成晨重的曲线截图](screenshots/1.2.1/trend-four-days-light.png)，四个称重点与连线均可见；截图数值仅为测试示例，不是用户记录或内置数据。

# 1.2.0 历史验收记录

执行日期：2026-10-19（Asia/Shanghai）。交付版本1.2.0 / versionCode4。Windows、Temurin17.0.20.1+1、SDK35、Gradle Wrapper8.11.1。模拟设备独立FoodLogQuotaTest（Pixel6 / Android15 API35 x86_64），WHPX加速，Wi-Fi与移动数据均关闭。以下均使用隔离的模拟器测试数据，未在用户实体手机上执行。

公开源码见 [GitHub 仓库](https://github.com/lntanovo/foodlog-android)，对应安装包见 [v1.2.0 Release](https://github.com/lntanovo/foodlog-android/releases/tag/v1.2.0)。公共截图使用合成测试计划，私有历史数据夹具不随仓库或APK分发。

## 实际结果

| 检查 | 结果 |
|---|---|
| Wrapper assembleDebug | 成功，生成可签名验证的APK，versionCode4 |
| 纯Kotlin业务回归 | 26项通过，0失败/跳过 |
| Robolectric + 实际Room数据库/ViewModel | 39项通过，0失败/跳过 |
| Android模拟器仪器/UI | 19项通过，0失败/跳过 |
| Python只读迁移回归 | 4项通过 |
| lintDebug | 成功，无Error；存在固定依赖有新版本的提示 |
| 断网运行 | 模拟器关闭Wi-Fi和数据后执行UI流程通过；主APK无INTERNET权限 |
| 强制停止 → 重启 | 实际SQLite业务记录逐表比较一致 |
| 同签名versionCode3 → 4覆盖安装 | adb install -r成功，Room保持v2，全部原业务记录逐表一致 |
| APK公共种子与隐私检查 | assets仅foods_seed_v1.json，11种食品，无私人餐食、体重和目标 |

覆盖安装时先在旧版模拟器写入测试记录，再强制停止、重启并覆盖升级，对比安装前后版本号、Room版本、各表行数与业务内容SHA256。Robolectric另直接从已提交的v1 schema建库，验证所有旧表及其JSON行在迁移后保留。

## 核心回归覆盖

营养计算使用食品标签与合成份量用例，覆盖荞麦面60g/2袋、牛奶1盒250g、脆麦片18g、两款蛋白粉30g、鸡蛋50g、番茄150g、混合餐累计、自定义标签300kcal优先于4/4/9、无能量时294kcal宏量估算、g/ml拒绝无依据转换、密度换算、未知纤维/真实零脂肪、未知份量、先累计后舍入和非法输入。这些数值是算术回归用例，不表示用户的实际摄入。

体重覆盖14个连续自然日、两个独立7日窗口、缺测及非标准称重排除。目标覆盖个人系数算术、训练未填写、独立参考体重和历史日目标快照保持。

Room覆盖首次安装无私人记录、种子幂等、草稿不计入、编辑替换同ID、零数量过滤、食品新版本不改旧餐快照、删除撤销、同日体重更新、完整备份往返及重复恢复、非法版本拒绝、食品库不泄露个人资料、实际旧文件导入去重/冲突替换，以及关闭磁盘数据库后重新打开仍保留餐食。新增覆盖原始60.草稿、完整删除撤销包、目标并发更新、局部自动保存写入、模板及复制的新ID和营养快照、只复制前一自然日POSTED记录、v1 JSON/GZIP兼容、超过旧30MB限制的备份恢复，以及解压流超过256MiB时及时拒绝。

ViewModel回归覆盖：数据库事务挂起期间连续保存/返回/丢弃/改量/改单位不会把POSTED降回草稿；估算个数换成克数仍保留估算，实际重新输入克数后才解除；仅凭SavedStateHandle中的草稿ID从Room重建原始输入。文件任务重建后可继续导出或恢复预览，备份内容不进入SavedStateHandle，错误文件类型不会写入业务数据。

模拟器流程覆盖五页离线导航、自定义食品创建及新版本、选择食品→填写60g→旋转→保存→修改为120g、历史日期旋转和跨页面保持、真实旧数据导入预览及第二次导入跳过、压缩完整备份导出→移除测试餐→预览恢复→确认替换。新增食品/份量定义、目标双模式、身体与个人资料表单旋转保留；60.草稿旋转及返回重开；60g↔1袋等量转换；立即丢弃空草稿；常用餐保存/使用/删除、提交时旋转；复制昨天只生成草稿、日期预览、重复确认及旋转后不重复提交。

文件流程测试由Espresso回传SAF选择结果到专用测试ContentProvider，执行真实压缩备份/JSON文件读写、ActivityResult回调、预览与Room事务。测试提供器及开发时可选使用的迁移夹具仅用于 **androidTest测试APK**，不会进入交付主APK；私有夹具也不进入公开源码。不同厂商系统文件选择器自身界面尚未逐一验证。

## 1.2.0 配额与日型回归

新增15项数据库/计算回归：三组系数与直接克数换算、能量4/4/9、日期标签独立于实际训练、缺少放纵日参考值时清除旧目标、同标签幂等、明确重算只影响单日、未来计划生效日期、并发完整性更新、旧身体表单保留标签、v1/v2备份升级及v3压缩备份往返。

新增6项界面流程：三类系数实时预览和保存；模式与标签页小数输入旋转保留；明确应用单日且不改身体/训练；首页三标签切换与旋转；未知摄入不显示0及未设放纵日不借用训练配额；旧训练目标与新回退标签不匹配时提示并允许显式更新。

自动仪器测试使用独立FoodLogQuotaTest模拟器，日常双击启动器仍使用FoodLogTest，两者各自存储。此次测试没有清空日常模拟器中的记录。

## 迁移核对 / Migration checks

公开迁移测试使用合成数据，个人输入与核对报告不随源码分发。
Public migration tests use synthetic data. Personal input and audit reports are excluded.

## 复跑

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat connectedDebugAndroidTest
python tools/test_migrate.py
```

测试报告：app/build/reports/tests/testDebugUnitTest/、app/build/reports/androidTests/connected/debug/、app/build/reports/lint-results-debug.html。部分历史数据集成测试依赖本地可选的私有迁移夹具；公开仓库不含该文件，缺少时相应用例会跳过，普通业务测试与合成数据回归仍可执行。

## 尚未执行/范围边界

- 用户实体手机、Android8至14及其他厂商系统的运行/文件选择器兼容测试。
- 已验证Room v1→v2；未来继续升级必须新增显式Migration与保留数据测试，禁止清库回退。
- 正式release签名及其升级链路；本次仅验证debug同签名升级。
- 当前已完成常用餐、复制昨天、三日型体重系数配额、首页参考值和UI整理。尚未实现：整餐估算、CSV、包装照片、识别、账号同步等。

浅色/深色首页截图已实际检查，位于 `docs/screenshots/1.2.0/`，使用65kg合成测试计划展示参考值，不是内置配额，不含用户记录；深色模式的系统状态栏与导航栏图标随应用外观同步切换。已检查日型标签、参考值、五项底部导航、配额编辑和130%系统字号。业务回归后仅统一了弹窗及边框配色，随后重新完成构建、Lint和截图检查。

早期构建/测试失败均在开发过程中修复；此处结论以成功构建和回归结果为准，不将编译成功等同于实体手机验证。
