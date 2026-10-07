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

## PR 检查

指向 `main` 的 Pull Request 会触发 `PR Check`，依次执行 Lint、单元测试和 Debug 构建，
失败时上传检查报告。该工作流不读取任何 Secrets，来自 fork 的 PR 需要维护者在 PR 页面
批准后才会运行，批准前请重点检查 `.github/` 下的改动。

`main` 分支通过仓库规则集要求 `Lint / Test / Debug Build` 检查通过后才能合并。
修改该 job 名称时需同步更新规则集。

## 构建与发布

以下情况会在同一次工作流中生成 Debug APK 和经过签名验证的 Release APK，
并作为两个可直接下载的 Actions Artifact 保存 14 天：

- 向 `main` 推送提交
- 推送 `v*` 标签
- 在 GitHub 的 `Actions > Eta Build` 中手动运行

同一工作流会并行执行 Lint 与单元测试。检查失败不影响 APK 上传，但推送标签时不会创建
Release 草稿。

非标签构建会在版本名后追加 SemVer 构建元数据 `+<短提交号>`，例如 `Eta-v3.2.0+a1b2c3d.apk`，
用于区分同一版本号下的不同构建；只有标签构建产出不带后缀的正式版本名。Actions Artifact
需要登录才能下载且到期失效，仅供开发测试，面向用户的分发只使用 GitHub Releases。

推送 `v*` 标签时，工作流还会创建一个只附带 Release APK 的 GitHub Release 草稿，
说明预填自动生成的变更列表。草稿不会被应用内更新检查读到，发布始终由维护者手动完成。

正式发布前先更新 `versionCode` 和 `versionName`，然后创建与
`versionName` 对应的标签。`versionCode` 使用 `yyyyMMdd` 加两位当日序号，
只能递增，否则已安装用户无法覆盖升级。推送标签时，工作流会校验标签与 `versionName`
一致，不一致时构建失败。例如发布 `2.2.2`：

```bash
git tag v2.2.2
git push origin v2.2.2
```

标签推送后，等待 `Eta Build` 工作流完成，然后：

1. 在仓库的 `Releases` 中打开 `vX.Y.Z` 草稿，确认附件为 `Eta-vX.Y.Z.apk`。
2. 改写发布说明。
3. 检查版本、说明和附件后，由维护者手动发布。
