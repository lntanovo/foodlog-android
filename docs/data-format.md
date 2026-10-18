# 食记数据格式 v2

1.1.0（versionCode 3）新导出文件扩展名为 `.foodlog.gz`，内容为GZIP压缩的UTF-8 JSON；根字段为 `schemaVersion:2`、`appVersion:"1.1.0"`、`kind`、`exportedAt`。读取按文件内容识别压缩格式，不依赖扩展名；原v1纯JSON完整备份、食品库、旧迁移文件及种子继续兼容，解析校验后规范为v2。未来schema版本拒绝导入。

`meta`包含`datasetId`、`timezone`（业务时区）、`profile`、`revision`、`lastExport`。十进制业务量使用字符串，未知为null，业务日期严格ISO yyyy-MM-dd，时间为UTC Instant，实体ID为UUID。Room本地数据库版本为2；`MIGRATION_1_2`只增加`meal_templates`表，保留旧表及旧记录；v1、v2 Room schema均保留在源码中。

## 文件类型

| kind | 内容与操作 |
|---|---|
| FULL_BACKUP | meta及foods、versions、meals、templates、days、bodies、plans、targets、origins全部实体，完整替换恢复 |
| FOOD_LIBRARY | 仅所选食品和当前营养版本、份量；新datasetId、空个人资料，无餐食、常用餐、体重、目标、导入审计 |
| LEGACY_IMPORT | 电脑转换的食品、实际餐食、身体/训练、可选历史配额及来源键，预览后增量导入 |

`foods.activeVersionId`引用不可变`versions`。每个version包含原始基准量/单位、标签kcal和kJ、可选平均kcal、六项营养、来源/估算字段、干熟口径、份量定义、可选密度。不以舍入的每100g值替代原始标签。

`meals`保存DRAFT/POSTED、日期、餐次、条目、备注和可选replacesMealId。每个条目保存数量、单位、归一数量、数量完整性、完整FoodVersion快照及精确派生营养。DRAFT编辑已有餐食时用独立ID和replacesMealId，确认前原POSTED餐食保持有效。保存时以事务替换原ID、删除编辑草稿。

条目新增可空 `draftQuantityText`，仅DRAFT允许，最长256字符。尚未完成的输入如 `60.` 原样保存，此时标准quantity、normalizedAmount和派生营养为null；完整十进制输入可保留对应原文。提交POSTED前必须完成份量文本并清除此字段，明确未知份量仍使用quantity=null且draftQuantityText=null。未完成草稿从不计入已吃摄入。

单位切换先按原单位归一，再换算目标单位：60g荞麦面按每袋60g定义换为1袋。跨g/ml需要食品密度，缺少密度时拒绝换算。估算份量切成g/ml仍保留估算标记，用户重新输入实称值后可解除份量估算；营养标签的估算字段保留在不可变快照上。

`templates`为常用餐数组，v1文件缺省为空。每项含UUID `id`、`name`（去除首尾空白后1至100字符）、`type`、食品条目快照`items`、`createdAt`、`updatedAt`。常用餐条目不允许未完成文本，引用与营养计算使用餐食相同规则。应用常用餐创建全新meal/item UUID的DRAFT，replacesMealId为空；之后修改食品当前版本不改变模板快照。

复制昨天按所选业务日期的前一自然日查找POSTED餐食，忽略草稿和空餐；创建目标日期的新DRAFT与新meal/item UUID，备注添加“复制自 yyyy-MM-dd”并保留原备注，不带replacesMealId。重复复制在UI确认后可再次创建独立草稿，只有用户保存已吃才进入摄入。模板和复制不会自动修改目标日期的训练、身体记录或日目标。

`days`与`bodies`以date唯一；未填写训练为UNKNOWN。非标准体重独立标记，不计标准晨重均值。`plans`每次修改新建；`targets`保存date唯一的计划及日型快照，不随新计划改变。

`origins`以namespace+key唯一。固定namespace为foodlog-personal-legacy-v1，饮食键为日期/餐次/食品UUID/同名序号，体重为body/日期，训练为day/日期，目标为plan/生效日。文件哈希仅用于核对源文件，不作为去重键。fingerprint基于来源内容，物理行号变化不会改变来源键。实际改动会列为日期冲突；用户逐日选择跳过或替换。

## 校验与恢复

导出压缩前JSON与读取解压后JSON统一限制为256MiB。导出先以计数流校验大小，再在IO线程流式编码并GZIP压缩；读取以有界流解压和解析，避免30MB单边限制以及主线程构造完整JSON字符串。兼容的String parse/export接口继续保留，受相同上限约束。

完整导入校验先于任何业务写入，检查版本、ID/日期重复、单位、数值、日期、引用和餐食计算一致性，拒绝未来版本、损坏快照、非法外键和负数/NaN/Infinity。真实零值合法，基准和密度必须正数。普通更新按实体差异写入；自动保存草稿只校验并写当前餐食及引用版本，界面快照按meta.revision缓存更新，避免重写和重新解码全部历史。所有相关保存使用单一Room事务。

完整恢复在应用私有files/recovery/保存恢复前 `.json.gz` 文件，再一次事务清空各业务实体并写入已验证的备份，包括常用餐。不是破坏性schema迁移，也不是智能合并。重复恢复不追加记录。可在设置取出最近的恢复前备份（对外为`.foodlog.gz`），重新通过完整恢复入口使用。

文件操作用应用私有目录保存传输副本和任务日志，恢复文件选择、导入复制和导出写入状态。导入若在应用提交期间中断，不擅自再次覆盖，恢复后要求用户重新核对并确认。删除餐食返回含原餐食、关联编辑草稿和导入来源的撤销包，撤销在单一事务内整体恢复；未持久化草稿可安全丢弃。

食品库导入时已有同ID食品保留本机当前版本，已有不可变版本ID内容冲突则拒绝整次导入。旧数据冲突替换只替换明确选择日期的餐食及文件提供的身体/训练值，日目标仍保留已有快照，需用户在当天明确重算。

不备份图片，因为当前未创建照片附件。DataStore外观设置不是业务数据，不含在完整备份中；业务个人资料、全部食品版本、草稿和常用餐均包含。
