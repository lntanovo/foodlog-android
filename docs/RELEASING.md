# 发布新版本 / Publishing a new version

## 中文

1. 修改 `app/build.gradle.kts` 的 `versionName` 与递增的 `versionCode`，更新更新记录。涉及备份/数据库格式时提供兼容处理与迁移测试。
2. 运行 `gradlew testDebugUnitTest lintDebug assembleDebug`。在独立测试设备验证界面和覆盖安装；仪器测试可能重置或卸载测试应用。
3. 沿用原 applicationId 和签名，确认新 APK 可覆盖已安装版本。仓库不包含签名私钥；GitHub 临时运行器自动生成的 debug 密钥不能替代原发布密钥。
4. 提交源码到 `main`，创建带说明的 `vX.Y.Z` 标签并推送。例如：

   ```bash
   git add <本次修改的源码文件>
   git commit -m "Release X.Y.Z"
   git tag -a vX.Y.Z -m "FoodLog X.Y.Z"
   git push origin main --follow-tags
   ```

5. 在同名 GitHub Release 上传已验证的 `foodlog-X.Y.Z-debug.apk`、该标签的源码 ZIP 和 `SHA256SUMS.txt`，填写中英双语更新说明。保留旧标签和旧 Release，不用新文件覆盖旧版本。

个人完整备份、旧记录、Excel、数据库、签名材料、环境配置和工具链不进入 Git 或 Release。当前未添加项目许可证；第三方依赖仍受各自许可证约束。

## English

1. Update `versionName`, increment `versionCode`, and update the changelog. Provide compatibility handling and migration tests for data-format changes.
2. Run `gradlew testDebugUnitTest lintDebug assembleDebug`. Verify the UI and upgrade on an isolated device; instrumentation tests may reset or uninstall the test app.
3. Keep the application ID and signing identity. Signing keys are excluded from the repository. A temporary CI runner's automatically generated debug key cannot replace the original distribution key.
4. Commit the source to `main`, create an annotated `vX.Y.Z` tag, and push the branch and tags using the example above.
5. Create the corresponding GitHub Release with the verified APK, a source ZIP from that tag, SHA-256 checksums and bilingual notes. Keep previous tags and releases intact.

Never publish personal backups, legacy records, workbooks, databases, signing material, local environment settings or SDK caches. No project license has been granted yet; third-party dependencies retain their own licenses.
