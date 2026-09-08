# 发布指南

当前仓库：[XiaoLeXLDW/fuck-mi-aicr-default-browser](https://github.com/XiaoLeXLDW/fuck-mi-aicr-default-browser)。
当前版本为 **v0.3.4 Pre-release**（code `11` / 协议 `200` / 服务代 `30005`），
包名为 `dev.codex.mibrowserredirector`。下载、签名、校验值与 CI 信息见[版本说明](releases/v0.3.4.md)。

Wiki 内容通过[仓库内文档](wiki/Home.md)阅读；导出方法见下文。

## 1. 检查当前源码与文档

在项目根目录运行：

```powershell
.\scripts\verify-repository.ps1
.\tests\verify-stop.Tests.ps1
.\tests\verify-stop-process.Tests.ps1
.\tests\current-docs.Tests.ps1
.\tests\export-wiki.Tests.ps1
.\scripts\build.ps1 -Variant Release -LocalTestSigning
```

当前 Pre-release 沿用历史测试证书以保持升级连续性。`-LocalTestSigning` 要求本机已有
`keys/redirector-test.jks`；新克隆应按[构建与签名](BUILDING.md)使用 Debug 或未签名 Release。
测试签名不代表正式稳定版。设备验收按[实机清单](实机验收.md)独立记录，未测场景标“待验”。
同时核对版本号、下载链接和功能描述。

## 2. 检查发布来源

```powershell
git remote -v
git status --short
git diff --check
```

核对源码提交、版本号和 `origin`。签名私钥、SDK、缓存、设备日志和 `dist/` 产物不提交。
发布所用提交的 Actions `Android checks` 应通过。该工作流运行 Debug / Release 单测、Lint、
构建及离线 PowerShell 回归，不持有发行密钥、不自动创建 Release。

## 3. 发布可安装 APK

发布新版本前递增源码版本号与 versionCode，以下步骤使用新版本对应的标签和文件名。

1. 固定源码提交并创建新版本标签，再从该提交构建，核验包名、版本、签名、大小与 SHA-256。
2. 将已签名产物命名为 `MiBrowserRedirector-v<版本号>-universal.apk`，同步生成同名 `.sha256`。
3. 使用该标签创建 **Pre-release**，填写版本说明并附 APK 和校验文件。
4. 回下载发布附件，核对大小、SHA-256、签名和标签指向；保留对应源码与第三方许可证。
5. 更新 README、快速开始、更新记录与版本说明，使下载入口和版本信息一致。

若未来更换正式证书，需先处理 Android 覆盖安装的签名关系。已发布安装包的变更通过新版本交付，
不移动旧标签或覆盖旧附件。

## 4. 导出 Wiki

Wiki 的可维护源文件在 [docs/wiki](wiki/Home.md)。本地预览：

```powershell
.\scripts\export-wiki.ps1
```

若将来启用独立 GitHub Wiki，先在网站创建初始页面，再按
[GitHub Wiki 文档](https://docs.github.com/en/communities/documenting-your-project-with-wikis/adding-or-editing-wiki-pages)
准备独立的 `.wiki.git` 仓库。运行：

```powershell
.\scripts\export-wiki.ps1 -Repository XiaoLeXLDW/fuck-mi-aicr-default-browser -SourceRevision HEAD
```

`-SourceRevision` 默认 `HEAD`，也可指定待发布的标签或提交，导出时解析为完整 SHA。
同级页面链接指向 Wiki，跨目录文档和资源指向该源码提交；先确认对应提交已推送到主仓库。
审阅 `dist/wiki/` 的导出内容后再同步到 Wiki 克隆。导出器只写文件，不自动提交或推送。
