# 账号与云备份 / Accounts and cloud backups

## 中文

食记 1.3.0 的日常记录仍保存在手机本地，无需登录。云备份是可选的手动功能：同一账号可在另一台设备取回一个完整版本，再核对并确认替换。它不会自动同步、合并或解决两台设备的编辑冲突。

### 使用

1. 在“设置 → 账号与云备份”输入已开通的用户名和密码。账号由环境所有者在腾讯云 CloudBase 用户管理中预置；应用不提供自行注册或微信登录。
2. 在保存着完整记录的设备点击“备份本机记录到此账号”，等待成功提示。每次生成独立版本，失败可以重试，之前的成功版本继续保留。
3. 新设备登录同一账号，点击“查看云端备份”，选择日期与来源设备，再点“预览并恢复”。
4. 对照本机和云端的食品、餐食、身体、目标、常用餐与来源数量。确认替换后才恢复；不会自动合并。期间本机记录有变化时需重新核对。
5. 恢复前先写入并校验本机保护备份，成功后以数据库事务替换。可在“本地备份与恢复”取出最近的恢复前副本。保护副本与本机记录都会随卸载或清除应用数据而删除。

登录、退出、密码错误、断网与云端没有备份都不会清空本机记录。换机前需要先在旧设备备份；登录本身不会把尚未上传的记录变到云端。公共 APK 不附带使用者的账号或记录。

### 到期与独立保管

费用、额度和有效期以自己的腾讯云控制台为准，不承诺永久免费。云服务到期后可能停服，保留期结束后云端副本可能被清除；手机中的本地记录仍然保留。当前资源点套餐文档说明到期未续费后有 15 天隔离期，之后销毁；具体套餐请核对控制台与[官方说明](https://cloud.tencent.com/document/product/876/136006)。不要等到停服后才尝试下载。

定期使用“设置 → 本地备份与恢复 → 导出完整备份”，将 `.foodlog.gz` 文件另存电脑或其他自选位置。这个文件无需腾讯云账号即可恢复，也能用于日后更换云环境。卸载前尤其要导出，并确认文件可预览。

### 隐私与实现

- 手机使用用户自己的访问令牌，通过 HTTPS 访问国内 CloudBase；APK 没有管理员密钥。不收集分析统计。
- 登录令牌以 Android Keystore 加密并原子保存，不保存登录密码；登录状态不进入业务备份。
- 私有存储和清单均按服务端认证身份隔离；用户只可新增、读取自己的版本，不能覆盖或删除已有版本。
- 完整备份包含个人记录。它通过 HTTPS 传输并受云端权限保护，**不是端到端加密**；环境管理员具备管理数据的权限。
- SHA-256、压缩大小、归属、数据格式、数量与日期摘要均在恢复前检查。压缩文件上限 64 MiB，解压上限 256 MiB。
- 中断任务只在用户操作后重试或回到预览，应用重启不会悄悄重放替换操作。

### 自行部署

本仓库默认环境只供所有者预置的账号使用。下载源码不会获得云端管理权限；自行使用云功能时，请创建自己的国内 CloudBase PG 环境，在用户管理中启用用户名密码并预置账号，然后：

1. 检查现有 schema、权限与费用，再通过 CloudBase 版本迁移工具应用 `cloudbase/migrations/20261019051410_foodlog_cloud_backups.sql`。不要在共享环境中重复应用或修改其他应用的表。
2. 在 `CloudConfig` 中设置自己的环境网关。该环境 ID 是客户端配置，不是管理密钥；不要向手机加入 API Key、SecretId 或 SecretKey。
3. 用两个合成账号验证清单、私有文件和签名下载均不能越权；再做备份、预览、恢复和旧版覆盖升级测试。
4. 新版保持自己的 applicationId 与签名，递增 versionCode。不要为迁移而清除应用数据。

## English

FoodLog 1.3.0 keeps everyday records locally and works without signing in. Optional manual cloud backups let another device retrieve a complete version under the same account. A restore requires review and confirmation; there is no automatic synchronization or merge.

### Use

1. Open **Settings → Account and cloud backup** and sign in with a provisioned username/password. The environment owner creates accounts in CloudBase user management. Self-registration and WeChat login are not included.
2. On the device holding your records, choose **Back up this device** and wait for confirmation. Each backup is a separate version; failed transfers can be retried without replacing earlier versions.
3. On the new device, sign in to the same account, open the cloud list and select **Preview and restore**.
4. Compare local and incoming counts and dates. Confirm only when you intend to replace all local business data. Changes made since the preview require another review.
5. A verified local protection copy is written before the transactional restore. Export the latest pre-restore copy from local backup settings if needed. Uninstalling or clearing app data also deletes local protection copies.

Signing in/out, an incorrect password, offline operation or an empty cloud account never clears local records. Back up the old device before switching: signing in alone does not upload anything. The public APK contains no personal account or records.

### Expiry and independent copies

Check your CloudBase console for fees, quota and expiry; the service is not promised to remain free. Expiry can stop cloud access and eventually delete cloud copies. Records already on your phone remain local. Current resource-point package documentation describes a 15-day isolation period before destruction; check your actual plan and the [official policy](https://cloud.tencent.com/document/product/876/136006).

Regularly export a complete `.foodlog.gz` backup through local backup settings and keep another copy on your computer. Restoring this file does not require a CloudBase account. Export and verify it before uninstalling, before cloud expiry or before changing environments.

### Privacy and deployment

User tokens authorize HTTPS calls to domestic CloudBase. Tokens are encrypted with Android Keystore and saved atomically; passwords and tokens are excluded from business backups. The APK contains no administrator credentials or analytics. Server-side policies isolate each user's metadata and private files. Versions are append-only for app users.

Backups contain personal records and are **not end-to-end encrypted**: the cloud environment administrator has management access. Restore checks include ownership, SHA-256, size, format, counts and dates. Limits are 64 MiB compressed and 256 MiB decompressed. Interrupted restores never replay automatically.

The default environment accepts only accounts provisioned by its owner. For your own deployment, create a CloudBase PG environment, check its existing schema/costs, apply the versioned SQL migration in `cloudbase/migrations/`, enable username/password authentication and provision accounts. Set your gateway in `CloudConfig`. Never put administrative API keys or Tencent Cloud secrets in an APK. Test two-account isolation and a real backup/restore before use. Keep your application ID and signing key stable across updates.
