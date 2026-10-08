# Eta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 构建与发布

以下情况会在同一次工作流中生成 Debug APK 和经过签名验证的 Release APK，
并作为两个可直接下载的 Actions Artifact 保存 14 天：

- 向 `main` 推送提交
- 推送 `v*` 标签
- 在 GitHub 的 `Actions > Eta Build` 中手动运行

工作流不会创建、修改或发布 GitHub Release。

Debug APK 会先独立构建并上传；签名配置缺失或 Release 构建失败时，
已生成的 Debug Artifact 仍可下载，工作流会继续明确报告 Release 失败。

`Eta-vivo` 仓库使用独立的持久发布证书，四项 Secrets 已配置。
本机备份位于 `~/.local/share/eta-vivo/signing/`，其中
`eta-vivo-release.jks` 为证书，`credentials.json` 为签名参数。
目录权限为 `0700`，文件权限为 `0600`；备份不在 Git 内。
迁移或重建仓库时继续使用同一证书，并重新设置上述 Secrets。
不要把证书、密码或备份提交到 Git，也不要每次构建重新生成证书。

正式发布前先更新 `versionCode` 和 `versionName`，然后创建与
`versionName` 对应的标签。例如发布 `2.2.2`：

```bash
git tag v2.2.2
git push origin v2.2.2
```

标签推送后，等待 `Eta Build` 工作流完成，然后：

1. 从该次工作流的 `Artifacts` 下载 `app-release.apk`。
2. 在仓库的 `Releases > Draft a new release` 中选择已有标签。
3. 填写 Release Notes 并上传 APK。
4. 检查版本、说明和附件后，由维护者手动发布。
