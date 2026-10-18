# 食记数据格式 v1

所有文件UTF-8 JSON；`schemaVersion:1`、`appVersion`、`kind`、`exportedAt`。`meta`包含`datasetId`、`timezone`（业务时区）、`profile`、`revision`、`lastExport`。十进制业务量使用字符串，未知为null，业务日期严格ISO yyyy-MM-dd，时间为UTC Instant，实体ID为UUID。

## 文件类型

| kind | 内容与操作 |
|---|---|
| FULL_BACKUP | meta及foods、versions、meals、days、bodies、plans、targets、origins全部实体，完整替换恢复 |
| FOOD_LIBRARY | 仅所选食品和当前营养版本、份量；新datasetId、空个人资料，无餐食、体重、目标、导入审计 |
| LEGACY_IMPORT | 电脑转换的食品、实际餐食、身体/训练、可选历史配额及来源键，预览后增量导入 |

`foods.activeVersionId`引用不可变`versions`。每个version包含原始基准量/单位、标签kcal和kJ、可选平均kcal、六项营养、来源/估算字段、干熟口径、份量定义、可选密度。不以舍入的每100g值替代原始标签。

`meals`保存DRAFT/POSTED、日期、餐次、条目、备注和可选replacesMealId。每个条目保存数量、单位、归一数量、数量完整性、完整FoodVersion快照及精确派生营养。DRAFT编辑已有餐食时用独立ID和replacesMealId，确认前原POSTED餐食保持有效。保存时以事务替换原ID、删除编辑草稿。

`days`与`bodies`以date唯一；未填写训练为UNKNOWN。非标准体重独立标记，不计标准晨重均值。`plans`每次修改新建；`targets`保存date唯一的计划及日型快照，不随新计划改变。

`origins`以namespace+key唯一。固定namespace为foodlog-personal-legacy-v1，饮食键为日期/餐次/食品UUID/同名序号，体重为body/日期，训练为day/日期，目标为plan/生效日。文件哈希仅用于核对源文件，不作为去重键。fingerprint基于来源内容，物理行号变化不会改变来源键。实际改动会列为日期冲突；用户逐日选择跳过或替换。

## 校验与恢复

解析有30MB限制。完整校验先于任何写入，检查版本、ID/日期重复、单位、数值、日期、引用和餐食计算一致性，拒绝未来版本、损坏快照、非法外键和负数/NaN/Infinity。真实零值合法，基准和密度必须正数。所有相关保存使用单一Room事务。

完整恢复在应用私有files/recovery/保存恢复前JSON，再一次事务清空各实体并写入已验证的备份。不是破坏性schema迁移，也不是智能合并。重复恢复不追加记录。可在设置取出最近的恢复前备份，重新通过完整恢复入口使用。

食品库导入时已有同ID食品保留本机当前版本，已有不可变版本ID内容冲突则拒绝整次导入。旧数据冲突替换只替换明确选择日期的餐食及文件提供的身体/训练值，日目标仍保留已有快照，需用户在当天明确重算。

不备份图片，因为P0未创建照片附件。DataStore外观设置不是业务数据，不含在完整备份中；业务个人资料、全部食品版本和草稿等均包含。
